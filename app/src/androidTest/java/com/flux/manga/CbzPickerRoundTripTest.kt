package com.flux.manga

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Adds unique synthetic books only; never resets or removes any existing book. */
@RunWith(AndroidJUnit4::class)
class CbzPickerRoundTripTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = inst.uiAutomation
    private val hostPicker get() = InstrumentationRegistry.getArguments().getString("hostPicker") == "true"
    private val library get() = CbzLibrary(WorldDataStore(inst.targetContext))
    private val evidence get() = File(inst.targetContext.getExternalFilesDir(null), "m2-picker-return-evidence").apply { mkdirs() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun systemClick(vararg labels: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val found = automation.rootInActiveWindow?.let(::nodes).orEmpty().firstOrNull {
                it.isVisibleToUser && (it.text?.toString() in labels || it.contentDescription?.toString() in labels)
            }
            if (found != null) {
                var target: AccessibilityNodeInfo? = found
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(100)
        }
        error("Missing document picker control: ${labels.toList()}")
    }
    private fun awaitPicker() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.rootInActiveWindow?.packageName?.toString() in setOf("com.android.documentsui", "com.google.android.documentsui")) return
            SystemClock.sleep(100)
        }
        error("Expected system DocumentsUI")
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun openPicker() {
        compose.openLocalManagement()
        compose.awaitTag("local-import")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty() }
        click("local-import")
        if (!hostPicker) awaitPicker()
    }
    private fun page(number: Int) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText("第 $number / 5 页 · 单章节").fetchSemanticsNodes().isNotEmpty()
    }
    private fun saved() = compose.waitUntil(15_000) {
        compose.onAllNodesWithText("进度已保存").fetchSemanticsNodes().isNotEmpty()
    }
    private fun existingFiles(): Map<String, Pair<String, Long>> {
        val app = inst.targetContext
        return listOf(File(app.noBackupFilesDir, "business/sfw/cbz"), File(app.noBackupFilesDir, "business/nsfw"),
            File(app.getExternalFilesDir(null), "m2-validation-evidence"))
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (dir.name != "m2-validation-evidence" || it.extension == "cbz") }.toList() }
            .associate { it.absolutePath to (hash(it.readBytes()) to it.lastModified()) }
    }
    @Test fun importReadAndContinue() = roundTrip("normal", 0)
    @Test fun pickerBeyondSessionTimeoutStillImports() = roundTrip("timeout", 31_000)

    private fun roundTrip(label: String, waitMs: Long): Unit = runBlocking {
        val externalDeath = InstrumentationRegistry.getArguments().getString("pickerDeath") == "true"
        val resultLabel = if (externalDeath) "process-death" else label
        val oldBooks = library.cards(PrototypeWorld.SFW).associate { it.key to library.detail(it.key) }
        val oldFiles = existingFiles()
        val token = UUID.randomUUID().toString()
        val name = "FluxManga-${token.take(8)}.cbz"
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(CbzFixtures.valid().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries += entry.name to zip.readBytes() }
        }
        entries += "roundtrip-id.txt" to token.toByteArray()
        val bytes = CbzFixtures.archive(entries)
        val resolver = inst.targetContext.contentResolver
        val original = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.comicbook+zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        })!!
        resolver.openOutputStream(original)!!.use { it.write(bytes) }
        val record = JSONObject().put("name", name).put("uri", original.toString()).put("sha256", hash(bytes))
            .put("existingBooks", oldBooks.size).put("existingFiles", oldFiles.size).put("waitMs", waitMs)
            .put("pid", android.os.Process.myPid())
        if (externalDeath) {
            record.put("bookDigests", JSONObject().apply { oldBooks.forEach { (key, detail) -> put(key.mangaId, hash(detail.toString().toByteArray())) } })
            record.put("fileDigests", JSONObject().apply { oldFiles.forEach { (path, value) -> put(path, JSONObject().put("sha256", value.first).put("mtime", value.second)) } })
        }
        File(evidence, "$resultLabel-source.json").writeText(record.toString(2))
        File(evidence, "picker-request.json").writeText(record.put("requestId", token).put("action", if (externalDeath) "selectAfterKill" else "select").toString())
        // Finish instrumentation before the host kills the ordinary app. Killing an instrumented
        // target also tears down its Activity task and cannot model ordinary background death.
        if (externalDeath) return@runBlocking
        val generation = compose.activity.worldSession.identity.value.generation
        openPicker()
        if (!hostPicker) {
            if (waitMs > 0) SystemClock.sleep(waitMs)
            systemClick("显示根目录", "Show roots")
            systemClick("下载", "Downloads")
            systemClick(name)
        }
        compose.waitUntil(waitMs + 30_000) {
            try { compose.onAllNodesWithText("导入完成，原文件保持不变").fetchSemanticsNodes().isNotEmpty() }
            catch (e: IllegalStateException) { if (e.message?.startsWith("No compose hierarchies found") == true) false else throw e }
        }
        if (waitMs > 30_000) assertTrue("Session timeout must still invalidate old owners", compose.activity.worldSession.identity.value.generation > generation)
        val imported = library.cards(PrototypeWorld.SFW).single { it.title == name.removeSuffix(".cbz") }
        val detail = library.detail(imported.key)!!
        assertEquals(hash(bytes), detail.sha256)
        assertEquals(CbzFixtures.order, detail.chapter.pages.map { it.entryName })
        assertEquals(oldBooks.size + 1, library.cards(PrototypeWorld.SFW).size)
        readAndContinue(imported)
        oldBooks.forEach { (key, expected) -> assertEquals("Existing book changed: ${key.mangaId}", expected, library.detail(key)) }
        oldFiles.forEach { (path, expected) -> assertEquals("Existing original/copy/private file changed", expected, hash(File(path).readBytes()) to File(path).lastModified()) }
        assertArrayEquals(bytes, resolver.openInputStream(original)!!.use { it.readBytes() })
        automation.takeScreenshot()?.let { bitmap ->
            File(evidence, "$resultLabel-reader.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        File(evidence, "$resultLabel-result.json").writeText(record.put("passed", true).put("id", imported.key.mangaId)
            .put("generationAfter", compose.activity.worldSession.identity.value.generation).put("sourcePreserved", true)
            .put("existingDataPreserved", true).put("page", 2).put("mode", "STRIP").toString(2))
    }
    private suspend fun readAndContinue(imported: MangaCard) {
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-book-${imported.title}"))
        click("local-book-${imported.title}"); compose.awaitTag("local-read"); click("local-read")
        if (imported.progress == null) {
            page(1); compose.awaitTag("reader-loaded-1-0")
            compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }
        } else {
            // A disconnected host may retry after this unique fixture already saved page 2.
            // Verify the saved position instead of resetting it for the test.
            assertEquals(2, imported.progress.ordinal)
            assertEquals("STRIP", imported.progress.mode)
            compose.awaitTag("reader-strip")
        }
        page(2)
        compose.awaitTag("reader-loaded-2-0"); saved()
        compose.onNodeWithTag("reader-mode-STRIP").performScrollTo().performClick()
        compose.awaitTag("reader-strip"); saved()
        compose.activityRule.scenario.recreate()
        compose.awaitTag("reader-strip"); page(2); saved()
        compose.onNodeWithTag("reader-back").performClick()
        compose.awaitTag("local-read")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("local-progress").assertTextContains("上次阅读：第 2 页")
        click("local-read"); compose.awaitTag("reader-strip"); page(2)
        assertEquals("STRIP", library.detail(imported.key)!!.card.progress!!.mode)
    }
    @Test fun verifyPickerAfterProcessDeath(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPickerDeath") == "true")
        val record = JSONObject(File(evidence, "process-death-source.json").readText())
        assertNotEquals(record.getInt("pid"), android.os.Process.myPid())
        val cards = library.cards(PrototypeWorld.SFW)
        assertEquals(record.getInt("existingBooks") + 1, cards.size)
        val imported = cards.single { it.title == record.getString("name").removeSuffix(".cbz") }
        assertEquals(record.getString("sha256"), library.detail(imported.key)!!.sha256)
        assertEquals(CbzFixtures.order, library.detail(imported.key)!!.chapter.pages.map { it.entryName })
        val books = record.getJSONObject("bookDigests")
        books.keys().forEach { id ->
            assertEquals(books.getString(id), hash(library.detail(MangaKey(PrototypeWorld.SFW, "cbz", id)).toString().toByteArray()))
        }
        val files = record.getJSONObject("fileDigests")
        files.keys().forEach { path ->
            assertEquals(files.getJSONObject(path).getString("sha256"), hash(File(path).readBytes()))
            assertEquals(files.getJSONObject(path).getLong("mtime"), File(path).lastModified())
        }
        val uri = android.net.Uri.parse(record.getString("uri"))
        assertEquals(record.getString("sha256"), hash(inst.targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
        compose.openLocalManagement()
        compose.awaitTag("local-import")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty() }
        readAndContinue(imported)
        File(evidence, "process-death-result.json").writeText(record.put("passed", true).put("sourcePreserved", true)
            .put("existingDataPreserved", true).put("page", 2).put("mode", "STRIP").toString(2))
    }
    @Test fun cancelPickerKeepsLibraryAndExistingData(): Unit = runBlocking {
        val old = library.cards(PrototypeWorld.SFW).associate { it.key to library.detail(it.key) }
        File(evidence, "picker-request.json").writeText(JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("action", "cancel").put("waitMs", 0).toString())
        openPicker()
        if (!hostPicker) automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(10_000) {
            try { compose.onAllNodesWithTag("local-query").fetchSemanticsNodes().isNotEmpty() }
            catch (e: IllegalStateException) { if (e.message?.startsWith("No compose hierarchies found") == true) false else throw e }
        }
        assertEquals(old, library.cards(PrototypeWorld.SFW).associate { it.key to library.detail(it.key) })
        File(evidence, "cancel.txt").writeText("PASS system picker cancellation: existing books and progress unchanged")
    }
}
