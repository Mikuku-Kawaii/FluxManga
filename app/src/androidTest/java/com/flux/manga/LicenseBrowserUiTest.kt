package com.flux.manga

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import com.flux.manga.ui.pages.OpenSourceLicensesPage
import com.flux.manga.ui.pages.LicenseEntryPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compile-only this round. Public bundled materials; no network or data writes. */
class LicenseBrowserUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }

    @Test fun catalogSearchRemainsReachableInNarrowLargeTextLayout() {
        var opened: String? = null
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.SFW)) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { OpenSourceLicensesPage({}, { opened = it }) }
                }
            }
        } }
        compose.onNodeWithTag("license-query").performScrollTo().performTextInput("fluxmanga")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("license-catalog").performScrollToNode(hasText("1 项"))
        compose.onNodeWithTag("license-catalog").performScrollToNode(hasTestTag("license-entry-fluxmanga"))
        compose.onNodeWithTag("license-entry-fluxmanga").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("fluxmanga", opened) }
    }

    @Test fun unknownRestoredEntryOffersRetryAndBackInPrivatePalette() {
        var backs = 0
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.NSFW)) {
                LicenseEntryPage("missing-public-component", { backs++ }, {})
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
