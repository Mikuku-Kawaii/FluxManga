package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class VisitsState(val items: List<VisitItem> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val more: Boolean = false, val error: String? = null)
internal class VisitsModel(app: Application, val session: WorldSession, private val pageSize: Int) : AndroidViewModel(app) {
    val repository = BrowsingHistory(app, session)
    private val identity = session.identity.value
    private val mutable = MutableStateFlow(VisitsState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    fun refresh() = load(false)
    fun refresh(silent: Boolean) = load(false, silent)
    fun more() { if (state.value.more) load(true) }
    private fun load(append: Boolean, silent: Boolean = false) {
        if (work?.isActive == true || !session.accepts(identity)) return
        mutable.value = state.value.copy(busy = !silent || state.value.loading, error = null)
        work = viewModelScope.launch {
            try {
                val batch = repository.page(if (append) pageSize else maxOf(pageSize, state.value.items.size),
                    if (append) state.value.items.lastOrNull()?.visit else null)
                ensureActive()
                if (session.accepts(identity)) mutable.value = VisitsState(
                    (if (append) state.value.items + batch.items else batch.items).distinctBy { it.visit.stableKey },
                    loading = false, more = batch.more)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session.accepts(identity)) mutable.value = state.value.copy(loading = false, busy = false,
                error = if (append) "更多浏览记录读取失败，请重试" else "浏览记录读取失败，已有内容保留") }
        }
    }
    fun remove(row: BrowseVisit?) {
        if (state.value.busy || !session.accepts(identity)) return
        work?.cancel() // A silent read must not publish old rows after an explicit removal.
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                repository.remove(row)
                val batch = repository.page(maxOf(pageSize, state.value.items.size))
                if (session.accepts(identity)) mutable.value = VisitsState(batch.items, loading = false, more = batch.more)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session.accepts(identity)) mutable.value = state.value.copy(busy = false,
                error = "记录删除或刷新未完成，可刷新核对；作品和进度保持") }
        }
    }
    fun leave() { work?.cancel(); work = null; mutable.value = state.value.copy(busy = false) }
}

internal class VisitRecorderModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val repository = BrowsingHistory(app, session)
    private val identity = session.identity.value
    private var recorded = false
    private var work: Job? = null
    private val mutable = MutableStateFlow(false)
    val failed = mutable.asStateFlow()
    fun record(kind: String, source: String, manga: String) {
        if (recorded || work?.isActive == true || !session.accepts(identity)) return
        work = viewModelScope.launch {
            try { repository.record(kind, source, manga); recorded = true; mutable.value = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session.accepts(identity)) mutable.value = true }
        }
    }
}

internal data class SearchHistoryState(val rows: List<SearchHistory> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null)
internal class SearchHistoryModel(app: Application, private val session: WorldSession,
    private val saved: SavedStateHandle) : AndroidViewModel(app) {
    var query by mutableStateOf(saved.get<String>("online-query").orEmpty()); private set
    fun query(value: String) { query = value.take(200); saved["online-query"] = query }
    private val repository = BrowsingHistory(app, session)
    private val identity = session.identity.value
    private val lock = Mutex()
    private val mutable = MutableStateFlow(SearchHistoryState())
    val state = mutable.asStateFlow()
    init { refresh() }
    private fun run(operation: suspend () -> Unit) {
        viewModelScope.launch {
            lock.withLock {
                if (!session.accepts(identity)) return@withLock
                mutable.value = state.value.copy(busy = true, error = null)
                try {
                    operation()
                    val rows = repository.searches()
                    if (session.accepts(identity)) mutable.value = SearchHistoryState(rows, loading = false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (session.accepts(identity)) mutable.value = state.value.copy(loading = false, busy = false,
                    error = "搜索记录操作未完成，请刷新核对；搜索输入保留") }
            }
        }
    }
    fun refresh() = run { }
    fun submit(source: String, query: String) { if (query.isNotBlank()) run { repository.submit(source, query) } }
    fun remove(row: SearchHistory?) = run { repository.removeSearch(row) }
}
