package com.flux.manga.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException

internal data class ImportBook(val key: String, val manga: String, val title: String, val author: String = "")

/** Shared layout only: each source keeps its existing pagination and import controller. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CollectionImportPage(
    session: WorldSession, source: String?, sourceName: String, account: String,
    books: List<ImportBook>, signedIn: Boolean, busy: Boolean,
    selected: List<String>, select: (List<String>) -> Unit,
    query: String, changeQuery: (String) -> Unit,
    back: () -> Unit, login: () -> Unit, open: (String) -> Unit,
    primaryLabel: String, canImport: Boolean, importBooks: () -> Unit,
    pause: () -> Unit, refresh: () -> Unit,
    scroll: LazyListState = rememberLazyListState(),
    error: String? = null, notice: String? = null, summary: String? = null,
    total: Long? = null, favorite: Boolean? = null, changeFavorite: (Boolean) -> Unit = {},
    information: BookInformationModel? = null,
    scope: @Composable () -> Unit = {}, pagination: @Composable () -> Unit = {},
    options: @Composable ColumnScope.() -> Unit = {},
    footerExtra: @Composable () -> Unit = {},
    cover: @Composable (ImportBook) -> Unit,
) {
    var selecting by rememberSaveable(source) { mutableStateOf(selected.isNotEmpty()) }
    var filter by rememberSaveable(source) { mutableStateOf("ALL") }
    var menu by remember { mutableStateOf(false) }
    val identity by session.identity.collectAsStateWithLifecycle()
    val visibility by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var saved by remember(identity, source) { mutableStateOf<Set<String>?>(null) }
    var savedError by remember(identity, source) { mutableStateOf<String?>(null) }
    LaunchedEffect(identity, source, busy, visibility.foreground, visibility.visibilityEpoch) {
        if (!visibility.foreground || source == null) { saved = null; return@LaunchedEffect }
        try {
            val ids = WorldDataStore(context).worldData(identity.world, { session.accepts(identity) }) { dao ->
                dao.onlineBooks().filter { it.sourceId == source }.map { it.mangaId }.toSet()
            }
            if (session.accepts(identity)) { saved = ids; savedError = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { savedError = "入库状态读取失败" }
    }
    val visible = books.filter { book -> book.title.contains(query.trim(), true) &&
        (saved == null || filter == "ALL" || (book.manga in saved!!) == (filter == "SAVED")) }
    fun cancelSelection() { selecting = false; select(emptyList()) }
    BackHandler(selecting && !busy) { cancelSelection() }
    Column(Modifier.fillMaxSize().imePadding()) {
        SettingsHeading("收藏导入", listOf(sourceName, account).filter(String::isNotBlank).joinToString(" · "), back,
            modifier = Modifier.padding(horizontal = 20.dp), actions = { ReferenceActionsButton("导入设置", { menu = true }) })
        BookResultsLayout { columns ->
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                ReferenceRefreshColumn(state = scroll, refreshing = busy, refresh = refresh,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!signedIn && !(busy && books.isNotEmpty())) item("login") {
                        Text("登录后查看收藏")
                        TextButton(login, enabled = !busy) { Text("连接账号") }
                    } else {
                        item("search") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            scope()
                            ReferenceSearchField(query, changeQuery, "搜索已加载的收藏", enabled = !busy)
                        } }
                        stickyHeader("selection") {
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("收藏", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Text((total?.let { "$it 部" } ?: "已加载 ${books.size} 部") +
                                        if (selecting) " · 已选 ${selected.size} 部" else "",
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    ShelfTextButton("选择", { selecting = true }, !busy && !selecting)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("ALL" to "全部", "NEW" to "未入库", "SAVED" to "已入库").forEach { (value, label) ->
                                        ShelfTextButton(label, { filter = value }, !busy && (value == "ALL" || saved != null), selected = filter == value)
                                    }
                                }
                                if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ShelfTextButton("全选当前结果", { select(addImportSelection(selected, visible.map { it.key })) }, !busy && visible.isNotEmpty())
                                    ShelfTextButton("清空", { select(emptyList()) }, !busy && selected.isNotEmpty())
                                    ShelfTextButton("取消", ::cancelSelection, !busy)
                                }
                                val hidden = hiddenImportSelection(selected, visible.map { it.key })
                                if (selecting && hidden > 0) Text("含筛选外 $hidden 部", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (visible.isEmpty() && !busy && error == null) item("empty") { Text(if (books.isEmpty()) "暂无收藏" else "没有符合筛选的作品") }
                        bookGridItems(visible, columns, { it.key }) { book ->
                            BookResultCard(book.title, open = {
                                if (selecting) select(if (book.key in selected) selected - book.key else selected + book.key)
                                else open(book.manga)
                            }, metadata = book.author, enabled = !busy, cover = {
                                Box {
                                    cover(book)
                                    if (selecting) ShelfSelectionBadge(book.key in selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
                                    if (book.manga in saved.orEmpty()) Surface(Modifier.align(Alignment.BottomStart).padding(4.dp), shape = MaterialTheme.shapes.small) {
                                        Text("已入库", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            })
                        }
                        item("pagination") { pagination() }
                    }
                    savedError?.let { item("saved-error") { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    error?.let { item("error") { RequestFeedback(it); TextButton(refresh, enabled = !busy) { Text("重试读取") } } }
                }
                }
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    notice?.takeIf { it != summary }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            Text("正在处理…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(pause) { Text("暂停") }
                        } else if (!signedIn) {
                            TextButton(refresh) { Text("验证已保存账号") }
                        } else {
                            footerExtra()
                            ReferenceButton(importBooks, enabled = canImport && signedIn && (!selecting || selected.isNotEmpty() || !primaryLabel.startsWith("导入"))) {
                                Text(if (selecting && primaryLabel.startsWith("导入")) "导入选中（${selected.size}）" else primaryLabel)
                            }
                        }
                    }
                }
            }
        }
    }
    if (menu) ReferenceSheet(onDismissRequest = { menu = false }, title = { Text("导入设置") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (favorite != null) SettingsSwitchRow("导入后加入收藏", "只影响新书目", favorite, changeFavorite, enabled = !busy)
            information?.let { SettingsDisclosure("补齐作品资料") { BookInformationPanel(it, enabled = !busy) } }
            TextButton({ menu = false; login() }, enabled = !busy) { Text("账号设置") }
            options()
        } }, confirmButton = { TextButton({ menu = false }) { Text("完成") } })
}

@Composable
internal fun ImportScopePicker(current: String, choices: List<Pair<String, String>>, enabled: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton({ expanded = true }, enabled = enabled) {
        Text((choices.firstOrNull { it.first == current }?.second ?: "收藏分类") + " ▾")
    }
    if (expanded) ReferenceSheet(onDismissRequest = { expanded = false }, title = { Text("收藏范围") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.forEach { (id, label) -> SettingsChoiceRow(label, current == id,
                { expanded = false; choose(id) }, enabled = enabled) }
        } }, confirmButton = { TextButton({ expanded = false }) { Text("关闭") } })
}
