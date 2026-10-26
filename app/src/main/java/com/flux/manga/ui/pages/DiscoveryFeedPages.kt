package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.SourceContinuation
import com.flux.manga.data.OnlineLibrary
import com.flux.manga.data.MangaCard
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.DiscoverySection

@Composable
private fun discoveryFeedModel(session: WorldSession, sourceId: String, section: DiscoverySection): DiscoveryFeedModel {
    val app = LocalContext.current.applicationContext as Application
    val model: DiscoveryFeedModel = viewModel(key = "discovery-feed:$sourceId:${section.name}", factory = viewModelFactory {
        initializer { DiscoveryFeedModel(app, session, sourceId, section) }
    })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.feed.start()
        onDispose { model.feed.leave() }
    }
    return model
}

@Composable
internal fun DiscoveryFeedSummary(session: WorldSession, sourceId: String, section: DiscoverySection,
    open: (String, String) -> Unit, sources: () -> Unit) {
    val model = discoveryFeedModel(session, sourceId, section)
    val state by model.feed.state.collectAsStateWithLifecycle()
    DiscoveryFeedStatus(model, state, section, sourceId, sources, summary = true)
    if (state.cards.isEmpty() && state.busy) ReadLayoutPlaceholder()
    if (state.cards.isNotEmpty() && (state.error == null || state.busy)) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.cards.take(6), key = { it.key.mangaId }) { card ->
            ReferencePanel(Modifier.width(156.dp).clickable(role = Role.Button, onClickLabel = "查看详情") {
                open(sourceId, card.key.mangaId)
            }) {
                OnlineCover(model.library, sourceId, card.key.mangaId)
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick = { open(sourceId, card.key.mangaId) }) { Text("查看详情") }
            }
        }
    }
}

@Composable
internal fun DiscoveryFeedList(session: WorldSession, sourceId: String, section: DiscoverySection,
    back: () -> Unit, sources: () -> Unit, open: (String, String) -> Unit) {
    val model = discoveryFeedModel(session, sourceId, section)
    val state by model.feed.state.collectAsStateWithLifecycle()
    ReferenceRefreshColumn(refreshing = state.busy, refresh = { model.feed.refresh() }, refreshEnabled = !state.unsupported, modifier = Modifier.fillMaxSize().testTag("discovery-section-${section.name}"), state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "heading") {
            ReferenceHeading(section.title, "当前空间 · 图源提供的真实内容", back)
            DiscoveryFeedStatus(model, state, section, sourceId, sources)
        }
        if (state.busy && state.cards.isEmpty()) item("layout") { ReadLayoutPlaceholder() }
        items(if (state.error != null && !state.busy) emptyList() else state.cards, key = { it.key.mangaId }) { card -> DiscoveryFeedBook(model.library, card, open) }
        item(key = "pagination") {
            state.appendError?.let {
                RequestFeedback("后续页加载失败：$it", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.next is SourceContinuation.Next) TextButton(onClick = feedbackAttempt(model.feed::more), enabled = !state.busy,
                modifier = Modifier.testTag("discovery-more")) {
                Text(if (state.appendError != null) "重试后续页" else "加载更多")
            } else if (state.loaded && state.cards.isNotEmpty()) Text("已显示全部结果")
        }
    }
}

@Composable
internal fun DiscoveryFeedBook(library: OnlineLibrary, card: MangaCard, open: (String, String) -> Unit) {
    BookResultCard(card.title, { open(card.key.sourceId, card.key.mangaId) }, grid = false, metadata = card.author) {
        OnlineCover(library, card.key.sourceId, card.key.mangaId, fillWidth = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryFeedStatus(model: DiscoveryFeedModel, state: DiscoveryFeedState, section: DiscoverySection,
    sourceId: String, sources: () -> Unit, summary: Boolean = false) {
    if (section == DiscoverySection.UPDATES && sourceId != com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID) Text("服务端最近更新的作品，不含新上架；作品资料修改也可能列入。",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.unsupported) {
        Text("${discoverySourceName(sourceId)} 尚不支持${section.title}，可在来源选择中切换。", Modifier.testTag("discovery-unsupported"))
    } else {
        if (state.busy) {
            Text(if (state.loaded) "正在加载，已有内容保留…" else "正在读取图源…",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            RequestLoading()
            TextButton(onClick = model.feed::cancel) { Text("取消请求") }
        }
        state.error?.let { RequestFeedback(it, busy = state.busy, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.loaded && !state.busy && state.error == null && state.cards.isEmpty()) {
            Text(if (state.next is SourceContinuation.Next) "这一页没有可见作品，仍可加载后续页。" else "图源暂无${section.title}")
        }
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.error != null || state.notice != null) TextButton(onClick = feedbackAttempt(model.feed::refresh), enabled = !state.busy) { Text("重试加载") }
            if (sourceId != com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID && (!summary || state.error != null)) TextButton(onClick = sources) { Text("图源设置") }
        }
    }
}
