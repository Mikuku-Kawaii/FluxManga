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
import com.flux.manga.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic UI fixtures. Compile-only until device acceptance resumes. */
class AccountImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }

    @Test fun largeTextPreviewKeepsSelectionAndDetailActionsIndependentInBothPalettes() {
        var world by mutableStateOf(PrototypeWorld.SFW)
        var selected by mutableStateOf(false)
        var opens = 0
        var selections = 0
        val title = "合成书目：同名作品的独立卷，保留完整长标题"
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        ImportPreviewRow(title, "阅读列表第 2 项", selected, true,
                            { selected = it; selections++ }, { opens++ })
                    }
                }
            }
        } }
        for (palette in PrototypeWorld.entries) {
            compose.runOnIdle { world = palette }
            compose.onNode(isToggleable()).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithContentDescription("查看详情：$title").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.runOnIdle { assertEquals(2, opens); assertEquals(2, selections); assertFalse(selected) }
    }

    @Test fun filterNoMatchRetainsHiddenSelectionAndCanBeCleared() {
        var query by mutableStateOf("未找到")
        var selectedVisible = 0
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.SFW)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                    ImportPreviewFilter(query, { query = it }, 3, if (query.isBlank()) 3 else 0,
                        2, if (query.isBlank()) 0 else 2, true, { selectedVisible++ })
                }
            }
        } }
        compose.onNodeWithText("已读取 3 条 · 当前显示 0 条 · 已选 2 条（筛选外 2 条仍保留）").assertExists()
        compose.onNodeWithText("选择筛选结果").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("清除筛选").performScrollTo().performClick()
        compose.onNodeWithText("已读取 3 条 · 当前显示 3 条 · 已选 2 条").assertExists()
        compose.runOnIdle { assertEquals("", query); assertEquals(0, selectedVisible) }
    }

    @Test fun verifiedAccountCollapsesFormAndChangingAccountClearsPassword() {
        var verified by mutableStateOf(false)
        var password by mutableStateOf("synthetic-password")
        var logins = 0
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.SFW)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                    AccountCredentials("fixture", "synthetic-user", password, {}, { password = it }, true,
                        verified, false, { logins++ }, {}, null)
                }
            }
        } }
        compose.onNodeWithContentDescription("显示密码").performScrollTo().performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.runOnIdle { verified = true; password = "" }
        compose.onNodeWithTag("fixture-account-password").assertDoesNotExist()
        compose.onNodeWithText("切换账号").performScrollTo().performClick()
        compose.onNodeWithTag("fixture-account-password").assertExists()
        compose.onNodeWithContentDescription("显示密码").assertExists()
        compose.onNodeWithText("登录并验证").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals("", password); assertEquals(0, logins) }
    }
}
