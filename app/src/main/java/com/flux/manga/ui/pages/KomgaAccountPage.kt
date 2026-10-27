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
    val visibleEntries = remember(state.entries, previewQuery) { filterImportEntries(state.entries, previewQuery) { it.title } }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.start()
        onDispose { model.leave(); confirmation = null }
    }
    LaunchedEffect(state.entries) { selected = selected.filter { id -> state.entries.any { (it.bookId ?: it.seriesId) == id } } }
    val canImport = state.verified && state.loaded && !state.busy && state.entriesLoaded && state.task?.done != false
    val informationModel = state.source?.let { rememberBookInformation(session, it) }
    ReferenceRefreshColumn(refreshing = state.busy, refresh = model::start, modifier = Modifier.fillMaxSize().imePadding().testTag("komga-account-import"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("Komga 授权列表", "", back) }
        informationModel?.let { info -> item("information") { SourceInformationEntry(info, enabled = !state.busy) } }
        item("account") { AccountPanel("账号") {
            AccountSummary(accountStatus(state.loaded, state.verified, state.source != null, state.busy),
                "已授权书库、系列集合与阅读列表")
            if (!state.verified) ReferenceButton(onClick = settings, enabled = !state.busy) { Text("连接账号") }
            AccountMoreActions("账号管理") { close ->
                if (!state.verified) TextButton(onClick = feedbackAttempt({ close(); model.start() }), enabled = !state.busy) { Text("验证已保存账号") }
                if (state.verified) {
                    TextButton(onClick = { close(); settings() }, enabled = !state.busy) { Text("账号设置 / 重新登录") }
                }
                state.user?.let { Text("服务器账号 ID：$it") }
                Text("导入账号有权访问的服务器书库、系列集合或阅读列表；这些列表不等于个人喜欢或收藏。")
            }
        } }
        if (state.busy || state.error != null || state.notice != null) item("status") {
            AccountRequestStatus(state.busy, state.stage, state.error, state.notice, model::cancel, model::start)
        }
        state.task?.let { task -> item("task") { AccountPanel("导入任务") {
            Text(if (task.done) "本次导入已完成" else "可恢复的导入任务", style = MaterialTheme.typography.titleMedium)
            Text("${task.list.kind.title()} · ${task.list.name}")
            Text("新增系列 ${task.added} · 已存在条目 ${task.existing}")
            ImportTaskProgress(task.cursor, task.selection?.size, task.done)
            Text(task.selection?.let { "未处理 ${it.size - task.cursor} 条" }
                ?: if (task.done) "已读至列表末页" else "下一页 ${task.page + 1} · 未处理总量未知")
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!task.done) ReferenceButton(onClick = { model.resume() }, enabled = state.verified && state.loaded && !state.busy) { Text("继续任务") }
                TextButton(onClick = library, enabled = !state.busy) { Text("查看在线书库") }
                AccountMoreActions("任务管理") { close ->
                    if (task.selection == null) TextButton(onClick = { close(); confirmation = "rescan" }, enabled = state.verified && state.loaded && !state.busy) { Text("从第一页重扫去重") }
                    TextButton(onClick = { close(); confirmation = "finish" }, enabled = state.verified && state.loaded && !state.busy) { Text(if (task.done) "收起结果" else "结束此任务") }
                    Text("失败页整页保留待办，不跳过异常项；阅读列表多卷可对应同一系列。")
                }
            }
        } } }
        if (state.verified) {
            item("types") { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KomgaListKind.entries.forEach { kind -> FilterChip(state.kind == kind, { model.category(kind) },
                    label = { Text(kind.title()) }, enabled = !state.busy) }
            } }
            if (state.listsLoaded && state.lists.isEmpty()) item("empty-lists") { Text("当前账号没有可见的${state.kind.title()}。") }
            items(state.lists, key = { "list:${it.kind}:${it.id}" }) { list -> Column {
                Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Button,
                    onClickLabel = if (state.selected == list) "重新读取此列表" else "预览并选择") { model.select(list) }
                    .heightIn(min = 64.dp).padding(vertical = 12.dp).semantics { this.selected = state.selected == list },
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                    val listSummary = if (list.kind == KomgaListKind.LIBRARY) "仅含授权作品" else
                        (if (list.ordered) "有序列表" else "服务器返回顺序") +
                            (if (list.filtered) " · 当前账号仅可见部分内容" else " · 服务器共享列表")
                    Text((if (state.selected == list) "已选 · " else "") + listSummary,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            } }
            if (!state.listsLoaded || state.nextList != null) item("more-lists") { TextButton(onClick = model::moreLists, enabled = !state.busy) { Text(if (state.listsLoaded) "加载更多列表 / 重试" else "重试列表目录") } }
            state.selected?.let { list ->
                item("preview") { AccountPanel("选择书目") {
                    Text(list.name, style = MaterialTheme.typography.titleLarge)
                    ImportPreviewFilter(previewQuery, { previewQuery = it }, state.entries.size, visibleEntries.size,
                        selected.size, hiddenImportSelection(selected, visibleEntries.map { it.bookId ?: it.seriesId }), !state.busy,
                        { selected = addImportSelection(selected, visibleEntries.map { it.bookId ?: it.seriesId }) })
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(favorite, enabled = !state.busy,
                        role = Role.Checkbox, onValueChange = { favorite = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(favorite, null, enabled = !state.busy); Text("同时收藏新作品", Modifier.weight(1f))
                    }
                    ReferenceButton(onClick = { confirmation = "all" }, enabled = canImport,
                        modifier = Modifier.fillMaxWidth()) { Text("一键导入整个列表") }
                    AccountMoreActions("导入选项与说明") { _ ->
                        Text("整列表导入从第一页按需读取至末页，不受当前预览筛选限制。已有收藏、阅读方式和进度保持。")
                        if (list.kind == KomgaListKind.READ_LIST) Text("同一系列的多卷合并为一部作品，卷身份和顺序保留在导入关联中，不改变本机阅读位置。")
                    }
                } }
                if (state.entriesLoaded && state.entries.isEmpty()) item("empty-entries") { Text("本页没有作品。") }
                selectionHeader("selection-actions") {
                    ImportSelectionActions(selected.size, !state.busy, canImport,
                        { selected = state.entries.map { it.bookId ?: it.seriesId } }, { selected = emptyList() }, { confirmation = "selected" })
                }
                items(visibleEntries, key = { "entry:${it.bookId ?: it.seriesId}" }) { entry ->
                    val key = entry.bookId ?: entry.seriesId
                    ImportPreviewRow(entry.title, if (entry.bookId != null) "阅读列表第 ${entry.position.toLong() + 1} 项" else null,
                        key in selected, !state.busy,
                        { checked -> selected = if (checked) addImportSelection(selected, listOf(key)) else selected - key },
                        { state.source?.let { open(it, entry.seriesId) } })
                }
                if (!state.entriesLoaded || state.nextEntry != null) item("more-entries") { TextButton(onClick = model::moreEntries, enabled = !state.busy && state.entries.size < 1000) { Text(if (state.entriesLoaded) "加载更多作品 / 重试" else "重试作品预览") } }
            }
        }
    }
    if (confirmation != null) ReferenceDialog(onDismissRequest = { confirmation = null }, title = { Text("确认操作") },
        text = { Text(when (confirmation) {
            "all" -> "导入当前列表的全部授权条目，按页执行；列表变化时可重扫去重。仅保存摘要和在线引用，不下载正文或改写服务器。"
            "selected" -> "导入本次选择的 ${selected.size} 条；已有收藏、阅读方式、进度和原文件保持。"
            "rescan" -> "从第一页重新扫描并去重，保留已经导入的作品；本次统计将重新计算。"
            else -> "结束当前任务，保留已提交书目、收藏、进度和原文件。"
        }, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { ReferenceDialogConfirm(when (confirmation) {
            "all" -> "导入整个列表"; "selected" -> "导入所选书目"; "rescan" -> "重新扫描"; else -> "结束任务"
        }, onClick = {
            val action = confirmation; confirmation = null
            when (action) { "all" -> model.begin(null, favorite); "selected" -> model.begin(selected.toSet(), favorite); "rescan" -> model.resume(true); else -> model.finish() }
        }) }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回") } })
}
