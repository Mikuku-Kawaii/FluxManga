package com.flux.manga

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var readerTitle = ReaderFixtures.title
    private val library get() = CbzLibrary(WorldDataStore(compose.activity))
    private val evidence get() = File(compose.activity.getExternalFilesDir(null), "m2-reader-evidence").apply { mkdirs() }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag != "reader-tools" && tag != "reader-back") node.performScrollTo()
        node.performClick()
    }
    private fun enterLibrary() {
        compose.openLocalManagement()
        compose.awaitTag("local-query")
        compose.onNodeWithTag("local-query").performTextReplacement(readerTitle)
        Espresso.closeSoftKeyboard()
    }
    private fun openDetail() {
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-book-${readerTitle}"))
        compose.onNodeWithTag("local-book-${readerTitle}").performClick()
        compose.awaitTag("local-book-title")
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-read"))
        click("local-read"); compose.awaitTag("local-reader")
    }
    private fun page(number: Int) {
        compose.waitUntil(15000) { compose.onAllNodesWithText("第 $number / 5 页 · 单章节").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun saved() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("进度已保存").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun jump(number: Int) {
        compose.onNodeWithTag("reader-jump-input").performScrollTo().performTextReplacement(number.toString())
        Espresso.closeSoftKeyboard(); click("reader-jump"); page(number)
    }
    @Test fun repeatedPagedSwipesWithAsyncImages(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerStress") == "true")
        val key = ReaderFixtures.prepareUnique(library, evidence)
        readerTitle = library.detail(key)!!.card.title
        library.saveProgress(key, "page1.png", 0f, ReadingMode.LTR)
        enterLibrary(); openDetail(); page(1)
        repeat(12) { round ->
            // Do not wait for image decode: exercise snapshots arriving during pager layout.
            compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; page(2)
            compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; page(3)
            compose.onNodeWithTag("reader-pager").performTouchInput { swipeRight() }; page(2)
            compose.onNodeWithTag("reader-pager").performTouchInput { swipeRight() }; page(1)
            if (round % 3 == 2) { compose.activityRule.scenario.recreate(); page(1) }
        }
        saved()
        assertEquals("page1.png", library.detail(key)!!.card.progress!!.pageId)
    }
    @Test fun modesSwipesStripAnchorRecreationBackgroundAndContinue(): Unit = runBlocking {
        val key = ReaderFixtures.prepareUnique(library, evidence)
        readerTitle = library.detail(key)!!.card.title // Import deduplication keeps the existing book title.
        library.saveProgress(key, "page1.png", 0f, ReadingMode.LTR)
        enterLibrary(); openDetail(); page(1)
        compose.awaitTag("reader-loaded-1-0")
        compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; page(2)
        click("reader-mode-RTL")
        compose.onNodeWithTag("reader-pager").performTouchInput { swipeRight() }; page(3)
        click("reader-prev"); page(2)
        compose.awaitTag("reader-loaded-2-0")
        compose.onNodeWithTag("reader-zoom").performClick()
        compose.onNodeWithText("还原").assertExists()
        compose.onNodeWithTag("reader-zoom").performClick()
        capture("paged-${if (compose.activity.resources.configuration.fontScale > 1.5) "large" else "normal"}")
        jump(3); click("reader-mode-STRIP")
        compose.awaitTag("reader-strip")
        compose.onNodeWithTag("reader-strip").performTouchInput { swipeUp() }
        saved()
        val anchor = library.detail(key)!!.card.progress!!
        assertEquals("STRIP", anchor.mode); assertEquals("page3.png", anchor.pageId); assertTrue(anchor.fraction > 0f)
        compose.activityRule.scenario.recreate()
        compose.awaitTag("reader-strip"); saved()
        val recreated = library.detail(key)!!.card.progress!!
        assertEquals(anchor.pageId, recreated.pageId); assertEquals(anchor.fraction, recreated.fraction, .015f)
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.awaitTag("reader-strip"); saved()
        compose.onNodeWithTag("reader-strip").assertHeightIsAtLeast(48.dp)
        capture("landscape-${if (compose.activity.resources.configuration.fontScale > 1.5) "large" else "normal"}")
        assertEquals(anchor.fraction, library.detail(key)!!.card.progress!!.fraction, .015f)
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        compose.awaitTag("reader-strip"); saved()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.awaitTag("reader-strip"); saved()
        capture("strip-${if (compose.activity.resources.configuration.fontScale > 1.5) "large" else "normal"}")
        jump(3); saved()
        assertEquals(0f, library.detail(key)!!.card.progress!!.fraction, .001f)
        compose.awaitTag("reader-loaded-3-0")
        click("reader-tools"); compose.onNodeWithTag("reader-mode-STRIP").assertDoesNotExist()
        click("reader-tools")
        jump(5); saved(); click("reader-back")
        compose.awaitTag("local-list")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-progress"))
        compose.onNodeWithTag("local-progress").assertTextContains("上次阅读：第 5 页")
        Espresso.pressBack(); compose.awaitTag("local-list")
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-query"))
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-continue-${key.mangaId}"))
        compose.onNodeWithTag("local-continue-${key.mangaId}").performScrollTo().performClick()
        compose.awaitTag("reader-strip"); page(5)
        File(evidence, "ui-${if (compose.activity.resources.configuration.fontScale > 1.5) "large" else "normal"}.txt")
            .writeText("PASS LTR/RTL swipes, page navigation, zoom, tiled strip, fractional anchor recreate/background, tools, detail/list continue; anchor=${anchor.fraction}")
    }
    @Test fun holdForExternalRestart(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerPhase") == "hold")
        val key = ReaderFixtures.prepareUnique(library, evidence)
        readerTitle = library.detail(key)!!.card.title // Import deduplication keeps the existing book title.
        library.saveProgress(key, "page3.png", .3f, ReadingMode.STRIP)
        enterLibrary(); openDetail(); compose.awaitTag("reader-strip")
        compose.onNodeWithTag("reader-strip").performTouchInput { swipeUp() }; saved()
        val detail = library.detail(key)!!
        val progress = detail.card.progress!!
        File(evidence, "restart-ready.json").writeText(JSONObject().put("pid", Process.myPid())
            .put("id", key.mangaId).put("page", progress.pageId).put("fraction", progress.fraction)
            .put("sha256", detail.sha256).toString())
        Thread.sleep(120000) // Host kills this instrumentation while the reader is still open.
        fail("Host did not stop the reader process")
    }
    @Test fun continueAfterExternalRestart(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerPhase") == "resume")
        val old = JSONObject(File(evidence, "restart-ready.json").readText())
        assertNotEquals(old.getInt("pid"), Process.myPid())
        val key = MangaKey(PrototypeWorld.SFW, "cbz", old.getString("id"))
        readerTitle = library.detail(key)!!.card.title
        enterLibrary()
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-continue-${key.mangaId}"))
        compose.onNodeWithTag("local-continue-${key.mangaId}").performScrollTo().performClick()
        compose.awaitTag("reader-strip"); saved()
        val detail = library.detail(key)!!; val progress = detail.card.progress!!
        assertEquals(old.getString("sha256"), detail.sha256)
        assertEquals(old.getString("page"), progress.pageId)
        assertEquals(old.getDouble("fraction").toFloat(), progress.fraction, .015f)
        capture("restarted-strip")
        File(evidence, "restart-result.txt").writeText("PASS external force-stop ${old.getInt("pid")} -> ${Process.myPid()}, SFW library continue restores ${progress.pageId} / ${progress.fraction} / ${progress.mode}; archive SHA256 unchanged")
    }
}


