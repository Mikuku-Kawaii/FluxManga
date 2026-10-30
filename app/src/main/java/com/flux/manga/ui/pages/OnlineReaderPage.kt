package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

internal data class OnlineReaderState(
    val pages: List<SourcePageResource> = emptyList(), val index: Int = 0,
    val mode: ReadingMode = ReadingMode.LTR, val modeExplicit: Boolean = false, val loading: Boolean = false,
    val error: String? = null, val saveError: String? = null, val saved: Boolean = true,
    val fraction: Float = 0f, val jumpRevision: Int = 0,
    val chapters: List<SourceChapter> = emptyList(), val chapterNext: SourceContinuation = SourceContinuation.Next(SourcePageRequest.Start),
    val boundaryMessage: String? = null, val chapterBusy: Boolean = false, val chapterError: String? = null, val offlineIds: Set<String> = emptySet(),
    val nextBusy: Boolean = false, val nextError: String? = null, val atSeriesEnd: Boolean = false,
    val cacheNotice: String? = null,
)

internal data class OnlineReaderBitmapKey(val chapter: String, val page: String, val width: Int,
    val top: Int, val bottom: Int?)

internal class OnlineReaderModel(app: Application, val session: WorldSession, private val sourceId: String,
    mangaId: String, private val requestedChapter: String?, private val savedState: SavedStateHandle, val offlineOnly: Boolean = false,
    val syntheticPrivate: Boolean = false, syntheticNamespace: String = SyntheticPrivateReaderRepository.admissionNamespace
        ?: SyntheticPrivateReaderRepository.NAMESPACE,
    readerRepository: ReaderRepository? = null, private val data: WorldDataStore = WorldDataStore(app)) : AndroidViewModel(app) {
    private val baozi = isBaoziSource(sourceId)
    private val copy = isCopySource(sourceId)
    val privateContent = syntheticPrivate || isPrivateComicSource(sourceId)
    fun failure(error: Exception) = if (syntheticPrivate) "合成图片读取失败，可重试" else if (isEhSource(sourceId) || isExSource(sourceId)) ehFailure(error, GallerySite.forSource(sourceId)) else if (isPicaSource(sourceId)) picaFailure(error) else if (isJmSource(sourceId)) jmFailure(error) else if (baozi) baoziFailure(error) else if (copy) copyMangaFailure(error) else komgaFailure(error)
    private val cacheSession = savedState.get<String>("reader-cache-session") ?: java.util.UUID.randomUUID().toString()
        .also { savedState["reader-cache-session"] = it }
    private val previewFiles = ReaderEncodedCache(data, cacheSession)
    val repository: ReaderRepository = readerRepository ?: if (syntheticPrivate) SyntheticPrivateReaderRepository(app, session, syntheticNamespace)
        else if (offlineOnly && isPrivateComicSource(sourceId)) PrivateOfflineReader(app, session, sourceId, mangaId)
        else if (isJmSource(sourceId)) JmReaderRepository(app, session, sourceId, mangaId, cacheSession)
        else if (isPicaSource(sourceId)) PicaReaderRepository(app, session, sourceId, mangaId, cacheSession)
        else if (isEhSource(sourceId) || isExSource(sourceId)) EhReaderRepository(app, session, sourceId, mangaId, cacheSession)
        else OnlineReaderRepository(app, session, sourceId, mangaId, offlineOnly = offlineOnly, cacheSession = cacheSession)
    private val identity = session.identity.value
    private val mangaKey = repository.mangaKey
    private val mutable = MutableStateFlow(OnlineReaderState())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var loaded = false
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writeLock = Mutex()
    private var revision = 0L
    private data class ProgressWrite(val page: SourcePageResource, val mode: ReadingMode, val fraction: Float, val explicit: Boolean)
    private var pending: ProgressWrite? = null
    private val pendingChapters = linkedMapOf<String, Pair<Long, ProgressWrite>>()
    private var visibleLoaded: String? = null
    private val loadedTiles = mutableSetOf<String>()
    private var saveDelay: Job? = null
    private var chapterJob: Job? = null
    private var retryChapter: String? = null
    private var retryAtEnd = false
    private var retryResume = true
    private var retryDirection: Int? = null
    private var nextJob: Job? = null
    private var nextTicket = 0L
    private var nextAttempt: String? = null
    private var terminalChapter: String? = null
    private var endViewportKey: String? = null
    private var pendingEnd: SourcePageResource? = null
    private var savedEnd: String? = null
    private val bookInformation = OnlineBookInformationStore(data, session)
    fun endPageShown(page: SourcePageResource?, top: Int = 0) {
        endViewportKey = page?.let { "${it.chapterId}:${it.id}:$top" }
        recordBookEnd()
    }
    private fun recordBookEnd() {
        if (syntheticPrivate || !repository.isActive() || !session.state.value.foreground || state.value.loading) return
        val last = state.value.pages.lastOrNull() ?: return
        val key = endViewportKey ?: return
        if (last.chapterId != terminalChapter || !key.startsWith("${last.chapterId}:${last.id}:") || key !in loadedTiles) return
        if (savedEnd == pageIdentity(last) || pendingEnd == last) return
        pendingEnd = last
        mutable.value = state.value.copy(saved = false)
        flush()
    }
    private var continuousReading = true
    private fun pageIdentity(page: SourcePageResource) = "${page.chapterId}:${page.id}"
    private val bitmaps = DisplayBitmapStore<OnlineReaderBitmapKey>(reader = true)
    private val previews = DisplayBitmapStore<OnlineReaderBitmapKey>(reader = true, preview = true)
    fun cachedBitmap(key: OnlineReaderBitmapKey): Bitmap? =
        if (repository.isActive() && (!privateContent || session.state.value.foreground)) bitmaps.get(key) else null
    fun cachedPreview(key: OnlineReaderBitmapKey): Bitmap? =
        if (repository.isActive() && (!privateContent || session.state.value.foreground)) previews.get(key) else null
    private fun previewKey(key: OnlineReaderBitmapKey) = ResourceKey(
        SampleKey(identity.world, mangaKey.sourceId, mangaKey.mangaId),
        "preview:${key.chapter.length}:${key.chapter}${key.page.length}:${key.page}:${key.width}:${key.top}:${key.bottom}")
    suspend fun preview(key: OnlineReaderBitmapKey): Bitmap? {
        cachedPreview(key)?.let { return it }
        val epoch = session.state.value.visibilityEpoch
        val bytes = previewFiles.read(previewKey(key)) {
            repository.isActive() && session.state.value.foreground && session.state.value.visibilityEpoch == epoch
        } ?: return null
        return try { withContext(Dispatchers.Default) {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }?.also {
            currentCoroutineContext().ensureActive(); repository.active()
            if (!session.state.value.foreground || session.state.value.visibilityEpoch != epoch) throw CancellationException()
            previews.put(key, it)
        } } finally { bytes.fill(0) }
    }
    private val imageWork = mutableMapOf<OnlineReaderBitmapKey, Deferred<Bitmap>>()
    private val imagePriorities = mutableMapOf<OnlineReaderBitmapKey, ContentPriority>()
    suspend fun image(page: SourcePageResource, key: OnlineReaderBitmapKey): Bitmap {
        cachedBitmap(key)?.let { return it }
        val priority = currentCoroutineContext()[ContentPriorityContext] ?: ContentPriorityContext(ContentPriority.READER)
        if (priority.priority == ContentPriority.READER && imagePriorities[key] != ContentPriority.READER) {
            imageWork.remove(key)?.cancel()
        }
        val task = imageWork[key]?.takeUnless { it.isCancelled } ?: viewModelScope.async(priority, start = CoroutineStart.LAZY) {
            decodeImage(page, key)
        }.also { created ->
            imageWork[key] = created
            imagePriorities[key] = priority.priority
            created.invokeOnCompletion { if (imageWork[key] === created) { imageWork.remove(key); imagePriorities.remove(key) } }
        }
        task.start()
        try { return task.await() }
        catch (cancelled: CancellationException) {
            if (priority.priority != ContentPriority.READER && imageWork[key] === task) task.cancel()
            throw cancelled
        }
    }
    private suspend fun decodeImage(page: SourcePageResource, key: OnlineReaderBitmapKey): Bitmap {
        cachedBitmap(key)?.let { return it }
        val epoch = session.state.value.visibilityEpoch
        val bitmap = repository.image(page, key.width, key.top, key.bottom)
        try {
            val preview = if (key.bottom != null && cachedPreview(key) == null) withContext(Dispatchers.Default) {
                val width = minOf(160, bitmap.width)
                Bitmap.createScaledBitmap(bitmap, width, (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1), true)
            } else null
            currentCoroutineContext().ensureActive(); repository.active()
            if (privateContent && (!session.state.value.foreground || session.state.value.visibilityEpoch != epoch)) throw CancellationException()
            if (preview != null) {
                val bytes = withContext(Dispatchers.Default) {
                    java.io.ByteArrayOutputStream().use { output ->
                        check(preview.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output))
                        output.toByteArray()
                    }
                }
                try { previewFiles.write(previewKey(key), bytes) {
                    repository.isActive() && session.state.value.foreground && session.state.value.visibilityEpoch == epoch
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { mutable.value = state.value.copy(cacheNotice = "预览缓存未保存，当前图片仍可阅读；请检查剩余空间") }
                finally { bytes.fill(0) }
            }
            currentCoroutineContext().ensureActive(); repository.active()
            if (!session.state.value.foreground || session.state.value.visibilityEpoch != epoch) throw CancellationException()
            if (preview != null) previews.put(key, preview)
            bitmaps.put(key, bitmap)
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
    /** Cache only the chapter the reader has entered, without decoding full-size bitmaps. */
    suspend fun prefetchCurrentChapter() {
        if (offlineOnly) return
        val initial = state.value
        val chapter = initial.pages.getOrNull(initial.index)?.chapterId ?: return
        val remaining = initial.pages.filter { it.chapterId == chapter }.toMutableList()
        fun active() {
            repository.active()
            val current = state.value
            if (!session.state.value.foreground || current.loading ||
                current.pages.getOrNull(current.index)?.chapterId != chapter) throw CancellationException()
        }
        while (remaining.isNotEmpty()) {
            currentCoroutineContext().ensureActive(); active()
            // Reprioritize after a seek; once the later pages are cached, fill earlier gaps.
            val ordinal = state.value.pages[state.value.index].ordinal
            val page = remaining.firstOrNull { it.ordinal >= ordinal } ?: remaining.first()
            try {
                // Every repository fetches/caches the encoded image before reading its bounds.
                val size = withContext(ContentPriorityContext(ContentPriority.CHAPTER)) { repository.dimensions(page) }
                currentCoroutineContext().ensureActive(); active()
                dimensions(page, size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (full: ReaderStorageFullException) {
                mutable.value = state.value.copy(cacheNotice = full.message)
                return
            }
            catch (_: Exception) {
                // A speculative failure must not block other pages. The page's existing
                // foreground loader reports the actual error and offers image retry.
            }
            remaining.remove(page)
            yield()
        }
    }
    private fun stopPrefetch() {
        nextTicket++; nextJob?.cancel(); nextJob = null; nextAttempt = null
        mutable.value = state.value.copy(nextBusy = false)
    }
    /** Prepare only the next chapter, near its boundary. Never writes reading progress. */
    fun prefetchNext(continuous: Boolean, retry: Boolean = false) {
        if (continuousReading != continuous) { stopPrefetch(); continuousReading = continuous }
        if (!continuous) {
            val current = state.value
            val chapter = current.pages.getOrNull(current.index)?.chapterId ?: return
            val end = current.pages.indexOfLast { it.chapterId == chapter }
            if (end < current.pages.lastIndex) mutable.value = state.value.copy(pages = current.pages.take(end + 1), nextError = null,
                atSeriesEnd = terminalChapter == chapter)
        }
        val current = state.value
        if (current.loading || current.pages.isEmpty() || !repository.isActive() || !session.state.value.foreground) return
        val last = current.pages.last().chapterId
        if (current.pages[current.index].chapterId != last || current.pages.lastIndex - current.index > 3 ||
            nextJob?.isActive == true || current.chapterBusy) return
        if (terminalChapter == last) { mutable.value = current.copy(atSeriesEnd = true); recordBookEnd(); return }
        if (!retry && nextAttempt == last) return
        nextAttempt = last
        val ticket = ++nextTicket
        mutable.value = current.copy(nextBusy = true, nextError = null)
        nextJob = viewModelScope.launch {
            try {
                val caller = currentCoroutineContext()
                fun active() {
                    caller.ensureActive(); repository.active()
                    if (ticket != nextTicket || !session.state.value.foreground) throw CancellationException()
                }
                val directory = if (offlineOnly) ReaderDirectory(repository.completeOfflineCatalog(), SourceContinuation.End)
                    else ReaderDirectory(state.value.chapters, state.value.chapterNext)
                val target = adjacentReaderChapter(mangaKey, last, 1, directory, repository::chapters,
                    publish = { active(); mutable.value = state.value.copy(chapters = it.chapters, chapterNext = it.next) }, active = ::active)
                if (target == null) { terminalChapter = last; mutable.value = state.value.copy(atSeriesEnd = true); recordBookEnd(); return@launch }
                if (!continuous) return@launch
                if (offlineOnly && target.id !in repository.offlineIds()) throw IOException("下一话尚未下载，可从目录选择已下载章节")
                val pages = repository.pages(target.id)
                active()
                require(pages.isNotEmpty() && pages.all { it.manga == mangaKey && it.chapterId == target.id }) { "下一话正文为空或身份不匹配" }
                val warmed = pages.toMutableList()
                pages.take(3).forEachIndexed { index, page ->
                    val size = repository.dimensions(page); active()
                    warmed[index] = page.copy(width = size.first.toUInt(), height = size.second.toUInt())
                }
                active()
                // Append without replacing the current list/viewport or clearing its image cache.
                mutable.value = state.value.copy(pages = state.value.pages + warmed, nextError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == nextTicket && repository.isActive()) mutable.value = state.value.copy(nextError = readerError(error))
            } finally {
                if (ticket == nextTicket) mutable.value = state.value.copy(nextBusy = false)
            }
        }
    }
    fun retry() {
        val target = retryChapter
        if (target != null) chapter(target, retryAtEnd, retryResume)
        else retryDirection?.let(::adjacent) ?: load()
    }

    fun load() {
        if (state.value.loading || loaded) return
        mutable.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val progress = repository.progress()
                val chapter = savedState.get<String>("chapter") ?: requestedChapter ?: progress?.chapterId
                    ?: if (syntheticPrivate) "chapter-1" else throw IOException("尚无在线阅读进度，请返回详情选择章节")
                val pages = repository.pages(chapter)
                ensureActive(); repository.active()
                val chapterProgress = repository.chapterProgress(chapter)
                ensureActive(); repository.active()
                val anchor = if (chapterProgress != null) chapterReadingAnchor(pages, chapterProgress) else onlineReadingAnchor(pages, progress)
                val index = anchor.first
                val explicit = progress?.modeExplicit ?: false
                val default = if (syntheticPrivate || (explicit && decodeReadingMode(progress?.mode) != null)) ReadingMode.LTR else data.readingDefault(identity.world)
                repository.active()
                val mode = resolveReadingMode(progress?.mode, explicit, default)
                mutable.value = OnlineReaderState(pages, index, mode, modeExplicit = explicit, fraction = anchor.second, offlineIds = if(offlineOnly) repository.offlineIds() else emptySet())
                savedState["chapter"] = chapter
                loaded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = state.value.copy(loading = false,
                    error = if (error is IOException) error.message else failure(error))
            }
        }
    }
    fun cancel() {
        loadJob?.cancel()
        mutable.value = state.value.copy(loading = false, error = "加载已取消，可重试")
    }
    fun position(index: Int) {
        if (state.value.loading || index !in state.value.pages.indices) return
        if (!repository.isActive()) return
        val sameDisplayedPage = index == state.value.index && visibleLoaded == pageIdentity(state.value.pages[index])
        if (state.value.pages.getOrNull(state.value.index)?.chapterId != state.value.pages[index].chapterId) flush()
        mutable.value = state.value.copy(index = index, fraction = 0f, jumpRevision = state.value.jumpRevision + 1, boundaryMessage = null)
        if (sameDisplayedPage) record() else visibleLoaded = null
    }
    fun settledPage(index: Int, mode: ReadingMode) {
        if (state.value.mode == mode && index != state.value.index) position(index)
    }
    fun mode(mode: ReadingMode) {
        if (!repository.isActive()) return
        if (state.value.mode == mode && state.value.modeExplicit) return
        val changed = state.value.mode != mode
        // An explicit choice can update already displayed progress without moving its anchor.
        mutable.value = state.value.copy(mode = mode, modeExplicit = true)
        if (visibleLoaded == state.value.pages.getOrNull(state.value.index)?.let(::pageIdentity) && visibleLoaded != null) record()
        if (changed) {
            loadedTiles.clear()
            visibleLoaded = null
        }
    }
    // Do not move durable progress to an image that failed to load or was only prefetched.
    fun displayed(pageId: String, top: Int = 0, chapterId: String? = state.value.pages.getOrNull(state.value.index)?.chapterId) {
        if (privateContent && !session.state.value.foreground) return
        if (state.value.loading || !repository.isActive()) return
        val key = "$chapterId:$pageId"
        loadedTiles.add("$key:$top")
        if (state.value.pages.getOrNull(state.value.index)?.let(::pageIdentity) == key &&
            (state.value.mode != ReadingMode.STRIP || onlineTileTop(state.value.pages[state.value.index], state.value.fraction) == top)) {
            visibleLoaded = key
            record()
        }
        recordBookEnd()
    }
    fun unloaded(pageId: String, top: Int, chapterId: String? = state.value.pages.getOrNull(state.value.index)?.chapterId) {
        loadedTiles.remove("$chapterId:$pageId:$top")
    }
    fun stripPosition(index: Int, fraction: Float, top: Int) {
        if (privateContent && !session.state.value.foreground) return
        if (state.value.loading || !repository.isActive() || state.value.mode != ReadingMode.STRIP || index !in state.value.pages.indices) return
        if (state.value.pages[state.value.index].chapterId != state.value.pages[index].chapterId) flush()
        mutable.value = state.value.copy(index = index, fraction = fraction.coerceIn(0f, .9999f))
        val page = state.value.pages[index]
        if ("${pageIdentity(page)}:$top" in loadedTiles) { visibleLoaded = pageIdentity(page); record() } else visibleLoaded = null
    }
    fun dimensions(page: SourcePageResource, size: Pair<Int, Int>) {
        val index = state.value.pages.indexOfFirst { it.id == page.id && it.chapterId == page.chapterId }
        if (index < 0 || !repository.isActive()) return
        val current = state.value.pages[index]
        if (current.width == size.first.toUInt() && current.height == size.second.toUInt()) return
        mutable.value = state.value.copy(pages = state.value.pages.toMutableList().also {
            it[index] = current.copy(width = size.first.toUInt(), height = size.second.toUInt())
        })
    }
    private fun record() {
        if (!repository.isActive()) return
        val next = ProgressWrite(state.value.pages[state.value.index], state.value.mode,
            state.value.fraction, state.value.modeExplicit)
        savedState["chapter"] = next.page.chapterId
        if (pending == next) return
        pending = next; revision++
        pendingChapters.remove(next.page.chapterId)
        pendingChapters[next.page.chapterId] = revision to next
        mutable.value = state.value.copy(saved = false, saveError = null)
        saveDelay?.cancel()
        saveDelay = viewModelScope.launch { if (next.mode == ReadingMode.STRIP) delay(250); flush().join() }
    }
    fun flush(): Job {
        return writer.launch {
            writeLock.withLock {
                try {
                    // Keep the last unsaved anchor of each visited chapter, including after a write failure.
                    for ((chapter, entry) in pendingChapters.toMap()) {
                        val (version, snapshot) = entry
                        repository.save(snapshot.page, snapshot.mode, snapshot.fraction, snapshot.explicit)
                        if (pendingChapters[chapter]?.first == version) pendingChapters.remove(chapter)
                    }
                    pendingEnd?.let { end ->
                        bookInformation.markReadToEnd(sourceId, mangaKey.mangaId, end.chapterId)
                        savedEnd = pageIdentity(end)
                        if (pendingEnd == end) pendingEnd = null
                    }
                    mutable.value = state.value.copy(saved = pendingChapters.isEmpty() && pendingEnd == null, saveError = null)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.value = state.value.copy(saveError = "阅读进度未保存，请重试") }
            }
        }
    }
    fun moreChapters() {
        if (state.value.chapterBusy || state.value.nextBusy || state.value.loading || !repository.isActive()) return
        val request = (state.value.chapterNext as? SourceContinuation.Next)?.request ?: return
        mutable.value = state.value.copy(chapterBusy = true, chapterError = null)
        chapterJob = viewModelScope.launch {
            try {
                val result = repository.chapters(request)
                ensureActive(); repository.active()
                mutable.value = state.value.copy(chapters = (state.value.chapters + result.items).distinctBy { it.id },
                    chapterNext = result.continuation, chapterBusy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = state.value.copy(chapterBusy = false, chapterError = failure(error)) }
        }
    }
    fun advance(direction: Int, continuous: Boolean) {
        if (state.value.loading || state.value.pages.isEmpty()) return
        val next = state.value.index + direction
        if (next in state.value.pages.indices) position(next)
        else if (continuous) { if (direction > 0) prefetchNext(true, retry = true) else adjacent(direction) }
        else mutable.value = state.value.copy(boundaryMessage = "已到章节边界，可从目录选择或开启连读")
    }
    fun adjacent(direction: Int) {
        if (state.value.loading || state.value.pages.isEmpty() || !repository.isActive()) return
        chapterJob?.cancel()
        retryDirection = direction; retryChapter = null
        val current = state.value.pages[state.value.index].chapterId
        val currentStart = state.value.pages.indexOfFirst { it.chapterId == current }
        val currentEnd = state.value.pages.indexOfLast { it.chapterId == current }
        val buffered = if (direction > 0) currentEnd + 1 else currentStart - 1
        if (buffered in state.value.pages.indices) { retryDirection = null; position(buffered); return }
        stopPrefetch()
        mutable.value = state.value.copy(loading = true, chapterBusy = false, error = null, boundaryMessage = null)
        loadJob = viewModelScope.launch {
            try {
                val context = currentCoroutineContext()
                val directory = if (offlineOnly) ReaderDirectory(repository.completeOfflineCatalog(), SourceContinuation.End)
                    else ReaderDirectory(state.value.chapters, state.value.chapterNext)
                val target = adjacentReaderChapter(mangaKey, current, direction, directory, repository::chapters,
                    publish = { mutable.value = state.value.copy(chapters = it.chapters, chapterNext = it.next, chapterError = null) },
                    active = { context.ensureActive(); repository.active() })
                if (target == null) {
                    retryDirection = null
                    if (direction > 0) terminalChapter = current
                    mutable.value = state.value.copy(loading = false, atSeriesEnd = direction > 0 || state.value.atSeriesEnd,
                        boundaryMessage = if (direction > 0) null else "已到第一章")
                    recordBookEnd()
                } else {
                    if (offlineOnly && target.id !in repository.offlineIds()) throw IOException("相邻章节尚未下载。")
                    retryChapter = target.id; retryAtEnd = direction < 0; retryResume = false
                    changeChapter(target.id, retryAtEnd, resume = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = state.value.copy(loading = false, error = readerError(error)) }
        }
    }
    fun chapter(id: String, atEnd: Boolean = false, resume: Boolean = true) {
        if (state.value.loading || state.value.pages.getOrNull(state.value.index)?.chapterId == id || !repository.isActive()) return
        stopPrefetch()
        chapterJob?.cancel()
        mutable.value = state.value.copy(loading = true, chapterBusy = false, error = null, boundaryMessage = null)
        retryChapter = id; retryAtEnd = atEnd; retryResume = resume; retryDirection = null
        loadJob = viewModelScope.launch {
            try { changeChapter(id, atEnd, resume) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = state.value.copy(loading = false, error = readerError(error)) }
        }
    }
    private fun readerError(error: Exception) = if (error is IOException || error is IllegalArgumentException || error is IllegalStateException)
        error.message ?: "章节切换失败，请重试" else failure(error)
    private suspend fun changeChapter(id: String, atEnd: Boolean, resume: Boolean) {
        val savedChapter = if (resume) repository.chapterProgress(id) else null
        val context = currentCoroutineContext()
        val pages = prepareReaderChapter(mangaKey, id,
            save = { flush().join(); state.value.saveError == null }, fetch = { repository.pages(id) },
            release = repository::clearImages, active = { context.ensureActive(); repository.active() })
        if (pages == null) { mutable.value = state.value.copy(loading = false); return }
        loadedTiles.clear(); visibleLoaded = null
        savedState["chapter"] = id
        retryChapter = null; retryDirection = null
        val anchor = chapterEntryAnchor(pages, savedChapter, resume, atEnd, state.value.mode)
        mutable.value = state.value.copy(pages = pages, index = anchor.first,
            fraction = anchor.second, loading = false, error = null,
            boundaryMessage = null, nextError = null, atSeriesEnd = false, jumpRevision = state.value.jumpRevision + 1)
    }
    fun background() {
        imageWork.values.toList().forEach { it.cancel() }; imageWork.clear(); imagePriorities.clear()
        stopPrefetch()
        bitmaps.evictAll()
        previews.evictAll()
        if (privateContent) { loadJob?.cancel(); chapterJob?.cancel(); mutable.value = state.value.copy(loading = false, chapterBusy = false) }
        loadedTiles.clear(); visibleLoaded = null; flush(); writer.launch { repository.clearImages() }
    }
    fun leave() { loadJob?.cancel(); chapterJob?.cancel(); mutable.value = state.value.copy(loading = false, chapterBusy = false); background() }
    override fun onCleared() {
        bitmaps.evictAll()
        previews.evictAll()
        saveDelay?.cancel()
        flush().invokeOnCompletion { writer.launch { try { repository.releaseImages() } finally { try { previewFiles.close() } finally { repository.close(); writer.cancel() } } } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnlineReaderPage(session: WorldSession, sourceId: String, mangaId: String,
    chapterId: String?, back: () -> Unit, offlineOnly: Boolean = false, syntheticPrivate: Boolean = false,
    finished: () -> Unit = back) {
    val app = LocalContext.current.applicationContext as Application
    val model: OnlineReaderModel = viewModel(key = if (syntheticPrivate) "private-synthetic-reader" else null, factory = viewModelFactory {
        initializer { OnlineReaderModel(app, session, sourceId, mangaId, chapterId, createSavedStateHandle(), offlineOnly, syntheticPrivate) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val readings = if (syntheticPrivate) null else rememberChapterReadings(session, sourceId, mangaId)
    val readingState = readings?.state?.collectAsStateWithLifecycle()?.value ?: ChapterReadingState()
    val scope = rememberCoroutineScope()
    var sampleOptions by remember { mutableStateOf(ReaderOptions()) }
    val (options, changeOptions) = if (syntheticPrivate) sampleOptions to { next: ReaderOptions -> sampleOptions = next }
        else rememberReaderOptions(session.identity.value.world)
    var leaving by remember { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf<ReaderTool?>(null) }
    var chaptersOpen by rememberSaveable { mutableStateOf(false) }
    // Admission fixtures remain isolated and do not become product statistics.
    val statistics = if (syntheticPrivate) null else rememberReadingTime(session, "online", sourceId, mangaId,
        !state.loading && state.error == null && state.pages.isNotEmpty() && tool == null && !chaptersOpen && !leaving)
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch { model.flush().join(); if (model.state.value.saveError == null) back(); leaving = false }
        }
    }
    BackHandler(onBack = leave)
    val finish: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch { model.flush().join(); if (model.state.value.saveError == null) finished(); leaving = false }
        }
    }
    val owner = LocalLifecycleOwner.current
    val sessionState by session.state.collectAsState()
    LaunchedEffect(model, sessionState.foreground) {
        if (model.privateContent) { if (sessionState.foreground) model.load() else model.background() }
    }
    DisposableEffect(owner, model) {
        model.load()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            tool = null; chaptersOpen = false; model.background()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.leave() }
    }
    var tools by remember { mutableStateOf(false) }
    val chrome = state.pages.isNotEmpty() && (tools || state.error != null)
    ReaderImmersive(chrome || tool != null || chaptersOpen)
    LaunchedEffect(state.saveError, state.boundaryMessage, state.cacheNotice) {
        if (state.saveError != null || state.boundaryMessage != null || state.cacheNotice != null) tools = true
    }
    val currentChapter = state.pages.getOrNull(state.index)?.chapterId.orEmpty()
    val chapterStart = state.pages.indexOfFirst { it.chapterId == currentChapter }.coerceAtLeast(0)
    val chapterCount = state.pages.count { it.chapterId == currentChapter }
    LaunchedEffect(model, owner, currentChapter, state.loading, sessionState.foreground) {
        if (!state.loading && sessionState.foreground) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.prefetchCurrentChapter() }
        }
    }
    LaunchedEffect(state.index, state.pages.size, state.loading, state.chapterBusy, options.continuous, sessionState.foreground) {
        model.prefetchNext(options.continuous)
    }
    val openChapters: () -> Unit = {
        chaptersOpen = true; readings?.refresh(); if (state.chapters.isEmpty()) model.moreChapters()
    }
    CompositionLocalProvider(LocalReadingTime provides statistics,
        LocalReaderTap provides { tools = !tools }, LocalReaderChromeVisible provides chrome) {
        Box(Modifier.fillMaxSize().background(ReaderBackground).testTag(if (syntheticPrivate) "private-synthetic-reader" else if (isBaoziSource(sourceId)) "baozi-reader" else if (isCopySource(sourceId)) "copy-reader" else "komga-reader")) {
            if (state.pages.isEmpty()) ReaderLoadingScreen(state.error ?: state.saveError,
                state.loading || (state.error == null && state.saveError == null), leave, model::retry, model::cancel)
            if (state.pages.isNotEmpty()) ReaderCanvas(Modifier.fillMaxSize().background(WorldColors.DarkBackground)) {
                val width = with(LocalDensity.current) { maxWidth.roundToPx() }
                key(state.pages.first().chapterId, state.mode) {
                    if (state.mode == ReadingMode.STRIP) key(state.jumpRevision) {
                        OnlineStrip(model, state, width, options, !chrome && tool == null && !chaptersOpen && !leaving, finish)
                    } else OnlinePagedReader(model, state, width, options, !chrome && tool == null && !chaptersOpen && !leaving,
                        readerSpreadEnabled(options.spread, state.mode, maxWidth.value, maxHeight.value), finish)
                }
            }
            if (state.pages.isNotEmpty() && state.index >= state.pages.lastIndex - 1 && state.nextError != null) {
                Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.nextError!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { model.prefetchNext(options.continuous, retry = true) }) { Text(if (options.continuous) "重试下一话" else "重试目录") }
                    }
                }
            }
            if (chrome) Column(Modifier.align(Alignment.TopCenter).referenceToolEntrance(fromTop = true)) {
                ReaderTopBar(state.chapters.withChapterTitles().firstOrNull { it.id == currentChapter }?.title ?: if (offlineOnly) "离线阅读" else "在线阅读",
                    state.mode, !state.loading && !leaving && state.pages.isNotEmpty(), leaving, leave, model::mode,
                    { tool = it }, { tools = false }, chapters = openChapters)
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .verticalScroll(rememberScrollState())) {
                        ReadingTimeFeedback(statistics)
                        if (syntheticPrivate) Text("合成图片验证 · 独立测试进度 · 不连接图源", Modifier.padding(horizontal = 12.dp))
                        if (state.loading) {
                            RequestLoading()
                            TextButton(onClick = model::cancel) { Text("取消加载") }
                        }
                        state.boundaryMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                        state.error?.let { message -> RequestFeedback(message, Modifier.padding(16.dp)); TextButton(feedbackAttempt(model::retry)) { Text("重试") } }
                        if (readings != null && readingState.error != null) ChapterReadingStatus(readingState, readings::refresh)
                    }
                }
            }
            if (chrome && state.pages.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter).referenceToolEntrance()) {
                ReaderSeekBar(currentChapter, state.index - chapterStart, chapterCount, !state.loading && !leaving, { model.position(chapterStart + it) },
                    previous = { model.advance(-1, options.continuous) }, next = { model.advance(1, options.continuous) },
                    canPrevious = state.index > 0 || options.continuous, canNext = state.index < state.pages.lastIndex || options.continuous,
                    showPage = { tool = ReaderTool.PAGE }) {
                    state.cacheNotice?.let { RequestFeedback(it, persistent = true) }
                    if (!state.saved || state.saveError != null) ReaderSaveFeedback(state.saved, state.saveError, { model.flush() }, "online-reader-save", forceBack = back)
                }
            }
        }
        if (state.pages.isNotEmpty()) {
            if (chaptersOpen) ReaderChapterDialog(state, offlineOnly, readingState, !state.loading && !leaving,
                dismiss = { chaptersOpen = false }, select = { model.chapter(it) }, more = model::moreChapters,
                restart = { model.position(chapterStart) }, adjacent = model::adjacent, refreshReadings = { readings?.refresh() },
                mark = if (readings == null) null else { completed -> readings.mark(currentChapter, completed) })
            tool?.let { selectedTool ->
                ReaderToolDialog(selectedTool, currentChapter, state.index - chapterStart, chapterCount, state.mode, options,
                    !state.loading && !leaving, online = true, dismiss = { tool = null }, selectMode = model::mode,
                    jumpTo = { model.position(chapterStart + it) }, changeOptions = changeOptions,
                    optionsSummary = if (syntheticPrivate) "合成样本选项仅本次生效，不修改应用偏好。"
                        else "阅读操作按空间即时保存，保留作品的阅读方式与进度。")
            }
        }
    }
}

