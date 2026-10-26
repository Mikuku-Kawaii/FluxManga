package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CopyAccountPage(session: WorldSession, back: () -> Unit, open: (String, String) -> Unit, library: () -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if (identity.world != PrototypeWorld.SFW) {
        SettingsHeading("账号尚未开放", "请返回表世界", back); return
    }
    val context = LocalContext.current
    val model = remember(session, identity.generation) { session.copyAccount(context.applicationContext) }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selection by rememberSaveable(state.source) { mutableStateOf(emptyList<String>()) }
    var previewQuery by rememberSaveable(state.source) { mutableStateOf("") }
    val visibleEntries = remember(state.entries, previewQuery) { filterImportEntries(state.entries, previewQuery) { it.title } }
    var favorite by rememberSaveable { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.start()
        onDispose { password = ""; confirm = null; model.leave() }
    }
    LaunchedEffect(state.source) { if (state.username.isNotEmpty()) username = state.username }
    LaunchedEffect(state.entries) {
        if (state.verified) selection = selection.filter { id -> state.entries.any { it.collectionId == id } }
    }
    LaunchedEffect(state.verified) { if (state.verified) password = "" }
    val unfinished = state.task?.done == false
    val informationModel = state.source?.let { rememberBookInformation(session, it) }
    ReferenceRefreshColumn(refreshing = state.busy, refresh = model::start, modifier = Modifier.fillMaxSize().imePadding().testTag("copy-account-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("拷贝漫画设置", "", back) }
        informationModel?.let { info -> item("information") { BookInformationPanel(info, enabled = !state.busy) } }
        item("account") { AccountPanel("账号") {
            AccountSummary(accountStatus(state.loaded, state.verified, state.hasCredential, state.busy),
                "云收藏（当前接口范围）", state.username)
            WebAccountEntry("https://mangacopy.com/web/login?url=person%2Fhome",
                "使用官方用户名登录或注册，成功后自动连接账号与云收藏。", state.loaded && !state.busy,
                connect = { page, _, _ -> model.connectWebToken(page.copyToken()) },
                autoConnectKey = { page, _, _ -> page.copyToken().takeIf(String::isNotBlank) },
                afterConnected = model::start)
            SettingsDisclosure("API 账号连接") {
                AccountCredentials("copy", username, password, { username = it }, { password = it },
                    enabled = state.loaded && !state.busy, verified = state.verified, busy = state.busy,
                    login = { keyboard?.hide(); model.signIn(username, password) }, reload = model::start,
                    logout = if (state.hasCredential) { { model.logout() } } else null)
            }
        } }
        if (state.busy || state.error != null || state.notice != null) item("status") {
            AccountRequestStatus(state.busy, state.stage, state.error, state.notice, model::cancel, model::start)
        }
        state.task?.let { job -> item("import-job") { AccountPanel("导入任务") {
            Text(if (job.done) "本次导入已完成" else "可恢复的云收藏导入", style = MaterialTheme.typography.titleMedium)
            Text("新增 ${job.added} · 已存在 ${job.existing}")
            ImportTaskProgress(job.cursor, job.selection?.size, job.done)
            Text(job.selection?.let { "待处理 ${it.size - job.cursor} 条" }
                ?: if (job.done) "已读至当前范围末页" else "下一页 ${job.offset / 21 + 1} · 剩余数量待读取")
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!job.done) ReferenceButton(onClick = { model.resume() }, enabled = !state.busy && state.verified && state.loaded) { Text("继续此任务") }
                TextButton(onClick = library, enabled = !state.busy) { Text("查看在线书库") }
                AccountMoreActions("任务管理") { close ->
                    if (job.selection == null) TextButton(onClick = { close(); confirm = "rescan" }, enabled = state.loaded && state.verified && !state.busy) { Text("从头重扫去重") }
                    TextButton(onClick = { close(); confirm = "finish" }, enabled = state.loaded && state.verified && !state.busy) { Text(if (job.done) "收起结果" else "结束此任务") }
                    Text("列表变化时可从头重扫去重；已经导入的书目和本机进度保留。")
                }
            }
        } } }
        item("snapshot") { AccountPanel("书库导入") {
            if (!state.verified) {
                Text("连接账号后，在这里选择云收藏并导入。", style = MaterialTheme.typography.bodyMedium)
                Text("已有书目与阅读进度保留。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("当前范围的云收藏 · 其他类别尚未确认", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ImportPreviewFilter(previewQuery, { previewQuery = it }, state.entries.size, visibleEntries.size,
                    selection.size, hiddenImportSelection(selection, visibleEntries.map { it.collectionId }), !state.busy,
                    { selection = addImportSelection(selection, visibleEntries.map { it.collectionId }) })
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(favorite, enabled = !state.busy,
                    role = Role.Checkbox, onValueChange = { favorite = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(favorite, null, enabled = !state.busy)
                    Text("同时收藏新书目", Modifier.weight(1f))
                }
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selection = state.entries.map { it.collectionId } }, enabled = !state.busy) { Text("选择已读取条目") }
                    if (selection.isNotEmpty()) TextButton(onClick = { selection = emptyList() }, enabled = !state.busy) { Text("清除选择") }
                }
                ReferenceButton(onClick = { confirm = "import" }, enabled = state.loaded && !state.busy && selection.isNotEmpty() && !unfinished,
                    modifier = Modifier.fillMaxWidth()) { Text("导入选中 ${selection.size} 本") }
                ReferenceButton(onClick = { confirm = "all" }, enabled = state.loaded && state.entriesLoaded && !state.busy && !unfinished,
                    modifier = Modifier.fillMaxWidth()) { Text("一键导入全部收藏 · 当前接口范围") }
                AccountMoreActions("导入选项与说明") { _ ->
                    state.reportedTotal?.let { Text("接口本次报告 $it 条；列表可能变化，不代表其他收藏类别。") }
                    Text("可导入选中条目，或从第一页逐页导入当前范围。只保存书目引用，不下载正文；已有收藏、阅读方式和进度保持。")
                }
                if (state.entriesLoaded && state.entries.isEmpty()) Text(if (state.nextOffset == null) "当前范围没有收藏。" else "本页无可显示条目，可继续读取下一页。")
                if (!state.entriesLoaded || state.nextOffset != null) TextButton(onClick = model::moreEntries,
                    enabled = state.loaded && !state.busy && state.entries.size < 1000) { Text("加载更多 / 重试本页") }
                if (unfinished) Text("请先继续或结束上次任务，再创建新的导入。")
            }
        } }
        if (state.verified) {
            items(visibleEntries, key = { "book:${it.collectionId}" }) { entry ->
                val key = entry.collectionId
                ImportPreviewRow(entry.title, null, key in selection, !state.busy,
                    { checked -> selection = if (checked) addImportSelection(selection, listOf(key)) else selection - key },
                    { state.source?.let { open(it, entry.mangaId) } })
            }
        }
    }
    if (confirm != null) ReferenceDialog(onDismissRequest = { confirm = null },
        title = { Text("确认操作") }, text = { Text(when (confirm) {
            "all" -> "从第一页读取当前接口范围的云收藏。列表可能变化，可重扫去重；不保证覆盖其他收藏类别。"
            "import" -> "导入选中的 ${selection.size} 条书目引用，已有收藏、阅读方式、进度和原文件保持。"
            "rescan" -> "从第一页重新扫描并去重，保留已导入作品；本次统计重新计算。"
            else -> "结束任务，保留已提交书目和进度，未处理部分不再继续。"
        }, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { ReferenceDialogConfirm(when (confirm) {
            "all" -> "导入当前范围"; "import" -> "导入所选书目"; "rescan" -> "重新扫描"; else -> "结束任务"
        }, onClick = {
            val action = confirm; confirm = null
            when (action) { "all" -> model.begin(null, favorite); "import" -> model.begin(selection.toSet(), favorite)
                "rescan" -> model.resume(true); else -> model.finish() }
        }) }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("返回") } })
}
