package com.flux.manga.ui.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.DiscoverySection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DiscoveryFeedState(
    val cards: List<MangaCard> = emptyList(),
    val next: SourceContinuation = SourceContinuation.End,
    val busy: Boolean = false, val attempted: Boolean = false, val loaded: Boolean = false,
    val unsupported: Boolean = false, val error: String? = null, val appendError: String? = null,
    val notice: String? = null,
)

/** Entry-owned state, with an injected read boundary for host tests. No book/history writes. */
internal class DiscoveryFeedController(
    private val scope: CoroutineScope,
    private val accepts: () -> Boolean,
    private val failureMessage: (Exception) -> String = ::komgaFailure,
    private val restore: suspend () -> DiscoveryFeedState? = { null },
    private val save: suspend (DiscoveryFeedState) -> Unit = {},
    private val refreshRestored: Boolean = false,
    private val load: suspend (SourcePageRequest) -> SourceCardPage,
) {
    private val mutable = MutableStateFlow(DiscoveryFeedState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var revision = 0L

    fun start() { if (!state.value.attempted) fetch(false, useSaved = true) }
    fun restart() { leave(); mutable.value = DiscoveryFeedState(); refresh() }
    fun refresh() { fetch(false) }
    fun more() { fetch(true) }
    private fun fetch(append: Boolean, useSaved: Boolean = false) {
        if (!accepts() || state.value.busy || state.value.unsupported) return
        val request = if (append) (state.value.next as? SourceContinuation.Next)?.request ?: return else SourcePageRequest.Start
        val ticket = ++revision
        mutable.value = state.value.copy(busy = true, attempted = true, error = null, appendError = null, notice = null)
        work = scope.launch {
            try {
                if (useSaved) restore()?.let { saved ->
                    ensureActive()
                    if (!accepts() || ticket != revision) return@launch
                    mutable.value = saved.copy(busy = refreshRestored)
                    if (!refreshRestored) return@launch
                }
                val result = load(request)
                ensureActive()
                if (!accepts() || ticket != revision) return@launch
                mutable.value = state.value.copy(
                    cards = (if (append) state.value.cards + result.cards else result.cards).distinctBy { it.key },
                    next = result.continuation, loaded = true)
                try { save(state.value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (accepts() && ticket == revision) mutable.value = state.value.copy(notice = "内容已加载，但本地保存失败；下拉刷新可重试")
                }
                ensureActive()
                if (accepts() && ticket == revision) mutable.value = state.value.copy(busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                if (!accepts() || ticket != revision) return@launch
                mutable.value = when {
                    error is SourceException.Unsupported -> state.value.copy(busy = false, unsupported = true)
                    append -> state.value.copy(busy = false, appendError = failureMessage(error))
                    else -> state.value.copy(busy = false, error = failureMessage(error))
                }
            }
        }
    }
    fun cancel() { stop(explicit = true) }
    fun leave() { stop(explicit = false) }
    private fun stop(explicit: Boolean) {
        ++revision
        work?.cancel(); work = null
        val old = state.value
        mutable.value = old.copy(busy = false,
            attempted = if (!explicit && old.busy && !old.loaded) false else old.attempted,
            notice = if (explicit && old.busy) "请求已取消，已有内容保留" else old.notice)
    }
}

internal class DiscoveryFeedModel(app: Application, session: WorldSession, sourceId: String,
    section: DiscoverySection) : AndroidViewModel(app) {
    private val blacklist = TagBlacklistFilter(app, session)
    private val identity = session.identity.value
    private val isCopy = sourceId == CopyMangaEndpoint.SOURCE_ID
    private val access = if (isCopy) null else KomgaAccess(app, session, sourceId)
    val library = OnlineLibrary(app, session)
    val feed = DiscoveryFeedController(viewModelScope,
        { identity.world == com.flux.manga.ui.navigation.PrototypeWorld.SFW && session.accepts(identity) },
        if (sourceId == BaoziEndpoint.SOURCE_ID) ::baoziFailure else if (isCopy) ::copyMangaFailure else ::komgaFailure) { page ->
        val result = if (sourceId == BaoziEndpoint.SOURCE_ID) {
            withContext(Dispatchers.IO) { session.baoziSources.client(app).source.let {
                if (section == DiscoverySection.RECOMMENDATIONS) it.recommendations(page)
                else it.search("", page, updates = true)
            } }
        } else if (isCopy) {
            val client = withContext(Dispatchers.IO) { session.copyMangaSources.client() }
            if (section == DiscoverySection.RECOMMENDATIONS) client.recommendations(page) else client.updates(page)
        } else {
            if (section == DiscoverySection.RECOMMENDATIONS) throw SourceException.Unsupported(SourceOperation.RECOMMENDATIONS)
            check(section == DiscoverySection.UPDATES)
            requireNotNull(access).client().updates(page)
        }
        blacklist.apply(result)
    }
}
