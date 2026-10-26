package com.flux.manga

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.flux.manga.data.*
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.*
import kotlinx.coroutines.flow.distinctUntilChanged
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Component checks only; no real books, preferences, system font or server are mutated. */
class ReadingModeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }

    @Test fun largeTextSettingsKeepDraftUntilSaveAndExposeReadFailure() {
        var state by mutableStateOf(ReadingSettingsState(ReadingMode.LTR, ReadingMode.LTR, loading = false))
        var world by mutableStateOf(PrototypeWorld.SFW)
        var saves = 0
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Box(Modifier.width(320.dp)) {
                        ReadingSettingsContent(world, state, { state = state.copy(draft = it) },
                            { saves++; state = state.copy(stored = state.draft) }, {}, {})
                    }
                }
            }
        } }
        for (space in PrototypeWorld.entries) {
            compose.runOnIdle { world = space; state = ReadingSettingsState(ReadingMode.LTR, ReadingMode.LTR, loading = false) }
            compose.onNodeWithTag("reading-settings").performScrollToNode(hasTestTag("reading-default-VERTICAL"))
            compose.onNodeWithTag("reading-default-VERTICAL").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(ReadingMode.LTR, state.stored); assertEquals(ReadingMode.VERTICAL, state.draft) }
            compose.onNodeWithTag("reading-settings").performScrollToNode(hasTestTag("reading-default-save"))
            compose.onNodeWithTag("reading-default-save").performClick()
            compose.runOnIdle { assertEquals(ReadingMode.VERTICAL, state.stored) }
        }
        compose.runOnIdle { assertEquals(2, saves); state = ReadingSettingsState(loading = false, error = "读取失败") }
        compose.onNodeWithTag("reading-default-save").assertDoesNotExist()
        compose.onNodeWithText("重试读取").assertExists()
    }

    @Test fun sharedPagedReaderSnapsInEachDirectionAndKeepsModeSwitchAnchor() {
        var mode by mutableStateOf(ReadingMode.LTR)
        var page by mutableIntStateOf(2)
        var fraction by mutableFloatStateOf(.625f)
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme {
                key(mode) {
                    val capturedMode = mode
                    val pager = rememberPagerState(initialPage = page) { 8 }
                    LaunchedEffect(pager) {
                        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
                            fraction = pagedFraction(page, it, fraction); page = it
                        }
                    }
                    ReadingPager(pager, capturedMode, "test-pager", { it }) {
                        Box(Modifier.fillMaxSize().testTag("test-page-$it")) { Text("第 ${it + 1} 页") }
                    }
                }
            }
        } }
        compose.runOnIdle { mode = ReadingMode.VERTICAL }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, page); assertEquals(.625f, fraction) }
        compose.onNodeWithTag("test-pager").performTouchInput { swipeUp() }
        compose.waitUntil { page == 3 }
        compose.runOnIdle { assertEquals(0f, fraction); mode = ReadingMode.RTL }
        compose.onNodeWithTag("test-pager").performTouchInput { swipeRight() }
        compose.waitUntil { page == 4 }
        compose.runOnIdle { mode = ReadingMode.LTR }
        compose.onNodeWithTag("test-pager").performTouchInput { swipeLeft() }
        compose.waitUntil { page == 5 }
    }
}
