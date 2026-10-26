package com.flux.manga

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.SampleManga
import com.flux.manga.data.SampleScenario
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.MangaReader
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.PageState
import com.flux.manga.ui.pages.RootPageViewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal fun ComposeContentTestRule.awaitTag(tag: String) {
    waitUntil(5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

internal fun ComposeContentTestRule.useSamplePages() {
    if (onAllNodesWithTag("local-mode").fetchSemanticsNodes().isNotEmpty()) return
    awaitTag("shelf-more")
    onNodeWithTag("shelf-actions").performClick()
    onNodeWithText("更多书库操作 ›").performScrollTo().performClick()
    onNodeWithText("开发验证").performScrollTo().performClick()
    onNodeWithText("固定样本验证").performScrollTo().performClick()
    awaitTag("local-mode") // Readiness is the mode switch, not an off-screen lazy item at 200% font.
}


/** Current product route. local-query belongs to local management, not global search. */
internal fun ComposeContentTestRule.openLocalManagement() {
    if (onAllNodesWithTag("local-query").fetchSemanticsNodes().isNotEmpty()) return
    awaitTag("shelf-more")
    onNodeWithTag("shelf-actions").performScrollTo().performClick()
    onNodeWithText("本地管理 / 导入与页序").performScrollTo().performClick()
    awaitTag("local-query")
}
internal fun ComposeContentTestRule.openSampleSearch() {
    awaitTag("open-search")
    onNodeWithTag("open-search").performClick()
    awaitTag("search-query")
}

@RunWith(AndroidJUnit4::class)
class M1PagesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performClick() }
    private fun detailClick(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "m1-pages-evidence").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun allRootsHaveLoadingEmptyErrorRetry_andSearchIsIsolated() {
        listOf(MainTab.HOME, MainTab.LIBRARY).forEach { tab ->
            click("tab-${tab.name}")
            compose.awaitTag("sample-1")
            click("root-scenario-EMPTY")
            compose.awaitTag("root-loading")
            if (tab == MainTab.LIBRARY) capture("root-loading")
            compose.awaitTag("root-empty")
            compose.onNodeWithTag("sample-1").assertDoesNotExist()
            if (tab == MainTab.LIBRARY) capture("root-empty")
            click("root-refresh")
            compose.awaitTag("sample-1")
            click("root-scenario-ERROR")
            compose.awaitTag("root-error")
            if (tab == MainTab.LIBRARY) capture("root-error")
            click("root-retry")
            compose.awaitTag("sample-1")
        }
        compose.openSampleSearch()
        compose.onNodeWithTag("search-query").performTextReplacement("no-match")
        Espresso.closeSoftKeyboard()
        compose.awaitTag("root-empty")
        compose.onNodeWithTag("search-query").performTextReplacement("60")
        Espresso.closeSoftKeyboard()
        compose.awaitTag("sample-60")
        compose.onNodeWithTag("sample-1").assertDoesNotExist()
        capture("search-result")
        compose.runOnIdle { compose.activity.navigation.select(MainTab.HOME) }
        compose.awaitTag("sample-1")
        capture("home-content")
        // The search child remains in its library stack.
        click("tab-LIBRARY")
        compose.onNodeWithTag("search-query").assertTextContains("60")
        compose.onNodeWithTag("sample-60").assertIsDisplayed()
        Log.i("FluxM1Test", "PASS sample home/library roots and search child: loading/empty/error/retry; query filtering and tab isolation")
    }

    @Test fun detailReaderRoutes_statesRecreationAndPopOwnership() {
        compose.awaitTag("sample-3")
        click("sample-3")
        compose.awaitTag("detail-content")
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        detailClick("add-mark")
        detailClick("detail-scenario-EMPTY")
        compose.awaitTag("detail-empty")
        detailClick("detail-refresh")
        compose.awaitTag("detail-content")
        detailClick("detail-scenario-ERROR")
        compose.awaitTag("detail-error")
        capture("detail-error")
        detailClick("detail-retry")
        compose.awaitTag("detail-content")
        compose.onNodeWithTag("detail-marks").assertTextEquals("验证标记：1")
        capture("detail-content")
        detailClick("open-reader")
        compose.awaitTag("reader-content")
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(MangaReader(MainTab.LIBRARY, PrototypeWorld.SFW, "sample", "3", "chapter-1"), compose.activity.navigation.currentStack.last())
        }
        detailClick("reader-scenario-EMPTY")
        compose.awaitTag("reader-empty")
        detailClick("reader-refresh")
        compose.awaitTag("reader-content")
        detailClick("reader-scenario-ERROR")
        compose.awaitTag("reader-error")
        detailClick("reader-retry")
        compose.awaitTag("reader-content")
        detailClick("reader-next")
        compose.onNodeWithTag("reader-page").assertTextEquals("占位页 2 / 3")
        val session = compose.activity.worldSession
        val identity = session.identity.value
        compose.activityRule.scenario.recreate()
        compose.awaitTag("reader-content")
        assertSame(session, compose.activity.worldSession)
        assertEquals(identity, compose.activity.worldSession.identity.value)
        compose.onNodeWithTag("reader-page").assertTextEquals("占位页 2 / 3")
        capture("reader-recreated")
        detailClick("reader-next")
        compose.onNodeWithTag("reader-next").assertIsNotEnabled()
        detailClick("reader-previous")
        detailClick("reader-back")
        compose.awaitTag("detail-content")
        compose.onNodeWithTag("detail-marks").assertTextEquals("验证标记：1")
        detailClick("open-reader")
        compose.awaitTag("reader-content")
        compose.onNodeWithTag("reader-page").assertTextEquals("占位页 1 / 3")
        Espresso.pressBack()
        compose.awaitTag("detail-content")
        Espresso.pressBack()
        compose.awaitTag("list-LIBRARY")
        compose.onNodeWithTag("main-tabs").assertIsDisplayed()
        Log.i("FluxM1Test", "PASS detail/reader identity, empty/error/retry, recreation retains session/page, pop releases reader only, back restores root")
    }

    @Test fun replacementAndEntryDisposalCancelRequests_andSessionIsNotSaved(): Unit = runBlocking {
        lateinit var model: RootPageViewModel
        lateinit var owner: ViewModelStore
        val saved = SavedStateHandle(mapOf("query" to "no-match"))
        val session = compose.activity.worldSession
        compose.runOnIdle {
            owner = ViewModelStore()
            model = RootPageViewModel(saved, session)
            owner.put("page", model)
            model.selectScenario(SampleScenario.ERROR)
            model.selectScenario(SampleScenario.CONTENT)
            model.updateQuery("60")
        }
        compose.waitUntil(5_000) { model.state.value is PageState.Content }
        val content = model.state.value as PageState.Content<List<SampleManga>>
        assertEquals(listOf("60"), content.value.map { it.id })
        delay(500)
        assertEquals(content, model.state.value)
        compose.runOnIdle {
            model.selectScenario(SampleScenario.ERROR)
            owner.clear()
        }
        delay(600)
        assertEquals(PageState.Loading, model.state.value) // Cleared request cannot publish an error.
        assertNotEquals(session.identity.value.processSession, WorldSession().identity.value.processSession)
        assertFalse(session.accepts(session.identity.value.copy(generation = 1)))
        assertEquals("60", saved.get<String>("query"))
        Log.i("FluxM1Test", "PASS replacement has no stale error, entry disposal cancels load, SavedState owns query but not session identity")
    }
}
