package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.flux.manga.ui.glass.WorldColors
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class ReaderPosition(val page: Int, val fraction: Float, val mode: ReadingMode)
data class LocalReaderState(val detail: MangaDetail? = null, val position: ReaderPosition = ReaderPosition(0, 0f, ReadingMode.LTR),
    val modeExplicit: Boolean = false, val loading: Boolean = true, val error: String? = null, val saveError: String? = null,
    val saved: Boolean = true, val anchorRevision: Int = 0, val bookmarkError: String? = null)

class LocalReaderViewModel(app: Application, private val session: WorldSession, id: String,
    private val bookmarkId: String? = null, private val savedState: SavedStateHandle = SavedStateHandle(),
    private val data: WorldDataStore = WorldDataStore(app)) : androidx.lifecycle.AndroidViewModel(app) {
    val library = CbzLibrary(data)
    private val key = MangaKey(PrototypeWorld.SFW, "cbz", id)
    private val identity = session.identity.value
    private val mutable = MutableStateFlow(LocalReaderState())
    val state = mutable.asStateFlow()
    // This writer drains the final snapshot after entry removal. It is closed in onCleared.
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writeLock = Mutex()
    private var pending: Job? = null
    private var revision = 0L
    private var storedRevision = 0L
    private val pixels = DisplayBitmapStore<String>(reader = true)
    private val previews = DisplayBitmapStore<String>(reader = true, preview = true)
    private val previewFiles = ReaderEncodedCache(data, savedState.get<String>("reader-cache-session") ?:
        java.util.UUID.randomUUID().toString().also { savedState["reader-cache-session"] = it })
    private fun imageKey(page: PageResource, top: Int, bottom: Int, width: Int, whole: Boolean) = "${page.id}:$top:$bottom:$width:$whole"
    internal fun cached(page: PageResource, top: Int, bottom: Int, width: Int, whole: Boolean) =
        pixels.get(imageKey(page, top, bottom, width, whole))
    internal suspend fun preview(page: PageResource, top: Int, bottom: Int, width: Int, whole: Boolean): Bitmap? {
        val id = imageKey(page, top, bottom, width, whole)
        previews.get(id)?.let { return it }
        val bytes = previewFiles.read(ResourceKey(key, "preview:$id")) { session.accepts(identity) } ?: return null
        return try { withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            ?.also { currentCoroutineContext().ensureActive(); if (!session.accepts(identity)) throw CancellationException(); previews.put(id, it) } } finally { bytes.fill(0) }
    }
    internal suspend fun image(page: PageResource, top: Int, bottom: Int, width: Int, whole: Boolean): Bitmap {
        cached(page, top, bottom, width, whole)?.let { return it }
        val id = imageKey(page, top, bottom, width, whole)
        val image = library.readPage(page, top, bottom, width, whole)
        currentCoroutineContext().ensureActive()
        if (!session.accepts(identity)) throw CancellationException()
        if (!whole && previews.get(id) == null) {
            val preview = withContext(Dispatchers.Default) {
                val w = minOf(160, image.width)
                Bitmap.createScaledBitmap(image, w, (image.height.toLong() * w / image.width).toInt().coerceAtLeast(1), true)
            }
            val bytes = withContext(Dispatchers.Default) { java.io.ByteArrayOutputStream().use { output ->
                check(preview.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)); output.toByteArray()
            } }
            try { previewFiles.write(ResourceKey(key, "preview:$id"), bytes) { session.accepts(identity) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { /* The CBZ remains the local source; preview writes are optional. */ }
            finally { bytes.fill(0) }
            currentCoroutineContext().ensureActive()
            if (!session.accepts(identity)) throw CancellationException()
            previews.put(id, preview)
        }
        currentCoroutineContext().ensureActive()
        if (!session.accepts(identity)) throw CancellationException()
        pixels.put(id, image)
        return image
    }
    init { load() }
    fun load() {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                val detail = library.detail(key) ?: throw IOException("本地书目已不存在")
                if (!detail.card.available) throw CbzException(ImportFailure.MISSING)
                val progress = detail.card.progress
                val index = detail.chapter.pages.indexOfFirst { it.id == progress?.pageId }.coerceAtLeast(0)
                val explicit = progress?.modeExplicit ?: false
                val default = if (explicit && decodeReadingMode(progress?.mode) != null) ReadingMode.LTR
                    else data.readingDefault(identity.world)
                val mode = resolveReadingMode(progress?.mode, explicit, default)
                val bookmark = if (bookmarkId != null && savedState.get<Boolean>("bookmark-applied") != true)
                    LocalBookmarks(data, session, key.mangaId).find(bookmarkId)
                        ?: throw IOException("书签已不存在，可返回书目或继续上次阅读") else null
                if (session.accepts(identity)) {
                    mutable.value = LocalReaderState(detail,
                        ReaderPosition(index, progress?.fraction?.coerceIn(0f, 1f) ?: 0f, mode), modeExplicit = explicit, loading = false)
                    if (bookmark != null) {
                        openBookmark(bookmark)
                        if (state.value.bookmarkError == null) savedState["bookmark-applied"] = true
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: IOException) { mutable.value = mutable.value.copy(loading = false, error = e.message) }
        }
    }
    fun continueProgress() { savedState["bookmark-applied"] = true; load() }
    fun openBookmark(bookmark: LocalBookmark) {
        val detail = state.value.detail ?: return
        if (!session.accepts(identity) || bookmark.sourceId != key.sourceId || bookmark.mangaId != key.mangaId) return
        val page = detail.chapter.pages.indexOfFirst { it.id == bookmark.pageId }
        val mode = decodeReadingMode(bookmark.mode)
        if (page < 0 || mode == null || !bookmark.fraction.isFinite()) {
            mutable.value = state.value.copy(bookmarkError = "书签位置已不可用，保留当前阅读位置与备注")
            return
        }
        position(page, bookmark.fraction, mode, explicit = true)
        // Recreate the viewport from the exact anchor, including nonzero strip fractions.
        mutable.value = state.value.copy(anchorRevision = state.value.anchorRevision + 1, bookmarkError = null)
    }
    fun position(page: Int, fraction: Float = 0f, mode: ReadingMode = state.value.position.mode,
        explicit: Boolean = state.value.modeExplicit) {
        val current = state.value
        val detail = current.detail ?: return
        if (!session.accepts(identity)) return
        val next = ReaderPosition(page.coerceIn(detail.chapter.pages.indices), fraction.coerceIn(0f, .9999f), mode)
        if (next == current.position && explicit == current.modeExplicit && (detail.card.progress != null || revision > 0)) return
        revision++
        mutable.value = current.copy(position = next, modeExplicit = explicit, saved = false, saveError = null)
        pending?.cancel()
        pending = viewModelScope.launch { if (mode == ReadingMode.STRIP) delay(250); flush().join() }
    }
    fun mode(mode: ReadingMode) = position(state.value.position.page, state.value.position.fraction, mode, explicit = true)
    fun settledPage(page: Int, mode: ReadingMode) {
        val current = state.value.position
        if (current.mode == mode) position(page, pagedFraction(current.page, page, current.fraction))
    }
    fun flush(): Job {
        val snapshot = state.value
        val version = revision
        return writer.launch {
            val detail = snapshot.detail ?: return@launch
            if (version == 0L || version <= storedRevision) return@launch
            writeLock.withLock {
                if (version <= storedRevision) return@withLock
                try {
                    library.saveProgress(key, detail.chapter.pages[snapshot.position.page].id,
                        snapshot.position.fraction, snapshot.position.mode, snapshot.modeExplicit) { session.accepts(identity) }
                    storedRevision = version
                    if (version == revision) mutable.value = state.value.copy(saved = true, saveError = null)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: IOException) { mutable.value = state.value.copy(saveError = "进度未保存，请重试：${e.message}") }
            }
        }
    }
    fun releaseDisplay() { pixels.evictAll(); previews.evictAll() }
    override fun onCleared() {
        pending?.cancel()
        pixels.evictAll(); previews.evictAll()
        flush().invokeOnCompletion { writer.launch { try { previewFiles.close() } finally { writer.cancel() } } }
    }
}

data class ReaderTile(val pageIndex: Int, val page: PageResource, val top: Int, val bottom: Int) {
    val key get() = "${page.id}:$top"
}
fun readerTiles(pages: List<PageResource>): List<ReaderTile> = pages.flatMapIndexed { index, page ->
    val step = (page.width.toLong() * 2).coerceIn(1024, 2048).toInt()
    (0 until page.height step step).map { top -> ReaderTile(index, page, top, minOf(page.height, top + step)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalReaderPage(session: WorldSession, mangaId: String, back: () -> Unit, bookmarkId: String? = null) {
    val app = LocalContext.current.applicationContext as Application
    val model: LocalReaderViewModel = viewModel(factory = viewModelFactory { initializer { LocalReaderViewModel(app, session, mangaId, bookmarkId, createSavedStateHandle()) } })
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val (options, changeOptions) = rememberReaderOptions(session.identity.value.world)
    var leaving by remember { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf<ReaderTool?>(null) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    ReaderImmersive(controls || state.loading || state.error != null || tool != null || showBookmarks)
    LaunchedEffect(state.saveError, state.bookmarkError) {
        if (state.saveError != null || state.bookmarkError != null) controls = true
    }
    val statistics = rememberReadingTime(session, "local", "cbz", mangaId,
        !state.loading && state.error == null && state.detail != null && tool == null && !showBookmarks && !leaving,
        state.detail?.card?.title)
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch { model.flush().join(); if (model.state.value.saveError == null) back(); leaving = false }
        }
    }
    BackHandler(onBack = leave)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { tool = null; showBookmarks = false; model.releaseDisplay(); model.flush() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.releaseDisplay(); model.flush() }
    }
    if (state.loading || state.error != null) {
        ReaderLoadingScreen(state.error, state.loading, leave, model::load) {
            if (bookmarkId != null && state.error != null) TextButton(onClick = model::continueProgress) { Text("继续上次阅读") }
        }
    } else state.detail?.let { detail ->
        val bookmarks = rememberLocalBookmarks(session, mangaId, showBookmarks)
        CompositionLocalProvider(LocalReadingTime provides statistics,
            LocalReaderTap provides { controls = !controls }, LocalReaderChromeVisible provides controls) {
            Box(Modifier.fillMaxSize().testTag("local-reader")) {
                ReaderCanvas(Modifier.fillMaxSize().clipToBounds().background(WorldColors.DarkBackground)) {
                    val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                    key(state.position.mode, state.anchorRevision) {
                        if (state.position.mode == ReadingMode.STRIP) StripReader(detail, state.position, model, widthPx, options, !controls && tool == null && !showBookmarks && !leaving)
                        else PagedReader(detail, state.position, model, widthPx, options, !controls && tool == null && !showBookmarks && !leaving,
                            readerSpreadEnabled(options.spread, state.position.mode, maxWidth.value, maxHeight.value))
                    }
                }
                if (controls) Column(Modifier.align(Alignment.TopCenter).referenceToolEntrance(fromTop = true)) {
                    ReaderTopBar(detail.card.title, state.position.mode, !leaving, leaving, leave, model::mode,
                        { tool = it }, { controls = false }, local = true, bookmarks = { showBookmarks = true })
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 160.dp)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .verticalScroll(rememberScrollState())) {
                            ReadingTimeFeedback(statistics)
                            state.bookmarkError?.let { RequestFeedback(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (controls) Box(Modifier.align(Alignment.BottomCenter).referenceToolEntrance()) {
                    ReaderSeekBar(mangaId, state.position.page, detail.chapter.pages.size, !leaving,
                        jump = { model.position(it) }, previous = { model.position(state.position.page - 1) },
                        next = { model.position(state.position.page + 1) }, canPrevious = state.position.page > 0,
                        canNext = state.position.page < detail.chapter.pages.lastIndex, showPage = { tool = ReaderTool.PAGE }, local = true) {
                        if (!state.saved || state.saveError != null) ReaderSaveFeedback(state.saved, state.saveError, { model.flush() }, "reader-save")
                    }
                }
            }
            if (showBookmarks) LocalBookmarkDialog(bookmarks, !leaving, { showBookmarks = false }, model::openBookmark,
                add = { val position = model.state.value.position; bookmarks.add(detail.chapter.pages[position.page], position) })
            tool?.let { selectedTool ->
                ReaderToolDialog(selectedTool, mangaId, state.position.page, detail.chapter.pages.size, state.position.mode,
                    options, !leaving, online = false, dismiss = { tool = null }, selectMode = model::mode,
                    jumpTo = { model.position(it) }, changeOptions = changeOptions)
            }
        }
    }
}

@Composable
private fun PagedReader(detail: MangaDetail, position: ReaderPosition, model: LocalReaderViewModel, width: Int,
    options: ReaderOptions, keysEnabled: Boolean, spread: Boolean) {
    val unit = if (spread) 2 else 1
    val count = detail.chapter.pages.size
    key(spread) {
        val pager = rememberPagerState(initialPage = position.page / unit) { (count + unit - 1) / unit }
        var zoomedPages by remember(pager.settledPage) { mutableStateOf(emptySet<Int>()) }
        LaunchedEffect(position.page) { if (!pager.isScrollInProgress && pager.settledPage != position.page / unit) pager.scrollToPage(position.page / unit) }
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
            model.settledPage(settledReaderAnchor(model.state.value.position.page, it, spread), position.mode)
        } }
        ReaderVolumeKeys(options.volume && keysEnabled && zoomedPages.isEmpty(), options.invertVolume) {
            model.position(((pager.settledPage + it) * unit).coerceIn(0, count - 1))
        }
        ReadingPager(pager, position.mode, "reader-pager", { detail.chapter.pages[it * unit].id }, enabled = zoomedPages.isEmpty()) { group ->
            Row(Modifier.fillMaxSize()) {
                spreadIndices(group, count, spread, position.mode == ReadingMode.RTL).forEach { index ->
                    val page = detail.chapter.pages[index]
                    ReaderImage(model, page, 0, page.height, width / unit, true,
                        Modifier.weight(1f).fillMaxHeight(), options.doubleTap,
                        group == pager.settledPage && !pager.isScrollInProgress) { zoomedPages = if (it) zoomedPages + index else zoomedPages - index }
                }
            }
        }
    }
}

@Composable
private fun StripReader(detail: MangaDetail, position: ReaderPosition, model: LocalReaderViewModel, width: Int, options: ReaderOptions, keysEnabled: Boolean) {
    val tiles = remember(detail) { readerTiles(detail.chapter.pages) }
    val initial = remember(width) { position }
    val initialPage = detail.chapter.pages[initial.page]
    val y = initial.fraction * initialPage.height
    val initialIndex = tiles.indexOfFirst { it.pageIndex == initial.page && y < it.bottom }.coerceAtLeast(0)
    val initialOffset = ((y - tiles[initialIndex].top).coerceAtLeast(0f) * width / initialPage.width).toInt()
    // Pixel offsets from SavedState are invalid after a width change. Rebuild from the
    // ViewModel's stable page + relative image anchor for rotation and font changes.
    val list = remember(width) { LazyListState(initialIndex, initialOffset) }
    LaunchedEffect(list, width) {
        snapshotFlow { list.firstVisibleItemIndex to list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged().collectLatest { (first, last) ->
                val neighbors = ((first - 2).coerceAtLeast(0) until first).reversed().toList() +
                    ((last ?: first) + 1..minOf((last ?: first) + 2, tiles.lastIndex)).toList()
                for (index in neighbors) {
                    val tile = tiles.getOrNull(index) ?: continue
                    try { model.image(tile.page, tile.top, tile.bottom, width, false) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { }
                }
            }
    }
    // External page commands use fraction zero. Scroll observations keep fractional anchors.
    LaunchedEffect(position.page, position.fraction == 0f) {
        val currentTile = tiles.getOrNull(list.firstVisibleItemIndex)
        if (!list.isScrollInProgress && position.fraction == 0f &&
            (currentTile?.pageIndex != position.page || currentTile?.top != 0 || list.firstVisibleItemScrollOffset != 0)) {
            list.scrollToItem(tiles.indexOfFirst { it.pageIndex == position.page })
        }
    }
    LaunchedEffect(list) {
        snapshotFlow {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            item?.let {
                val tile = tiles[it.index]
                val within = list.firstVisibleItemScrollOffset.toFloat() / it.size.coerceAtLeast(1)
                tile.pageIndex to ((tile.top + within * (tile.bottom - tile.top)) / tile.page.height).coerceIn(0f, .9999f)
            }
        }.distinctUntilChanged().collect { it?.let { (page, fraction) -> if (model.state.value.position.mode == ReadingMode.STRIP) model.position(page, fraction) } }
    }
    val scope = rememberCoroutineScope()
    var keyScroll by remember { mutableStateOf<Job?>(null) }
    ReaderZoomBox(detail.chapter.pages.first().id, options.doubleTap, Modifier.fillMaxSize()) { zoomed ->
    ReaderVolumeKeys(options.volume && keysEnabled && !zoomed, options.invertVolume) { direction ->
        if (keyScroll?.isActive != true) keyScroll = scope.launch { list.scrollBy(direction * list.layoutInfo.viewportSize.height * .85f) }
    }
    LazyColumn(state = list, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize().testTag("reader-strip")) {
        itemsIndexed(tiles, key = { _, tile -> tile.key }) { _, tile ->
            ReaderImage(model, tile.page, tile.top, tile.bottom, width, false,
                Modifier.fillMaxWidth().aspectRatio(tile.page.width.toFloat() / (tile.bottom - tile.top)),
                selected = list.layoutInfo.visibleItemsInfo.any { it.key == tile.key })
        }
    }
    }
}

@Composable
private fun ReaderImage(model: LocalReaderViewModel, page: PageResource, top: Int, bottom: Int, width: Int, whole: Boolean, modifier: Modifier,
    doubleTap: Boolean = false, selected: Boolean = true, zoomChanged: (Boolean) -> Unit = {}) {
    var bitmap by remember(page.id, top, bottom, width) { mutableStateOf(model.cached(page, top, bottom, width, whole)) }
    var decoded by remember(page.id, top, bottom, width) { mutableStateOf(bitmap != null) }
    var error by remember(page.id, top) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(page, top, bottom, width, attempt) {
        error = null
        try {
            if (bitmap == null && !whole) bitmap = try { model.preview(page, top, bottom, width, whole) } catch (_: IOException) { null }
            bitmap = model.image(page, top, bottom, width, whole)
            decoded = true
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: IOException) { error = e.message ?: "图片加载失败"; bitmap = null; decoded = false }
    }
    // Compose may retain the previous draw frame. Drop references instead of recycling a displayed bitmap.
    ReadingImageVisibility(selected && decoded && bitmap != null)
    Box(modifier.clipToBounds().testTag("reader-image-${page.ordinal}-$top"), contentAlignment = Alignment.Center) {
        bitmap?.let { image ->
            @Composable fun Pixels() {
                Image(image.asImageBitmap(), "第 ${page.ordinal} 页${if (whole) "" else "，分段 ${top + 1} 至 $bottom"}",
                    contentScale = if (whole) ContentScale.Fit else ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().testTag("reader-loaded-${page.ordinal}-$top"))
            }
            if (whole) ReaderZoomBox(page.id, doubleTap, Modifier.fillMaxSize(), selected, image.width, image.height,
                changed = zoomChanged) { Pixels() } else Pixels()
        } ?: if (error == null) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RequestFeedback(error!!, color = Color.White, modifier = Modifier.testTag("reader-image-error"))
            ReferenceButton(onClick = { attempt++ }) { Text("重试图片") }
        }
    }
}
