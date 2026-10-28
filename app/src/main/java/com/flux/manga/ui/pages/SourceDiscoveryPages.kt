package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.flux.manga.data.BrowseVisit
import com.flux.manga.ui.navigation.DiscoverySection
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld

@Composable
internal fun discoveryModel(session: WorldSession): SourceDiscoveryModel {
    val model = preloadedPages(session).discovery()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.start() }
    return model
}

/** Home and tag discovery deliberately do not instantiate ShelfViewModel or TagsViewModel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceDiscoveryPage(session: WorldSession, tags: Boolean, bottomClearance: Dp,
    search: () -> Unit, sources: () -> Unit, settings: () -> Unit,
    switchError: String?, section: (DiscoverySection) -> Unit, visit: (BrowseVisit) -> Unit, openBook: (String, String) -> Unit, openTag: (String, String) -> Unit, openTags: (String, List<com.flux.manga.ui.navigation.TagTarget>) -> Unit, reportScroll: (LazyListState) -> Unit) {
    val model = discoveryModel(session)
    val state by model.state.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    if (tags && identity.world == PrototypeWorld.SFW) {
        UnifiedTagsPage(session, state, bottomClearance, settings, openTags, reportScroll, switchError, model::refresh)
        return
    }
    if (tags) {
        PrivateTagsPage(session, bottomClearance, settings, sources, search, reportScroll, openTags)
        return
    }
    DiscoveryHomeShowcase(session, model, state, bottomClearance, search, sources, settings, switchError,
        section, openBook, reportScroll)
}

@Composable
private fun DiscoveryCapabilityPanel(title: String, feature: String, state: DiscoveryState, tag: String,
    open: (() -> Unit)? = null, sources: () -> Unit = {}, retry: () -> Unit = {}) {
        ReferencePanel(Modifier.testTag(tag)) {
        if (open != null) TextButton(onClick = open, modifier = Modifier.testTag("open-$tag").semantics { heading() }) {
            Text("$title ›", style = MaterialTheme.typography.titleLarge)
        } else Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(when (state.status) {
            DiscoveryStatus.LOADING -> "正在确认图源…"
            DiscoveryStatus.UNCONFIGURED -> "请先配置图源，再查看可用的$feature"
            DiscoveryStatus.READY -> "当前 Komga 适配尚未接入$feature"
            DiscoveryStatus.FAILED -> "图源状态尚未确认，请重试读取配置"
            DiscoveryStatus.PRIVATE -> "当前空间尚未开放$feature"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (open != null && state.status == DiscoveryStatus.UNCONFIGURED) TextButton(onClick = sources) { Text("配置图源") }
        if (open != null && state.status == DiscoveryStatus.FAILED) TextButton(onClick = feedbackAttempt(retry)) { Text("重试读取配置") }
    }
}

@Composable
private fun DiscoveryHomeFeed(session: WorldSession, state: DiscoveryState, section: DiscoverySection, tag: String,
    all: () -> Unit, open: (String, String) -> Unit, sources: () -> Unit, retry: () -> Unit, model: MixedDiscoveryModel?) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DiscoverySectionHeading(section.title, "open-$tag", actions = {
            if (model != null) {
                val feedState by model.feed.state.collectAsStateWithLifecycle()
                CompactFeedAction(model.feed, feedState, model.resultFeeds, sources)
            }
        }, all = all)
        if (model != null) MixedFeedSummary(model, section, open, sources)
        else Text("正在读取图源…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.status == DiscoveryStatus.FAILED) TextButton(onClick = feedbackAttempt(retry)) { Text("重试读取配置") }
    }
}

@Composable
internal fun DiscoverySectionPage(session: WorldSession, section: DiscoverySection, back: () -> Unit,
    sources: () -> Unit, open: (String, String) -> Unit) {
    val model = discoveryModel(session)
    val state by model.state.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    if (state.status != DiscoveryStatus.LOADING) {
        MixedFeedPage(session, state.sourceId, section, back, sources, open,
            configurationError = state.status == DiscoveryStatus.FAILED, retryConfiguration = model::refresh)
    } else Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp)) {
        SettingsHeading(section.title, "", back)
        Text(if (state.status == DiscoveryStatus.PRIVATE) "当前空间尚未开放" else "正在读取配置…")
    }
}

@Composable
internal fun OnlineSearchPage(session: WorldSession, back: () -> Unit, sources: () -> Unit,
    open: (String, String) -> Unit, initialQuery: String = "") {
    val model = discoveryModel(session)
    val state by model.state.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as Application
    val history = if (initialQuery.isBlank()) preloadedPages(session).searchHistory()
        else viewModel<SearchHistoryModel>(factory = viewModelFactory {
            initializer { SearchHistoryModel(app, session, createSavedStateHandle()) }
        })
    AggregateSearchPage(session, state, history, back, sources, model::refresh, open, initialQuery)
}

/** Old user-tag IDs are not source tags. Retain saved route decoding without reading old links. */
@Composable
internal fun LegacyTagPage(back: () -> Unit, tags: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp)) {
        item {
            ReferenceHeading("标签入口已调整", "旧用户标签与关联数据已保留", back)
            Text("此入口已停用，请返回标签页浏览。")
            TextButton(onClick = tags) { Text("前往图源标签") }
        }
    }
}
