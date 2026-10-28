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
    var favorite by rememberSaveable { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.reopen()
        onDispose { password = ""; confirm = null; model.leave() }
    }
    LaunchedEffect(state.source) { if (state.username.isNotEmpty()) username = state.username }
    LaunchedEffect(state.entries) {
        if (state.verified) selection = selection.filter { id -> state.entries.any { it.collectionId == id } }
    }
    LaunchedEffect(state.verified) { if (state.verified) password = "" }
    val navigation = rememberSourceSettingsNavigation(back)
    val unfinished = state.task?.done == false
    val readingSource = com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID
    val informationModel = state.source?.let { rememberBookInformation(session, readingSource) }
    val coverLibrary = remember(session, identity) { com.flux.manga.data.OnlineLibrary(context, session) }
    if (navigation.section == SourceSettingsSection.IMPORT) {
        CollectionImportPage(session, state.source, "拷贝漫画", state.username,
            books = state.entries.map { ImportBook(it.collectionId, it.mangaId, it.title) }, signedIn = state.verified, busy = state.busy,
            selected = selection, select = { selection = it }, query = previewQuery, changeQuery = { previewQuery = it },
            back = navigation::back, login = { navigation.open(SourceSettingsSection.ACCOUNT) },
            open = { id -> open(readingSource, id) },
            primaryLabel = if (unfinished) "继续导入" else "导入全部", canImport = state.loaded && !state.busy && (unfinished || state.entriesLoaded),
            importBooks = { if (unfinished) model.resume() else { confirm = if (selection.isEmpty()) "all" else "import" } }, pause = model::cancel, refresh = model::start,
            scroll = navigation.scroll, error = state.error, notice = state.notice,
            summary = state.task?.let { "${if (it.done) "已完成" else "导入进度"} · 新增 ${it.added} · 已存在 ${it.existing}" },
            total = state.reportedTotal,
            favorite = favorite, changeFavorite = { favorite = it }, information = informationModel, librarySource = readingSource,
            pagination = { if (!state.entriesLoaded || state.nextOffset != null) TextButton(model::moreEntries,
                    enabled = state.loaded && !state.busy && state.entries.size < 1000) { Text("加载更多 / 重试本页") } },
            options = {
                if (state.task != null) {
                    SettingsDisclosure("任务详情") {
                        val job = state.task!!
                        ImportTaskProgress(job.cursor, job.selection?.size, job.done)
                        Text(job.selection?.let { "待处理 ${it.size - job.cursor} 条" } ?: "下一页 ${job.offset / 21 + 1}")
                        if (job.selection == null) TextButton({ confirm = "rescan" }, enabled = !state.busy) { Text("重新扫描") }
                        TextButton({ confirm = "finish" }, enabled = !state.busy) { Text(if (job.done) "收起结果" else "结束任务") }
                    }
                }
                TextButton(library, enabled = !state.busy) { Text("查看书库") }
            },
            footerExtra = { if (state.task != null && !unfinished) TextButton(library) { Text("查看书库") } },
            cover = { book -> OnlineCover(coverLibrary, readingSource, book.manga, fillWidth = true) })
    } else {
    ReferenceRefreshColumn(state = navigation.scroll, refreshing = state.busy, refresh = model::start, modifier = Modifier.fillMaxSize().imePadding().testTag("copy-account-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading(navigation.title("拷贝漫画"), "", navigation::back) }
        sourceSettingsOverview(navigation,
            account = accountStatus(state.loaded, state.verified, state.hasCredential, state.busy) + state.username.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            imports = { navigation.open(SourceSettingsSection.IMPORT) },
            importSummary = if (unfinished) "有未完成任务，可继续导入" else "云收藏 · 支持全部或选中导入", connectionSummary = "其他账号连接方式")

        if (navigation.section == SourceSettingsSection.ACCOUNT || navigation.section == SourceSettingsSection.CONNECTION) item("account") { AccountPanel(navigation.section.label) {
            AccountSummary(accountStatus(state.loaded, state.verified, state.hasCredential, state.busy),
                "云收藏（当前接口范围）", state.username)
            if (navigation.section == SourceSettingsSection.ACCOUNT) {
            WebAccountEntry("https://mangacopy.com/web/login?url=person%2Fhome",
                "使用官方用户名登录或注册，成功后自动连接账号与云收藏。", state.loaded && !state.busy,
                connect = { page, _, _ -> model.connectWebToken(page.copyToken()) },
                autoConnectKey = { page, _, _ -> page.copyToken().takeIf(String::isNotBlank) },
                afterConnected = model::start)
            if (state.hasCredential) TextButton(model::logout, enabled = !state.busy) { Text("退出账号 · 保留书库") }
            TextButton({ navigation.open(SourceSettingsSection.CONNECTION) }, enabled = !state.busy) { Text("其他连接方式") }
            }
            if (navigation.section == SourceSettingsSection.CONNECTION) {
                AccountCredentials("copy", username, password, { username = it }, { password = it },
                    enabled = state.loaded && !state.busy, verified = state.verified, busy = state.busy,
                    login = { keyboard?.hide(); model.signIn(username, password) }, reload = model::start,
                    logout = if (state.hasCredential) { { model.logout() } } else null)
            }
        } }
        if (state.busy || state.error != null || state.notice != null) item("status") {
            AccountRequestStatus(state.busy, state.stage, state.error, state.notice, model::cancel, model::start)
        }
    }
    }
    if (confirm != null) ReferenceDialog(onDismissRequest = { confirm = null },
        title = { Text("确认操作") }, text = { Text(when (confirm) {
            "all" -> "从第一页导入当前范围的云收藏，自动跳过已有书目。"
            "import" -> "导入选中的 ${selection.size} 条书目，已有记录保留。"
            "rescan" -> "从第一页重新扫描，跳过已有书目并重新统计。"
            else -> "结束任务，保留已提交书目和进度，未处理部分不再继续。"
        }, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { ReferenceDialogConfirm(when (confirm) {
            "all" -> "导入当前范围"; "import" -> "导入所选书目"; "rescan" -> "重新扫描"; else -> "结束任务"
        }, onClick = {
            val action = confirm; confirm = null
            when (action) { "all" -> model.begin(null, favorite); "import" -> model.begin(selection.toSet(), favorite)
                "rescan" -> model.resume(true); else -> model.finish() }
        }) }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("返回") } })
}
