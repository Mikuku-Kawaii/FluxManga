package com.flux.manga

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalHomeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val lib get() = CbzLibrary(WorldDataStore(compose.activity))
    private fun scroll(tag: String, list: String = "reference-shelf") {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
    }
    private fun click(tag: String, list: String = "reference-shelf") { scroll(tag, list); compose.onNodeWithTag(tag).performClick() }
    private fun refresh() {
        scroll("shelf-more")
        compose.onNodeWithTag("shelf-actions").performClick()
        compose.onNodeWithText("刷新书库").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("shelf-loading").fetchSemanticsNodes().isEmpty() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        // Dialog window animation is outside Compose's test clock.
        android.os.SystemClock.sleep(400)
        val profile = if (compose.activity.resources.configuration.fontScale > 1.5f) "large" else "normal"
        val dir = File(compose.activity.getExternalFilesDir(null), "m2-home-evidence").apply { mkdirs() }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name-$profile.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun libraryContinueAndAllBooks_removeCancelConfirmAndReturn(): Unit = runBlocking {
        val library = lib
        val before = library.cards(PrototypeWorld.SFW).associate { it.key to library.detail(it.key) }
        val nonce = java.util.UUID.randomUUID().toString()
        val title = "UI-R7 合成主页 $nonce"
        // Per-run identity; never remove a pre-existing synthetic book or overwrite an original.
        val original = File(compose.activity.getExternalFilesDir(null), "$nonce.cbz").apply {
            writeBytes(CbzFixtures.archive(listOf("page1.png" to CbzFixtures.image(color = Color.rgb(240, 240, 230)),
                "page2.png" to CbzFixtures.image(color = Color.rgb(30, 30, 60)), "fixture.txt" to nonce.toByteArray())))
        }
        val hash = ReaderFixtures.hash(original); val modified = original.lastModified()
        val key = library.import(PrototypeWorld.SFW, CbzSource("$title.cbz") { original.inputStream() }).key
        val tile = "shelf-book-${key.world}/local/${key.sourceId.length}:${key.sourceId}${key.mangaId.length}:${key.mangaId}"
        library.saveProgress(key, "page2.png", 0f, ReadingMode.RTL)
        compose.awaitTag("shelf-more"); refresh()
        scroll("shelf-continue"); compose.onNodeWithTag("shelf-continue").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        capture("continue-cover")
        scroll("shelf-reading-filters")
        val readingBounds = compose.onNodeWithTag("shelf-reading-filters").fetchSemanticsNode().boundsInRoot
        val groupBounds = compose.onNodeWithTag("shelf-group-filters").fetchSemanticsNode().boundsInRoot
        assertTrue("Filter sections overlap", readingBounds.bottom <= groupBounds.top)
        capture("collection-filters")
        click("shelf-continue")
        compose.awaitTag("local-reader")
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        compose.waitUntil(10000) { compose.onAllNodesWithText("第 2 / 2 页 · 单章节").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-back").performClick()
        compose.awaitTag("reference-shelf")
        compose.activityRule.scenario.recreate(); compose.awaitTag("reference-shelf")
        click("shelf-actions")
        compose.onNodeWithText("全部书目").performScrollTo().performClick()
        compose.awaitTag("reference-shelf"); refresh()
        scroll(tile); capture("all-books-cover")
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag(tile))).performClick()
        compose.awaitTag("local-book-title"); click("local-remove", "local-list")
        compose.awaitTag("local-remove-cancel"); compose.onNodeWithTag("local-remove-cancel").performClick()
        assertNotNull(library.detail(key))
        click("local-remove", "local-list"); compose.awaitTag("local-remove-confirm")
        capture("remove-scope")
        compose.onNodeWithTag("local-remove-confirm").performClick()
        compose.awaitTag("reference-shelf"); refresh()
        assertNull(library.detail(key))
        compose.onNodeWithTag(tile).assertDoesNotExist()
        compose.onNodeWithTag("reference-shelf").performScrollToIndex(0)
        compose.onNodeWithText("返回").performClick(); compose.awaitTag("main-tabs")
        compose.onNodeWithTag("tab-LIBRARY").performClick(); compose.awaitTag("reference-shelf")
        refresh()
        // Existing books / progress and the per-run original survive removal of our copy.
        before.forEach { (oldKey, detail) -> assertEquals(detail, library.detail(oldKey)) }
        assertEquals(hash, ReaderFixtures.hash(original)); assertEquals(modified, original.lastModified())
        val dir = File(compose.activity.getExternalFilesDir(null), "m2-home-evidence")
        File(dir, "source-preserved.txt").writeText("PASS synthetic original SHA256=$hash; mtime=$modified; cancel preserves record; confirm removes it; pre-existing metadata and progress unchanged")
    }
}
