package com.flux.manga

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.flux.manga.data.*
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic fixtures; no network, queue writes, installs or background services. */
class DownloadUiFixtureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }

    @Test fun largeTextChapterSelectionKeepsFullTitleAndAccessibleTarget() {
        var world by mutableStateOf(PrototypeWorld.SFW)
        var selected by mutableStateOf(false)
        var count = 0
        val title = "合成长章节标题：保留既有阅读位置与下载身份"
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        DownloadChapterChoice(title, selected, true, "已暂停") { selected = it; count++ }
                    }
                }
            }
        } }
        for (palette in PrototypeWorld.entries) {
            compose.runOnIdle { world = palette }
            compose.onNodeWithText(title).assertExists()
            compose.onNode(isToggleable()).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.runOnIdle { assertEquals(2, count); assertFalse(selected) }
    }

    @Test fun clearingTitleFilterDoesNotClearHiddenSelection() {
        var query by mutableStateOf("missing")
        val selected = listOf("a", "b")
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                    DownloadTitleFilter(query, { query = it }, if(query.isBlank()) 2 else 0, 2)
                    DownloadSelectionSummary(selected, if(query.isBlank()) selected else emptyList())
                }
            }
        } }
        compose.onNodeWithText("已选 2 项，其中 2 项在筛选外；选中操作仍包含这些项目。").assertExists()
        compose.onNodeWithText("清除标题筛选").performScrollTo().performClick()
        compose.onNodeWithText("已选 2 项").assertExists()
        compose.onNodeWithText("当前显示 2 / 2 项").assertExists()
    }

    @Test fun unknownProgressIsIndeterminateOnlyWhileActive() {
        var active by mutableStateOf(true)
        var total by mutableStateOf<Long?>(null)
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme { Column { TransferProgress(2, total, active, "合成下载进度") } }
        } }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.runOnIdle { active = false }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        compose.runOnIdle { total = 4 }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.5f, 0f..1f))).assertExists()
    }
}