@Composable
private fun OnlinePagedReader(model: OnlineReaderModel, state: OnlineReaderState, width: Int,
    options: ReaderOptions, keysEnabled: Boolean, spread: Boolean, finished: () -> Unit) {
    val unit = if (spread) 2 else 1
    val groups = (state.pages.size + unit - 1) / unit
    key(spread) {
        val pager = rememberPagerState(initialPage = state.index / unit) { groups + if (state.atSeriesEnd) 2 else 0 }
        val onFinished by rememberUpdatedState(finished)
        LaunchedEffect(pager, groups, state.atSeriesEnd) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                if (state.atSeriesEnd && page == groups + 1) onFinished()
            }
        }
        val scope = rememberCoroutineScope()
        var zoomedPages by remember(pager.settledPage) { mutableStateOf(emptySet<Int>()) }
        LaunchedEffect(state.index, state.jumpRevision) {
            if (!pager.isScrollInProgress && pager.settledPage != state.index / unit) pager.scrollToPage(state.index / unit)
        }
        LaunchedEffect(pager, options.continuous) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { group ->
                if (group * unit < model.state.value.pages.size) model.settledPage(settledReaderAnchor(model.state.value.index, group, spread), state.mode)
            }
        }
        LaunchedEffect(pager, state.pages.lastOrNull()?.let { "${it.chapterId}:${it.id}" }, state.mode) {
            snapshotFlow { pager.settledPage.takeUnless { pager.isScrollInProgress } }.distinctUntilChanged().collect { group ->
                val pages = model.state.value.pages
                val last = pages.lastIndex
                val containsLast = group != null && group * unit <= last && group * unit + unit > last
                model.endPageShown(pages.lastOrNull().takeIf { containsLast })
            }
        }
        ReaderVolumeKeys(options.volume && keysEnabled && zoomedPages.isEmpty() && !state.loading, options.invertVolume) { direction ->
            val target = pager.settledPage + direction
            if (target in 0 until groups) scope.launch { pager.animateScrollToPage(target) }
            else if (target in groups..groups + 1 && state.atSeriesEnd) scope.launch { pager.animateScrollToPage(target) }
            else if (options.continuous) { if (direction > 0) model.prefetchNext(true, retry = true) else model.adjacent(direction) }
        }
        ReadingPager(pager, state.mode, "online-pager", { group -> if (group < groups) "${state.pages[group * unit].chapterId}:${state.pages[group * unit].id}" else "series-end-${group - groups}" },
            enabled = zoomedPages.isEmpty() && !state.loading) { group ->
            if (group >= groups) ReaderSeriesEnd(Modifier.fillMaxSize())
            else Row(Modifier.fillMaxSize()) {
                spreadIndices(group, state.pages.size, spread, state.mode == ReadingMode.RTL).forEach { index ->
                    OnlineReaderImage(model, state.pages[index], width / unit,
                        index == state.index && group == pager.settledPage && !pager.isScrollInProgress,
                        Modifier.weight(1f).fillMaxHeight(), doubleTap = options.doubleTap,
                        gestureActive = group == pager.settledPage && !pager.isScrollInProgress && !state.loading) {
                            zoomedPages = if (it) zoomedPages + index else zoomedPages - index
                        }
                }
            }
        }
    }
}

