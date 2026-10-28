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
import androidx.test.espresso.Espresso
import com.flux.manga.ui.glass.DockEffects
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.SettingsPage
import com.flux.manga.ui.pages.SourceManagementPage
import com.flux.manga.ui.pages.DockEffectControls
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only compiled this round. No library writes, imports or device settings changes. */
class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }

    @Test fun allThreeRootsOpenSettingsAndReturnThroughTheirOwnStack() {
        compose.awaitTag("shelf-more")
        for ((tab, more) in listOf("HOME" to "home-more", "LIBRARY" to "shelf-more", "TAGS" to "tags-more")) {
            compose.onNodeWithTag("tab-$tab").performClick()
            compose.awaitTag(more)
            compose.onNodeWithTag(more).performClick()
            compose.awaitTag("settings-page")
            compose.onNodeWithTag("main-tabs").assertDoesNotExist()
            compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("settings-sources"))
            compose.onNodeWithTag("settings-sources").performScrollTo().performClick()
            compose.awaitTag("source-management")
            compose.onNodeWithText("拷贝漫画").assertExists()
            compose.onNodeWithText("包子漫画").assertExists()
            Espresso.pressBack(); compose.awaitTag("settings-page")
            compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("settings-appearance"))
            compose.onNodeWithTag("settings-appearance").performScrollTo().performClick()
            compose.awaitTag("appearance-settings")
            compose.activityRule.scenario.recreate()
            compose.awaitTag("appearance-settings")
            compose.onNodeWithTag("settings-back").performClick()
            compose.awaitTag("settings-page")
            compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("settings-about"))
            compose.onNodeWithTag("settings-about").performScrollTo().performClick()
            compose.awaitTag("about-settings")
            compose.onNodeWithTag("app-version").assertIsDisplayed()
            Espresso.pressBack(); compose.awaitTag("settings-page")
            Espresso.pressBack(); compose.awaitTag(more)
            compose.onNodeWithTag("tab-$tab").assertIsSelected()
        }
    }

    @Test fun unifiedDirectoryRetainsAllActionsAtLargeTextInBothPalettes() {
        var world by mutableStateOf(PrototypeWorld.SFW)
        var clicked = ""
        var count = 0
        fun action(name: String): () -> Unit = { clicked = name; count++ }
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        SettingsPage(action("back"), action("appearance"), action("sources"), action("about"),
                            action("reading"), action("library"), action("maintenance"), action("startup"))
                    }
                }
            }
        } }
        for (palette in PrototypeWorld.entries) {
            compose.runOnIdle { world = palette }
            for (entry in listOf("appearance", "reading", "sources", "library", "maintenance", "about")) {
                val before = count
                compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("settings-$entry"))
                compose.onNodeWithTag("settings-$entry").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                compose.runOnIdle { assertEquals(entry, clicked); assertEquals(before + 1, count) }
            }
            compose.onNodeWithText("当前空间：表世界").assertDoesNotExist()
            compose.onNodeWithText("当前空间：里世界").assertDoesNotExist()
            compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("settings-back"))
            compose.onNodeWithTag("settings-back").assertContentDescriptionEquals("返回")
        }
    }

    @Test fun sourceDirectoryShowsBlockedCapabilitiesWithoutOpeningAccounts() {
        var calls = 0
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.NSFW)) {
                SourceManagementPage(false, {}, { calls++ }, { calls++ }, { calls++ })
            }
        } }
        compose.onNodeWithTag("source-admission").assertExists()
        for (tag in listOf("manage-komga", "manage-copy-account", "manage-baozi-account")) {
            compose.onNodeWithTag("source-management").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun narrowLargeTextSwitchesKeepSingleToggleActionsAndIndependentValues() {
        var effects by mutableStateOf(DockEffects(sampling = true, blur = true))
        var world by mutableStateOf(PrototypeWorld.SFW)
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        DockEffectControls(effects, change = { effects = it })
                    }
                }
            }
        } }
        for (space in PrototypeWorld.entries) {
            compose.runOnIdle { world = space; effects = DockEffects(sampling = true, blur = true) }
            compose.onNodeWithTag("dock-low-effects").performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
            compose.onNodeWithTag("dock-sampling").performScrollTo().assertIsNotEnabled().assertIsOn()
            compose.onNodeWithTag("dock-blur").performScrollTo().assertIsNotEnabled().assertIsOn()
            compose.onNodeWithTag("dock-reduce-motion").performScrollTo().performClick().assertIsOn()
            compose.onNodeWithTag("dock-low-effects").performScrollTo().performClick().assertIsOff()
            compose.runOnIdle { assertTrue(effects.sampling); assertTrue(effects.blur); assertTrue(effects.reduceMotion) }
            for (tag in listOf("dock-fusion", "dock-saturation")) {
                compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
            }
            for (tag in listOf("dock-highlight", "dock-refraction")) {
                val node = compose.onNodeWithTag(tag).performScrollTo()
                if (android.os.Build.VERSION.SDK_INT >= 33) node.performClick().assertIsOn() else node.assertIsNotEnabled()
            }
            compose.runOnIdle { assertTrue(effects.fusion); assertTrue(effects.saturation); assertTrue(effects.reduceMotion) }
        }
    }
}
