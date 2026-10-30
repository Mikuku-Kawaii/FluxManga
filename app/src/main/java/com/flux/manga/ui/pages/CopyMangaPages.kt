package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import android.app.Application
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.SourceContinuation
import com.flux.manga.data.CopyMangaEndpoint
import com.flux.manga.session.WorldSession

@Composable
private fun copyModel(session: WorldSession, mangaId: String?, manualSearch: Boolean, sourceId: String): CopyMangaModel {
    val app = LocalContext.current.applicationContext as Application
    val model: CopyMangaModel = viewModel(key = "copy-content:$sourceId:$mangaId:$manualSearch", factory = viewModelFactory {
        initializer { CopyMangaModel(app, session, createSavedStateHandle(), mangaId, manualSearch, sourceId) }
    })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.start()
        onDispose { model.leave() }
    }
    return model
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CopyMangaPage(session: WorldSession, back: () -> Unit, open: (String, String) -> Unit,
    sourceId: String = CopyMangaEndpoint.SOURCE_ID, mangaId: String? = null, read: ((String?) -> Unit)? = null,
    history: SearchHistoryModel? = null, sources: () -> Unit = {}, openTag: (String, String, String) -> Unit = { _, _, _ -> }, download: () -> Unit = {}, searchAuthor: ((String) -> Unit)? = null) {
    if (!com.flux.manga.data.isCopySource(sourceId)) {
        ReferenceHeading("图源已变更", "请返回后重新选择来源", back)
        return
    }
    val model = copyModel(session, mangaId, history != null, sourceId)
    val keyboard = LocalSoftwareKeyboardController.current
    val query = history?.query ?: model.query
    val changeQuery: (String) -> Unit = { history?.query(it); model.query(it) }
    val submit: () -> Unit = feedbackAttempt {
        if (query.isNotBlank()) {
            keyboard?.hide()
            model.query(query)
            history?.submit(sourceId, query)
            model.search()
        }
    }
    val feed by model.feed.state.collectAsStateWithLifecycle()
    val detail by model.detail.collectAsStateWithLifecycle()
    val chapters by model.chapters.state.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    // A new submitted search is a new result set; detail return keeps this entry and its scroll.
    var previousQuery by rememberSaveable { mutableStateOf(model.submitted) }
    LaunchedEffect(model.submitted) {
        if (mangaId == null && previousQuery != model.submitted) scroll.scrollToItem(0)
        previousQuery = model.submitted
    }
    BookResultsLayout(enabled = mangaId == null) { columns ->
        ReferenceRefreshColumn(refreshing = feed.busy || detail.busy, refresh = { if (mangaId == null) model.feed.refresh() else model.refreshDetail() }, refreshEnabled = true, modifier = Modifier.fillMaxSize().testTag(if (mangaId == null) "copy-feed" else "copy-detail"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") {
                if (mangaId != null) DetailHeading(back) else SourceBrowseHeading("拷贝漫画", back,
                    "查看图源推荐", { changeQuery(""); model.recommendations() }, sources,
                    "搜索当前来源的作品，包含尚未入库的漫画。推荐列表按图源顺序展示，可从菜单返回推荐。刷新使用已提交的搜索词，修改输入后需再次搜索。阅读进度保存在本机。")
            }
            if (mangaId == null) {
                item("search") {
                    SourceBrowseQuery(query, changeQuery, query.isNotBlank() && (!feed.busy || query.trim() != model.submitted), submit, "copy-query")
                }
                item("result-summary") {
                    Text(if (history != null && !feed.attempted) "输入关键词后搜索"
                        else if (model.submitted.isEmpty()) "图源推荐" else "搜索：${model.submitted}", style = MaterialTheme.typography.titleMedium)
                    Text("已显示 ${feed.cards.size} 部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (history != null) item("history") {
                    SearchHistoryPanel(history, sourceId, { changeQuery(it); model.query(it); history.submit(sourceId, it); model.search(); keyboard?.hide() }, sources)
                }
                if (feed.busy || feed.error != null || feed.notice != null || feed.unsupported || (feed.loaded && feed.cards.isEmpty())) item("state") {
                    SourceBrowseStatus(feed, model.feed::refresh, model.feed::cancel,
                        if (model.submitted.isEmpty()) "图源暂无推荐" else "没有匹配作品，可修改搜索词")
                }
                bookGridItems(feed.cards, columns, { "${it.key.sourceId}:${it.key.mangaId}" }) { card ->
                    BookResultCard(card.title, { open(card.key.sourceId, card.key.mangaId) }, cover = {
                        CopyMangaCover(model.covers, card.key.mangaId, fillWidth = true)
                    })
                }
                item("pagination") {
                    key(model, model.submitted) { SourceBrowsePagination(feed, model.feed::more, "copy-more", scroll) }
                }
            } else {
                if (!readContentLayout(detail.detail != null, detail.busy, detail.error ?: detail.notice.takeIf { detail.detail == null }, model::refreshDetail, detail = true, cancel = model::cancelDetail))
                    return@ReferenceRefreshColumn
                item("state") { CopyRequestStatus(detail.busy, detail.detail != null, detail.error, detail.notice, model::refreshDetail, model::cancelDetail) }
                detail.detail?.let { result ->
                    item("title") {
                        DetailHero(result.manga.title, { CopyMangaCover(model.covers, result.manga.key.mangaId, fillWidth = true) }, tags = {
                            WorkTags(detail.themes?.map { it.name to it.pathWord }, compact = true, openInDialog = true) { id, name -> openTag(CopyMangaEndpoint.SOURCE_ID, id, name) }
                        }) {
                            WorkMetadata(result.metadata, "$sourceId:$mangaId", searchAuthor)
                        }
                    }
                    item("actions") {
                        DetailActions(detail.favorite, detail.saving, favoriteEnabled = detail.localReady && !detail.busy,
                            toggleFavorite = model::favorite, download = download,
                            read = { read?.invoke(if (detail.hasProgress) null else chapters.items.firstOrNull()?.id) },
                            canRead = read != null && (detail.hasProgress || chapters.items.isNotEmpty()), hasProgress = detail.hasProgress,
                            description = result.description, descriptionIdentity = "$sourceId:$mangaId",
                            readingPosition = readingPosition(detail.progress, chapterEntries(chapters.items)))
                    }
                }
                if (detail.detail != null) {
                    val groups = detail.groups
                    if (groups == null || groups.size != 1) item("groups") {
                        when {
                            groups == null -> Text("图源未提供章节分组信息，可刷新详情重试")
                            groups.isEmpty() -> Text("暂无章节分组")
                            groups.size > 1 -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(groups, key = { it.id }) { group ->
                                    FilterChip(selected = chapters.groupId == group.id, onClick = { model.selectGroup(group.id) },
                                        modifier = Modifier.widthIn(max = 260.dp),
                                        label = { Text(group.chapterCount?.let { "${group.title} · $it 章" } ?: group.title) })
                                }
                            }
                            else -> Unit
                        }
                    }
                    if (chapters.groupId != null) {
                        if (chapters.busy || chapters.error != null || chapters.notice != null ||
                            (chapters.loaded && chapters.items.isEmpty())) item("chapter-state") {
                            CopyRequestStatus(chapters.busy, chapters.loaded, chapters.error, chapters.notice,
                                model.chapters::refresh, model.chapters::cancel)
                            if (chapters.loaded && !chapters.busy && chapters.error == null && chapters.items.isEmpty()) {
                                Text(if (chapters.next is SourceContinuation.Next) "本页暂无章节，可加载后续页" else "该分组暂无章节")
                            }
                        }
                        if (chapters.items.isNotEmpty() || chapters.next is SourceContinuation.Next ||
                            chapters.busy || chapters.appendError != null) item("directory") {
                            RecordedDetailCatalog(session, sourceId, mangaId, chapterEntries(chapters.items), detail.progress?.chapterId, { read?.invoke(it) },
                                busy = chapters.busy, hasMore = chapters.next is SourceContinuation.Next, more = model.chapters::more,
                                appendError = chapters.appendError, enabled = read != null,
                                totalCount = chapters.total ?: detail.groups?.firstOrNull { it.id == chapters.groupId }?.chapterCount?.toLong())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyRequestStatus(busy: Boolean, loaded: Boolean, error: String?, notice: String?, refresh: () -> Unit, cancel: () -> Unit) {
    if (busy) {
        Text(if (loaded) "正在加载，已有内容保留…" else "正在读取图源…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        RequestLoading()
        TextButton(onClick = cancel) { Text("取消请求") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (error != null || notice != null) TextButton(onClick = feedbackAttempt(refresh), enabled = !busy) { Text("重试加载") }
}
