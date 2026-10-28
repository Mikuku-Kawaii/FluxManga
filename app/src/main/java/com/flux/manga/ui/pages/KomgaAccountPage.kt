package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import com.flux.manga.core.KomgaListKind
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld

private fun KomgaListKind.title() = when (this) {
    KomgaListKind.LIBRARY -> "授权书库"
    KomgaListKind.COLLECTION -> "系列集合"
    KomgaListKind.READ_LIST -> "阅读列表"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KomgaAccountPage(session: WorldSession, back: () -> Unit, settings: () -> Unit,
    open: (String, String) -> Unit, library: () -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if (identity.world != PrototypeWorld.SFW) { SettingsHeading("账号尚未开放", "请返回表世界", back); return }
    val context = LocalContext.current
    val model = remember(session, identity.generation) { session.komgaAccount(context.applicationContext) }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var selected by rememberSaveable(state.source, state.selected?.kind, state.selected?.id) { mutableStateOf(emptyList<String>()) }
    var previewQuery by rememberSaveable(state.source, state.selected?.kind, state.selected?.id) { mutableStateOf("") }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.start()
        onDispose { model.leave(); confirmation = null }
    }
    LaunchedEffect(state.entries) { selected = selected.filter { id -> state.entries.any { (it.bookId ?: it.seriesId) == id } } }
    val canImport = state.verified && state.loaded && !state.busy && state.entriesLoaded && state.task?.done != false
    val informationModel = state.source?.let { rememberBookInformation(session, it) }
    var preview by rememberSaveable { mutableStateOf(false) }
    val listsScroll = rememberLazyListState()
    val booksScroll = rememberLazyListState()
    val coverLibrary = remember(session, identity) { com.flux.manga.data.OnlineLibrary(context, session) }
    androidx.activity.compose.BackHandler(preview) { preview = false }
    if (preview && state.selected != null) {
        CollectionImportPage(session, state.source, "Komga", state.user.orEmpty(),
            books = state.entries.map { ImportBook(it.bookId ?: it.seriesId, it.seriesId, it.title,
                if (it.bookId != null) "列表第 ${it.position.toLong() + 1} 项" else "") },
            signedIn = state.verified, busy = state.busy, selected = selected, select = { selected = it },
            query = previewQuery, changeQuery = { previewQuery = it }, back = { preview = false }, login = settings,
            open = { id -> state.source?.let { open(it, id) } },
            primaryLabel = if (state.task?.done == false) "继续导入" else "导入全部",
            canImport = canImport || (state.task?.done == false && state.verified && !state.busy),
            importBooks = { if (state.task?.done == false) model.resume() else confirmation = if (selected.isEmpty()) "all" else "selected" },
            pause = model::cancel, refresh = { state.selected?.let(model::select) }, scroll = booksScroll,
            error = state.error, notice = state.notice,
            summary = state.task?.let { "${if (it.done) "已完成" else "导入进度"} · 新增 ${it.added} · 已存在 ${it.existing}" },
            favorite = favorite, changeFavorite = { favorite = it }, information = informationModel,
            scope = { TextButton({ preview = false }, enabled = !state.busy) { Text("${state.selected!!.name} ▾") } },
            pagination = {
                if (!state.entriesLoaded || state.nextEntry != null) TextButton(model::moreEntries,
                    enabled = !state.busy && state.entries.size < 1000) { Text("加载更多 / 重试本页") }
            }, options = {
                state.task?.let { task -> SettingsDisclosure("任务详情", task.list.name) {
                    ImportTaskProgress(task.cursor, task.selection?.size, task.done)
                    Text(task.selection?.let { "未处理 ${it.size - task.cursor} 条" } ?: "下一页 ${task.page + 1}")
                    if (task.selection == null) TextButton({ confirmation = "rescan" }, enabled = !state.busy) { Text("重新扫描") }
                    TextButton({ confirmation = "finish" }, enabled = !state.busy) { Text(if (task.done) "收起结果" else "结束任务") }
                } }
                TextButton(library, enabled = !state.busy) { Text("查看书库") }
            }, footerExtra = { if (state.task?.done == true) TextButton(library) { Text("查看书库") } },
            cover = { book -> state.source?.let { OnlineCover(coverLibrary, it, book.manga, fillWidth = true) } })
    } else {
        ReferenceRefreshColumn(state = listsScroll, refreshing = state.busy, refresh = model::start,
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { SettingsHeading("选择收藏列表", "Komga", back,
                actions = { ReferenceActionsButton("账号设置", settings) }) }
            if (!state.verified) item("login") {
                Text(accountStatus(state.loaded, false, state.source != null, state.busy))
                TextButton(settings, enabled = !state.busy) { Text("连接账号") }
            }
            item("status") { AccountRequestStatus(state.busy, state.stage, state.error, state.notice, model::cancel, model::start) }
            if (state.verified) {
                item("types") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KomgaListKind.entries.forEach { kind -> ShelfTextButton(kind.title(), { model.category(kind) }, !state.busy, selected = state.kind == kind) }
                } }
                if (state.listsLoaded && state.lists.isEmpty()) item("empty") { Text("没有可用的${state.kind.title()}") }
                items(state.lists, key = { "list:${it.kind}:${it.id}" }) { list ->
                    SettingsRow(list.name, if (list.filtered) "仅含账号可见作品" else "",
                        "komga-import-list-${list.id}", null, { model.select(list); preview = true }, enabled = !state.busy)
                }
                if (!state.listsLoaded || state.nextList != null) item("more") {
                    TextButton(model::moreLists, enabled = !state.busy) { Text("加载更多列表 / 重试") }
                }
                state.task?.let { task -> item("resume") {
                    Text("${task.list.name} · 新增 ${task.added} · 已存在 ${task.existing}", style = MaterialTheme.typography.bodySmall)
                    if (!task.done) TextButton({ model.resume() }, enabled = !state.busy) { Text("继续上次导入") }
                    AccountMoreActions("任务详情") { close ->
                        ImportTaskProgress(task.cursor, task.selection?.size, task.done)
                        if (task.selection == null) TextButton({ close(); confirmation = "rescan" }, enabled = !state.busy) { Text("重新扫描") }
                        TextButton({ close(); confirmation = "finish" }, enabled = !state.busy) { Text(if (task.done) "收起结果" else "结束任务") }
                        TextButton(library, enabled = !state.busy) { Text("查看书库") }
                    }
                } }
            }
        }
    }
    if (confirmation != null) ReferenceDialog(onDismissRequest = { confirmation = null }, title = { Text("确认操作") },
        text = { Text(when (confirmation) {
            "all" -> "导入当前列表的全部可访问书目，不下载正文。已有书目自动跳过。"
            "selected" -> "导入选中的 ${selected.size} 条，已有记录保留。"
            "rescan" -> "从第一页重新扫描并去重，保留已经导入的作品；本次统计将重新计算。"
            else -> "结束当前任务，保留已提交书目、收藏、进度和原文件。"
        }, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { ReferenceDialogConfirm(when (confirmation) {
            "all" -> "导入整个列表"; "selected" -> "导入所选书目"; "rescan" -> "重新扫描"; else -> "结束任务"
        }, onClick = {
            val action = confirmation; confirmation = null
            when (action) { "all" -> model.begin(null, favorite); "selected" -> model.begin(selected.toSet(), favorite); "rescan" -> model.resume(true); else -> model.finish() }
        }) }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回") } })
}
