package com.flux.manga.ui.pages

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.TagSnapshot
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import java.util.UUID

@Composable
internal fun libraryGroupsModel(session: WorldSession): TagsViewModel {
    val app = LocalContext.current.applicationContext as Application
    return preloadedPages(session).groups()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryGroupsPage(session: WorldSession, bookKey: String?, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model = libraryGroupsModel(session)
    val state by model.state.collectAsStateWithLifecycle()
    val shelf = preloadedPages(session).shelf()
    val library by shelf.state.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) { model.refresh(); shelf.refresh() } }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var menuGroup by remember { mutableStateOf<String?>(null) }
    var batch by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val book = library.books.firstOrNull { it.stableKey == bookKey }
    val group = state.tags.firstOrNull { it.id == selectedGroup }
    val editable = state.ready && !state.busy
    val canAssociate = editable && !library.loading && library.errors.isEmpty()
    val snapshot = TagSnapshot(state.tags, state.links)
    val members = remember(snapshot, selectedGroup, library.books) {
        groupedShelfBooks(library.books, snapshot, selectedGroup, identity.world).map { it.stableKey }.toSet()
    }
    val displayed = remember(library.books, query) { library.books.filter { it.title.contains(query.trim(), true) } }
    val displayedKeys = remember(displayed) { displayed.map { it.stableKey }.toSet() }
    val batchMode = selecting || selectedKeys.isNotEmpty()
    val hiddenSelected = selectedKeys.count { it !in displayedKeys }
    val leave: () -> Unit = {
        if (batchMode) { selecting = false; selectedKeys = emptyList() }
        else if (selectedGroup != null) { selectedGroup = null; selectedKeys = emptyList() }
        else back()
    }
    BackHandler(selectedGroup != null && editing == null && deleting == null && batch == null && menuGroup == null, leave)
    ReferenceRefreshColumn(refreshing = state.busy || library.loading, refresh = { model.refresh(); shelf.refresh() },
        modifier = Modifier.fillMaxSize().imePadding().testTag("library-groups"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading(if (bookKey != null) "作品分组" else if (selectedGroup != null) group?.name ?: "分组已移除" else "自定义分组",
            "", leave) }
        if (state.busy || library.loading || state.error != null || !state.ready || library.errors.isNotEmpty() ||
            bookKey != null || identity.world != PrototypeWorld.SFW) item {
            if (state.busy || library.loading) RequestLoading()
            state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            library.errors.forEach { RequestFeedback(it, color = MaterialTheme.colorScheme.error) }
            if (!state.ready || state.error != null || library.errors.isNotEmpty()) TextButton(onClick = feedbackAttempt({ model.refresh(); shelf.refresh() }), enabled = !state.busy && !library.loading) { Text("重试核对结果") }
            if (bookKey != null) Text(book?.title ?: if (library.loading) "正在读取作品…" else "书目暂不可用，请返回刷新；原分组关系保留")
            if (identity.world != PrototypeWorld.SFW) Text("分组与作品关联仅保存在当前空间。")
        }
        if (selectedGroup == null) {
            item { TextButton(onClick = { creating = true; name = ""; editing = UUID.randomUUID().toString() }, enabled = editable) { Text("新建分组") } }
            if (state.ready && state.tags.isEmpty()) item { Text("还没有自定义分组") }
            items(state.tags, key = { it.id }) { row ->
                SettingsPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bookKey != null) {
                            val linked = book?.let { b -> state.links.any { it.tagId == row.id && it.matches(b) } } == true
                            Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(linked, enabled = canAssociate && book != null,
                                role = Role.Checkbox, onValueChange = { if (book != null) model.associate(row.id, book, it) }),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(linked, null, enabled = canAssociate && book != null)
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (linked) "已加入" else "未加入", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else Column(Modifier.weight(1f).clickable(enabled = editable, role = Role.Button,
                            onClickLabel = "编辑分组书目", onClick = { selectedGroup = row.id; selecting = false; selectedKeys = emptyList(); query = "" })
                            .heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text("编辑书目 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            ReferenceActionsButton("分组操作：${row.name}", { menuGroup = row.id })
                            DropdownMenu(expanded = menuGroup == row.id, onDismissRequest = { menuGroup = null }) {
                                DropdownMenuItem(text = { Text("重命名") }, enabled = editable,
                                    onClick = { menuGroup = null; creating = false; name = row.name; editing = row.id })
                                DropdownMenuItem(text = { Text("删除分组") }, enabled = editable,
                                    onClick = { menuGroup = null; deleting = row.id })
                            }
                        }
                    }
                }
            }
        } else if (group != null) {
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (batchMode) "批量选择 · 确认加入或移出后保存" else "勾选即保存 · 包含未收藏书目",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LibraryQueryField(query, { query = it }, "group-book-query")
                Text("${displayed.size} 部匹配" + if (batchMode) " · 已选 ${selectedKeys.size} 部" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (batchMode && hiddenSelected > 0) Text("有 $hiddenSelected 部未在当前结果中显示，批量选择仍保留。", style = MaterialTheme.typography.bodySmall)
                if (!batchMode) TextButton(onClick = { selecting = true }, enabled = canAssociate) { Text("批量管理") }
                else {
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selectedKeys = displayed.map { it.stableKey } }, enabled = canAssociate && displayed.size <= 10000) { Text("选择筛选结果") }
                        if (selectedKeys.isNotEmpty()) TextButton(onClick = { selectedKeys = emptyList() }, enabled = !state.busy) { Text("清除选择") }
                        TextButton(onClick = { selecting = false; selectedKeys = emptyList() }, enabled = !state.busy) { Text("退出批量") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReferenceButton(onClick = { batch = true }, enabled = canAssociate && selectedKeys.isNotEmpty()) { Text("加入分组") }
                        OutlinedButton(onClick = { batch = false }, enabled = canAssociate && selectedKeys.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 40.dp)) { Text("移出分组") }
                    }
                }
            } }
            if (displayed.isEmpty() && !library.loading && library.errors.isEmpty()) item { Text(if (query.isBlank()) "当前空间还没有书目" else "没有符合条件的书目") }
            items(displayed, key = { it.stableKey }) { row ->
                val checked = if (batchMode) row.stableKey in selectedKeys else row.stableKey in members
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = canAssociate,
                        role = Role.Checkbox, onValueChange = { value ->
                            if (batchMode) selectedKeys = if (value) (selectedKeys + row.stableKey).distinct() else selectedKeys - row.stableKey
                            else model.associate(group.id, row, value)
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, null, enabled = canAssociate)
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Text((if (batchMode) "批量选择 · " else "") + if (row.stableKey in members) "已在分组" else "未加入分组", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        item { SettingsPanel { SettingsDisclosure("分组使用说明") {
            Text("一部作品可加入多个分组；旧自建标签在这里保留。图源标签仍从标签页浏览。")
            Text("普通勾选即时保存关联；批量管理中勾选仅表示选择，确认加入或移出后保存。选择筛选结果会替换已有批量选择。")
            Text("移出分组或删除分组都保留书目、收藏、阅读进度、下载与原文件。")
        } } }
    }
    editing?.let { id -> ReferenceDialog(onDismissRequest = { if (!state.busy) editing = null }, title = { Text(if (creating) "新建分组" else "重命名分组") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it }, label = { Text("分组名称（最多 40 字）") }, singleLine = true,
            shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()); state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { ReferenceDialogConfirm("保存分组", onClick = { model.save(id, name, creating) { editing = null } }, enabled = editable) },
        dismissButton = { TextButton(onClick = { editing = null }, enabled = !state.busy) { Text("取消") } }) }
    deleting?.let { id -> ReferenceDialog(onDismissRequest = { if (!state.busy) deleting = null }, title = { Text("删除分组") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("删除「${state.tags.firstOrNull { it.id == id }?.name.orEmpty()}」及其分组关联。书目、收藏、其他分组、阅读进度、下载与原文件均保留。"); state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { ReferenceDialogConfirm("删除分组", onClick = { model.delete(id) { deleting = null } }, enabled = editable, destructive = true) },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy) { Text("取消") } }) }
    batch?.let { adding -> ReferenceDialog(onDismissRequest = { if (!state.busy) batch = null }, title = { Text(if (adding) "批量加入分组" else "批量移出分组") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("对已选 ${selectedKeys.size} 部作品修改「${group?.name.orEmpty()}」的关联；其他分组与阅读数据保持。"); state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error) }
            if (selectedKeys.any { key -> library.books.none { it.stableKey == key } }) Text("部分已选书目已不可用，请取消后刷新并重新选择")
        } },
        confirmButton = { ReferenceDialogConfirm(if (adding) "确认加入" else "确认移出", onClick = {
            val rows = library.books.filter { it.stableKey in selectedKeys }
            if (group != null && rows.size == selectedKeys.size) model.associateMany(group.id, rows, adding) { batch = null; selectedKeys = emptyList(); selecting = false }
        }, enabled = canAssociate && group != null && selectedKeys.all { key -> library.books.any { it.stableKey == key } }) },
        dismissButton = { TextButton(onClick = { batch = null }, enabled = !state.busy) { Text("取消") } }) }
}
