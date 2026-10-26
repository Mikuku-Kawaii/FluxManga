package com.flux.manga

import android.content.pm.ActivityInfo
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.manga.ui.navigation.MainTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationPrototypeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }

    private fun selectStack(tab: MainTab) {
        val activity = compose.activity
        compose.runOnIdle { activity.navigation.select(tab) }
    }

    @Test
    fun rootsKeepScrollQueryAndReselection_detailReturnsToItsRoot() {
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 书库")
        compose.awaitTag("sample-1")
        compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(20)
        compose.onNodeWithTag("tab-HOME").performClick()
        compose.awaitTag("sample-1")
        compose.onNodeWithTag("list-HOME").performScrollToIndex(30)
        compose.onNodeWithTag("tab-HOME").performClick()
        compose.onNodeWithTag("sample-31").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("sample-21").assertIsDisplayed()
        compose.onNodeWithTag("open-search").performClick()
        compose.onNodeWithTag("search-query").performTextInput("orbit")
        Espresso.closeSoftKeyboard()
        compose.awaitTag("sample-1")
        compose.onNodeWithTag("list-SEARCH").performScrollToIndex(10)
        compose.onNodeWithTag("sample-11").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail-title").assertTextEquals("书库 · 作品 11")
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithTag("list-SEARCH").assertIsDisplayed()
        compose.onNodeWithTag("sample-11").assertIsDisplayed()
        // The header now scrolls with the page; check restored position before scrolling to query.
        compose.onNodeWithTag("list-SEARCH").performScrollToIndex(0)
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 搜索")
        compose.onNodeWithTag("search-query").assertTextContains("orbit")
        Espresso.pressBack()
        compose.onNodeWithTag("sample-21").assertIsDisplayed()
        compose.onNodeWithTag("tab-TAGS").performClick()
        compose.onNodeWithTag("list-TAGS").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("sample-21").assertIsDisplayed()
        Log.i("FluxNavTest", "PASS root scroll/query/reselection/detail return")
    }

    @Test
    fun independentDetailStacks_surviveRealRotation_popReleasesOnlyItsEntry() {
        Log.i("FluxNavTest", "BEGIN independent stacks")
        MainTab.primary.forEachIndexed { index, tab ->
            // Product detail screens hide the main bar. Drive the same selection action
            // directly to exercise dormant detail stacks without adding test-only UI.
            selectStack(tab)
            // For the real tag stack, inject a synthetic
            // child to exercise that stack's entry ownership, not tag functionality.
            compose.runOnIdle { compose.activity.navigation.openDetail("1") }
            compose.awaitTag("detail-content")
            repeat(index + 1) { compose.onNodeWithTag("add-mark").performScrollTo().performClick() }
        }
        Log.i("FluxNavTest", "ALL_STACKS_READY")
        val oldActivity = compose.activity
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) { compose.activity !== oldActivity }
        compose.waitForIdle()
        MainTab.primary.forEachIndexed { index, tab ->
            selectStack(tab)
            compose.onNodeWithTag("detail-marks").assertTextEquals("验证标记：${index + 1}")
            selectStack(tab)
            compose.onNodeWithTag("detail-marks").assertTextEquals("验证标记：${index + 1}")
            assertEquals(2, compose.activity.navigation.stacks.getValue(tab).size)
        }
        Log.i("FluxNavTest", "ROTATION_AND_RESELECT_PASSED")
        selectStack(MainTab.HOME)
        compose.onNodeWithTag("detail-back").performScrollTo().performClick()
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 首页")
        compose.onNodeWithTag("sample-1").performClick()
        compose.awaitTag("detail-content")
        compose.onNodeWithTag("detail-marks").assertTextEquals("验证标记：0")
        selectStack(MainTab.LIBRARY)
        compose.onNodeWithTag("detail-marks").assertTextEquals("验证标记：2")
        selectStack(MainTab.TAGS)
        compose.onNodeWithTag("detail-marks").assertTextEquals("验证标记：3")
        Log.i("FluxNavTest", "POP_ISOLATION_PASSED")
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Test
    fun systemBackHidesKeyboard_thenReturnsToLibrary_thenLeavesApp() {
        compose.onNodeWithTag("tab-HOME").performClick()
        compose.onNodeWithTag("open-search").performClick()
        compose.onNodeWithTag("search-query").performClick().performTextInput("keyboard")
        compose.waitForIdle()
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 搜索")
        // Search is a child: hiding IME must not reveal the root dock or pop the page.
        compose.waitUntil(5_000) {
            !androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
                .isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 首页")
        Espresso.pressBack()
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 书库")
        Espresso.pressBackUnconditionally()
        compose.waitUntil(5_000) { compose.activityRule.scenario.state != Lifecycle.State.RESUMED }
        Log.i("FluxNavTest", "PASS IME/root/library/system exit")
    }
}
