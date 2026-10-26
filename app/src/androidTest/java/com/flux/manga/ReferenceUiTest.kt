package com.flux.manga

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.flux.manga.ui.glass.DockEffects
import com.flux.manga.ui.glass.LiquidBottomBar
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.ReferenceButton
import com.flux.manga.ui.pages.ReferenceHeading
import com.flux.manga.ui.pages.ReferencePanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No fixture imports, preference writes or device configuration changes. Not yet run on device. */
class ReferenceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }

    @Test fun discoveryRoots_doNotExposeLibrarySectionsOrUserTagManagement() {
        compose.awaitTag("shelf-more")
        compose.onNodeWithTag("tab-HOME").performClick()
        compose.awaitTag("discovery-home")
        compose.onNodeWithTag("open-search").assertIsDisplayed()
        compose.onAllNodesWithText("继续阅读").assertCountEquals(0)
        compose.onAllNodesWithText("最近导入").assertCountEquals(0)
        compose.onNodeWithTag("discovery-home").performScrollToNode(hasTestTag("recommendations-status"))
        compose.onNodeWithTag("recommendations-status").assertIsDisplayed()
        compose.onNodeWithTag("discovery-home").performScrollToNode(hasTestTag("source-updates-status"))
        compose.onNodeWithTag("source-updates-status").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.awaitTag("discovery-home")
        compose.onNodeWithTag("tab-TAGS").performClick()
        compose.awaitTag("list-TAGS")
        compose.onNodeWithTag("list-TAGS").performScrollToNode(hasTestTag("source-tag-query"))
        compose.onNodeWithTag("source-tag-query").assertIsDisplayed()
        for (label in listOf("添加", "创建用户标签", "用户标签筛选", "重命名", "删除"))
            compose.onAllNodesWithText(label).assertCountEquals(0)
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.awaitTag("shelf-more")
        compose.onNodeWithTag("shelf-actions").performClick()
        compose.onNodeWithText("全部书目").performScrollTo().performClick()
        compose.onNodeWithTag("root-title").assertTextEquals("全部书目")
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
    }

    @Test fun homeSectionHeadingsOpenIndependentPagesAndRestoreHome() {
        compose.awaitTag("shelf-more")
        compose.onNodeWithTag("tab-HOME").performClick(); compose.awaitTag("discovery-home")
        compose.onNodeWithTag("discovery-source-status").assertDoesNotExist()
        for ((button, page) in listOf("open-recommendations-status" to "mixed-book-list",
            "open-source-updates-status" to "mixed-book-list", "open-visits" to "visits-page")) {
            compose.onNodeWithTag("discovery-home").performScrollToNode(hasTestTag(button))
            compose.onNodeWithTag(button).performClick(); compose.awaitTag(page)
            compose.onNodeWithTag("main-tabs").assertDoesNotExist()
            compose.activityRule.scenario.recreate(); compose.awaitTag(page)
            Espresso.pressBack(); compose.awaitTag("discovery-home")
            compose.onNodeWithTag("tab-HOME").assertIsSelected()
        }
        compose.onNodeWithTag("discovery-home").performScrollToNode(hasTestTag("open-search"))
        compose.onNodeWithTag("open-search").performClick()
        compose.awaitTag("online-search-history")
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        Espresso.pressBack(); compose.awaitTag("discovery-home")
    }

    @Test fun productSearch_noMatchRetainsQueryAcrossRecreation_andReturnsToLibrary() {
        compose.awaitTag("shelf-more")
        compose.onNodeWithTag("shelf-actions").performClick()
        compose.onNodeWithText("搜索").performScrollTo().performClick()
        compose.onNodeWithTag("reference-shelf").performScrollToNode(hasTestTag("shelf-query"))
        val query = "UI-R7-no-match-${java.util.UUID.randomUUID()}"
        compose.onNodeWithTag("shelf-query").performTextReplacement(query)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        compose.onNodeWithTag("reference-shelf").performScrollToNode(hasTestTag("shelf-empty"))
        compose.onNodeWithText("没有符合当前查询与筛选的书目").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.awaitTag("reference-shelf")
        compose.onNodeWithTag("reference-shelf").performScrollToNode(hasTestTag("shelf-query"))
        compose.onNodeWithTag("shelf-query").assertTextContains(query)
        Espresso.pressBack()
        compose.awaitTag("main-tabs")
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
    }

    @Test fun componentProfiles_keepLabelsTargetsAndSelection_atLargeFont() {
        var width by mutableStateOf(392.dp)
        var font by mutableFloatStateOf(1f)
        var world by mutableStateOf(PrototypeWorld.SFW)
        var low by mutableStateOf(false)
        var selected by mutableStateOf(MainTab.LIBRARY)
        compose.runOnUiThread {
            // Replace the screen with composition-only fixtures; never change system font_scale.
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                    MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                        Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
                            ReferenceHeading("很长的标题与无空格标识ABCDEFGHIJKLMNOPQRSTUVWXYZ", "布局样本", {})
                            ReferencePanel {
                                Text("读取失败，已保留查询与内容。可以滚动后重试。")
                                ReferenceButton({}, Modifier.testTag("profile-retry")) { Text("重试读取内容") }
                            }
                            LiquidBottomBar(world, selected, { selected = it }, {},
                                effects = DockEffects(lowEffects = low, reduceMotion = true))
                        }
                    }
                }
            }
        }
        for (profile in listOf(392 to 1f, 320 to 1f, 320 to 2f)) {
            for (space in PrototypeWorld.entries) for (lowEffects in listOf(false, true)) {
                compose.runOnIdle {
                    width = profile.first.dp; font = profile.second; world = space; low = lowEffects
                }
                compose.onNodeWithTag("profile-retry").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("switch-world").performScrollTo().assertHeightIsAtLeast(48.dp)
                val circle = compose.onNodeWithTag("dock-circle").fetchSemanticsNode().boundsInRoot
                assertEquals(circle.width, circle.height, 1f)
                MainTab.primary.forEach { tab ->
                    compose.onNodeWithTag("tab-${tab.name}").performScrollTo()
                        .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithTag("dock-label-${tab.name}", true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    if (layout.hasVisualOverflow) {
                        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                        val file = java.io.File(compose.activity.getExternalFilesDir(null), "oppo-dock-overflow.png")
                        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    assertFalse("Clipped label: $profile / $space / $tab size=${layout.size} paragraph=${layout.multiParagraph.width} right=${layout.getLineRight(0)} constraints=${layout.layoutInput.constraints} lines=${layout.lineCount} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}", layout.hasVisualOverflow)
                }
            }
        }
    }
}
