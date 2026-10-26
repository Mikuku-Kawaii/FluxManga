package com.flux.manga.ui.pages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.data.SampleLoadException

import com.flux.manga.data.SampleScenario
import com.flux.manga.data.SampleManga
import com.flux.manga.data.SampleChapter
import com.flux.manga.session.SessionIdentity
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.MangaReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PageState<out T> {
    data object Loading : PageState<Nothing>
    data object Empty : PageState<Nothing>
    data class Error(val message: String) : PageState<Nothing>
    data class Content<T>(val value: T) : PageState<T>
}

/** Entry-scoped requests; the UI only observes snapshots and sends actions. */
abstract class SamplePageViewModel<T>(
    protected val savedState: SavedStateHandle,
    protected val session: WorldSession,
) : ViewModel() {
    protected val repository = session.repository
    private val mutableState = MutableStateFlow<PageState<T>>(PageState.Loading)
    val state = mutableState.asStateFlow()
    val scenario = savedState.getStateFlow("scenario", SampleScenario.CONTENT.name)
    private var job: Job? = null
    private var request = 0L

    protected abstract suspend fun fetch(identity: SessionIdentity, scenario: SampleScenario): T?

    fun selectScenario(scenario: SampleScenario) {
        savedState["scenario"] = scenario.name
        reload()
    }

    // The error fixture fails one attempt; retry returns to the regular local data path.
    fun retry() = selectScenario(SampleScenario.CONTENT)

    protected fun reload() {
        val revision = ++request
        job?.cancel()
        val identity = session.identity.value
        val selected = SampleScenario.valueOf(scenario.value)
        mutableState.value = PageState.Loading
        job = viewModelScope.launch {
            try {
                val result = fetch(identity, selected)
                ensureActive()
                if (revision == request && session.accepts(identity)) {
                    mutableState.value = result?.let { PageState.Content(it) } ?: PageState.Empty
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SampleLoadException) {
                if (revision == request && session.accepts(identity)) {
                    mutableState.value = PageState.Error(failure.message.orEmpty())
                }
            }
        }
    }
}

class RootPageViewModel(savedState: SavedStateHandle, session: WorldSession,
                        private val includeHistory: Boolean = true) : SamplePageViewModel<List<SampleManga>>(savedState, session) {
    val query = savedState.getStateFlow("query", "")
    private val mutableHistory = MutableStateFlow<List<String>>(emptyList())
    val history = mutableHistory.asStateFlow()
    private val mutableHistoryError = MutableStateFlow<String?>(null)
    val historyError = mutableHistoryError.asStateFlow()
    private val mutableHistoryBusy = MutableStateFlow(false)
    val historyBusy = mutableHistoryBusy.asStateFlow()
    // Each tab owns a separate entry model. Library/discover never display search history.
    init { reload(); if (includeHistory) historyAction() }
    fun rememberQuery() = historyAction(query.value)
    fun clearHistory() = historyAction(clear = true)
    fun retryHistory() = historyAction()
    private fun historyAction(queryToSave: String? = null, clear: Boolean = false) {
        if (mutableHistoryBusy.value) return
        val identity = session.identity.value
        mutableHistoryBusy.value = true
        mutableHistoryError.value = null
        viewModelScope.launch {
            try {
                if (clear) repository.clearHistory(identity) { session.accepts(identity) }
                else if (queryToSave != null) repository.remember(identity, queryToSave) { session.accepts(identity) }
                val result = repository.history(identity)
                ensureActive()
                if (session.accepts(identity)) mutableHistory.value = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SampleLoadException) {
                if (session.accepts(identity)) mutableHistoryError.value = "搜索历史暂不可用，请重试"
            } finally { mutableHistoryBusy.value = false }
        }
    }
    fun updateQuery(value: String) {
        savedState["query"] = value
        reload()
    }
    override suspend fun fetch(identity: SessionIdentity, scenario: SampleScenario) = repository.list(identity, query.value, scenario)
}

class ReaderViewModel(savedState: SavedStateHandle, session: WorldSession, private val route: MangaReader) : SamplePageViewModel<SampleChapter>(savedState, session) {
    val page = savedState.getStateFlow("page", 1)
    init { reload() }
    override suspend fun fetch(identity: SessionIdentity, scenario: SampleScenario) = repository.chapter(identity, route.world, route.sourceId, route.mangaId, route.chapterId, scenario)
    fun move(delta: Int) {
        val chapter = (state.value as? PageState.Content)?.value ?: return
        savedState["page"] = (page.value + delta).coerceIn(1, chapter.pages)
    }
}

