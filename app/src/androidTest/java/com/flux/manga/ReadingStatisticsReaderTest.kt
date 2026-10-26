package com.flux.manga

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.ReferenceShapes
import com.flux.manga.ui.ReferenceTypography
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual CBZ reader / image decoder / progress writer with isolated generated archives.
 * Host-interactive cases require explicit runner arguments. Never changes the device lock. */
class ReadingStatisticsReaderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as Application
    private lateinit var data: WorldDataStore
    private lateinit var namespace: String
    private lateinit var book: String
    private lateinit var reader: LocalReaderViewModel
    private lateinit var timer: ReadingTimeModel
    private val owners = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val results = JSONObject()
    private var spreadBefore: Boolean? = null
    private var spreadChanged = false
    private val marker get() = File(app.getExternalFilesDir(null), "reading-statistics-host.json")

    private fun setup(recovered: JSONObject? = null) {
        compose.waitUntil(10_000) { !compose.activity.isCovered && compose.activity.worldSession.state.value.foreground }
        namespace = recovered?.getString("namespace") ?: "reading-full-${UUID.randomUUID()}"
        require(namespace.matches(Regex("reading-full-[a-f0-9-]{36}")))
        data = WorldDataStore(app, namespace)
        book = recovered?.getString("book") ?: runBlocking {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                for (i in 1..4) {
                    val bitmap = Bitmap.createBitmap(480, if (i == 3) 3600 else 720, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(0xff397da8.toInt() + i * 1000)
                    zip.putNextEntry(ZipEntry("page-$i.png")); bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry(); bitmap.recycle()
                }
            }
            val bytes = output.toByteArray()
            CbzLibrary(data).import(PrototypeWorld.SFW, CbzSource("合成统计全模式.cbz") { bytes.inputStream() }).key.mangaId
        }
        results.put("namespace", namespace).put("book", book)
    }
    private fun mount() {
        compose.runOnUiThread {
            reader = LocalReaderViewModel(app, compose.activity.worldSession, book, savedState = SavedStateHandle(), data = data)
            timer = ReadingTimeModel(app, compose.activity.worldSession, "local", "cbz", book, data)
            owners.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${LocalReaderViewModel::class.java.canonicalName}", reader)
            owners.viewModelStore.put("reading-time:local:cbz:$book", timer)
            val identity = compose.activity.worldSession.identity.value
            owners.viewModelStore.put("local-bookmarks:${identity.processSession}:${identity.world}:${identity.generation}:$book",
                LocalBookmarkModel(LocalBookmarks(data, compose.activity.worldSession, book), SavedStateHandle()))
        }
        content()
        compose.waitUntil(10_000) { reader.state.value.detail != null && !reader.state.value.loading }
        loaded()
    }
    private fun content() {
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.SFW),
                typography = ReferenceTypography, shapes = ReferenceShapes) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owners) {
                    LocalReaderPage(compose.activity.worldSession, book, {})
                }
            } }
        }
    }
    private fun loaded() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher("loaded pixels") {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("reader-loaded-")
            }).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
    private fun total(): Long = runBlocking { data.worldData(PrototypeWorld.SFW) {
        it.total("0000-01-01", null, "local", "cbz", book).durationMs
    } }
    private fun checkpoint(): Long {
        compose.runOnUiThread { timer.retry() }
        SystemClock.sleep(350)
        return total()
    }
    private fun mode(mode: ReadingMode) {
        compose.onNodeWithText("方式 · ${reader.state.value.position.mode.label}").performScrollTo().performClick()
        compose.onNodeWithTag("reader-mode-${mode.name}").performScrollTo().performClick()
        compose.waitUntil(8_000) { reader.state.value.position.mode == mode }
        loaded()
    }
    @After fun finish() {
        inst.runOnMainSync { owners.viewModelStore.clear() }
        if (spreadChanged) {
            val edit = app.getSharedPreferences("reader-controls", 0).edit()
            spreadBefore?.let { edit.putBoolean("SFW.spread", it) } ?: edit.remove("SFW.spread")
            assertTrue(edit.commit())
        }
        if (::namespace.isInitialized) {
            File(app.getExternalFilesDir(null), "$namespace-result.json").writeText(results.toString(2))
        }
    }

    @Test fun actualCbzFourModesTurningAndToolsPause() {
        setup(); mount()
        for (mode in ReadingMode.entries) {
            mode(mode)
            val before = checkpoint()
            val started = SystemClock.elapsedRealtime()
            SystemClock.sleep(1_300)
            compose.onNodeWithTag("reader-next").performClick()
            loaded()
            val after = checkpoint()
            assertTrue("${mode.name} did not count displayed body", after - before >= 1_000)
            assertTrue("${mode.name} counted multiple images", after - before <= SystemClock.elapsedRealtime() - started + 500)
            results.put(mode.name, after - before)
            // Use the actual mode dialog to pause timing while loaded body remains underneath.
            compose.onNodeWithText("方式 · ${mode.label}").performScrollTo().performClick()
            compose.onNodeWithTag("reader-mode-${mode.name}").assertExists()
            compose.waitForIdle()
            SystemClock.sleep(300)
            val paused = checkpoint()
            SystemClock.sleep(1_100)
            assertEquals("Reader tools counted time", paused, checkpoint())
            compose.onNodeWithTag("reader-mode-${mode.name}").performScrollTo().performClick()
            compose.runOnUiThread { reader.position(0) }
            loaded()
        }
        val before = reader.state.value.position
        runBlocking { reader.flush().join() }
        val saved = runBlocking { CbzLibrary(data).detail(MangaKey(PrototypeWorld.SFW, "cbz", book))!!.card.progress!! }
        assertEquals(before.mode.name, saved.mode)
        assertEquals(reader.state.value.detail!!.chapter.pages[before.page].id, saved.pageId)
        assertFalse(timer.error.value)
    }

    private fun home() {
        val session = compose.activity.worldSession
        assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        val deadline = SystemClock.elapsedRealtime() + 8_000
        // Compose's Activity getter synchronizes with the foreground UI; never call it while Home owns focus.
        while (session.state.value.foreground && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertFalse(session.state.value.foreground)
        SystemClock.sleep(600) // Allow ON_PAUSE's existing asynchronous checkpoint to commit.
    }
    private fun foreground() {
        // After Home, background startActivity may be blocked by Android. Shell models the user's task launch.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
            "am start --activity-reorder-to-front -n com.flux.manga/.MainActivity")).bufferedReader().use { it.readText() }
        compose.waitUntil(10_000) { compose.activity.worldSession.state.value.foreground && !compose.activity.isCovered }
        compose.waitForIdle()
    }

    @Test fun homeShortBackgroundPreservesOwnerAndResumesTiming() {
        setup(); mount(); mode(ReadingMode.VERTICAL)
        compose.runOnUiThread { reader.position(1) }; loaded()
        SystemClock.sleep(1_200)
        val identity = compose.activity.worldSession.identity.value
        home()
        val stopped = total()
        SystemClock.sleep(2_000)
        assertEquals("Home dwell was counted", stopped, total())
        foreground(); loaded()
        assertEquals(identity, compose.activity.worldSession.identity.value)
        assertEquals(1, reader.state.value.position.page)
        assertEquals(ReadingMode.VERTICAL, reader.state.value.position.mode)
        SystemClock.sleep(1_200)
        val resumed = checkpoint()
        assertTrue(resumed > stopped + 800)
        assertTrue(resumed - stopped < 5_000)
        results.put("case", "home-short").put("stopped", stopped).put("resumed", resumed)
    }

    @Test fun homeLongBackgroundRevokesOldSessionAndReopensSavedReader() {
        setup(); mount(); mode(ReadingMode.STRIP)
        marker.writeText(JSONObject().put("phase", "LONG_READER_READY").put("namespace", namespace).toString())
        compose.runOnUiThread { reader.position(2) }; loaded()
        runBlocking { reader.flush().join() }
        SystemClock.sleep(1_200)
        val session = compose.activity.worldSession
        val identity = session.identity.value
        val oldRepository = ReadingStatistics(data, session)
        home()
        marker.writeText(JSONObject().put("phase", "LONG_BACKGROUND").put("namespace", namespace).toString())
        val stopped = total()
        val started = SystemClock.elapsedRealtime()
        repeat(7) { SystemClock.sleep(5_000); assertEquals("Long background time counted", stopped, total()) }
        foreground()
        marker.writeText(JSONObject().put("phase", "LONG_FOREGROUND").put("namespace", namespace).toString())
        assertFalse(session.accepts(identity))
        assertEquals(PrototypeWorld.SFW, session.identity.value.world)
        assertTrue(session.identity.value.generation > identity.generation)
        try {
            runBlocking { oldRepository.save(listOf(ReadingTimeRow("stale", "2026-10-24", "local", "cbz", book, "合成旧会话", 60_000))) }
            fail("Old generation wrote statistics")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(stopped, total())
        mount() // Explicitly reopen the synthetic work under the new session; do not restore a stale route.
        assertEquals(2, reader.state.value.position.page)
        assertEquals(ReadingMode.STRIP, reader.state.value.position.mode)
        SystemClock.sleep(1_200)
        val resumed = checkpoint()
        assertTrue(resumed > stopped + 800)
        assertTrue(resumed - stopped < 6_000)
        results.put("case", "home-long").put("backgroundMs", SystemClock.elapsedRealtime() - started)
            .put("stopped", stopped).put("resumed", resumed).put("generationBefore", identity.generation)
            .put("generationAfter", session.identity.value.generation)
    }

    @Test fun systemMultiWindowFocusResizeAndFullscreenRecovery() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("statisticsMultiWindow") == "true")
        setup(); mount(); mode(ReadingMode.VERTICAL)
        compose.runOnUiThread { reader.position(1) }; loaded()
        runBlocking { reader.flush().join() }
        val originalIdentity = compose.activity.worldSession.identity.value
        val toggleAccepted = inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        marker.writeText(JSONObject().put("phase", "ENTER_MULTI_WINDOW").put("namespace", namespace).put("pid", Process.myPid())
            .put("systemToggleAccepted", toggleAccepted).toString())
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (!compose.activity.isInMultiWindowMode && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        assertTrue("System multi-window was not entered", compose.activity.isInMultiWindowMode)
        // Reattach the isolated fixture after the real window configuration change.
        if (compose.activity.worldSession.accepts(originalIdentity)) { content(); loaded() } else mount()
        assertEquals(1, reader.state.value.position.page)
        assertEquals(ReadingMode.VERTICAL, reader.state.value.position.mode)
        results.put("multiWindow", true).put("windowWidth", compose.activity.resources.configuration.screenWidthDp)
            .put("windowHeight", compose.activity.resources.configuration.screenHeightDp)
        marker.writeText(JSONObject().put("phase", "FOCUS_OTHER_WINDOW").put("namespace", namespace).toString())
        val focusDeadline = SystemClock.elapsedRealtime() + 120_000
        while (compose.activity.hasWindowFocus() && SystemClock.elapsedRealtime() < focusDeadline) SystemClock.sleep(200)
        assertFalse("Other window did not take focus", compose.activity.hasWindowFocus())
        compose.waitForIdle(); SystemClock.sleep(500)
        val unfocused = total()
        SystemClock.sleep(2_000)
        assertEquals("Visible but unfocused window counted time", unfocused, total())
        marker.writeText(JSONObject().put("phase", "FOCUS_READER").put("namespace", namespace).toString())
        val readerDeadline = SystemClock.elapsedRealtime() + 120_000
        while (!compose.activity.hasWindowFocus() && SystemClock.elapsedRealtime() < readerDeadline) SystemClock.sleep(200)
        assertTrue(compose.activity.hasWindowFocus())
        assertTrue(compose.activity.isInMultiWindowMode)
        compose.waitForIdle(); loaded(); SystemClock.sleep(1_200)
        val focused = checkpoint()
        assertTrue(focused > unfocused + 800)
        results.put("unfocused", unfocused).put("refocused", focused)
        marker.writeText(JSONObject().put("phase", "EXIT_MULTI_WINDOW").put("namespace", namespace).toString())
        val exitDeadline = SystemClock.elapsedRealtime() + 120_000
        while (compose.activity.isInMultiWindowMode && SystemClock.elapsedRealtime() < exitDeadline) SystemClock.sleep(200)
        assertFalse("Window did not return to fullscreen", compose.activity.isInMultiWindowMode)
        content(); loaded()
        assertEquals(1, reader.state.value.position.page)
        assertEquals(ReadingMode.VERTICAL, reader.state.value.position.mode)
        val before = checkpoint(); SystemClock.sleep(1_100)
        assertTrue(checkpoint() > before + 700)
        results.put("case", "multi-window").put("fullscreenRecovered", true)
        marker.writeText(JSONObject().put("phase", "COMPLETE").put("namespace", namespace).toString())
    }

    @Test fun actualLandscapeSpreadCountsOneIntervalAndRestoresPosition() {
        setup(); mount()
        val prefs = app.getSharedPreferences("reader-controls", 0)
        spreadBefore = if (prefs.contains("SFW.spread")) prefs.getBoolean("SFW.spread", false) else null
        spreadChanged = true
        assertTrue(prefs.edit().putBoolean("SFW.spread", true).commit())
        mode(ReadingMode.LTR)
        val original = compose.activity
        compose.runOnUiThread { original.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity !== original }
        content(); loaded()
        compose.onNodeWithTag("reader-loaded-1-0").assertExists()
        compose.onNodeWithTag("reader-loaded-2-0").assertExists()
        val before = checkpoint(); val started = SystemClock.elapsedRealtime()
        SystemClock.sleep(1_600)
        val after = checkpoint()
        assertTrue(after - before >= 1_300)
        assertTrue("Spread double counted", after - before < SystemClock.elapsedRealtime() - started + 500)
        results.put("spreadElapsed", after - before)
        mode(ReadingMode.RTL)
        compose.onNodeWithTag("reader-loaded-1-0").assertExists()
        compose.onNodeWithTag("reader-loaded-2-0").assertExists()
        compose.runOnUiThread { reader.position(2) }
        loaded(); runBlocking { reader.flush().join() }
        val landscape = compose.activity
        compose.runOnUiThread { landscape.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity !== landscape }
        content(); loaded()
        assertEquals(2, reader.state.value.position.page)
        assertEquals(ReadingMode.RTL, reader.state.value.position.mode)
    }

    @Test fun screenLockDoesNotCountAndManualUnlockResumes() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("statisticsLock") == "true")
        setup(); mount(); SystemClock.sleep(1_200)
        val identity = compose.activity.worldSession.identity.value
        inst.uiAutomation.executeShellCommand("input keyevent 223").close()
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!keyguard.isKeyguardLocked && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Device did not lock", keyguard.isKeyguardLocked)
        SystemClock.sleep(700)
        val locked = total()
        SystemClock.sleep(2_000)
        assertEquals("Lockscreen time counted", locked, total())
        marker.writeText(JSONObject().put("phase", "LOCKED").put("namespace", namespace).put("pid", Process.myPid()).toString())
        val unlockDeadline = SystemClock.elapsedRealtime() + 240_000
        while (keyguard.isKeyguardLocked && SystemClock.elapsedRealtime() < unlockDeadline) SystemClock.sleep(200)
        assertFalse("Manual unlock required", keyguard.isKeyguardLocked)
        // The user may unlock to the launcher; bring the same Activity back without bypassing keyguard.
        app.startActivity(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        compose.waitForIdle()
        if (!compose.activity.worldSession.accepts(identity)) {
            assertEquals(PrototypeWorld.SFW, compose.activity.worldSession.identity.value.world)
            assertEquals("Long background counted the locked interval", locked, total())
            mount()
            results.put("longBackgroundNewGeneration", true)
        } else loaded()
        SystemClock.sleep(1_500)
        val resumed = checkpoint()
        assertTrue("Unlocked body did not resume", resumed > locked + 900)
        assertTrue("Lock gap included", resumed - locked < 8_000)
        results.put("locked", locked).put("resumed", resumed)
    }

    @Test fun hostKillAfterPeriodicCheckpoint() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("statisticsDeath") == "true")
        setup(); mount(); mode(ReadingMode.VERTICAL)
        compose.runOnUiThread { reader.position(1) }
        loaded(); runBlocking { reader.flush().join() }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (total() < 5_000 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        val saved = total(); assertTrue(saved >= 5_000)
        // Deliberately keep an unsaved tail. Host kills only this PID after checking the marker.
        SystemClock.sleep(1_000)
        marker.writeText(JSONObject().put("phase", "READY_TO_KILL").put("namespace", namespace)
            .put("book", book).put("pid", Process.myPid()).put("savedMs", saved)
            .put("page", 1).put("mode", ReadingMode.VERTICAL.name).toString())
        SystemClock.sleep(30_000)
        fail("Host did not terminate the verified process")
    }

    @Test fun reopenAfterHostKill() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("statisticsRecover") == "true")
        val old = JSONObject(marker.readText())
        require(old.getString("phase") == "READY_TO_KILL")
        assertNotEquals(old.getInt("pid"), Process.myPid())
        setup(old)
        val saved = old.getLong("savedMs")
        assertEquals("Cold launch replayed or lost committed time", saved, total())
        SystemClock.sleep(1_200)
        assertEquals("Offline gap counted", saved, total())
        mount()
        assertEquals(old.getInt("page"), reader.state.value.position.page)
        assertEquals(old.getString("mode"), reader.state.value.position.mode.name)
        SystemClock.sleep(1_200)
        val resumed = checkpoint()
        assertTrue(resumed > saved + 800)
        assertTrue(resumed - saved < 6_000)
        results.put("savedBeforeKill", saved).put("recovered", resumed).put("newPid", Process.myPid())
    }
}
