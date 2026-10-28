package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flux.manga.privacy.StartupPreferences

@Composable
internal fun StartupSettingsEntry(open: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { StartupPreferences(context) }
    SettingsRow("启动与返回", startupPages.firstOrNull { it.first == preferences.startPage }?.second.orEmpty(),
        "settings-startup", SettingsSymbol.STARTUP, open)
}

private val startupPages = listOf("HOME" to "首页", "LIBRARY" to "书库", "TAGS" to "标签", "LAST" to "上次使用的主页面")

@Composable
internal fun StartupSettingsPage(back: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { StartupPreferences(context) }
    var page by remember { mutableStateOf(preferences.startPage) }
    var returns by remember { mutableStateOf(preferences.returnToPublic) }
    var seconds by remember { mutableIntStateOf(preferences.backgroundSeconds) }
    LazyColumn(Modifier.fillMaxSize().testTag("startup-settings"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("启动与返回", "", back) }
        item("start-page") { SettingsGroup("打开时默认页面") {
            startupPages.forEach { (value, label) ->
                SettingsChoiceRow(label, page == value, { page = value; preferences.startPage = value })
            }
        } }
        item("start-page-hint") {
            Text("下次启动生效，返回前台保留当前页面。", style = MaterialTheme.typography.bodySmall)
        }
        item("return") { SettingsGroup {
            SettingsSwitchRow("退出后返回表世界", "关闭后，下次打开恢复上次空间；不会恢复私密详情或阅读页。",
                returns, { returns = it; preferences.returnToPublic = it })
        } }
        if (returns) item("return-delay") { SettingsGroup("后台自动返回等待时间") {
            listOf(0 to "立即", 30 to "30 秒", 60 to "1 分钟", 300 to "5 分钟").forEach { (value, label) ->
                SettingsChoiceRow(label, seconds == value, { seconds = value; preferences.backgroundSeconds = value })
            }
        } }
        item("privacy-hint") {
            Text("后台遮挡、加密存储和已开启的生物识别验证始终保留。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
