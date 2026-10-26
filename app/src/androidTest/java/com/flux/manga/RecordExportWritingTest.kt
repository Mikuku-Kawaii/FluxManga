package com.flux.manga

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.pages.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Production page/model/writer with isolated records. No production namespace override is
 * exposed by Intent or preferences. The local provider exists only in this test process. */
class RecordExportWritingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as Application
    private val owners = ViewModelStore()
    private lateinit var data: WorldDataStore
    private lateinit var model: RecordExportModel
    private lateinit var provider: ControlledExportProvider
    private lateinit var namespace: String
    private var before: Map<ExportTable, List<ExportRecord>> = emptyMap()
    private var picker: (RecordExportModel) -> Unit = {}
    private val selection = RecordExportPickerSelection()
    private var chosen: ControlledExportProvider.Target? = null
    private fun recordDigest() = MessageDigest.getInstance("SHA-256").digest(snapshot().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun snapshot() = runBlocking { data.recordExportData(PrototypeWorld.SFW, { true }) { dao ->
        val rows = DatabaseExportRows(dao)
        ExportTable.entries.associateWith { table -> buildList {
            var offset = 0
            do { val page = rows.page(table, offset); addAll(page); offset += page.size } while (page.size == EXPORT_PAGE_SIZE)
        } }
    } }

    private fun setup(realResolver: Boolean = false) {
        compose.awaitTag("shelf-more")
        compose.waitUntil(10_000) { !compose.activity.isCovered }
        namespace = "export-writing-${UUID.randomUUID()}"
        data = WorldDataStore(app, namespace)
        runBlocking { data.worldData(PrototypeWorld.SFW) { dao ->
            repeat(160) { i ->
                val id = "book-${i.toString().padStart(3, '0')}"
                dao.insertBook(LocalBook(mangaId = id, title = "合成书目 $i", archiveName = "absent-$id.cbz",
                    sha256 = id, archiveBytes = 100, importedAt = 1, summary = "合成说明\n".repeat(240)))
                dao.insertLocalBookmark(LocalBookmark("mark-$i", "cbz", id, "page", i, .25f, "STRIP", "合成备注 \" 😀\n", 1, 2))
                dao.putProgress(ReadingProgress("cbz", id, "chapter", "page", i, .25f, "STRIP", 3, false))
            }
            dao.putOnlineBook(OnlineBook("synthetic", "online", "合成在线", true, 1))
            dao.putOnlineProgress(OnlineReadingProgress("synthetic", "online", "chapter", "page", 2, .5f, "RTL", 2))
            dao.putChapterReading(ChapterReading("synthetic", "online", "chapter", null, null, 0f, 3, true))
        } }
        before = snapshot()
        provider = ControlledExportProvider(app)
        compose.runOnUiThread {
            model = RecordExportModel(app, compose.activity.worldSession, data,
                if (realResolver) app.contentResolver else ContentResolver.wrap(provider))
            owners.put("export", model)
        }
    }

    private fun mount() {
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme {
                CompositionLocalProvider(LocalRecordExportPicker provides { owner -> picker(owner) }) {
                    RecordExportContent(model, compose.activity.worldSession) { }
                }
            } }
        }
        compose.awaitTag("record-export")
        compose.waitUntil(10_000) { model.state.value.counts != null && !model.state.value.loading }
    }

    private fun click(label: String) {
        compose.onNodeWithTag("record-export").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }

    private fun ready(blocked: Boolean = false, broken: Boolean = false) {
        picker = { owner ->
            assertTrue(selection.request(owner))
            chosen = provider.newTarget(blocked, broken)
            selection.result(chosen!!.uri)
        }
        mount()
        click("我了解导出文件是明文，仅包含当前空间记录")
        click("选择保存位置")
        compose.onNodeWithText("开始导出当前空间记录").assertExists()
    }

    private fun writing(): ControlledExportProvider.Target {
        click("开始导出当前空间记录")
        val target = requireNotNull(chosen)
        assertTrue("Actual output must precede interruption", target.firstBytes.await(8, TimeUnit.SECONDS))
        assertEquals(RecordExportPhase.WRITING, model.state.value.phase)
        return target
    }

    private fun settled(target: ControlledExportProvider.Target) {
        target.release.countDown()
        compose.waitUntil(10_000) { model.state.value.phase == RecordExportPhase.IDLE }
        assertTrue(target.finished.await(8, TimeUnit.SECONDS))
        assertFalse(target.file.readText().contains("\"complete\": true"))
        assertTrue(model.state.value.message!!.contains("不完整"))
        assertEquals(before, snapshot())
    }

    @After fun closeOwners() {
        if (::provider.isInitialized) provider.releaseAll()
        inst.runOnMainSync { owners.clear() }
    }

    @Test fun fullPageSystemSaveStartCompleteAndReadback() {
        setup(realResolver = true)
        val uri = AtomicReference<Uri?>()
        lateinit var launcher: ActivityResultLauncher<String>
        compose.runOnUiThread {
            launcher = compose.activity.activityResultRegistry.register("writing-system-save", RecordCreateDocument()) {
                uri.set(it); selection.result(it)
            }
        }
        picker = { owner -> if (selection.request(owner)) launcher.launch("FluxManga-synthetic-full-${UUID.randomUUID()}.json") }
        mount()
        click("我了解导出文件是明文，仅包含当前空间记录")
        click("选择保存位置")
        val deadline = SystemClock.uptimeMillis() + 10_000
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node); for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(descendants(it)) }
        }
        var saved = false
        while (!saved && SystemClock.uptimeMillis() < deadline) {
            val root = inst.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() in setOf("com.android.documentsui", "com.google.android.documentsui")) {
                val save = descendants(root!!).firstOrNull { it.isClickable && it.isEnabled && it.text?.toString() in setOf("保存", "Save", "SAVE") }
                saved = save?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            if (!saved) SystemClock.sleep(100)
        }
        assertTrue("System save must run", saved)
        compose.waitUntil(10_000) { model.state.value.phase == RecordExportPhase.READY }
        assertEquals(0L, app.contentResolver.openFileDescriptor(uri.get()!!, "r")!!.use { it.statSize })
        click("开始导出当前空间记录")
        compose.waitUntil(10_000) { model.state.value.phase == RecordExportPhase.COMPLETE }
        compose.onNodeWithText("导出完成。此文件只包含记录，不能替代原文件或完整备份。").assertExists()
        val json = JSONObject(app.contentResolver.openInputStream(uri.get()!!)!!.bufferedReader().use { it.readText() })
        assertTrue(json.getBoolean("complete")); assertEquals("SFW", json.getString("world"))
        assertEquals(483L, model.state.value.written)
        assertEquals(160, json.getJSONArray("localBooks").length()); assertEquals(160, json.getJSONArray("bookmarks").length())
        assertEquals(.25, json.getJSONArray("localProgress").getJSONObject(0).getDouble("fraction"), .00001)
        assertEquals(before, snapshot())
        compose.runOnUiThread { launcher.unregister() }
    }

    @Test fun stopWaitsForBlockedProviderThenRetryUsesNewTarget() {
        setup(); ready(blocked = true)
        val first = writing()
        click("停止导出")
        assertEquals(RecordExportPhase.CANCELLING, model.state.value.phase)
        compose.onNodeWithText("正在停止…").assertIsNotEnabled()
        settled(first)
        val partial = first.file.readBytes()
        compose.runOnUiThread { model.start() }
        assertEquals(1, first.opens.get())
        picker = { owner -> assertTrue(selection.request(owner)); chosen = provider.newTarget(); selection.result(chosen!!.uri) }
        click("选择保存位置"); click("开始导出当前空间记录")
        compose.waitUntil(10_000) { model.state.value.phase == RecordExportPhase.COMPLETE }
        assertNotEquals(first.uri, chosen!!.uri)
        assertArrayEquals(partial, first.file.readBytes())
        assertTrue(JSONObject(chosen!!.file.readText()).getBoolean("complete"))
        assertEquals(before, snapshot())
    }

    @Test fun realBackgroundStopsWriteAndForegroundDoesNotReplay() {
        setup(); ready(blocked = true)
        val target = writing()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        target.release.countDown()
        assertTrue(target.finished.await(8, TimeUnit.SECONDS))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settled(target)
        compose.onNodeWithText("选择保存位置").assertExists()
        assertEquals(1, target.opens.get())
    }

    @Test fun configurationInterruptionKeepsPartialFileNotice() {
        setup(); ready(blocked = true)
        val target = writing()
        compose.activityRule.scenario.recreate()
        settled(target)
        val notice = model.state.value.message
        mount()
        assertEquals("Restoring the page must retain the incomplete-file warning", notice, model.state.value.message)
        compose.onNodeWithText(notice!!).assertExists()
        assertEquals(1, target.opens.get())
    }

    @Test fun generationChangeRejectsOldWriterAndLateUri() {
        setup(); ready(blocked = true)
        val target = writing()
        compose.runOnUiThread { compose.activity.worldSession.returnToPublicForPrivacy() }
        settled(target)
        assertFalse(model.active())
        compose.runOnUiThread { model.receive(target.uri); model.start() }
        assertEquals(1, target.opens.get())
        assertEquals(before, snapshot())
    }

    @Test fun providerWriteFailureShowsRetryWithoutReusingTarget() {
        setup(); ready(broken = true)
        click("开始导出当前空间记录")
        val target = chosen!!
        assertTrue(target.finished.await(8, TimeUnit.SECONDS))
        compose.waitUntil(10_000) { model.state.value.phase == RecordExportPhase.IDLE }
        assertTrue(model.state.value.message!!.startsWith("导出失败"))
        assertFalse(target.file.readText().contains("\"complete\": true"))
        compose.runOnUiThread { model.start() }
        assertEquals(1, target.opens.get()); assertEquals(before, snapshot())
    }

    /** Explicitly selected by the host, which kills this process after the durable marker appears. */
    @Test fun hostDeathWhileWriting() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("exportHostDeath") == "true")
        setup(); ready(blocked = true)
        val recordsHash = recordDigest()
        val target = writing()
        File(app.getExternalFilesDir(null), "record-export-death.json").writeText(JSONObject()
            .put("namespace", namespace).put("output", target.file.absolutePath).put("pid", android.os.Process.myPid())
            .put("phase", model.state.value.phase.name).put("bytes", target.file.length()).put("recordsHash", recordsHash).toString())
        SystemClock.sleep(60_000)
        fail("Host did not terminate the writing process")
    }

    @Test fun recoverAfterHostDeathDoesNotReusePartialTarget() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("exportHostRecovery") == "true")
        compose.awaitTag("shelf-more")
        val marker = JSONObject(File(app.getExternalFilesDir(null), "record-export-death.json").readText())
        assertNotEquals(marker.getInt("pid"), android.os.Process.myPid())
        namespace = marker.getString("namespace")
        require(namespace.matches(Regex("export-writing-[a-f0-9-]+")))
        data = WorldDataStore(app, namespace); before = snapshot()
        assertEquals(483, before.values.sumOf { it.size })
        assertEquals(marker.getString("recordsHash"), recordDigest())
        val partial = File(marker.getString("output"))
        require(partial.canonicalFile.parentFile == File(app.cacheDir, "export-writing-targets").canonicalFile)
        val bytes = partial.readBytes(); assertTrue(bytes.isNotEmpty())
        assertFalse(bytes.toString(Charsets.UTF_8).contains("\"complete\": true"))
        provider = ControlledExportProvider(app)
        compose.runOnUiThread {
            model = RecordExportModel(app, compose.activity.worldSession, data, ContentResolver.wrap(provider))
            owners.put("recovered", model)
            model.receive(Uri.parse("content://synthetic-export/stale")); model.start()
        }
        assertEquals(RecordExportPhase.IDLE, model.state.value.phase)
        ready(); click("开始导出当前空间记录")
        compose.waitUntil(10_000) { model.state.value.phase == RecordExportPhase.COMPLETE }
        assertTrue(JSONObject(chosen!!.file.readText()).getBoolean("complete"))
        assertArrayEquals(bytes, partial.readBytes()); assertEquals(before, snapshot())
    }
}

