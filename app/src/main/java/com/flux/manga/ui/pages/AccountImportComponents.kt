package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

internal fun accountStatus(loaded: Boolean, verified: Boolean, saved: Boolean, busy: Boolean): String = when {
    !loaded -> if (busy) "正在读取账号" else "账号状态待核对"
    verified -> "已登录 · 会话已验证"
    saved -> "已保存账号，需验证当前会话"
    else -> "未登录"
}

/** Reports only the account facts supplied by its owner, never infers expiry from a network error. */
@Composable
internal fun AccountSummary(status: String, importScope: String, name: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(status, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("可导入：$importScope", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun <T> filterImportEntries(entries: List<T>, query: String, title: (T) -> String): List<T> =
    query.trim().let { text -> if (text.isEmpty()) entries else entries.filter { title(it).contains(text, ignoreCase = true) } }

internal fun addImportSelection(selected: List<String>, ids: List<String>) = (selected + ids).distinct()
internal fun hiddenImportSelection(selected: List<String>, visible: List<String>): Int = (selected.toSet() - visible.toSet()).size
internal fun importFraction(processed: Int, total: Int?): Float? =
    if (total != null && total > 0 && processed in 0..total) processed.toFloat() / total else null

@Composable
internal fun AccountPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsGroup(title.takeIf { it.isNotBlank() }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A permanent settings section; account loading never removes the import entry. */
@Composable
internal fun SourceLibraryImportCard(scope: String, available: Boolean, busy: Boolean,
    tag: String, open: () -> Unit) {
    AccountPanel("书库导入") {
        Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!available) Text("请先完成上方账号连接。", style = MaterialTheme.typography.bodyMedium)
        ReferenceButton(open, enabled = available && !busy, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text("选择书目并导入")
        }
    }
}

/** Secondary actions use a scrollable menu; request status and cancel remain on the page. */
@Composable
internal fun AccountMoreActions(title: String, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(title); Spacer(Modifier.width(4.dp)); SettingsChevron()
    }
    if (expanded) ReferenceSheet(onDismissRequest = { expanded = false }, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content { expanded = false }
        } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountCredentials(prefix: String, username: String, password: String, changeUsername: (String) -> Unit,
    changePassword: (String) -> Unit, enabled: Boolean, verified: Boolean, busy: Boolean,
    login: () -> Unit, reload: () -> Unit, logout: (() -> Unit)?,
    accountLabel: String = "账号", accountKeyboard: KeyboardType = KeyboardType.Text) {
    var editing by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canLogin = enabled && username.isNotBlank() && password.isNotEmpty()
    val submit = feedbackAttempt { if (canLogin) { keyboard?.hide(); focus.clearFocus(); login() } }
    LaunchedEffect(verified) { if (verified) editing = false }
    LaunchedEffect(password) { if (password.isEmpty()) visible = false }
    if (!verified || editing) {
        OutlinedTextField(username, changeUsername, label = { Text(accountLabel) }, singleLine = true,
            shape = MaterialTheme.shapes.small, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("$prefix-account-name"),
            keyboardOptions = KeyboardOptions(keyboardType = accountKeyboard, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
        OutlinedTextField(password, changePassword, label = { Text("密码") }, singleLine = true,
            shape = MaterialTheme.shapes.small, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), enabled = enabled,
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("$prefix-account-password"),
            trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = if (visible) "隐藏密码" else "显示密码" }) { Text(if (visible) "隐藏" else "显示") } })
        ReferenceButton(onClick = feedbackAttempt(submit), enabled = canLogin, modifier = Modifier.fillMaxWidth()) { Text("登录并验证") }
    }
    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (verified && editing) TextButton(onClick = { editing = false; visible = false; changePassword("") }, enabled = enabled) { Text("收起账号输入") }
        AccountMoreActions("账号管理") { close ->
            if (!verified) TextButton(onClick = feedbackAttempt({ close(); reload() }), enabled = !busy) { Text("验证已保存账号") }
            if (verified) {
                TextButton(onClick = { close(); editing = true; visible = false; changePassword("") }, enabled = enabled) { Text("切换账号") }
            }
            if (logout != null) TextButton(onClick = { close(); visible = false; changePassword(""); logout() }, enabled = enabled) { Text("退出登录") }
            Text("仅加密保存已验证的会话，不保存密码。换账号或退出登录保留已导入书目、进度和未完成任务。")
        }
    }
}

