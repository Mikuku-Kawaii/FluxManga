package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.SourceContinuation
import com.flux.manga.data.BaoziEndpoint
import com.flux.manga.session.WorldSession

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BaoziPage(session: WorldSession, back: () -> Unit, open: (String, String) -> Unit,
    mangaId: String? = null, sourceId: String = BaoziEndpoint.SOURCE_ID,
    history: SearchHistoryModel? = null, sources: () -> Unit = {}, read: ((String?) -> Unit)? = null, openTag: (String, String, String) -> Unit = { _, _, _ -> }, download: () -> Unit = {}, searchAuthor: ((String) -> Unit)? = null) {
    if (!com.flux.manga.data.isBaoziSource(sourceId)) { ReferenceHeading("来源不可用", "请返回后重新选择来源", back); return }
    val app = LocalContext.current.applicationContext as Application
    val model: BaoziModel = viewModel(key = "baozi:$sourceId:$mangaId", factory = viewModelFactory { initializer { BaoziModel(app, session, mangaId, sourceId) } })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.start()
        onDispose { model.leave() }
    }
    val feed by model.feed.state.collectAsStateWithLifecycle()
    val detail by model.detail.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    var previous by rememberSaveable { mutableStateOf(model.submitted) }
    LaunchedEffect(model.submitted) {
        if (previous != model.submitted) scroll.scrollToItem(0)
        previous = model.submitted
    }
    val requestFeedback = LocalRequestFeedback.current
    val submit: (String) -> Unit = { query ->
        requestFeedback?.beginAttempt()
        if (model.search(query)) { history?.query(query); history?.submit(sourceId, query); keyboard?.hide() }
    }
    BookResultsLayout(enabled = mangaId == null) { columns ->
        ReferenceRefreshColumn(refreshing = feed.busy || detail.busy, refresh = { if (mangaId == null) model.feed.refresh() else model.refreshDetail() }, refreshEnabled = mangaId != null || model.submitted != null, modifier = Modifier.fillMaxSize().testTag(if (mangaId == null) "baozi-search" else "baozi-detail"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { if (mangaId == null) ReferenceHeading("包子漫画搜索", "", back) else DetailHeading(back) }
            if (mangaId == null && history != null) {
                item("query") { ReferencePanel {
                    ReferenceSubmitSearchField(history.query, history::query, "搜索作品", { submit(history.query) },
                        modifier = Modifier.fillMaxWidth().testTag("baozi-query"), submitEnabled = history.query.isNotBlank())
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = feedbackAttempt({ submit("") })) { Text("浏览作品目录") }
                    }
                    Text(when (model.submitted) { null -> "主动搜索后加载；作品目录可分页浏览。"; "" -> "当前为作品目录，按图源顺序显示。"; else -> "当前关键词：${model.submitted}；此来源搜索结果无后续页。" })
                } }
                item("status") {
                    if (feed.busy && feed.cards.isEmpty()) ReadLayoutPlaceholder()
                    if (model.submitted != null) BaoziRequestStatus(feed.busy, feed.error, feed.notice, model.feed::refresh, model.feed::cancel)
                    if (feed.loaded && !feed.busy && feed.error == null && feed.cards.isEmpty()) Text("暂无匹配作品")
                }
                bookGridItems(if (feed.error != null && !feed.busy) emptyList() else feed.cards, columns, { "${it.key.sourceId}:${it.key.mangaId}" }) { card -> DiscoveryGridBook(model.library, card, open) }
                item("more") {
                    key(model, model.submitted) {
                        SourceBrowsePagination(feed, model.feed::more, "baozi-more", scroll, "more")
                    }
                }
                item("history") { SearchHistoryPanel(history, sourceId, submit, sources) }
            } else if (mangaId != null) {
                if (!readContentLayout(detail.book != null, detail.busy, detail.error ?: detail.notice.takeIf { detail.book == null }, model::refreshDetail, detail = true, cancel = model::cancelDetail))
                    return@ReferenceRefreshColumn
                item("state") { BaoziRequestStatus(detail.busy, detail.error, detail.notice, model::refreshDetail, model::cancelDetail) }
                detail.book?.let { book ->
                    item("book") { DetailHero(book.card.title, { OnlineCover(model.library, sourceId, mangaId, fillWidth = true) }) {
                        WorkMetadata(book.metadata, "$sourceId:$mangaId", searchAuthor)
                        WorkTags(book.tags?.map { it to it }, { it in com.flux.manga.data.BaoziCategories.tags }, compact = true) { id, name -> openTag(BaoziEndpoint.SOURCE_ID, id, name) }
                    } }
                    item("library") {
                        DetailActions(detail.favorite, detail.saving, toggleFavorite = model::favorite, download = download,
                            read = { read?.invoke(if (detail.hasProgress) null else book.chapters?.firstOrNull()?.id) },
                            canRead = read != null && (detail.hasProgress || !book.chapters.isNullOrEmpty()), hasProgress = detail.hasProgress,
                            description = book.description, descriptionIdentity = "$sourceId:$mangaId",
                            readingPosition = readingPosition(detail.progress, baoziChapterEntries(book.chapters.orEmpty())))
                    }
                    if (book.chapterError != null || book.chapters.isNullOrEmpty()) item("chapters-heading") { ReferencePanel {
                        Text("章节目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        when {
                            book.chapterError != null -> {
                                RequestFeedback(book.chapterError, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = feedbackAttempt(model::refreshDetail), enabled = !detail.busy) { Text("重试章节目录") }
                            }
                            book.chapters == null -> {
                                Text("此页面未提供可识别的章节目录")
                                TextButton(onClick = feedbackAttempt(model::refreshDetail), enabled = !detail.busy) { Text("重新读取目录") }
                            }
                            book.chapters.isEmpty() -> Text("图源目录暂无章节")
                            else -> Unit
                        }
                    } }
                    if (book.chapters.orEmpty().size > 1) item("directory") {
                        if (book.chapters != null) RecordedDetailCatalog(session, sourceId, mangaId, baoziChapterEntries(book.chapters), detail.progress?.chapterId,
                            { read?.invoke(it) }, enabled = read != null)
                    }
                }
            }
        }
    }
}

@Composable
private fun BaoziRequestStatus(busy: Boolean, error: String?, notice: String?, retry: () -> Unit, cancel: () -> Unit) {
    if (busy) {
        Text("正在读取包子漫画…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = cancel) { Text("取消请求") }
    }
    error?.let { RequestFeedback(it, busy = busy, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    notice?.let { Text(it) }
    if (error != null || notice != null) TextButton(onClick = feedbackAttempt(retry), enabled = !busy) { Text("重试加载") }
}
