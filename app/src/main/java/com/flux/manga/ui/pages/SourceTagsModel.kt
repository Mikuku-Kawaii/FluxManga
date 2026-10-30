package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SourceTagsState(
    val query: String = "", val tags: List<String> = emptyList(),
    val next: SourceContinuation = SourceContinuation.End,
    val busy: Boolean = false, val attempted: Boolean = false, val loaded: Boolean = false,
    val error: String? = null, val appendError: String? = null, val notice: String? = null,
)

/** Query submission replaces the directory, while refresh/append failures retain that query's rows. */
internal class SourceTagsController(private val scope: CoroutineScope, private val accepts: () -> Boolean,
    initialQuery: String = "", private val failureMessage: (Exception) -> String = ::komgaFailure,
    private val restore: suspend (String) -> SourceTagsState? = { null },
    private val save: suspend (SourceTagsState) -> Unit = {},
    private val load: suspend (String, SourcePageRequest) -> SourceTagList) {
    private val mutable = MutableStateFlow(SourceTagsState(query = initialQuery))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    fun start() { if (!state.value.attempted) fetch(false, useSaved = true) }
    fun search(query: String) {
        if (!accepts()) return
        leave()
        mutable.value = SourceTagsState(query = query)
        refresh()
    }
    fun refresh() { fetch(false) }
    fun more() { fetch(true) }
    private fun fetch(append: Boolean, useSaved: Boolean = false) {
        if (!accepts() || state.value.busy) return
        val page = if (append) (state.value.next as? SourceContinuation.Next)?.request ?: return else SourcePageRequest.Start
        val query = state.value.query
        val ticket = ++revision
        mutable.value = state.value.copy(busy = true, attempted = true, error = null, appendError = null, notice = null)
        job = scope.launch {
            try {
                if (useSaved) restore(query)?.let { saved ->
                    ensureActive()
                    if (accepts() && ticket == revision) mutable.value = saved
                    return@launch
                }
                val result = load(query, page)
                ensureActive()
                if (!accepts() || ticket != revision) return@launch
                mutable.value = state.value.copy(tags = (if (append) state.value.tags + result.tags else result.tags).distinct(),
                    next = result.continuation, loaded = true)
                try { save(state.value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (accepts() && ticket == revision) mutable.value = state.value.copy(notice = "标签已加载，但本地保存失败；下拉刷新可重试")
                }
                ensureActive()
                if (accepts() && ticket == revision) mutable.value = state.value.copy(busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                if (!accepts() || ticket != revision) return@launch
                val message = if (error is SourceException.NotFound) "标签接口不可用，请在图源管理中检查连接" else failureMessage(error)
                mutable.value = if (append) state.value.copy(busy = false, appendError = message)
                    else state.value.copy(busy = false, error = message)
            }
        }
    }
    fun cancel() { stop(true) }
    fun leave() { stop(false) }
    private fun stop(explicit: Boolean) {
        ++revision; job?.cancel(); job = null
        val old = state.value
        mutable.value = old.copy(busy = false,
            attempted = if (!explicit && old.busy && !old.loaded) false else old.attempted,
            notice = if (explicit && old.busy) "请求已取消，已有标签保留" else old.notice)
    }
}

internal class SourceTagsModel(app: Application, session: WorldSession, sourceId: String,
    private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val access = KomgaAccess(app, session, sourceId)
    var query by mutableStateOf(saved.get<String>("tag-query").orEmpty()); private set
    val directory = SourceTagsController(viewModelScope, access::isActive, saved.get<String>("tag-submitted").orEmpty()) { query, page ->
        if (sourceId == BaoziEndpoint.SOURCE_ID) SourceTagList(BaoziCategories.tags.keys.filter { it.contains(query, true) }, SourceContinuation.End)
        else access.client().tags(query, page)
    }
    fun query(value: String) { query = value.take(200); saved["tag-query"] = query }
    fun search() { val value = query.trim(); saved["tag-submitted"] = value; directory.search(value) }
}

internal class SourceTagBooksModel(app: Application, session: WorldSession, sourceId: String, tag: String) : AndroidViewModel(app) {
    private val blacklist = TagBlacklistFilter(app, session)
    private val access = KomgaAccess(app, session, sourceId)
    val library = OnlineLibrary(app, session)
    private val snapshot = CatalogPageCache(app, session, "tag-books:$sourceId", source = sourceId)
    val feed = DiscoveryFeedController(viewModelScope, access::isActive,
        if (sourceId == BaoziEndpoint.SOURCE_ID) ::baoziFailure else if (sourceId == CopyMangaEndpoint.SOURCE_ID) ::copyMangaFailure else ::komgaFailure,
        restore = { snapshot.feed(tag) }, save = { snapshot.saveFeed(tag, it) }) { page ->
        val result = if (sourceId == BaoziEndpoint.SOURCE_ID) withContext(Dispatchers.IO) {
            val type = BaoziCategories.tags[tag] ?: throw SourceException.InvalidRequest()
            session.baoziSources.client(app).source.search("", page, tag = type)
        } else if (sourceId == CopyMangaEndpoint.SOURCE_ID) withContext(Dispatchers.IO) { session.copyMangaSources.client().themed(tag, page) } else access.client().tagged(tag, page)
        blacklist.apply(result)
    }
}
