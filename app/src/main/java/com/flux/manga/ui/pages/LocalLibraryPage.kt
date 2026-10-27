package com.flux.manga.ui.pages

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

data class LocalLibraryState(val loading: Boolean = true, val importing: Boolean = false,
    val cards: List<MangaCard> = emptyList(), val detail: MangaDetail? = null,
    val cover: Bitmap? = null, val message: String? = null, val savingFavorite: Boolean = false,
    val query: String = "", val favoritesOnly: Boolean = false, val removing: Boolean = false,
    val revision: Int = 0, val batch: List<CbzBatchItem> = emptyList(), val cancellingImport: Boolean = false,
    val importPreview: CbzImportPreview? = null, val searchField: LocalSearchField = LocalSearchField.ALL, val readError: String? = null) {
    val visibleCards get() = cards.filter { (!favoritesOnly || it.favorite) && matchesLocalMetadata(it.title, it.localMetadata, query, searchField) }
}

class LocalLibraryViewModel(context: Context, private val session: WorldSession, private val key: MangaKey?,
    private val savedState: SavedStateHandle) : androidx.lifecycle.AndroidViewModel(context.applicationContext as android.app.Application) {
    private val app get() = getApplication<android.app.Application>()
    private val library = CbzLibrary(WorldDataStore(app))
    private val identity = session.identity.value
    private val mutableState = MutableStateFlow(LocalLibraryState(query = savedState["local-query"] ?: "",
        favoritesOnly = savedState["local-favorites"] ?: false,
        searchField = LocalSearchField.entries.firstOrNull { it.name == savedState.get<String>("local-search-field") } ?: LocalSearchField.ALL))
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var previewChoice: CompletableDeferred<Boolean>? = null
    fun confirmImportMetadata(preview: CbzImportPreview, adopt: Boolean) {
        if (state.value.importPreview === preview) previewChoice?.complete(adopt)
    }
    init { refresh() }
    fun searchField(value: LocalSearchField) {
        if (!session.accepts(identity)) return
        savedState["local-search-field"] = value.name
        mutableState.value = state.value.copy(searchField = value)
    }
    fun updateQuery(query: String) {
        if (!session.accepts(identity)) return
        val value = query.take(200)
        savedState["local-query"] = value
        mutableState.value = mutableState.value.copy(query = value)
    }
    fun favoritesOnly(value: Boolean) {
        if (!session.accepts(identity)) return
        savedState["local-favorites"] = value
        mutableState.value = mutableState.value.copy(favoritesOnly = value)
    }
    fun refresh() {
        if (work?.isCompleted == false) return
        work = viewModelScope.launch { load() }
    }
    private suspend fun load(message: String? = null) {
        if (!session.accepts(identity)) return
        mutableState.value = mutableState.value.copy(loading = true, message = message, readError = null)
        try {
            val cards = library.cards(identity.world)
            val detail = key?.let { library.detail(it) }
            var coverError: String? = null
            val cover = try { detail?.takeIf { it.card.available }?.let { library.cover(it) } }
            catch (c: CancellationException) { throw c }
            catch (_: IOException) { coverError = "封面加载失败，可刷新重试或在书目管理中恢复默认封面"; null }
            currentCoroutineContext().ensureActive()
            if (session.accepts(identity)) mutableState.value = mutableState.value.copy(cards = cards, detail = detail,
                loading = false, importing = false, savingFavorite = false, cover = cover,
                removing = false, revision = mutableState.value.revision + 1,
                message = message ?: coverError ?: when {
                    key != null && detail == null -> "未找到本地书目"
                    state.value.batch.isNotEmpty() -> cbzBatchSummary(state.value.batch)
                    savedState.get<Boolean>("cbz-batch-running") == true -> "上次导入可能已中断；已提交的书目保留，请核对书库并重新选择未完成文件，重复选择不会重置进度。"
                    else -> null
                })
            else cover?.recycle()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IOException) {
            val reason = failure.message ?: "本地书库暂不可用，请重试"
            fail(reason)
            if (session.accepts(identity)) mutableState.value = state.value.copy(readError = reason)
        }
    }
    fun setFavorite(card: MangaCard) {
        if (work?.isCompleted == false || !session.accepts(identity)) return
        mutableState.value = mutableState.value.copy(savingFavorite = true, message = null)
        work = viewModelScope.launch {
            try {
                library.setFavorite(card.key, !card.favorite) { session.accepts(identity) }
                load(if (card.favorite) "已取消收藏" else "已收藏")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IOException) { fail("收藏保存失败，可重试：${failure.message}") }
        }
    }
    internal val coverCache = session.pageCovers()
    internal fun cachedCover(card: MangaCard): Bitmap? =
        if (session.accepts(identity) && card.available) coverCache.peek(card.key.sourceId, card.key.mangaId) else null

    suspend fun loadCover(card: MangaCard): Bitmap? {
        if (!session.accepts(identity) || !card.available) return null
        return coverCache.getOrNull(card.key.sourceId, card.key.mangaId) { library.cover(card.key) }
    }
    suspend fun preview(page: PageResource): Bitmap {
        if (!session.accepts(identity)) throw CancellationException()
        val bitmap = library.readPage(page, targetWidth = 360, wholePage = true)
        if (session.accepts(identity)) return bitmap
        bitmap.recycle()
        throw CancellationException("Expired page preview")
    }
    fun remove(card: MangaCard, removed: () -> Unit) {
        if (work?.isCompleted == false || !session.accepts(identity)) return
        mutableState.value = mutableState.value.copy(removing = true, message = null)
        work = viewModelScope.launch {
            try {
                library.remove(card.key) { session.accepts(identity) }
                coverCache.remove(card.key.sourceId, card.key.mangaId)
                if (session.accepts(identity)) removed()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IOException) {
                // The DB may already be committed. Reload instead of displaying a removed book.
                load(failure.message ?: "移除未完成，请刷新重试")
            }
        }
    }
    fun import(uri: Uri) { import(listOf(uri)) }
    fun import(uris: List<Uri>): Boolean {
        if (state.value.importing || state.value.savingFavorite || state.value.removing || identity.world != PrototypeWorld.SFW || !session.accepts(identity)) return false
        val selected = uris.distinct()
        if (selected.isEmpty() || selected.size > MAX_CBZ_BATCH) return false
        mutableState.value = state.value.copy(batch = selected.mapIndexed { index, uri -> CbzBatchItem(uri, "所选文件 ${index + 1}") })
        startBatch()
        return true
    }
    fun retryImport() {
        if (work?.isCompleted == false || state.value.importing || identity.world != PrototypeWorld.SFW || !session.accepts(identity)) return
        if (state.value.batch.any { it.retryable }) startBatch()
    }
    private fun updateBatch(index: Int, transform: (CbzBatchItem) -> CbzBatchItem) {
        if (session.accepts(identity)) mutableState.value = state.value.copy(batch = state.value.batch.mapIndexed { i, item ->
            if (i == index) transform(item) else item
        })
    }
    private fun startBatch() {
        // Returning from the system picker can resume the page and start a refresh first.
        // A selected document replaces that read instead of silently dropping the import.
        val previous = work
        previous?.cancel()
        val indices = state.value.batch.indices.filter { state.value.batch[it].retryable }
        savedState["cbz-batch-running"] = true
        mutableState.value = state.value.copy(loading = false, importing = true, cancellingImport = false, message = "准备批量导入…")
        work = viewModelScope.launch {
            var interrupted = false
            try {
                previous?.join()
                for (index in indices) {
                    ensureActive()
                    if (!session.accepts(identity)) throw CancellationException("Expired import session")
                    val item = state.value.batch[index]
                    updateBatch(index) { it.copy(status = CbzBatchStatus.IMPORTING, message = null, key = null) }
                    mutableState.value = state.value.copy(message = "正在处理第 ${index + 1} / ${state.value.batch.size} 项；复制与校验中…")
                    try {
                        val source = CbzLibrary.source(app, item.uri)
                        updateBatch(index) { it.copy(name = source.displayName.take(200)) }
                        val result = library.importWithPreview(identity.world, source, preview = { preview ->
                            withContext(Dispatchers.Main.immediate) {
                                if (!session.accepts(identity)) throw CancellationException("Expired import preview")
                                val choice = CompletableDeferred<Boolean>()
                                previewChoice = choice
                                updateBatch(index) { it.copy(status = CbzBatchStatus.PREVIEW) }
                                mutableState.value = state.value.copy(importPreview = preview, message = "请确认第 ${index + 1} 项元数据，尚未入库")
                                try { choice.await() }
                                finally {
                                    if (previewChoice === choice) previewChoice = null
                                    if (session.accepts(identity)) {
                                        mutableState.value = state.value.copy(importPreview = null)
                                        updateBatch(index) { it.copy(status = CbzBatchStatus.IMPORTING) }
                                    }
                                }
                            }
                        }, allowed = { session.accepts(identity) })
                        updateBatch(index) { it.copy(key = result.key, status = when {
                            result.duplicate -> CbzBatchStatus.DUPLICATE
                            result.repaired -> CbzBatchStatus.REPAIRED
                            else -> CbzBatchStatus.IMPORTED
                        }) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        val message = when (failure) {
                            is CbzException -> failure.failure.userMessage
                            is SecurityException -> ImportFailure.PERMISSION.userMessage
                            else -> ImportFailure.IO.userMessage
                        }
                        updateBatch(index) { it.copy(status = CbzBatchStatus.FAILED, message = message) }
                    }
                }
            } catch (cancelled: CancellationException) {
                interrupted = true
                if (session.accepts(identity)) mutableState.value = state.value.copy(batch = state.value.batch.map {
                    if (it.status == CbzBatchStatus.IMPORTING || it.status == CbzBatchStatus.PREVIEW) it.copy(status = CbzBatchStatus.INTERRUPTED,
                        message = "可能已完成入库；刷新核对，重试会按内容去重并保留进度。") else it
                })
                throw cancelled
            } finally {
                // The single-file importer has finished its commit-aware cleanup before we reach here.
                if (session.accepts(identity) && viewModelScope.isActive) withContext(NonCancellable) {
                    savedState["cbz-batch-running"] = false
                    val summary = (if (interrupted) "导入已停止。" else "本批处理结束。") + cbzBatchSummary(state.value.batch)
                    try { load(summary) }
                    finally { if (session.accepts(identity)) mutableState.value = state.value.copy(importing = false, cancellingImport = false) }
                }
            }
        }
    }
    private fun fail(message: String) { if (session.accepts(identity)) mutableState.value = mutableState.value.copy(loading = false, importing = false, savingFavorite = false, removing = false, message = message) }
    fun cancelImport() {
        if (state.value.importing && !state.value.cancellingImport) {
            mutableState.value = state.value.copy(cancellingImport = true, message = "正在停止导入并清理本项临时副本；已提交书目保留…")
            work?.cancel()
        }
    }
    // Bitmap is owned by the immutable UI snapshot; don't recycle while Compose may still draw it.
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LocalLibraryPage(session: WorldSession, mangaId: String? = null, back: () -> Unit, open: (String) -> Unit, read: (String) -> Unit,
                     pickCbz: () -> Unit, pendingCbz: List<Uri> = emptyList(), consumeCbz: (List<Uri>) -> Unit = {},
                     searchMode: Boolean = false, openSources: () -> Unit = {}, openOnline: () -> Unit = {},
                     readBookmark: ((String, String) -> Unit)? = null, searchAuthor: ((String) -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val model: LocalLibraryViewModel = viewModel(factory = viewModelFactory {
        initializer { LocalLibraryViewModel(context, session, mangaId?.let { MangaKey(PrototypeWorld.SFW, "cbz", it) }, createSavedStateHandle()) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val openBook: (String) -> Unit = { keyboard?.hide(); focus.clearFocus(); open(it) }
    val busy = state.loading || state.importing || state.savingFavorite || state.removing
    var confirmRemoval by androidx.compose.runtime.saveable.rememberSaveable(mangaId) { mutableStateOf(false) }
    var previewOrdinal by androidx.compose.runtime.saveable.rememberSaveable(mangaId) { mutableStateOf<Int?>(null) }
    var showManagement by androidx.compose.runtime.saveable.rememberSaveable(mangaId) { mutableStateOf(false) }
    var showEditor by androidx.compose.runtime.saveable.rememberSaveable(mangaId) { mutableStateOf(false) }
    var showBookmarks by androidx.compose.runtime.saveable.rememberSaveable(mangaId) { mutableStateOf(false) }
    val bookmarks = if (mangaId != null) rememberLocalBookmarks(session, mangaId, showBookmarks) else null
    var showBatch by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showImportHelp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var rootMenu by remember { mutableStateOf(false) }
    var showFilters by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var readingFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ShelfFilter.ALL) }
    val visibleCards = remember(state.cards, state.query, state.searchField, state.favoritesOnly, readingFilter) {
        state.visibleCards.filter { readingFilter == ShelfFilter.ALL || it.shelfBook().status == readingFilter }
    }
    val hasFilters = state.favoritesOnly || state.searchField != LocalSearchField.ALL || readingFilter != ShelfFilter.ALL
    fun clearFilters() { model.favoritesOnly(false); model.searchField(LocalSearchField.ALL); readingFilter = ShelfFilter.ALL }
    // Deliver after onResume has resolved timeout/session replacement, to the current entry owner.
    LaunchedEffect(model, pendingCbz, lifecycle, state.importing, state.savingFavorite, state.removing) {
        if (pendingCbz.isNotEmpty() && lifecycle == Lifecycle.State.RESUMED && session.state.value.foreground) {
            if (model.import(pendingCbz)) consumeCbz(pendingCbz)
        }
    }
    var reversePages by androidx.compose.runtime.saveable.rememberSaveable(mangaId) { mutableStateOf(false) }
    val contentScroll = rememberLazyListState()
    val loadingScroll = rememberLazyListState()
    ReferenceRefreshColumn(refreshing = state.loading, refresh = { model.refresh() }, refreshEnabled = !busy,
        modifier = Modifier.fillMaxSize().testTag("local-list"), state = if (state.loading && state.cards.isEmpty()) loadingScroll else contentScroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mangaId != null) DetailHeading(back, backModifier = Modifier.testTag("local-back"),
                actions = { ReferenceActionsButton("书目管理", { showManagement = true }, Modifier.testTag("local-actions")) }) else SettingsHeading(if (searchMode) "搜索" else "本地书库",
                "",
                back, Modifier.testTag("local-title"), Modifier.testTag("local-back"), actions = {
                    Box {
                        ReferenceActionsButton("本地书库操作", { rootMenu = true })
                        DropdownMenu(rootMenu, { rootMenu = false }) {
                            if (searchMode) {
                                DropdownMenuItem(text = { Text("搜索在线书库") }, onClick = {
                                    rootMenu = false; keyboard?.hide(); focus.clearFocus(); openOnline()
                                })
                                DropdownMenuItem(text = { Text("图源浏览与搜索") }, onClick = {
                                    rootMenu = false; keyboard?.hide(); focus.clearFocus(); openSources()
                                })
                            } else DropdownMenuItem(text = { Text("导入说明") }, onClick = { rootMenu = false; showImportHelp = true })
                        }
                    }
                })
            if (mangaId == null) FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (!searchMode) ReferenceButton(onClick = pickCbz, enabled = identity.world == PrototypeWorld.SFW && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("local-import")) { Text("导入 CBZ", Modifier.weight(1f, fill = false)); Spacer(Modifier.width(8.dp)); SettingsChevron() }
                if (state.importing) TextButton(onClick = model::cancelImport, enabled = !state.cancellingImport,
                    modifier = Modifier.testTag("local-cancel")) { Text(if (state.cancellingImport) "正在停止…" else "停止导入") }
            }
            if (mangaId != null && state.importing) TextButton(onClick = model::cancelImport, enabled = !state.cancellingImport,
                modifier = Modifier.testTag("local-cancel")) { Text(if (state.cancellingImport) "正在停止…" else "停止导入") }
            state.message?.let { RequestFeedback(it, Modifier.testTag("local-message").semantics { liveRegion = LiveRegionMode.Polite }) }
            if (busy) CircularProgressIndicator(Modifier.testTag("local-loading"))
        } }
        if (mangaId != null && !readContentLayout(state.detail != null, state.loading,
                state.readError ?: state.message.takeIf { state.detail == null && !state.loading }, model::refresh, detail = true))
            return@ReferenceRefreshColumn
        if (mangaId == null && !state.loading && state.readError != null) {
            item("read-failure") { ReadFailure(state.readError!!, model::refresh) }
            return@ReferenceRefreshColumn
        }
        if (mangaId == null && state.loading && state.cards.isEmpty()) item("read-layout") { ReadLayoutPlaceholder() }
        if (mangaId == null) {
            if (!searchMode && state.batch.isNotEmpty()) {
                item(key = "batch-actions") {
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, verticalArrangement = Arrangement.Center) {
                        TextButton(onClick = { showBatch = !showBatch }, modifier = Modifier.semantics { stateDescription = if (showBatch) "已展开" else "已收起" }) {
                            Text(if (showBatch) "收起本批 ${state.batch.size} 项结果 ↑" else "查看本批 ${state.batch.size} 项结果 ›")
                        }
                        if (state.batch.any { it.retryable }) TextButton(onClick = feedbackAttempt(model::retryImport), enabled = !busy) {
                            Text("重试未完成项")
                        }
                    }
                }
                if (showBatch) items(state.batch, key = { "cbz-batch:${it.uri}" }) { item ->
                    ReferencePanel {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.status.label, color = if (item.status == CbzBatchStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        item.message?.let { RequestFeedback(it) }
                        item.key?.let { imported -> TextButton(onClick = { openBook(imported.mangaId) }, enabled = !busy) { Text("打开书目") } }
                    }
                }
            }
            item(key = "filters") {
                LibraryQueryField(state.query, model::updateQuery, "local-query", state.searchField.placeholder)
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, verticalArrangement = Arrangement.Center) {
                    Text(listOfNotNull("搜索：${state.searchField.label}", "仅收藏".takeIf { state.favoritesOnly },
                        readingFilter.takeIf { it != ShelfFilter.ALL }?.label
                    ).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showFilters = true }, modifier = Modifier.testTag("local-filters")) { Text("筛选") }
                    if (hasFilters) TextButton(onClick = ::clearFilters) { Text("清除筛选") }
                }
                Text(when {
                    state.loading && state.cards.isEmpty() -> "读取中…"
                    else -> "${visibleCards.size} 本 · 最近导入"
                },
                    Modifier.testTag("local-count").semantics { liveRegion = LiveRegionMode.Polite })

            }
            if (!state.loading && visibleCards.isEmpty()) item {
                Text(when {
                    state.cards.isEmpty() -> "还没有导入 CBZ"
                    state.query.isNotBlank() || hasFilters -> "没有符合当前查询与筛选的本地书目"
                    else -> "还没有导入 CBZ"
                }, Modifier.testTag("local-empty"))
                if (state.query.isNotBlank() || hasFilters) TextButton(onClick = { clearFilters(); model.updateQuery("") }) { Text("清除全部筛选") }
            }
            items(visibleCards, key = { it.key.mangaId }) { card ->
                ReferenceBookSummary(cover = { LocalCover(card, model, state.revision) },
                    onClick = { if (!busy) openBook(card.key.mangaId) }) {
                    TextButton(onClick = { openBook(card.key.mangaId) }, enabled = !busy, shape = androidx.compose.ui.graphics.RectangleShape,
                        modifier = Modifier.testTag("local-book-${card.title}"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    ShelfUpdateLabel(null, card.progress?.chapterId, online = false)
                    Text((card.pageCount?.let { "$it 页" } ?: "页数待获取") + if (card.available) "" else " · 副本缺失，请重新导入",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FavoriteAction(card, !busy) { model.setFavorite(card) }
                    card.progress?.let { progress ->
                        ReferenceButton(onClick = { read(card.key.mangaId) }, enabled = card.available && !busy,
                            modifier = Modifier.testTag("local-continue-${card.key.mangaId}")) { Text("继续阅读 · 第 ${progress.ordinal} 页") }
                    }
                }
            }

        } else state.detail?.let { detail ->
            item {
                DetailHero(detail.card.title, cover = {
                    Surface(Modifier.fillMaxWidth().aspectRatio(.7f).clip(RoundedCornerShape(8.dp)), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(contentAlignment = Alignment.Center) {
                            state.cover?.let { Image(it.asImageBitmap(), "《${detail.card.title}》封面", contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().testTag("local-cover")) } ?: Text(if (detail.card.available) "封面暂不可用" else "副本缺失")
                        }
                    }
                }, titleTag = "local-book-title") {
                    Text("本地 CBZ · ${detail.card.pageCount} 页", Modifier.testTag("local-book-summary"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    detail.metadata.author?.takeIf { it.isNotBlank() }?.let { Text(authorNames(listOf(it to it), searchAuthor, "作者：")) }
                    detail.metadata.volume?.takeIf { it.isNotBlank() }?.let { Text("卷号：$it") }
                    if (!detail.card.available) RequestFeedback(ImportFailure.MISSING.userMessage, Modifier.testTag("local-missing"))
                }
            }
            item {
                DetailActions(detail.card.favorite, state.savingFavorite, favoriteEnabled = !busy,
                    toggleFavorite = { model.setFavorite(detail.card) }, download = null,
                    read = { read(detail.card.key.mangaId) }, canRead = detail.card.available && !busy,
                    hasProgress = detail.card.progress != null, readModifier = Modifier.testTag("local-read"),
                    favoriteModifier = Modifier.testTag("local-favorite-${detail.card.key.mangaId}"),
                    description = detail.metadata.summary, descriptionIdentity = detail.card.key.mangaId,
                    readingPosition = detail.card.progress?.let { "第 ${it.ordinal} 页" })
                TextButton(onClick = { showBookmarks = true }, enabled = !busy,
                    modifier = Modifier.testTag("local-bookmarks")) { Text("书签与备注 ›") }
            }
            val orderedPages = if (reversePages) detail.chapter.pages.asReversed() else detail.chapter.pages
            item {
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, verticalArrangement = Arrangement.Center) {
                    Text("页目录 · ${orderedPages.size} 页", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { reversePages = !reversePages }) { Text(if (reversePages) "倒序 ↓" else "正序 ↑") }
                }
            }
            items(orderedPages, key = { it.id }) { page ->
                Column {
                    Row(Modifier.fillMaxWidth().testTag("local-page-${page.ordinal}")
                        .clickable(enabled = detail.card.available && !busy, role = Role.Button,
                            onClickLabel = "预览第 ${page.ordinal} 页") { previewOrdinal = page.ordinal }
                        .heightIn(min = 56.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${page.ordinal} 页", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("预览 ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

    }
    if (showFilters && mangaId == null) ReferenceSheet(onDismissRequest = { showFilters = false },
        title = { Text("搜索与筛选") }, confirmButton = { TextButton(onClick = { showFilters = false }) { Text("完成") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsDisclosure("搜索范围", state.searchField.label) { LocalSearchFieldOptions(state.searchField, model::searchField) }
            Text("阅读状态", style = MaterialTheme.typography.titleMedium)
            ShelfReadingFilters(readingFilter) { readingFilter = it }
            FilterChip(state.favoritesOnly, { model.favoritesOnly(!state.favoritesOnly) }, label = { Text("仅收藏") }, modifier = Modifier.testTag("local-favorites"))
            if (readingFilter == ShelfFilter.READ) Text("本地读到末页计为已读。", style = MaterialTheme.typography.bodySmall)
            Text("条件同时满足才显示；清除筛选保留搜索文字。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = ::clearFilters, enabled = hasFilters) { Text("清除筛选") }
        } })
    state.importPreview?.let { preview -> ComicInfoImportDialog(preview, { model.confirmImportMetadata(preview, it) }, model::cancelImport) }
    if (showEditor && mangaId != null) LocalBookEditor(session, mangaId,
        dismiss = { showEditor = false }, saved = { showEditor = false; model.refresh() })
    if (showBookmarks && bookmarks != null) LocalBookmarkDialog(bookmarks,
        canOpen = state.detail?.card?.available == true && !busy && readBookmark != null,
        dismiss = { showBookmarks = false }, open = { bookmark -> readBookmark?.invoke(bookmark.mangaId, bookmark.id) })
    if (showImportHelp) ReferenceDialog(onDismissRequest = { showImportHelp = false }, title = { Text("导入 CBZ") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支持多选，每批最多 $MAX_CBZ_BATCH 个文件。逐本校验后预览元数据，确认后才入库；单本失败不影响后续文件。")
            Text("保留原文件、已有收藏和阅读进度；相同内容不会重复添加。")
            Text("重试只处理失败、中断和未开始的项目。进程结束后需重新选择文件，不会自动恢复导入。")
        } }, confirmButton = { TextButton(onClick = { showImportHelp = false }) { Text("知道了") } })
    if (showManagement) state.detail?.let { detail ->
        ReferenceSheet(onDismissRequest = { showManagement = false }, title = { Text("书目管理") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CBZ · ${android.text.format.Formatter.formatFileSize(context, detail.archiveBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail.metadata.title?.takeIf { it.isNotBlank() && it != detail.card.title }?.let { Text("原书名：$it") }
                FilledTonalButton(onClick = { showManagement = false; showEditor = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("local-edit-info")) { Text("编辑书目信息") }
                OutlinedButton(onClick = { showManagement = false; confirmRemoval = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("local-remove"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移除此书目") }
                Text("移除会清理此书目的应用副本和记录，保留原文件。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { showManagement = false }) { Text("关闭") } })
    }
    state.detail?.chapter?.pages?.firstOrNull { it.ordinal == previewOrdinal }?.let { page ->
        LocalPagePreview(page, model) { previewOrdinal = null }
    }
    if (confirmRemoval) state.detail?.let { detail ->
        ReferenceDialog(onDismissRequest = { confirmRemoval = false },
            title = { Text("移除《${detail.card.title}》？") },
            text = { Text("将删除此书目的收藏、阅读进度、书签与备注、标签关联、应用内 CBZ 副本及封面缓存。保留你选择的原文件、用户标签和其他书目。", Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { ReferenceDialogConfirm("移除书目与副本", onClick = { confirmRemoval = false; model.remove(detail.card, back) },
                modifier = Modifier.testTag("local-remove-confirm"), destructive = true) },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }, modifier = Modifier.testTag("local-remove-cancel")) { Text("取消") } })
    }
}

/** On-demand single-page preview; uses the existing bounded decoder and never writes progress. */
@Composable
private fun LocalPagePreview(page: PageResource, model: LocalLibraryViewModel, dismiss: () -> Unit) {
    var attempt by remember(page.id) { mutableIntStateOf(0) }
    var failed by remember(page.id) { mutableStateOf(false) }
    var bitmap by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(page, attempt, active) {
        bitmap = null; failed = false
        if (active) try { bitmap = model.preview(page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { failed = true }
    }
    ReferenceDialog(onDismissRequest = dismiss, title = { Text("第 ${page.ordinal} 页预览") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 320.dp), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(), "第 ${page.ordinal} 页", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
                        ?: if (failed) {
                            RequestFeedback("第 ${page.ordinal} 页预览加载失败")
                            TextButton(onClick = feedbackAttempt { attempt++ }) { Text("重试预览") }
                        }
                        else CircularProgressIndicator()
                }
                Text("${page.entryName} · ${page.width}×${page.height}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭预览") } })
}

/** Cells borrow shared thumbnails; leaving composition releases only the visible reference. */
@Composable
internal fun LocalCover(card: MangaCard, model: LocalLibraryViewModel, revision: Int) {
    val cacheRevision by model.coverCache.revision.collectAsState()
    val cached = remember(model, card.key, revision, cacheRevision) { model.cachedCover(card) }
    var retry by remember(card.key, revision) { mutableIntStateOf(0) }
    var loading by remember(card.key, revision, retry, cacheRevision) { mutableStateOf(card.available && cached == null) }
    var failed by remember(card.key, revision, retry) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(cached, card.key, revision, retry, cacheRevision) {
        value = cached
        if (!card.available) { loading = false; return@produceState }
        if (value != null) { loading = false; return@produceState }
        try { value = model.loadCover(card) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { failed = true }
        finally { loading = false }
    }
    CoverArtwork(bitmap, loading, when {
        !card.available -> "副本缺失"; failed -> "封面加载失败"; loading -> "正在加载封面"; else -> "暂无封面"
    }, Modifier.fillMaxWidth().aspectRatio(.7f), retry = if (failed) ({ retry++ }) else null,
        description = "《${card.title}》封面", imageModifier = Modifier.testTag("cover-${card.key.mangaId}"),
        retryModifier = Modifier.testTag("cover-retry-${card.key.mangaId}"))
}

@Composable
private fun FavoriteAction(card: MangaCard, enabled: Boolean, click: () -> Unit) {
    TextButton(onClick = click, enabled = enabled, modifier = Modifier.testTag("local-favorite-${card.key.mangaId}")
        .semantics { selected = card.favorite; contentDescription = "${if (card.favorite) "取消收藏" else "收藏"}《${card.title}》" }) {
        Text(if (card.favorite) "取消收藏" else "收藏")
    }
}
