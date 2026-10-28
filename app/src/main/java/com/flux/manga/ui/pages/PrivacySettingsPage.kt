package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flux.manga.privacy.LauncherStyle
import com.flux.manga.privacy.PrivacyController

@Composable
internal fun PrivacySettingsPage(privacy: PrivacyController, back: () -> Unit) {
    var pending by remember { mutableStateOf<LauncherStyle?>(null) }
    LaunchedEffect(Unit) { privacy.refresh() }
    LazyColumn(Modifier.fillMaxSize().testTag("privacy-settings"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("隐私入口", "", back) }
        item { SettingsPanel {
            Text("当前空间保护", style = MaterialTheme.typography.titleMedium)
            Text(if (privacy.privateSpace) "里世界 · 退出后回到表世界" else "当前位于表世界",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (privacy.privateSpace) ReferenceButton(onClick = privacy::leavePrivateSpace,
                modifier = Modifier.testTag("privacy-exit-now")) { Text("立即退出里世界") }
        } }
        item { SettingsPanel {
            PrivacyToggle("长按进入里世界", "按住右侧切换键 1.5 秒进入，单击切换键返回",
                privacy.longPress, !privacy.busy, "privacy-long-press", privacy::changeLongPress)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PrivacyToggle("生物识别验证", "进入与后台返回时验证；开关此项也需验证",
                privacy.biometric, !privacy.busy, "privacy-biometric", privacy::changeBiometric)
            if (privacy.busy) RequestLoading()
            Text(privacy.availability, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsDisclosure("验证帮助与系统设置") {
                TextButton(onClick = privacy::enroll, enabled = !privacy.busy) { Text("系统生物识别设置") }
                Text("无法验证时可继续使用表世界。关闭此功能需先恢复系统生物识别。")
                Text("无障碍可使用切换键的长按动作。长按与生物识别设置即时生效，默认均关闭。")
            }
        } }
        item { SettingsPanel { SettingsDisclosure("桌面图标与名称", "当前：${privacy.launcher}") {
            LauncherStyle.entries.forEach { style ->
                OutlinedButton(onClick = { pending = style }, enabled = !privacy.busy,
                    modifier = Modifier.fillMaxWidth().testTag("launcher-${style.name}")) {
                    Text(if (style == LauncherStyle.STANDARD) "恢复 FluxManga" else "使用随手记图标与名称")
                }
            }
            Text("仅更换桌面入口，系统应用列表、通知和最近任务仍可能显示 FluxManga。桌面刷新可能稍有延迟。",
                style = MaterialTheme.typography.bodyMedium)
        } } }
        item { SettingsPanel { SettingsDisclosure("保护范围与后台行为") {
            Text("退出里世界会返回表世界，书库和进度保留。")
            Text("进入后台后遮挡内容，默认超过 30 秒返回表世界。启用生物识别后，每次返回均需验证。")
            Text("图源账号与权限请在“图源管理”中设置。")
        } } }
    }
    pending?.let { style -> ReferenceDialog(onDismissRequest = { pending = null },
        title = { Text("更改桌面入口") },
        text = { Text("改为${style.title}，桌面可能稍后刷新或关闭应用。仅更换桌面入口，系统其他位置仍可能显示 FluxManga。",
            Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm("更改桌面入口", onClick = { pending = null; privacy.setLauncher(style) }, enabled = !privacy.busy) },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }) }
}

@Composable
private fun PrivacyToggle(title: String, description: String, checked: Boolean, enabled: Boolean,
    tag: String, change: (Boolean) -> Unit) {
    SettingsSwitchRow(title, description, checked, change, Modifier.testTag(tag), enabled)
}
