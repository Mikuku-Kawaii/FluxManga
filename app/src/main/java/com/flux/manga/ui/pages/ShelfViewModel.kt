package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ShelfState(val books: List<ShelfBook> = emptyList(), val loading: Boolean = true,
    val showLoading: Boolean = true,
    val errors: List<String> = emptyList(), val saving: Set<String> = emptySet(),
    val layout: ShelfLayout = ShelfLayout.GRID, val sort: ShelfSort = ShelfSort.RECENT,
    val batchBusy: Boolean = false, val batchProcessed: Int = 0, val batchTotal: Int = 0,
    val batchCompleted: Set<String> = emptySet(), val batchMessage: String? = null)

internal enum class ShelfBatchAction { FAVORITE, UNFAVORITE, REMOVE_LOCAL }

internal class ShelfViewModel(app: Application, val session: WorldSession,
                              private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val local = CbzLibrary(WorldDataStore(app))
    private val online = OnlineLibrary(app, session)
    private val data = WorldDataStore(app)
    private val information = OnlineBookInformationStore(data, session)
    private val informationLoader = OnlineBookInformationLoader(app, session)
    private val jm = JmStore(app, session)
    private val pica = PicaStore(app, session)
    private val ex = EhStore(app, session, GallerySite.EX)
    private val eh = EhStore(app, session)
    private val mutable = MutableStateFlow(ShelfState())
    val state = mutable.asStateFlow()
    private val preferences = app.getSharedPreferences("shelf-display", Application.MODE_PRIVATE)
    private var work: Job? = null
    private var batchWork: Job? = null
    private var localRows = emptyList<ShelfBook>()
    private var onlineRows = emptyList<ShelfBook>()
    private var downloadRows = emptyList<ChapterDownload>()
    private fun active() = session.accepts(identity)
    init {
        refresh(silent = true)
        if(identity.world==PrototypeWorld.SFW) viewModelScope.launch {
            session.downloads(app).state.collect { queue ->
                if(queue.loaded && active()) {
                    downloadRows=queue.jobs
                    mutable.value=state.value.copy(books=withShelfDownloads(localRows+onlineRows,downloadRows))
                }
            }
        }
    }
    private fun readPresentation() {
        // Preferences contain only presentation enums; never titles, identities or queries.
        mutable.value = state.value.copy(
            layout = ShelfLayout.entries.firstOrNull { it.name == preferences.getString(shelfPreferenceKey(identity.world, "layout"), null) } ?: ShelfLayout.GRID,
            sort = ShelfSort.entries.firstOrNull { it.name == preferences.getString(shelfPreferenceKey(identity.world, "sort"), null) } ?: ShelfSort.RECENT)
    }
    fun layout(value: ShelfLayout) {
        if (!active()) return
        mutable.value = state.value.copy(layout = value)
        preferences.edit { putString(shelfPreferenceKey(identity.world, "layout"), value.name) }
    }
    fun sort(value: ShelfSort) {
        if (!active()) return
        mutable.value = state.value.copy(sort = value)
        preferences.edit { putString(shelfPreferenceKey(identity.world, "sort"), value.name) }
    }
    val filter = saved.getStateFlow("shelf-filter", ShelfFilter.ALL.name)
    fun filter(value: ShelfFilter) { if (active()) saved["shelf-filter"] = value.name }
    fun refresh() = refresh(silent = false)
    fun refresh(silent: Boolean) {
        if (work?.isActive == true || state.value.batchBusy || !active()) return
        // A child list may have changed the same world's preference while this entry was stopped.
        readPresentation()
        work = viewModelScope.launch {
            mutable.value = state.value.copy(loading = true, showLoading = !silent || state.value.loading, errors = emptyList())
            if (identity.world != PrototypeWorld.SFW) {
                try {
                    val errors = mutableListOf<String>()
                    val settings = try { jm.settings() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { errors += "JMComic 账号读取失败，可重试"; null }
                    val picaAccount = try { pica.account() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { errors += "PicACG 账号读取失败，可重试"; null }
                    val ehSettings = try { eh.settings() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { errors += "E-Hentai 账号读取失败，可重试"; null }
                    val exSettings = try { ex.settings() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { errors += "ExHentai 账号读取失败，可重试"; null }
                    val account = settings?.account
                    val rows = data.worldData(identity.world, ::active) { dao ->
                        val progress = dao.onlineProgressRows().associateBy { it.sourceId to it.mangaId }
                        dao.onlineBooks().filter { isPrivateComicSource(it.sourceId) }.map { book ->
                            val available = if (isExSource(book.sourceId)) exSettings?.account != null && book.sourceId == GallerySite.EX.source(exSettings.account)
                                else if (isPicaSource(book.sourceId)) book.sourceId == picaAccount?.source
                                else if (isEhSource(book.sourceId)) ehSettings != null && (book.sourceId == EH_SOURCE || book.sourceId == ehSettings.account?.source)
                                else settings != null && (book.sourceId == JM_SOURCE || book.sourceId == account?.source)
                            OnlineShelfItem(book, progress[book.sourceId to book.mangaId], available)
                                .shelfBook().copy(key = MangaKey(identity.world, book.sourceId, book.mangaId))
                        }
                    }
                    val info = information.readAll(rows.mapNotNull { it.remote?.book })
                    val enriched = rows.map { row -> row.copy(remote = row.remote?.copy(information = info[row.key.sourceId to row.key.mangaId]),
                        title = info[row.key.sourceId to row.key.mangaId]?.title?.takeIf(String::isNotBlank) ?: row.title) }
                    if (active()) {
                        onlineRows = enriched; mutable.value = state.value.copy(books = enriched, loading = false, errors = errors)
                        if (!silent) enriched.forEach { prepareInformation(it, refreshMetadata = true) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (active()) mutable.value = state.value.copy(loading = false, errors = listOf("私有书库读取失败，请重试")) }
                return@launch
            }
            val errors = mutableListOf<String>()
            try { localRows = local.cards(identity.world).map { it.shelfBook() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { errors += "本地书库读取失败；保留已显示内容，请重试" }
            try { onlineRows = online.shelf().map { it.shelfBook() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { errors += "在线书库读取失败；保留已显示内容，请重试" }
            try { downloadRows=DownloadStore(WorldDataStore(getApplication()).accountImportDatabase()).list { active() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { errors += "下载状态读取失败；保留已显示状态，请重试" }
            if (active()) mutable.value = state.value.copy(books = withShelfDownloads(localRows + onlineRows,downloadRows), loading = false, errors = errors)
            if (active() && !silent) onlineRows.forEach { prepareInformation(it, refreshMetadata = true) }
        }
    }
    fun favorite(book: ShelfBook) {
        if (!active() || state.value.loading || state.value.batchBusy || state.value.saving.isNotEmpty()) return
        val current = state.value.books.firstOrNull { it.stableKey == book.stableKey } ?: return
        mutable.value = state.value.copy(saving = state.value.saving + current.stableKey)
        viewModelScope.launch {
            try {
                if (identity.world == PrototypeWorld.NSFW) data.worldData(identity.world, ::active) { dao ->
                    val row = requireNotNull(dao.onlineBook(current.key.sourceId, current.key.mangaId))
                    dao.putOnlineBook(row.copy(favorite = !current.favorite))
                }
                else if (current.online) online.favorite(requireNotNull(current.remote).book, !current.favorite)
                else local.setFavorite(current.key, !current.favorite) { active() }
                if (active()) {
                    // Refresh after a known successful write, retaining other sources on read failure.
                    refresh(silent = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (active()) mutable.value = state.value.copy(errors = listOf("收藏保存结果待确认，请刷新后重试"))
            } finally {
                if (active()) mutable.value = state.value.copy(saving = state.value.saving - current.stableKey)
            }
        }
    }
    fun batch(keys: Set<String>, action: ShelfBatchAction) {
        if (!active() || work?.isActive == true || state.value.loading || state.value.batchBusy || state.value.saving.isNotEmpty()) return
        val rows = state.value.books.filter { it.stableKey in keys }
        if (rows.isEmpty() || rows.size != keys.size) return
        if (action == ShelfBatchAction.REMOVE_LOCAL && rows.any { it.online || it.key.world != PrototypeWorld.SFW }) return
        mutable.value = state.value.copy(batchBusy = true, batchTotal = rows.size, batchProcessed = 0,
            batchCompleted = emptySet(), batchMessage = null)
        batchWork = viewModelScope.launch {
            val completed = mutableSetOf<String>()
            var message: String? = null
            try {
                for (book in rows) {
                    ensureActive()
                    if (!active()) throw CancellationException()
                    if (action == ShelfBatchAction.REMOVE_LOCAL) {
                        local.remove(book.key, ::active)
                        session.pageCovers().remove(book.key.sourceId, book.key.mangaId)
                        localRows = localRows.filterNot { it.stableKey == book.stableKey }
                    } else {
                        val favorite = action == ShelfBatchAction.FAVORITE
                        data.worldData(identity.world, ::active) { dao ->
                            if (book.online) {
                                val current = requireNotNull(dao.onlineBook(book.key.sourceId, book.key.mangaId))
                                dao.putOnlineBook(current.copy(favorite = favorite))
                            } else check(dao.setFavorite(book.key.sourceId, book.key.mangaId, favorite) == 1)
                        }
                        fun ShelfBook.updated() = if (stableKey == book.stableKey) copy(favorite = favorite) else this
                        localRows = localRows.map { it.updated() }; onlineRows = onlineRows.map { it.updated() }
                    }
                    completed += book.stableKey
                    if (active()) mutable.value = state.value.copy(
                        books = withShelfDownloads(localRows + onlineRows, downloadRows), batchProcessed = completed.size)
                    yield()
                }
                message = "已处理 ${completed.size} 部书目"
            } catch (cancelled: CancellationException) {
                message = "已停止，已完成 ${completed.size}/${rows.size} 部；已提交的操作保留。"
                throw cancelled
            } catch (_: Exception) {
                message = "已完成 ${completed.size}/${rows.size} 部；其余操作未确认，请刷新核对后重试。"
            } finally {
                if (active()) {
                    mutable.value = state.value.copy(batchBusy = false, batchCompleted = completed.toSet(), batchMessage = message)
                    refresh(silent = true)
                }
            }
        }
    }
    fun cancelBatch() { batchWork?.cancel() }
    private val informationWork = mutableMapOf<String, Job>()

    // Metadata belongs to the page model, not a recycled lazy-list cell. Covers render independently.
    fun prepareInformation(book: ShelfBook, refreshMetadata: Boolean = false) {
        if (!active() || !book.online || !book.available || (!refreshMetadata && book.information?.let { it.detailsReady && it.coverReady && it.chaptersKnown } == true) ||
            informationWork[book.stableKey]?.isActive == true) return
        informationWork[book.stableKey] = viewModelScope.launch {
            try {
                val result = informationLoader.load(book.key.sourceId, book.key.mangaId, refreshMetadata = refreshMetadata)
                if (active()) {
                    fun ShelfBook.enriched() = if (stableKey != book.stableKey) this else copy(
                        title = result.info.title.takeIf(String::isNotBlank) ?: title,
                        remote = remote?.copy(information = result.info))
                    onlineRows = onlineRows.map { it.enriched() }
                    mutable.value = state.value.copy(books = state.value.books.map { it.enriched() })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep stored metadata; the cover has its own error/retry state. */ }
            finally { informationWork.remove(book.stableKey) }
        }
    }

    fun cachedCover(book: ShelfBook): Bitmap? =
        if (active()) session.pageCovers().peek(book.key.sourceId, book.key.mangaId) else null

    suspend fun cover(book: ShelfBook): Bitmap? {
        if (!active()) return null
        prepareInformation(book)
        if (book.online) {
            // Stored covers remain readable when their original source is temporarily unavailable.
            if (!book.available) return session.pageCovers().getOrNull(book.key.sourceId, book.key.mangaId) {
                information.cover(book.key.sourceId, book.key.mangaId)
            }
            return online.cover(book.key.sourceId, book.key.mangaId)
        }
        if (!book.available) return null
        return session.pageCovers().getOrNull(book.key.sourceId, book.key.mangaId) { local.cover(book.key) }
    }
    override fun onCleared() { data.clearMemory() }
}
