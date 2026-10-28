package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flux.manga.privacy.StartupPreferences

@Composable
internal fun StartupSettingsEntry() {
    val context = LocalContext.current
    val preferences = remember { StartupPreferences(context) }
    var expanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(preferences.startPage) }
    var returns by remember { mutableStateOf(preferences.returnToPublic) }
    var seconds by remember { mutableIntStateOf(preferences.backgroundSeconds) }
    val pages = listOf("HOME" to "首页", "LIBRARY" to "书库", "TAGS" to "标签", "LAST" to "上次使用的主页面")
    SettingsRow("启动与返回", pages.firstOrNull { it.first == page }?.second.orEmpty(), "settings-startup",
        SettingsSymbol.APPEARANCE, { expanded = true })
    if (expanded) ReferenceSheet(onDismissRequest = { expanded = false }, title = { Text("启动与返回") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("打开时默认页面", style = MaterialTheme.typography.titleMedium)
            pages.forEach { (value, label) ->
                SettingsChoiceRow(label, page == value, { page = value; preferences.startPage = value })
            }
            Text("下次启动生效，返回前台保留当前页面。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            SettingsSwitchRow("退出后返回表世界", "关闭后，下次打开恢复上次空间；不会恢复私密详情或阅读页。",
                returns, { returns = it; preferences.returnToPublic = it })
            if (returns) {
                Text("后台自动返回等待时间", style = MaterialTheme.typography.titleMedium)
                listOf(0 to "立即", 30 to "30 秒", 60 to "1 分钟", 300 to "5 分钟").forEach { (value, label) ->
                    SettingsChoiceRow(label, seconds == value, { seconds = value; preferences.backgroundSeconds = value })
                }
            }
            Text("后台遮挡、加密存储和已开启的生物识别验证始终保留。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton({ expanded = false }) { Text("完成") } })
}
