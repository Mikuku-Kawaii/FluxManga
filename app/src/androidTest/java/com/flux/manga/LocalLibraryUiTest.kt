package com.flux.manga

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val library get() = CbzLibrary(WorldDataStore(compose.activity))
    private fun scroll(tag: String) {
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag(tag))
    }
    private fun click(tag: String) { scroll(tag); compose.onNodeWithTag(tag).performClick() }
    private fun filter(value: String) {
        scroll("local-query")
        compose.onNodeWithTag("local-query").performTextReplacement(value)
        Espresso.closeSoftKeyboard()
        scroll("local-count")
    }
    private fun count(expected: Int) {
        scroll("local-count")
        compose.waitUntil(10000) { compose.onAllNodesWithText("$expected 本").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun favoriteState(key: MangaKey, expected: Boolean) {
        val tag = "local-favorite-${key.mangaId}"
        compose.waitUntil(10000) {
            scroll(tag)
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Selected] == expected
        }
    }
    @Test fun searchFavoritesDetailReturnAndRecreation(): Unit = runBlocking {
        val (a, _) = LocalLibraryFixtures.prepare(library)
        compose.openLocalManagement()
        compose.awaitTag("local-query")
        filter("合成检索"); count(2)
        filter("ALPHA_100%"); count(1)
        click("local-favorite-${a.mangaId}")
        favoriteState(a, true)
        click("local-favorites"); count(1)
        filter("Beta"); count(0)
        scroll("local-empty")
        compose.onNodeWithTag("local-empty").assertTextEquals("没有匹配的本地标题")
        filter("ALPHA_100%"); count(1)
        compose.activityRule.scenario.recreate()
        compose.awaitTag("local-query")
        scroll("local-query"); compose.onNodeWithTag("local-query").assertTextContains("ALPHA_100%")
        scroll("local-favorites"); compose.onNodeWithTag("local-favorites").assertIsSelected()
        count(1)
        click("local-book-${LocalLibraryFixtures.alpha}")
        compose.awaitTag("local-book-title")
        favoriteState(a, true)
        click("local-favorite-${a.mangaId}")
        favoriteState(a, false)
        Espresso.pressBack()
        compose.awaitTag("local-query")
        count(0) // Parent entry refreshes after detail changes; no manual refresh.
        click("local-all"); count(1)
        favoriteState(a, false)
        click("local-favorite-${a.mangaId}"); favoriteState(a, true)
        click("local-favorites"); count(1)
        filter("合成检索"); count(1)
        val evidence = File(compose.activity.getExternalFilesDir(null), "m2-library-evidence").apply { mkdirs() }
        val profile = if (compose.activity.resources.configuration.fontScale > 1.5f) "large-font" else "normal"
        scroll("local-query")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {
            File(evidence, "library-$profile.png").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }; it.recycle()
        }
        assertTrue(library.detail(a)!!.card.favorite)
        // Returning through the root and switching worlds discards entry-owned filters.
        Espresso.pressBack()
        compose.awaitTag("switch-world")
        compose.onNodeWithTag("switch-world").performClick()
        compose.awaitTag("root-title")
        assertEquals(PrototypeWorld.NSFW, compose.activity.worldSession.identity.value.world)
        compose.onNodeWithTag("open-local-library").assertDoesNotExist()
        compose.onNodeWithTag("switch-world").performClick()
        compose.openLocalManagement()
        compose.awaitTag("local-query")
        scroll("local-query"); assertEquals("", compose.onNodeWithTag("local-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        scroll("local-all"); compose.onNodeWithTag("local-all").assertIsSelected()
        filter("ALPHA_100%"); count(1); favoriteState(a, true)
        File(evidence, "ui-$profile.txt").writeText("PASS literal Unicode/case-insensitive title search; composed favorite filter; zero results; favorite/unfavorite; detail back refresh; Activity recreation retains filters; world switch clears filters and retains SFW favorite; only synthetic CBZ fixtures")
    }
}
