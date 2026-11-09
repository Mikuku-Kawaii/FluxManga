package com.flux.manga.ui.pages

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.flux.manga.data.HOME_SEARCH_HISTORY
import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.session.WorldSession

@Composable
internal fun OnlineSearchInput(session: WorldSession, back: () -> Unit, sources: () -> Unit, results: (String) -> Unit) {
    val history = preloadedPages(session).searchHistory()
    var draft by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val feedback = LocalRequestFeedback.current
    val submit: (String) -> Unit = { value ->
        val term = value.trim().take(200)
        if (term.isNotEmpty() && lifecycle == Lifecycle.State.RESUMED) {
            feedback?.beginAttempt()
            draft = term
            keyboard?.hide()
            history.submit(HOME_SEARCH_HISTORY, term)
            results(term)
        }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("搜索漫画", "", back) }
        item {
            ReferenceSubmitSearchField(draft, { draft = it.take(200) }, "搜索书名或作者", { submit(draft) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("aggregate-query"),
                submitEnabled = draft.isNotBlank(), actionModifier = Modifier.testTag("aggregate-submit"))
            LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) focus.requestFocus() }
        }
        item { SearchHistoryPanel(history, HOME_SEARCH_HISTORY, submit, sources) }
    }
}

@Composable
internal fun AggregateSearchPage(session: WorldSession, configuration: DiscoveryState,
    back: () -> Unit, sources: () -> Unit, retryConfiguration: () -> Unit, open: (String, String) -> Unit,
    initialQuery: String, recordAuthorSearch: Boolean) {
    val app = LocalContext.current.applicationContext as Application
    val komgaId = DiscoverySource.KOMGA.id(configuration)
    val pages = preloadedPages(session)
    val model = viewModel<AggregateSearchModel>(key = "aggregate-search:${session.identity.value}:$komgaId:${pages.revision.value}:$initialQuery", factory = viewModelFactory {
        initializer { AggregateSearchModel(app, session, komgaId) }
    })
    val state by model.search.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(model, pages, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            pages.visibleSearches.add(model)
            model.search.resume()
        }
        onDispose {
            pages.visibleSearches.remove(model)
            model.search.pause()
        }
    }
    val ready = configuration.status != DiscoveryStatus.LOADING && lifecycle == Lifecycle.State.RESUMED && model.search.sources.isNotEmpty()
    val scroll = rememberLazyListState()
    val feeds = remember(model) { model.search.sources.map { ResultFeed(it, it) } }
    var showSources by rememberSaveable { mutableStateOf(false) }
    val cards = mixedCards(feeds.map { it.id }, state.results)
    LaunchedEffect(model, ready, initialQuery) {
        if (ready && model.search.state.value.query.isEmpty()) {
            val submitted = model.search.submit(initialQuery)
            if (submitted && recordAuthorSearch) pages.searchHistory().submit(model.historyScope, initialQuery)
        }
    }
    if (showSources) MixedSourceStatusDialog(model.search, state, feeds, sources, { showSources = false })
    BookResultsLayout { columns ->
        ReferenceRefreshColumn(refreshing = state.busy, refresh = { feeds.filter { state.results[it.id]?.unsupported != true }.forEach { model.search.refresh(it.id) } }, refreshEnabled = ready && state.query.isNotEmpty(), modifier = Modifier.fillMaxSize().imePadding().testTag("aggregate-search"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { SettingsHeading("搜索结果", initialQuery, back, actions = {
                ResultActionsMenu(sources = sources, sourceStatus = if (state.query.isNotEmpty()) ({ showSources = true }) else null,
                    help = "搜索当前空间的全部图源，结果混合展示。分页与失败重试按图源独立处理。")
            }) }
            item("query") {
                if (configuration.status == DiscoveryStatus.LOADING) Text("正在读取图源配置…")
                else if (model.search.sources.isEmpty()) {
                    Text("当前没有启用可用图源")
                    TextButton(onClick = sources) { Text("前往图源管理") }
                }
                if (configuration.status == DiscoveryStatus.FAILED) {
                    Text("部分图源配置未能读取，其他结果仍可使用。")
                    TextButton(onClick = feedbackAttempt(retryConfiguration)) { Text("重试读取配置") }
                }
            }
            if (state.query.isNotEmpty()) item("results-heading") {
                MixedResultCount(cards.size)
            }
            if (state.query.isNotEmpty() && (cards.isEmpty() || hasMixedFeedback(state))) item("status") {
                if (cards.isNotEmpty()) CompactFeedFeedback(model.search, state, feeds, sources)
                else {
                    MixedRequestControls(model.search, state, feeds)
                    MixedResultEmpty(feeds, state, cards.size, false, {}, "没有匹配作品")
                }
            }
            bookGridItems(cards, columns, { "${it.key.sourceId}:${it.key.mangaId}" }) { card -> DiscoveryGridBook(model.library, card, open) }
            item("more") { MixedPagination(model.search, state, scroll, feeds) }
        }
    }
}
