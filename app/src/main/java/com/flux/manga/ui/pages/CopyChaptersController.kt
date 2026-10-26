package com.flux.manga.ui.pages

import com.flux.manga.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CopyChaptersState(val groupId: String? = null, val items: List<SourceChapter> = emptyList(),
    val next: SourceContinuation = SourceContinuation.End, val busy: Boolean = false,
    val attempted: Boolean = false, val loaded: Boolean = false, val error: String? = null,
    val appendError: String? = null, val notice: String? = null, val total: Long? = null)

/** One selected group in one detail entry. A late previous-group page cannot publish. */
internal class CopyChaptersController(private val scope: CoroutineScope, private val accepts: () -> Boolean,
    private val restore: suspend (String) -> CopyChaptersState? = { null },
    private val save: suspend (CopyChaptersState) -> Unit = {},
    private val load: suspend (String, SourcePageRequest) -> SourceChapterPage) {
    private val mutable = MutableStateFlow(CopyChaptersState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var revision = 0L
    fun select(id: String?) {
        if (id != state.value.groupId) { leave(); mutable.value = CopyChaptersState(groupId = id) }
        start()
    }
    fun start() { if (!state.value.attempted) refresh() }
    fun refresh() { fetch(false) }
    fun more() { fetch(true) }
    private fun fetch(append: Boolean) {
        val old = state.value
        val group = old.groupId ?: return
        if (!accepts() || old.busy) return
        val page = if (append) (old.next as? SourceContinuation.Next)?.request ?: return else SourcePageRequest.Start
        val ticket = ++revision
        mutable.value = old.copy(busy = true, attempted = true, error = null, appendError = null, notice = null)
        work = scope.launch {
            try {
                if (!append && !old.loaded) restore(group)?.let { cached ->
                    ensureActive(); if (!accepts() || ticket != revision) return@launch
                    mutable.value = cached.copy(busy = true, attempted = true, loaded = true)
                }
                val result = load(group, page)
                ensureActive(); if (!accepts() || ticket != revision) return@launch
                mutable.value = state.value.copy(busy = false, loaded = true,
                    items = (if (append) state.value.items + result.items else result.items).distinctBy { it.id }, next = result.continuation,
                    total = result.total?.toLong())
                save(state.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive(); if (!accepts() || ticket != revision) return@launch
                mutable.value = if (append) state.value.copy(busy = false, appendError = copyMangaFailure(error))
                    else state.value.copy(busy = false, error = copyMangaFailure(error))
            }
        }
    }
    fun cancel() { stop(true) }
    fun leave() { stop(false) }
    private fun stop(explicit: Boolean) {
        revision++; work?.cancel(); work = null
        val old = state.value
        mutable.value = old.copy(busy = false,
            attempted = if (!explicit && old.busy && !old.loaded) false else old.attempted,
            notice = if (explicit && old.busy) "章节请求已取消，已有列表保留" else old.notice)
    }
}
