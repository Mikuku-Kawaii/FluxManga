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
    var favorite by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) { model.retryRecovery(); model.start() }
        onDispose { confirm = null; model.leave() }
    }
    LaunchedEffect(state.snapshotRevision) {
        if (state.verified) selection = selection.filter { id -> state.entries.any { it.card.key.mangaId == id } }
    }
    val navigation = rememberSourceSettingsNavigation(back)
    val unfinished = (state.importJob?.remaining ?: 0) > 0
    val informationModel = state.sourceId?.let { rememberBookInformation(session, it) }
    val coverLibrary = remember(session, identity) { com.flux.manga.data.OnlineLibrary(context, session) }
    if (navigation.section == SourceSettingsSection.IMPORT) {
        CollectionImportPage(session, state.sourceId, "包子漫画", state.username,
            books = state.entries.map { ImportBook(it.card.key.mangaId, it.card.key.mangaId, it.card.title, it.card.author) }, signedIn = state.verified, busy = state.busy,
            selected = selection, select = { selection = it }, query = previewQuery, changeQuery = { previewQuery = it },
            back = navigation::back, login = { navigation.open(SourceSettingsSection.ACCOUNT) },
            open = { id -> state.sourceId?.let { open(it, id) } },
            primaryLabel = if (unfinished) "继续导入" else "导入已读取书架", canImport = state.loaded && !state.busy,
            importBooks = { if (unfinished) model.resumeImport() else { confirm = if (selection.isEmpty()) "all" else "import" } }, pause = model::cancel, refresh = model::start,
            scroll = navigation.scroll, error = state.error, notice = state.notice,
            summary = state.importJob?.let { "${if (it.remaining == 0) "已完成" else "待处理 ${it.remaining}"} · 新增 ${it.added} · 已存在 ${it.existing}" },

            favorite = favorite, changeFavorite = { favorite = it }, information = informationModel,
            pagination = { TextButton(model::start, enabled = !state.busy) { Text("刷新网站书架") } },
            options = {
                state.importJob?.let { job -> SettingsDisclosure("任务详情") {
                    ImportTaskProgress(job.cursor, job.items.size, job.remaining == 0)
                    TextButton({ confirm = "finish" }, enabled = !state.busy) { Text(if (job.remaining == 0) "收起结果" else "结束任务") }
                } }
                TextButton(library, enabled = !state.busy) { Text("查看书库") }
            },
            footerExtra = { if (state.importJob != null && !unfinished) TextButton(library) { Text("查看书库") } },
            cover = { book -> state.sourceId?.let { OnlineCover(coverLibrary, it, book.manga, fillWidth = true) } })
    } else {
    ReferenceRefreshColumn(state = navigation.scroll, refreshing = state.busy, refresh = model::start, modifier = Modifier.fillMaxSize().imePadding().testTag("baozi-account-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading(navigation.title("包子漫画"), "", navigation::back) }
        sourceSettingsOverview(navigation,
            account = accountStatus(state.loaded, state.verified, state.hasCredential, state.busy) + state.username.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            imports = { navigation.open(SourceSettingsSection.IMPORT) },
            importSummary = if (unfinished) "有未完成任务，可继续导入" else "网站书架 · 选择作品导入")

        if (navigation.section == SourceSettingsSection.ACCOUNT) item("account") { AccountPanel(navigation.section.label) {
            AccountSummary(accountStatus(state.loaded, state.verified, state.hasCredential, state.busy),
                "我的书架（已读取部分）", state.username)
            WebAccountEntry(BaoziWebSite.url,
                if (state.autoLoginAvailable) "已记住登录，掉线后在后台恢复，不弹出登录页。" else "设置一次自动登录，后续掉线在后台恢复。", state.loaded && !state.busy,
                connect = { page, _, name ->
                    val (token, browserSession) = page.baoziSession()
                    model.connectWebSession(name, token, browserSession, page.baoziAutoLogin)
                },
                autoConnectKey = { page, _, name ->
                    val origin = page.baoziOrigin()
                    page.cookie(origin, "TSID").takeIf { it.isNotBlank() && name.isNotBlank() }?.let { "$origin:$name:$it" }
                }, webSite = BaoziWebSite, restoreBaozi = model::savedWebAccount)
            state.recoveryNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(model::start, enabled = state.loaded && !state.busy) { Text("重新读取账号与书架") }
                if (state.hasCredential) TextButton(model::logout, enabled = state.loaded && !state.busy) { Text("退出账号") }
            }
        } }
        if (state.busy || state.error != null || state.notice != null) item("status") {
            AccountRequestStatus(state.busy, state.stage, state.error, state.notice, model::cancel, model::start)
        }
    }
    }
    if (confirm != null) ReferenceDialog(onDismissRequest = { confirm = null },
        title = { Text(if (confirm == "finish") "结束此导入任务？" else "导入书目") },
        text = { Text(when (confirm) {
            "all" -> "导入已读取书架的 ${state.entries.size} 部，保留已有收藏和阅读进度。"
            "import" -> "导入选中的 ${selection.size} 部，保留已有收藏和阅读进度。"
            else -> "结束剩余导入，已导入书目和进度保留。"
        }, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm(if (confirm == "finish") "结束任务" else "导入", onClick = {
            val action = confirm; confirm = null
            when (action) {
                "all" -> model.importSelected(state.entries.map { it.card.key.mangaId }.toSet(), favorite)
                "import" -> model.importSelected(selection.toSet(), favorite)
                else -> model.finishTask()
            }
        }) },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("返回") } })
}