/** A bounded pipe pause makes cancellation observable without large libraries or a full disk. */
internal class ControlledExportProvider(private val app: Application) : ContentProvider() {
    data class Target(val uri: Uri, val file: File, val blocked: Boolean, val broken: Boolean,
        val opens: AtomicInteger = AtomicInteger(), val firstBytes: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1), val finished: CountDownLatch = CountDownLatch(1))
    private val targets = ConcurrentHashMap<String, Target>()
    init { attachInfo(app, ProviderInfo().apply { authority = "synthetic-export" }) }
    fun newTarget(blocked: Boolean = false, broken: Boolean = false): Target {
        val id = UUID.randomUUID().toString()
        val directory = File(app.cacheDir, "export-writing-targets").apply { mkdirs() }
        return Target(Uri.parse("content://synthetic-export/$id"), File(directory, "$id.json"), blocked, broken).also { targets[id] = it }
    }
    fun releaseAll() { targets.values.forEach { it.release.countDown() } }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = requireNotNull(targets[uri.lastPathSegment]); require(mode == "wt")
        check(target.opens.incrementAndGet() == 1) { "Old target reused" }
        if (!target.blocked && !target.broken) return ParcelFileDescriptor.open(target.file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "synthetic-export-output", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input -> target.file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    val first = input.read(buffer)
                    if (first > 0) { output.write(buffer, 0, first); output.flush(); target.firstBytes.countDown() }
                    if (target.blocked) check(target.release.await(45, TimeUnit.SECONDS)) { "Synthetic pause expired" }
                    if (!target.broken) input.copyTo(output)
                } }
            } finally { target.finished.countDown() }
        }
        return pipe[1]
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Not supported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Not supported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Not supported")
}