@Composable
private fun ReaderSeriesEnd(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 320.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text("已读到当前目录最后一话\n再翻一页返回详情", color = WorldColors.DarkInk, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun OnlineReaderImage(model: OnlineReaderModel, page: SourcePageResource, width: Int, selected: Boolean,
    modifier: Modifier = Modifier, top: Int = 0, bottom: Int? = null, doubleTap: Boolean = false,
    gestureActive: Boolean = selected, zoomChanged: (Boolean) -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val visibility by model.session.state.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
        (!model.privateContent || (visibility.foreground && model.repository.isActive()))
    val epoch = if (model.privateContent) visibility.visibilityEpoch else 0L
    val bitmapKey = remember(page.chapterId, page.id, width, top, bottom) {
        OnlineReaderBitmapKey(page.chapterId, page.id, width, top, bottom)
    }
    var bitmap by remember(bitmapKey) { mutableStateOf(model.cachedBitmap(bitmapKey)) }
    var preview by remember(bitmapKey) { mutableStateOf(model.cachedPreview(bitmapKey)) }
    var error by remember(bitmapKey) { mutableStateOf<String?>(null) }
    var attempt by remember(bitmapKey) { mutableIntStateOf(0) }
    var bitmapEpoch by remember(bitmapKey) { mutableLongStateOf(if (bitmap != null || preview != null) epoch else -1) }
    DisposableEffect(bitmapKey) {
        onDispose {
            // Published Bitmap pixels can still be in a render command. Drop UI ownership;
            // do not recycle memory underneath a queued GPU draw or promise zeroization.
            bitmap = null
            preview = null
            model.unloaded(page.id, top, page.chapterId)
        }
    }
    LaunchedEffect(bitmapKey, attempt, active, epoch) {
        model.unloaded(page.id, top, page.chapterId)
        bitmap = if (active) model.cachedBitmap(bitmapKey) else null
        preview = if (active) model.cachedPreview(bitmapKey) else null
        if (bitmap != null || preview != null) bitmapEpoch = epoch
        error = null
        if (active) try {
            if (bitmap == null && preview == null && bottom != null) {
                preview = try { model.preview(bitmapKey) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { null }
                if (preview != null) bitmapEpoch = epoch
            }
            val result = bitmap ?: model.image(page, bitmapKey)
            bitmapEpoch = epoch; bitmap = result
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = if (failure is IOException) failure.message else model.failure(failure) }
    }
    val changing by remember(model) { model.state.map { it.loading }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = remember(model) { model.state.value.loading })
    LaunchedEffect(bitmap, selected, gestureActive, changing, active) { if (active && !changing && bitmap != null && (selected || gestureActive || bottom != null)) model.displayed(page.id, top, page.chapterId) }
    ReadingImageVisibility(selected && active && !changing && bitmap != null && bitmapEpoch == epoch)
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        (bitmap ?: preview.takeIf { error == null })?.takeIf { active && bitmapEpoch == epoch }?.let { pixels ->
            @Composable fun Pixels() {
                Image(pixels.asImageBitmap(), "第 ${page.ordinal} 页${if (bottom != null) "，分段 ${top + 1} 至 $bottom" else ""}",
                    contentScale = if (bottom == null) ContentScale.Fit else ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            if (bottom == null) ReaderZoomBox("${page.chapterId}:${page.id}", doubleTap, Modifier.fillMaxSize(), gestureActive,
                pixels.width, pixels.height, changed = zoomChanged) { Pixels() } else Pixels()
        } ?: if (error == null) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RequestFeedback(error!!, color = Color.White, persistent = true)
            ReferenceButton(onClick = { attempt++ }) { Text("重试图片") }
        }
    }
}

@Composable
private fun OnlineStrip(model: OnlineReaderModel, state: OnlineReaderState, width: Int, options: ReaderOptions, keysEnabled: Boolean, finished: () -> Unit) {
    val tiles = remember(state.pages) { onlineTiles(state.pages) }
    val initial = remember(width) { state.index to state.fraction }
    val list = remember(width) { LazyListState(tiles.indexOfFirst { it.index == initial.first }.coerceAtLeast(0)) }
    var restored by remember(width) { mutableStateOf(false) }
    val target = state.pages[initial.first.coerceIn(state.pages.indices)]
    LaunchedEffect(width, target.width, target.height) {
        if (!restored && target.width != null && target.height != null) {
            val top = onlineTileTop(target, initial.second)
            val index = tiles.indexOfFirst { it.index == initial.first && it.top == top }.coerceAtLeast(0)
            val offset = ((initial.second * target.height!!.toInt() - top).coerceAtLeast(0f) * width / target.width!!.toInt()).toInt()
            list.scrollToItem(index, offset)
            restored = true
        }
    }
    val currentTiles by rememberUpdatedState(tiles)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visibility by model.session.state.collectAsState()
    val predecodeActive = lifecycle.isAtLeast(Lifecycle.State.STARTED) && visibility.foreground && !state.loading
    LaunchedEffect(list, width, predecodeActive, visibility.visibilityEpoch) {
        var previousFirst = list.firstVisibleItemIndex
        if (predecodeActive) snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo
            val firstKey = visible.firstOrNull()?.key
            val first = currentTiles.indexOfFirst { it.key == firstKey }
            val last = currentTiles.indexOfFirst { it.key == visible.lastOrNull()?.key }
            val chapter = currentTiles.firstOrNull { it.key == firstKey }?.page?.chapterId
            val before = if (first < 0) emptyList() else currentTiles.subList((first - 2).coerceAtLeast(0), first).asReversed()
            val after = if (last < 0) emptyList() else currentTiles.drop(last + 1).take(2)
            Triple(first, before.filter { it.bottom != null && it.page.chapterId == chapter },
                after.filter { it.bottom != null && it.page.chapterId == chapter })
        }.distinctUntilChanged().collectLatest { (first, before, after) ->
            val upcoming = if (first < previousFirst) before + after else after + before
            previousFirst = first
            // Warm both directions; reversing a scroll should not expose recycled lazy-cell loaders.
            for (tile in upcoming) {
                try {
                    withContext(ContentPriorityContext(ContentPriority.NEIGHBOR)) {
                        model.image(tile.page, OnlineReaderBitmapKey(tile.page.chapterId, tile.page.id, width, tile.top, tile.bottom))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The visible tile retains its own error/retry UI. */ }
            }
        }
    }
    LaunchedEffect(list, restored) {
        if (restored) snapshotFlow {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            // Preloading dimensions can insert tiles above the viewport before layout catches up.
            val tile = currentTiles.firstOrNull { it.key == item?.key }
            if (item == null || tile?.bottom == null) null else {
                val fraction = (tile.top + list.firstVisibleItemScrollOffset.toFloat() / item.size.coerceAtLeast(1) * (tile.bottom - tile.top)) / tile.page.height!!.toInt()
                Triple(tile.index, fraction.coerceIn(0f, .9999f), tile.top)
            }
        }.distinctUntilChanged().collect { it?.let { (index, fraction, top) -> model.stripPosition(index, fraction, top) } }
    }
    LaunchedEffect(list, restored, predecodeActive, tiles.lastOrNull()) {
        if (restored && predecodeActive) snapshotFlow {
            val last = currentTiles.lastOrNull()
            val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == last?.key }
            if (last?.bottom != null && visible != null &&
                visible.offset + visible.size <= list.layoutInfo.viewportEndOffset &&
                visible.offset + visible.size > list.layoutInfo.viewportStartOffset) last else null
        }.distinctUntilChanged().collect { tile -> model.endPageShown(tile?.page, tile?.top ?: 0) }
        else model.endPageShown(null)
    }
    val scope = rememberCoroutineScope()
    var keyScroll by remember { mutableStateOf<Job?>(null) }
    ReaderZoomBox(state.pages.first().chapterId, options.doubleTap, Modifier.fillMaxSize(), active = !state.loading) { zoomed ->
    ReaderVolumeKeys(options.volume && keysEnabled && !zoomed && !state.loading, options.invertVolume) { direction ->
        if (keyScroll?.isActive != true) keyScroll = scope.launch {
            if ((direction > 0 && !list.canScrollForward) || (direction < 0 && !list.canScrollBackward)) {
                if (direction > 0 && state.atSeriesEnd) finished()
                else if (options.continuous) { if (direction > 0) model.prefetchNext(true, retry = true) else model.adjacent(direction) }
            } else list.scrollBy(direction * list.layoutInfo.viewportSize.height * .85f)
        }
    }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val boundary = remember(state.pages.first().chapterId, threshold) { ReaderBoundaryDrag(threshold) }
    val mayContinue by rememberUpdatedState(options.continuous && !zoomed && !state.loading && state.error == null && restored)
    val mayFinish by rememberUpdatedState(state.atSeriesEnd && !zoomed && !state.loading && state.error == null && restored && keysEnabled)
    val onFinished by rememberUpdatedState(finished)
    val connection = remember(list, boundary) { object : NestedScrollConnection {
        private var gestureStarted = false
        private var finishGesture = false
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && !gestureStarted) {
                gestureStarted = true
                // Reaching the end card in this drag must not also dismiss it.
                finishGesture = mayFinish && !list.canScrollForward
            }
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && boundary.drag(available.y,
                    !list.canScrollForward && (if (mayFinish) finishGesture else mayContinue))) {
                if (mayFinish) onFinished() else model.prefetchNext(true, retry = true)
            }
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            boundary.reset(); gestureStarted = false; finishGesture = false
            return Velocity.Zero
        }
    } }
    LazyColumn(state = list, userScrollEnabled = !zoomed && !state.loading,
        modifier = Modifier.fillMaxSize().nestedScroll(connection).testTag("online-strip")) {
        items(tiles, key = { it.key }) { tile ->
            if (tile.bottom == null) OnlineDimensions(model, tile.page)
            else OnlineReaderImage(model, tile.page, width, list.layoutInfo.visibleItemsInfo.any { it.key == tile.key },
                Modifier.fillMaxWidth().aspectRatio(tile.page.width!!.toFloat() / (tile.bottom - tile.top)), tile.top, tile.bottom)
        }
        if (state.atSeriesEnd) item("series-end") { ReaderSeriesEnd() }
    }
    }
}

@Composable
private fun OnlineDimensions(model: OnlineReaderModel, page: SourcePageResource) {
    var error by remember(page.id) { mutableStateOf<String?>(null) }
    var attempt by remember(page.id) { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visibility by model.session.state.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
        (!model.privateContent || (visibility.foreground && model.repository.isActive()))
    val epoch = if (model.privateContent) visibility.visibilityEpoch else 0L
    LaunchedEffect(page, attempt, active, epoch) {
        error = null
        if (active) try { model.dimensions(page, model.repository.dimensions(page)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = if (failure is IOException) failure.message else model.failure(failure) }
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (error == null) { CircularProgressIndicator(); Text("正在读取第 ${page.ordinal} 页尺寸", color = Color.White) }
        else { RequestFeedback(error!!, color = Color.White, persistent = true); ReferenceButton(onClick = { attempt++ }) { Text("重试图片") } }
    }
}
