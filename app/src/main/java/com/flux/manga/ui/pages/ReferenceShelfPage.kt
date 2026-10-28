package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.session.WorldSession
import com.flux.manga.data.LocalSearchField
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.navigation.ShelfSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ReferenceShelfPage(
    session: WorldSession, bottomClearance: Dp, open: (ShelfBook) -> Unit, read: (ShelfBook) -> Unit,
    addCbz: () -> Unit, localLibrary: () -> Unit, onlineLibrary: () -> Unit, sources: () -> Unit,
    search: () -> Unit, settings: () -> Unit, samples: () -> Unit, probe: () -> Unit, glass: () -> Unit,
    failSwitch: () -> Unit, list: (ShelfSection) -> Unit, reportScroll: (() -> Unit) -> Unit,
    section: ShelfSection? = null, back: () -> Unit = {}, switchError: String? = null,
    offline: (ShelfBook) -> Unit = open, downloads: () -> Unit = settings,
    storage: () -> Unit = settings, statistics: () -> Unit = settings,
    exportRecords: () -> Unit = settings,
    searchMode: Boolean = false, account: () -> Unit = sources, komgaAccount: () -> Unit = sources, copyAccount: () -> Unit = sources,
    initialAuthor: String = "",
) {
    val app = LocalContext.current.applicationContext as Application
    val model = preloadedPages(session).shelf()
    val state by model.state.collectAsStateWithLifecycle()
    val informationModel = rememberBookInformation(session)
    val informationState by informationModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(informationState.running, informationState.revision) {
        if (!informationState.running && informationState.revision > 0) model.refresh(silent = true)
    }
    val filterName by model.filter.collectAsStateWithLifecycle()
    val filter = ShelfFilter.entries.firstOrNull { it.name == filterName } ?: ShelfFilter.ALL
    val identity by session.identity.collectAsStateWithLifecycle()
    val world = identity.world
    val public = world == PrototypeWorld.SFW
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh(silent = true) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    androidx.activity.compose.BackHandler(selecting) {
        if (state.batchBusy) model.cancelBatch()
        else { selecting = false; selectedKeys = emptyList() }
    }
    var query by rememberSaveable(initialAuthor) { mutableStateOf(initialAuthor) }
    var searchField by rememberSaveable(initialAuthor) { mutableStateOf(if (initialAuthor.isBlank()) LocalSearchField.ALL else LocalSearchField.AUTHOR) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val isRoot = section == null && !searchMode
    var downloadFilter by rememberSaveable { mutableStateOf(ShelfDownloadFilter.ALL) }
    val books = remember(state.books, downloadFilter, isRoot, favoritesOnly, section, filter, state.sort, query, searchField) {
        shelfResults(filterShelfDownloads(state.books, downloadFilter), isRoot || favoritesOnly,
            section == ShelfSection.HISTORY, filter, state.sort, query, searchField)
    }
    val visibleKeys = books.map { it.stableKey }
    LaunchedEffect(visibleKeys) { selectedKeys = selectedKeys.filter { it in visibleKeys } }
    LaunchedEffect(state.batchCompleted) { selectedKeys = selectedKeys.filterNot { it in state.batchCompleted } }
    val canEdit = !state.loading && !state.batchBusy && state.saving.isEmpty()
    fun toggle(book: ShelfBook) {
        if (canEdit) selectedKeys = if (book.stableKey in selectedKeys) selectedKeys - book.stableKey else selectedKeys + book.stableKey
    }
    val latest = latestReadable(state.books)
    val scroll = rememberLazyGridState()
    val loadingScroll = rememberLazyGridState()
    // The short initial loading/empty frame must not clamp a restored content anchor.
    val shownScroll = if (state.loading && state.books.isEmpty()) loadingScroll else scroll
    SideEffect { reportScroll { shownScroll.firstVisibleItemIndex; shownScroll.firstVisibleItemScrollOffset; Unit } }
    val fontScale = LocalDensity.current.fontScale
    val collectionHeader: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().then(if (selecting) Modifier.background(MaterialTheme.colorScheme.background) else Modifier),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((if (searchMode) "搜索结果" else if (isRoot) "收藏" else "书目") +
                    (if (selecting) "（已选 ${selectedKeys.size} 部）" else ""), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                if (!selecting) Text(when {
                    state.loading && state.books.isEmpty() -> "读取中…"
                    else -> "${books.size} 部${if (state.errors.isNotEmpty()) " · 待确认" else ""}"
                },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
                if (!selecting) TextButton(onClick = { selecting = true; selectedKeys = emptyList() }, enabled = canEdit,
                    modifier = Modifier.testTag("shelf-batch-toggle")) { Text("批量") }
                if (!selecting) IconButton(onClick = { panel = "filters" }, enabled = !state.batchBusy, modifier = Modifier.testTag("shelf-actions")
                    .semantics { contentDescription = "筛选与排序" }) { ReferenceIcon(ReferenceSymbol.OPTIONS) }
                if (!selecting) IconButton(onClick = { model.layout(if (state.layout == ShelfLayout.GRID) ShelfLayout.LIST else ShelfLayout.GRID) },
                    modifier = Modifier.testTag("shelf-layout").semantics {
                        stateDescription = if (state.layout == ShelfLayout.GRID) "网格视图" else "列表视图"
                        contentDescription = if (state.layout == ShelfLayout.GRID) "切换列表视图" else "切换网格视图"
                    }) {
                    val color = LocalContentColor.current
                    Canvas(Modifier.size(24.dp).clearAndSetSemantics { }) {
                        val unit = size.width / 24
                        if (state.layout == ShelfLayout.GRID) repeat(3) { i ->
                            drawLine(color, androidx.compose.ui.geometry.Offset(3 * unit, (5 + i * 7) * unit),
                                androidx.compose.ui.geometry.Offset(21 * unit, (5 + i * 7) * unit), 2 * unit,
                                androidx.compose.ui.graphics.StrokeCap.Round)
                        } else repeat(4) { i ->
                            drawRoundRect(color, androidx.compose.ui.geometry.Offset((3 + i % 2 * 11) * unit, (3 + i / 2 * 11) * unit),
                                androidx.compose.ui.geometry.Size(7 * unit, 7 * unit), androidx.compose.ui.geometry.CornerRadius(unit))
                        }
                    }
                }
            }
            if (isRoot) ShelfReadingFilters(filter, model::filter, enabled = !state.batchBusy)
            if (!isRoot && (filter != ShelfFilter.ALL || favoritesOnly || downloadFilter != ShelfDownloadFilter.ALL || searchField != LocalSearchField.ALL)) {
                FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(filter.takeIf { it != ShelfFilter.ALL }?.label,
                        "仅收藏".takeIf { favoritesOnly }, downloadFilter.takeIf { it != ShelfDownloadFilter.ALL }?.label,
                        searchField.takeIf { it != LocalSearchField.ALL }?.label).joinToString(" · "),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (!selecting) TextButton(onClick = { favoritesOnly = false; model.filter(ShelfFilter.ALL); downloadFilter = ShelfDownloadFilter.ALL; searchField = LocalSearchField.ALL }, enabled = !state.batchBusy) { Text("重置") }
                }
            }
            if (selecting) ShelfSelectionBar(
                canEdit = canEdit, hasBooks = books.isNotEmpty(), hasSelection = selectedKeys.isNotEmpty(),
                selectAll = { selectedKeys = visibleKeys }, clear = { selectedKeys = emptyList() },
                unfavorite = { model.batch(selectedKeys.toSet(), ShelfBatchAction.UNFAVORITE) },
            )
            if (state.batchBusy) {
                Text("正在处理 ${state.batchProcessed}/${state.batchTotal} 部")
                if (!selecting) TextButton(model::cancelBatch) { Text("停止处理") }
            }
            state.batchMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

        }
    }
    ReferenceRefreshBox(state.loading && state.showLoading, { model.refresh() }, Modifier.fillMaxSize(),
        enabled = canEdit && !selecting) { BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = shelfColumns(maxWidth.value, fontScale, state.layout)
        val compactContinue = maxWidth < 340.dp || fontScale >= 1.5f
        Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Fixed(columns), state = shownScroll,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("reference-shelf"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomClearance),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isRoot) SettingsHeading(when {
                        searchMode -> "本机书目搜索"
                        section == ShelfSection.HISTORY -> "阅读记录"
                        else -> "全部书目"
                    }, "", back, actions = {
                        ReferenceActionsButton("书目操作", { panel = "more" }, Modifier.testTag("shelf-page-actions"))
                    }) else {
                        ReferencePageHeader("我的书库") {
                            IconButton(onClick = { panel = "add" }, modifier = Modifier.testTag("shelf-add").semantics { contentDescription = "添加书目" }) {
                                Text("+", fontSize = 30.sp, modifier = Modifier.clearAndSetSemantics { })
                            }
                            ReferenceMoreButton("设置", "shelf-more", settings)
                        }
                    }
                    if (informationState.running) TextButton({ panel = "information" }) { Text("正在补齐作品资料 · ${informationState.processed}/${informationState.total}") }
                    switchError?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (state.loading && state.showLoading) {
                        Text(if (state.books.isEmpty()) "正在读取书库…" else "正在刷新，保留已显示书目",
                            modifier = Modifier.testTag("shelf-loading").semantics { liveRegion = LiveRegionMode.Polite })
                        RequestLoading()
                    }
                    if (state.errors.isNotEmpty()) Text(
                        if (state.books.isEmpty()) "书库尚未读出" else "部分内容待确认，已保留可用书目",
                        modifier = Modifier.testTag("shelf-error").semantics { liveRegion = LiveRegionMode.Polite })
                    state.errors.forEach { RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (state.errors.isNotEmpty()) TextButton(onClick = feedbackAttempt(model::refresh), enabled = !state.loading) { Text("重试读取书库") }
                }
            }
            // Keep these structural keys in both modes so book anchors do not change.
            if (isRoot) {
                item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DiscoverySectionHeading("继续阅读", "shelf-history", "阅读记录") { list(ShelfSection.HISTORY) }
                    if (latest != null) ContinueCard(latest, model, open, read, compactContinue)
                    else if (state.loading) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp), contentAlignment = Alignment.Center) { Text("正在读取阅读记录…") }
                    }
                    else if (!state.loading) ReferenceEmptyState(if (state.errors.isEmpty()) "还没有可继续的阅读记录" else "暂时没有可确认的续读记录") {
                        TextButton(onClick = { list(ShelfSection.ALL) }) { Text("选择书目，开始阅读") }
                    }
                  }
                }
            } else item(key = "query", span = { GridItemSpan(maxLineSpan) }) {
                LibraryQueryField(query, { query = it }, "shelf-query", searchField.placeholder)
            }
            if (selecting) stickyHeader(key = "collection-heading") { collectionHeader() }
            else item(key = "collection-heading", span = { GridItemSpan(maxLineSpan) }) { collectionHeader() }
            if (state.loading && state.books.isEmpty()) item(key = "read-layout", span = { GridItemSpan(maxLineSpan) }) { ReadLayoutPlaceholder() }
            if (books.isEmpty() && !state.loading) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.testTag("shelf-empty").semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(when {
                        state.errors.isNotEmpty() -> "部分书目暂不可用，请重试"
                        searchMode && state.books.isEmpty() -> "还没有可搜索的本机书目"
                        searchMode -> "没有符合当前查询与筛选的书目"
                        query.isNotBlank() || favoritesOnly || filter != ShelfFilter.ALL || downloadFilter != ShelfDownloadFilter.ALL -> "没有符合条件的书目"
                        section == ShelfSection.HISTORY -> "尚无阅读记录"
                        isRoot -> "还没有收藏，去全部书目添加喜欢的故事"
                        else -> "还没有书目"
                    })
                    if (state.errors.isNotEmpty()) TextButton(onClick = feedbackAttempt(model::refresh)) { Text("重试读取书库") }
                    else if (!isRoot && (favoritesOnly || filter != ShelfFilter.ALL || query.isNotBlank() || downloadFilter != ShelfDownloadFilter.ALL || searchField != LocalSearchField.ALL))
                        TextButton(onClick = { favoritesOnly = false; model.filter(ShelfFilter.ALL); query = ""; downloadFilter = ShelfDownloadFilter.ALL; searchField = LocalSearchField.ALL }) { Text("清除全部筛选") }
                    else if (filter != ShelfFilter.ALL || query.isNotBlank()) TextButton(onClick = { model.filter(ShelfFilter.ALL); query = "" }) { Text("清除筛选") }
                    else if (downloadFilter != ShelfDownloadFilter.ALL) TextButton(onClick = { downloadFilter = ShelfDownloadFilter.ALL }) { Text("清除下载筛选") }
                    else if (isRoot || section == ShelfSection.HISTORY) TextButton(onClick = { list(ShelfSection.ALL) }) { Text("查看全部书目") }
                    else TextButton(onClick = { panel = "add" }) { Text("添加书目") }
                }
            }
            val groups = if (section == ShelfSection.HISTORY && state.sort == ShelfSort.RECENT)
                books.groupBy { readingDay(it) } else mapOf("" to books)
            groups.forEach { (day, rows) ->
                if (day.isNotEmpty()) item(key = "day:$day", span = { GridItemSpan(maxLineSpan) }) {
                    Text(day, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                }
                items(rows, key = { it.stableKey }, contentType = { state.layout }) { book ->
                    ShelfTile(book, model, state.layout == ShelfLayout.LIST, open,
                        selecting, book.stableKey in selectedKeys, canEdit,
                        toggle = { toggle(book) }, startSelection = { if (canEdit) { selecting = true; selectedKeys = listOf(book.stableKey) } })
                }
            }

        }
    }
    }
    }
    if (panel != null) ShelfPanel(when (panel) { "add" -> "添加书目"; "filters", "sort" -> "筛选与排序"; "dev" -> "开发验证"; "help" -> "书目说明"; "information" -> "作品资料"; else -> "书库操作" }, { panel = null }) {
        fun run(action: () -> Unit) { panel = null; action() }
        when (panel) {
            "information" -> BookInformationPanel(informationModel)
            "add" -> {
                if (public) PanelAction("导入本地 CBZ") { run(addCbz) }
                PanelAction("从图源导入") { run(sources) }
                if (public) PanelAction("本地文件管理") { run(localLibrary) }
                PanelAction("在线书目管理") { run(onlineLibrary) }
            }
            "filters", "sort" -> {
                if (!isRoot) {
                    SettingsDisclosure("搜索范围", searchField.label) { LocalSearchFieldOptions(searchField) { searchField = it } }
                    Text("阅读状态", style = MaterialTheme.typography.titleMedium)
                    ShelfReadingFilters(filter, model::filter)
                    FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("仅收藏") })
                }
                if (public) {
                    Text("下载状态", style = MaterialTheme.typography.titleMedium)
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShelfDownloadFilter.entries.forEach { value -> FilterChip(downloadFilter == value, { downloadFilter = value }, label = { Text(value.label) }) }
                    }
                }
                Text("排序", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfSort.entries.forEach { value -> FilterChip(state.sort == value, { model.sort(value) }, label = { Text(value.label) }) }
                }
                if (state.sort == ShelfSort.IMPORTED) Text("本地书目按导入时间排列，在线书目列在其后。", style = MaterialTheme.typography.bodySmall)
                if (filter == ShelfFilter.READ) Text("本地读到末页计为已读；在线总量未知时计为在读。", style = MaterialTheme.typography.bodySmall)

            }
            "help" -> Text(when {
                searchMode -> "搜索本机书名，以及本地 CBZ 的作者、卷号和简介。多个筛选条件需同时满足；清除筛选会保留关键词。"
                section == ShelfSection.HISTORY -> "显示读过的作品。仅浏览过详情的作品请在首页“最近观看”中查看。"
                else -> "显示本机全部书目。取消收藏会保留书目和阅读进度。"
            })
            else -> {
                if (!isRoot) PanelAction("添加书目") { panel = "add" }
                if (!searchMode) PanelAction("搜索") { run(search) }
                if (section != ShelfSection.ALL) PanelAction("全部书目") { run { list(ShelfSection.ALL) } }
                if (section != ShelfSection.HISTORY) PanelAction("阅读记录") { run { list(ShelfSection.HISTORY) } }
                PanelAction("筛选与排序") { panel = "filters" }
                PanelAction("设置") { run(settings) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShelfReadingFilters(filter: ShelfFilter, select: (ShelfFilter) -> Unit, enabled: Boolean = true) {
    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("shelf-reading-filters"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfFilter.entries.forEach { value ->
            ShelfTextButton(value.label, { select(value) }, enabled = enabled, selected = filter == value,
                modifier = Modifier.testTag("shelf-filter-${value.name}"))
        }
    }
}

@Composable
private fun ShelfPanel(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ReferenceSheet(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable
private fun PanelAction(label: String, enabled: Boolean = true, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
        Text(label, Modifier.weight(1f, fill = false))
        if (enabled) { Spacer(Modifier.width(8.dp)); SettingsChevron() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfTile(book: ShelfBook, model: ShelfViewModel, row: Boolean, open: (ShelfBook) -> Unit,
    selecting: Boolean, checked: Boolean, enabled: Boolean, toggle: () -> Unit, startSelection: () -> Unit) {
    val details = Modifier.combinedClickable(enabled = enabled, onClick = { if (selecting) toggle() else open(book) },
        onLongClick = if (selecting) null else startSelection,
        onClickLabel = if (selecting) "选择《${book.title}》" else "查看《${book.title}》详情",
        onLongClickLabel = if (selecting) null else "批量选择")
        .semantics { if (selecting) { role = Role.Checkbox; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off; contentDescription = "选择书目：${book.title}" } }
    @Composable fun Info(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                minLines = 2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            ShelfUpdateLabel(book.information, book.chapterId, book.online)
            Text(shelfReadingLabel(
                if (book.hasProgress && !book.validProgress) "阅读位置待核对" else book.status.label,
                book.information, book.chapterId, book.online),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!book.available) Text(if (book.online) "来源已失效" else "副本缺失", style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable fun Cover(modifier: Modifier) {
        Box(modifier) {
            ShelfCover(book, model, Modifier.fillMaxSize())
            if (selecting) {
                if (checked) Box(Modifier.matchParentSize().border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
                ShelfSelectionBadge(checked, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("shelf-book-${book.stableKey}").then(details)) {
        if (row && maxWidth >= 300.dp && fontScale < 1.5f) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(Modifier.width(98.dp).aspectRatio(.7f))
            Info(Modifier.weight(1f))
        } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Cover((if (row) Modifier.width(126.dp) else Modifier.fillMaxWidth()).aspectRatio(.7f))
            Info(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ShelfCover(book: ShelfBook, model: ShelfViewModel, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop) {
    val visibility by model.session.state.collectAsState()
    val private = book.key.world == PrototypeWorld.NSFW
    val epoch = if (private) visibility.visibilityEpoch else 0L
    val revision by model.session.pageCovers().revision.collectAsState()
    var bitmap by remember(model, book.stableKey, book.available, epoch, revision) { mutableStateOf(model.cachedCover(book)) }
    var failed by remember(model, book.stableKey) { mutableStateOf(false) }
    var missing by remember(model, book.stableKey) { mutableStateOf(false) }
    var attempt by remember(model, book.stableKey) { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && visibility.foreground &&
        visibility.phase == com.flux.manga.session.SwitchPhase.Stable
    LaunchedEffect(model, book.stableKey, book.available, book.information?.coverReady, active, visibility.visibilityEpoch, attempt, revision) {
        if (!active) { if (private) bitmap = null; return@LaunchedEffect }
        model.prepareInformation(book)
        if (bitmap != null) return@LaunchedEffect
        failed = false; missing = false
        try { bitmap = model.cover(book); missing = bitmap == null }
        catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) failed = true
            throw cancelled
        }
        catch (_: com.flux.manga.core.SourceException.NotFound) { missing = true }
        catch (_: Exception) { failed = true }
    }
    CoverArtwork(bitmap?.takeIf { !private || active }, active && book.available && bitmap == null && !failed && !missing,
        when { !book.available -> "封面不可用"; !active -> "封面"; missing -> "暂无封面"; failed -> "封面加载失败"; else -> "正在加载封面" },
        modifier, retry = if (active && failed) ({ attempt++ }) else null,
        description = "《${book.title}》封面", contentScale = contentScale)
}

@Composable
private fun ContinueCard(book: ShelfBook, model: ShelfViewModel, open: (ShelfBook) -> Unit,
                         read: (ShelfBook) -> Unit, stacked: Boolean) {
    @Composable fun Info(modifier: Modifier) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            ShelfUpdateLabel(book.information, book.chapterId, book.online)
            Text(shelfReadingLabel(if (book.online) "上次读到第 ${book.ordinal} 页" else book.positionText,
                book.information, book.chapterId, book.online),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            book.ratio?.let { fraction ->
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ReferenceButton(onClick = { read(book) }, modifier = Modifier.testTag("shelf-continue")) {
                ReferenceIcon(ReferenceSymbol.PLAY)
                Spacer(Modifier.width(8.dp))
                Text("继续阅读")
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        if (stacked) Column {
            ShelfCover(book, model, Modifier.fillMaxWidth().height(190.dp).clickable { open(book) }, ContentScale.Crop)
            Info(Modifier.fillMaxWidth())
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The text and controls determine height; the cover follows that measured height.
            // An intrinsic Row can constrain a wrapped title's button below its touch target.
            Box(Modifier.matchParentSize()) {
                ShelfCover(book, model, Modifier.fillMaxWidth(.36f).fillMaxHeight().clickable(onClickLabel = "查看详情") { open(book) }, ContentScale.Crop)
            }
            Info(Modifier.fillMaxWidth().padding(start = maxWidth * .36f))
        }
    }
}
