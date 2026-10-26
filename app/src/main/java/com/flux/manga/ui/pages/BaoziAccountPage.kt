package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BaoziAccountPage(session: WorldSession, back: () -> Unit, open: (String, String) -> Unit, library: () -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if (identity.world != PrototypeWorld.SFW) {
        SettingsHeading("账号尚未开放", "请返回表世界", back); return
    }
    val context = LocalContext.current
    val model = remember(session, identity.generation) { session.baoziAccount(context.applicationContext) }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var selection by rememberSaveable(state.sourceId) { mutableStateOf(emptyList<String>()) }
    var previewQuery by rememberSaveable(state.sourceId) { mutableStateOf("") }
    val visibleEntries = remember(state.entries, previewQuery) { filterImportEntries(state.entries, previewQuery) { it.card.title } }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.start()
        onDispose { confirm = null; model.leave() }
    }
    LaunchedEffect(state.snapshotRevision) {
        if (state.verified) selection = selection.filter { id -> state.entries.any { it.card.key.mangaId == id } }
    }
    val unfinished = (state.importJob?.remaining ?: 0) > 0
    val informationModel = state.sourceId?.let { rememberBookInformation(session, it) }
    ReferenceRefreshColumn(refreshing = state.busy, refresh = model::start, modifier = Modifier.fillMaxSize().imePadding().testTag("baozi-account-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("包子漫画设置", "", back) }
        informationModel?.let { info -> item("information") { BookInformationPanel(info, enabled = !state.busy) } }
        item("account") { AccountPanel("账号") {
            AccountSummary(accountStatus(state.loaded, state.verified, state.hasCredential, state.busy),
                "我的书架（当前页面快照）", state.username)
            WebAccountEntry(BaoziWebSite.url,
                if (state.autoLoginAvailable) "已记住登录，掉线后自动恢复。" else "设置一次自动登录，后续掉线无需重复输入密码。", state.loaded && !state.busy,
                connect = { page, _, name ->
                    val (token, browserSession) = page.baoziSession()
                    model.connectWebSession(name, token, browserSession, page.baoziAutoLogin)
                },
                autoConnectKey = { page, _, name ->
                    val origin = page.baoziOrigin()
                    page.cookie(origin, "TSID").takeIf { it.isNotBlank() && name.isNotBlank() }?.let { "$origin:$name:$it" }
                }, webSite = BaoziWebSite, restoreBaozi = model::savedWebAccount)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(model::start, enabled = state.loaded && !state.busy) { Text("重新读取账号与书架") }
                if (state.hasCredential) TextButton(model::logout, enabled = state.loaded && !state.busy) { Text("退出账号") }
            }
        } }
        if (state.busy || state.error != null || state.notice != null) item("status") {
            AccountRequestStatus(state.busy, state.stage, state.error, state.notice, model::cancel, model::start)
        }
        state.importJob?.let { job -> item("import-job") { AccountPanel("导入任务") {
            Text(if (job.remaining > 0) "未完成的选中书目导入" else "本次选中书目已处理", style = MaterialTheme.typography.titleMedium)
            Text("新增 ${job.added} · 已存在 ${job.existing} · 待处理 ${job.remaining}")
            ImportTaskProgress(job.cursor, job.items.size, job.remaining == 0)
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.remaining > 0) ReferenceButton(onClick = model::resumeImport, enabled = !state.busy && state.verified) { Text("继续此任务") }
                TextButton(onClick = library, enabled = !state.busy) { Text("查看在线书库") }
                AccountMoreActions("任务管理") { close ->
                    TextButton(onClick = { close(); confirm = "finish" }, enabled = state.loaded && !state.busy) { Text(if (job.remaining > 0) "结束此任务" else "收起结果") }
                    Text("统计仅针对这次选中的 ${job.items.size} 本；停止或失败保留已提交批次。")
                }
            }
        } } }
        item("snapshot") { AccountPanel("书库导入") {
            if (!state.verified) {
                Text("连接账号后，在这里选择书架中的作品并导入。", style = MaterialTheme.typography.bodyMedium)
                Text("已有书目与阅读进度保留。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("我的书架 · 本次快照，完整性未确认", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ImportPreviewFilter(previewQuery, { previewQuery = it }, state.entries.size, visibleEntries.size,
                    selection.size, hiddenImportSelection(selection, visibleEntries.map { it.card.key.mangaId }), !state.busy,
                    { selection = addImportSelection(selection, visibleEntries.map { it.card.key.mangaId }) })
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(favorite, enabled = !state.busy,
                    role = Role.Checkbox, onValueChange = { favorite = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(favorite, null, enabled = !state.busy)
                    Text("同时收藏新书目", Modifier.weight(1f))
                }
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selection = state.entries.map { it.card.key.mangaId } }, enabled = !state.busy) { Text("全选本次书目") }
                    if (selection.isNotEmpty()) TextButton(onClick = { selection = emptyList() }, enabled = !state.busy) { Text("清除选择") }
                }
                ReferenceButton(onClick = { confirm = "import" }, enabled = !state.busy && selection.isNotEmpty() && !unfinished,
                    modifier = Modifier.fillMaxWidth()) { Text("导入选中 ${selection.size} 本") }
                AccountMoreActions("导入说明") { _ ->
                    Text("账号服务：网页登录时使用的包子官网")
                    Text("来自账号的“我的书架”，只能确认本次页面快照。勾选后只导入书目引用，不下载正文；已有收藏和阅读进度保持。")
                }
                if (unfinished) Text("请先继续或结束上次任务，再创建新的导入。")
            }
        } }
        if (state.verified) {
            items(visibleEntries, key = { "book:${it.card.key.sourceId}:${it.card.key.mangaId}" }) { entry ->
                val key = entry.card.key.mangaId
                ImportPreviewRow(entry.card.title, null, key in selection, !state.busy,
                    { checked -> selection = if (checked) addImportSelection(selection, listOf(key)) else selection - key },
                    { open(entry.card.key.sourceId, entry.card.key.mangaId) })
            }
        }
    }
    if (confirm != null) ReferenceDialog(onDismissRequest = { confirm = null },
        title = { Text(if (confirm == "import") "导入选中的书目？" else "结束此导入任务？") },
        text = { Text(if (confirm == "import") "处理本次选中的 ${selection.size} 本。已有书目、收藏和阅读进度保持，不改写远端书架或下载正文。"
            else "已提交的书目和阅读进度保留，未处理部分不再继续；以后可重新选择导入。", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm(if (confirm == "import") "导入所选书目" else "结束任务", onClick = {
            val action = confirm; confirm = null
            if (action == "import") model.importSelected(selection.toSet(), favorite) else model.finishTask()
        }) },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("返回") } })
}