@Composable
internal fun AccountRequestStatus(busy: Boolean, stage: String?, error: String?, notice: String?, cancel: () -> Unit,
    retry: (() -> Unit)? = null) {
    if (busy || error != null || notice != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) {
            Text(stage?.takeIf { it.isNotBlank() } ?: "正在处理…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = cancel) { Text("停止并核对进度") }
        }
        error?.let { RequestFeedback(it, busy = busy, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (error != null && retry != null) TextButton(onClick = feedbackAttempt(retry), enabled = !busy) { Text("验证已保存账号") }
        notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
internal fun ImportTaskProgress(processed: Int, total: Int?, done: Boolean = false) {
    Text(if (total == null) "已处理 $processed 条" + (if (done) "" else " · 总量待确认") else "已处理 $processed / $total 条",
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportPreviewFilter(query: String, changeQuery: (String) -> Unit, loaded: Int,
    visible: Int, selected: Int, hiddenSelected: Int, enabled: Boolean, selectVisible: () -> Unit) {
    ReferenceSearchField(query, changeQuery, "筛选已读取书目", enabled = enabled,
        modifier = Modifier.testTag("import-preview-query"),
        trailingIcon = if (query.isNotEmpty()) ({ TextButton(onClick = { changeQuery("") }, enabled = enabled) { Text("清空") } }) else null)
    Text((if (query.isBlank()) "已读取 $loaded 条" else "匹配 $visible / $loaded 条") + " · 已选 $selected 条" +
        if (hiddenSelected > 0) "（含筛选外 $hiddenSelected 条）" else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (query.isNotBlank()) {
        if (hiddenSelected > 0) Text("筛选外已选条目仍会导入。", style = MaterialTheme.typography.bodySmall)
        if (visible == 0) Text("已读取书目中没有匹配结果；可清除筛选或继续读取。")
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = selectVisible, enabled = enabled && visible > 0) { Text("选择筛选结果") }
        }
    }
}

@Composable
internal fun ImportPreviewRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean,
    select: (Boolean) -> Unit, open: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp).toggleable(selected, enabled = enabled,
                role = Role.Checkbox, onValueChange = select), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, null, enabled = enabled)
                Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            IconButton(onClick = open, enabled = enabled, modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "查看详情：$title" }) { SettingsChevron() }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The private sources expose page/cursor imports, not a whole-account sync. */
@Composable
internal fun AccountLibraryImportPanel(
    scope: String, count: Int, batch: Boolean = false, ready: Boolean, signedIn: Boolean,
    busy: Boolean, login: () -> Unit, importPage: () -> Unit, library: (() -> Unit)?,
) {
    AccountPanel(if (batch) "当前批导入" else "当前页导入") {
        Text(scope, style = MaterialTheme.typography.bodyMedium)
        if (!ready) Text("正在读取账号状态", style = MaterialTheme.typography.bodySmall)
        else if (!signedIn) {
            Text("先登录原有账号，返回本页后读取云端收藏。")
            ReferenceButton(feedbackAttempt(login), enabled = !busy) { Text("登录账号") }
        } else {
            Text("新增书目加入本机收藏；已有书目保留收藏状态、阅读方式和进度。导入不会改动云端收藏或下载正文。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReferenceButton(importPage, enabled = !busy && count > 0) {
                Text("导入当前${if (batch) "批" else "页"} $count 本")
            }
        }
        library?.let { TextButton(it, enabled = !busy) { Text("查看本机书库") } }
    }
}
