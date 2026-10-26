package com.flux.manga

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.TopicCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Decorative fixtures only; no real private tags or source requests. */
class TopicArtworkUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }
    @Test fun decorativeSceneKeepsOneAccessibleActionInBothPalettes() {
        var world by mutableStateOf(PrototypeWorld.SFW)
        var opens = 0
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                Column(Modifier.width(160.dp)) { TopicCard("奇幻") { opens++ } }
            }
        } }
        for (palette in PrototypeWorld.entries) {
            compose.runOnIdle { world = palette }
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            compose.onNode(hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("奇幻").assertExists()
        }
        compose.runOnIdle { assertEquals(2, opens) }
    }
    @Test fun unknownLongTagRemainsCompleteAtLargeTextAndKeepsItsCallback() {
        val name = "合成自定义标签：不改变原名称与原图源查询"
        var opened: String? = null
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.SFW)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        TopicCard(name) { opened = name }
                    }
                }
            }
        } }
        compose.onNodeWithText(name).assertExists()
        compose.onNode(hasClickAction()).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(name, opened) }
    }
}
