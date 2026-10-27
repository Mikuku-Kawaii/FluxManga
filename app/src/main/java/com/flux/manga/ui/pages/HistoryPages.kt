package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.text.DateFormat
import java.util.Date

/** Only mounted by real detail/reader entries, never result cards or previews. */
@Composable
internal fun VisitedPage(session: WorldSession, kind: String, source: String, manga: String, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model: VisitRecorderModel = viewModel(factory = viewModelFactory { initializer { VisitRecorderModel(app, session) } })
    val failed by model.failed.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.record(kind, source, manga) }
    Column(Modifier.fillMaxSize()) {
        if (failed) {
            RequestFeedback("浏览记录未保存")
            TextButton(onClick = feedbackAttempt { model.record(kind, source, manga) }, modifier = Modifier.testTag("visit-save-error")) {
                Text("重试保存记录")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
internal fun visitsModel(session: WorldSession, preview: Boolean): VisitsModel {
    val app = LocalContext.current.applicationContext as Application
    val model = preloadedPages(session).visits()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.refresh(silent = true)
    }
    return model
}

@Composable
internal fun VisitsSummary(model: VisitsModel, open: (BrowseVisit) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    VisitsStatus(state, model::refresh)
    if (state.loading && state.items.isEmpty()) ReadLayoutPlaceholder()
    if (state.items.isNotEmpty()) Box(Modifier.testTag("recent-visits-preview")) {
        DiscoveryPreviewGrid(state.items.take(3), { it.visit.stableKey }) { item ->
            BookResultCard(item.visit.title, { open(item.visit) }, enabled = item.availability != VisitAvailability.PRIVATE,
                metadata = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.visit.visitedAt))) {
                VisitCover(item, model)
            }
            if (item.availability != VisitAvailability.AVAILABLE) Text(item.availability.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VisitsStatus(state: VisitsState, retry: () -> Unit) {
    if (state.loading) RequestLoading()
    else if (state.items.isEmpty() && state.error == null) Text("还没有浏览记录")
    state.error?.let {
        RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = feedbackAttempt(retry), enabled = !state.busy) { Text("重试读取记录") }
    }
}

@Composable
internal fun VisitsPage(session: WorldSession, back: () -> Unit, open: (BrowseVisit) -> Unit, sources: () -> Unit) {
    val model = visitsModel(session, false)
    val state by model.state.collectAsStateWithLifecycle()
    var clear by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    BackHandler(managing && !clear && !menu && !help) { managing = false }
    val scroll = rememberLazyListState()
    BookResultsLayout { columns ->
    ReferenceRefreshColumn(refreshing = state.busy, refresh = { model.refresh() }, refreshEnabled = true, state = scroll, modifier = Modifier.fillMaxSize().testTag("visits-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") {
            SettingsHeading("最近观看", "", { if (managing) managing = false else back() }, actions = {
                Box {
                    ReferenceActionsButton("浏览记录操作", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (managing) "完成管理" else "管理记录") },
                            enabled = managing || state.items.isNotEmpty(), onClick = { menu = false; managing = !managing })
                        DropdownMenuItem(text = { Text("清空浏览记录") }, enabled = !state.busy && state.items.isNotEmpty(),
                            onClick = { menu = false; clear = true })
                        DropdownMenuItem(text = { Text("记录说明") }, onClick = { menu = false; help = true })
                    }
                }
            })
        }
        if (state.loading || state.error != null || state.items.isEmpty()) item("status") { VisitsStatus(state, model::refresh) }
        if (state.loading && state.items.isEmpty()) item("read-layout") { ReadLayoutPlaceholder() }
        if (managing) item("manage") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("管理浏览记录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { managing = false }) { Text("完成") }
            }
        }
        bookGridItems(state.items, columns, { it.visit.stableKey }) { item ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookResultCard(item.visit.title, { open(item.visit) },
                    enabled = item.availability != VisitAvailability.PRIVATE,
                    metadata = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.visit.visitedAt))) {
                    VisitCover(item, model)
                }
                if (item.availability != VisitAvailability.AVAILABLE) Text(item.availability.message,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.availability == VisitAvailability.SOURCE_CHANGED || item.availability == VisitAvailability.SOURCE_UNKNOWN)
                    TextButton(onClick = sources) { Text("检查图源配置") }
                if (managing) TextButton(onClick = { model.remove(item.visit) }, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "删除浏览记录：${item.visit.title}" }) { Text("删除记录") }
            }
        }
        item("more") {
            if (state.busy && !state.loading) RequestLoading()
            AutomaticPagination(scroll, "more", state.items.size,
                state.more && !state.busy && state.error == null, model::more)
            Spacer(Modifier.height(8.dp))
        }
    }
    }
    if (help) ReferenceDialog(onDismissRequest = { help = false }, title = { Text("浏览记录") },
        text = { Text("仅保存当前空间的浏览记录，与阅读进度分开。删除或清空记录保留作品、收藏、原文件和阅读进度。",
            Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { help = false }) { Text("关闭") } })
    if (clear) ReferenceDialog(onDismissRequest = { clear = false }, title = { Text("清空浏览记录？") },
        text = { Text("只清空当前空间的浏览记录，作品、收藏、原文件和阅读进度保持。", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm("清空浏览记录", onClick = { clear = false; model.remove(null) }, enabled = !state.busy, destructive = true) },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}

@Composable
private fun VisitCover(item: VisitItem, model: VisitsModel, open: (() -> Unit)? = null) {
    val visibility by model.session.state.collectAsState()
    val private = isPrivateComicSource(item.visit.sourceId)
    val epoch = if (private) visibility.visibilityEpoch else 0L
    val revision by model.session.pageCovers().revision.collectAsState()
    var bitmap by remember(model, item.visit.stableKey, item.availability, epoch, revision) {
        mutableStateOf(if (item.availability == VisitAvailability.AVAILABLE) model.repository.cachedCover(item.visit) else null)
    }
    var failed by remember(model, item.visit.stableKey) { mutableStateOf(false) }
    var missing by remember(model, item.visit.stableKey) { mutableStateOf(false) }
    var attempt by remember(model, item.visit.stableKey) { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && visibility.foreground &&
        visibility.phase == com.flux.manga.session.SwitchPhase.Stable
    val available = item.availability == VisitAvailability.AVAILABLE
    LaunchedEffect(model, item.visit.stableKey, available, active, visibility.visibilityEpoch, attempt, revision) {
        if (!active) { if (private) bitmap = null; return@LaunchedEffect }
        if (!available || bitmap != null) return@LaunchedEffect
        failed = false; missing = false
        try { bitmap = model.repository.cover(item.visit); missing = bitmap == null }
        catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) failed = true
            throw cancelled
        }
        catch (_: com.flux.manga.core.SourceException.NotFound) { missing = true }
        catch (_: Exception) { failed = true }
    }
    CoverArtwork(bitmap?.takeIf { !private || active }, active && available && bitmap == null && !failed && !missing,
        when { !available -> "封面不可用"; !active -> "封面"; missing -> "暂无封面"; failed -> "封面加载失败"; else -> "正在加载封面" },
        Modifier.fillMaxWidth().aspectRatio(.7f).then(if (open != null) Modifier.clickable(role = Role.Button,
            onClickLabel = "打开《${item.visit.title}》详情", onClick = open) else Modifier),
        retry = if (active && failed) ({ attempt++ }) else null, description = "《${item.visit.title}》封面")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchHistoryPanel(model: SearchHistoryModel, sourceId: String?, search: (String) -> Unit, sources: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("online-search-history"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, verticalArrangement = Arrangement.Center) {
            Text("搜索记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            if (state.rows.isNotEmpty()) TextButton(onClick = { managing = !managing }) { Text(if (managing) "完成" else "管理") }
        }
        if (state.loading) Text("正在读取搜索记录…")
        else if (state.rows.isEmpty() && state.error == null) Text("暂无搜索记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = feedbackAttempt(model::refresh), enabled = !state.busy) { Text("重试读取记录") } }
        if (invalid) {
            RequestFeedback("此记录的图源与当前范围不同或已不可用，请检查图源配置。", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = sources) { Text("检查图源配置") }
        }
        if (!managing) FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (expanded) state.rows else state.rows.take(6)).forEach { row ->
                TextButton(onClick = feedbackAttempt({
                    invalid = sourceId == null || (!sourceId.startsWith("aggregate-v1:") && historySource(row.sourceId) != sourceId)
                    if (!invalid) search(row.query)
                }), modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface)) { Text(row.query) }
            }
        } else state.rows.forEach { row ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(row.query, Modifier.weight(1f))
                TextButton(onClick = { model.remove(row) }, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "删除搜索记录：${row.query}" }) { Text("删除") }
            }
        }
        if (!managing && state.rows.size > 6) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起记录" else "全部 ${state.rows.size} 条记录") }
        if (managing && state.rows.isNotEmpty()) TextButton(onClick = { clear = true }, enabled = !state.busy) { Text("清空搜索记录") }
    }
    if (clear) ReferenceDialog(onDismissRequest = { clear = false }, title = { Text("清空搜索记录？") },
        text = { Text("只清空当前空间在线搜索记录，样本历史、书目与阅读进度保持。", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm("清空搜索记录", onClick = { clear = false; model.remove(null) }, enabled = !state.busy, destructive = true) },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
