package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.session.WorldSession

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AggregateSearchPage(session: WorldSession, configuration: DiscoveryState, history: SearchHistoryModel,
    back: () -> Unit, sources: () -> Unit, retryConfiguration: () -> Unit, open: (String, String) -> Unit, initialQuery: String = "") {
    val app = LocalContext.current.applicationContext as Application
    val komgaId = DiscoverySource.KOMGA.id(configuration)
    val selection = com.flux.manga.data.SourceSelection(app, session.identity.value.world).fingerprint()
    val model = if (initialQuery.isBlank()) preloadedPages(session).search(komgaId)
        else viewModel<AggregateSearchModel>(key = "aggregate-search:$komgaId:$selection:$initialQuery", factory = viewModelFactory {
            initializer { AggregateSearchModel(app, session, komgaId) }
        })
    val state by model.search.state.collectAsStateWithLifecycle()
    val historyState by history.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.search.resume() }
    DisposableEffect(model, lifecycle) {
        if (initialQuery.isNotBlank() && lifecycle != Lifecycle.State.RESUMED) model.search.leave()
        onDispose { if (initialQuery.isNotBlank()) model.search.leave() }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = configuration.status != DiscoveryStatus.LOADING && lifecycle == Lifecycle.State.RESUMED && model.search.sources.isNotEmpty()
    val scroll = rememberLazyListState()
    val feeds = remember(model) { model.search.sources.map { ResultFeed(it, it) } }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var lastQuery by rememberSaveable { mutableStateOf(state.query) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.query) { if (state.query != lastQuery) scroll.scrollToItem(0); lastQuery = state.query }
    val requestFeedback = LocalRequestFeedback.current
    val submit: (String) -> Unit = { query ->
        requestFeedback?.beginAttempt()
        if (ready && model.search.submit(query)) { history.query(query); history.submit(model.historyScope, query); keyboard?.hide() }
    }
    val cards = mixedCards(feeds.map { it.id }, state.results)
    var started by remember(model, initialQuery) { mutableStateOf(state.query.isNotBlank()) }
    LaunchedEffect(ready, initialQuery) {
        if (ready && initialQuery.isNotBlank() && !started) { started = true; submit(initialQuery) }
    }
    if (showSources) MixedSourceStatusDialog(model.search, state, feeds, sources, { showSources = false })
    BookResultsLayout { columns ->
        ReferenceRefreshColumn(refreshing = state.busy, refresh = { feeds.filter { state.results[it.id]?.unsupported != true }.forEach { model.search.refresh(it.id) } }, refreshEnabled = ready && state.query.isNotEmpty(), modifier = Modifier.fillMaxSize().imePadding().testTag("aggregate-search"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { SettingsHeading("搜索漫画", "", back, actions = {
                ResultActionsMenu(sources = sources, sourceStatus = if (state.query.isNotEmpty()) ({ showSources = true }) else null,
                    help = "搜索当前空间的全部图源，结果混合展示。分页与失败重试按图源独立处理。")
            }) }
            item("query") {
                if (model.search.sources.isEmpty()) {
                    Text("当前没有启用可用图源")
                    TextButton(onClick = sources) { Text("前往图源管理") }
                }
                ReferenceSubmitSearchField(history.query, history::query, "搜索书名或作者", { submit(history.query) },
                    modifier = Modifier.fillMaxWidth().testTag("aggregate-query"),
                    submitEnabled = ready && history.query.isNotBlank() && !(state.busy && history.query.trim() == state.query),
                    actionModifier = Modifier.testTag("aggregate-submit"))
                if (configuration.status == DiscoveryStatus.FAILED) {
                    Text("部分图源配置未能读取，其他结果仍可使用。")
                    TextButton(onClick = feedbackAttempt(retryConfiguration)) { Text("重试读取配置") }
                }
            }
            if (state.query.isNotEmpty()) item("results-heading") {
                ReferenceActionRow(text = { Text("搜索结果", it, style = MaterialTheme.typography.titleMedium) }, actions = {
                    TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "收起记录" else "搜索记录") }
                })
                MixedResultCount(cards.size)
            }
            if (state.query.isNotEmpty() && (cards.isEmpty() || hasMixedFeedback(state))) item("status") {
                if (cards.isNotEmpty()) CompactFeedFeedback(model.search, state, feeds, sources)
                else {
                    MixedRequestControls(model.search, state, feeds)
                    MixedResultEmpty(feeds, state, cards.size, false, {}, "没有匹配作品")
                }
            }
            if (state.query.isEmpty() || showHistory || historyState.error != null) item("history") { SearchHistoryPanel(history, model.historyScope, submit, sources) }
            bookGridItems(cards, columns, { "${it.key.sourceId}:${it.key.mangaId}" }) { card -> DiscoveryGridBook(model.library, card, open) }
            item("more") { MixedPagination(model.search, state, scroll, feeds) }
        }
    }
}
