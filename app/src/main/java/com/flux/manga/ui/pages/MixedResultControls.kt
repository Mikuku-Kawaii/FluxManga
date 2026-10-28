package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.flux.manga.core.SourceContinuation

@Composable
internal fun CompactFeedFeedback(controller: AggregateSearchController, state: AggregateSearchState, feeds: List<ResultFeed>, sources: () -> Unit,
    showAction: Boolean = true) {
    val issues = feeds.filter { state.results[it.id]?.let { r -> r.error != null || r.appendError != null || r.notice != null || r.unsupported } == true }
    if (issues.isNotEmpty()) {
        val failed = issues.filter { state.results[it.id]?.let { r -> r.error != null || r.appendError != null } == true }.distinctBy { it.sourceId }.size
        val interrupted = issues.filter { state.results[it.id]?.notice != null }.distinctBy { it.sourceId }.size
        val unsupported = issues.filter { state.results[it.id]?.unsupported == true }.distinctBy { it.sourceId }.size
        val summary = listOfNotNull(
            "$failed 个图源加载失败".takeIf { failed > 0 },
            "$interrupted 个图源请求中断".takeIf { interrupted > 0 },
            "$unsupported 个图源不支持此查询".takeIf { unsupported > 0 },
        ).joinToString(" · ")
        @Composable fun Summary(modifier: Modifier = Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RequestFeedback(summary, style = MaterialTheme.typography.bodySmall,
                    color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, failure = failed > 0)
                if (issues.size == 1) (state.results[issues.first().id]?.let { it.error ?: it.appendError ?: it.notice })?.let {
                    RequestFeedback(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        failure = failed > 0)
                }
            }
        }
        if (showAction) ReferenceActionRow(text = { Summary(it) }, actions = { CompactFeedAction(controller, state, feeds, sources) })
        else Summary()
    }
    if (state.busy) {
        ReferenceActionRow(text = { modifier ->
            Text("正在加载…", modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }, actions = { TextButton({ controller.cancel() }) { Text("停止加载") } })
    }
}

@Composable
internal fun CompactFeedAction(controller: AggregateSearchController, state: AggregateSearchState, feeds: List<ResultFeed>, sources: () -> Unit) {
    val issues = feeds.mapNotNull { state.results[it.id] }.filter { it.error != null || it.appendError != null || it.notice != null || it.unsupported }
    var expanded by remember { mutableStateOf(false) }
    if (issues.isNotEmpty()) TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(if (issues.any { it.error != null || it.appendError != null || it.notice != null }) "详情与重试" else "查看详情")
    }
    if (expanded) MixedSourceStatusDialog(controller, state, feeds, sources, { expanded = false })
}

@Composable
internal fun BaoziBrowserHelp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val feedback = LocalRequestFeedback.current
    TextButton(onClick = {
        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(com.flux.manga.data.BaoziEndpoint.PUBLIC_URL))) }
        catch (_: android.content.ActivityNotFoundException) { feedback?.beginAttempt(); feedback?.show("未找到可打开网站的浏览器") }
    }) { Text("在浏览器打开包子漫画") }
    Text("如需官网验证，请到图源设置使用应用内网页登录并连接账号，再返回刷新。外部浏览器的会话无法接回。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A tag can have several independent requests at one source. Only the display filter groups them. */
internal data class ResultFeed(val id: String, val sourceId: String) {
    val title get() = discoverySourceName(sourceId)
}

internal fun resultFeedTitle(feed: ResultFeed, feeds: List<ResultFeed>): String {
    val siblings = feeds.filter { it.sourceId == feed.sourceId }
    return if (siblings.size <= 1) feed.title else "${feed.title} · 标签 ${siblings.indexOf(feed) + 1}"
}

internal fun resultScope(feeds: List<ResultFeed>, selected: String?): List<ResultFeed> =
    if (feeds.any { it.sourceId == selected }) feeds.filter { it.sourceId == selected } else feeds

internal fun resultFeedComplete(result: DiscoveryFeedState) = result.loaded && !result.busy &&
    !result.unsupported && result.error == null && result.appendError == null && result.notice == null &&
    result.next !is SourceContinuation.Next

internal fun hasMixedFeedback(state: AggregateSearchState) = state.busy || state.results.values.any {
    it.error != null || it.appendError != null || it.notice != null || it.unsupported
}

private fun resumeNextPage(result: DiscoveryFeedState) = result.appendError != null ||
    (result.notice != null && result.loaded && result.error == null && result.next is SourceContinuation.Next)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResultSourceFilter(feeds: List<ResultFeed>, selected: String?, count: Int, select: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = feeds.distinctBy { it.sourceId }
    val current = choices.firstOrNull { it.sourceId == selected }
    ReferenceActionRow(text = { modifier ->
        Text("已显示 $count 部", modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }, actions = {
        if (choices.size > 1) Box {
            TextButton(onClick = { expanded = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("result-source-filter").semantics { stateDescription = current?.title ?: "全部来源" }) {
                Text("${current?.title ?: "全部来源"} ▾")
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text("全部来源") }, onClick = { expanded = false; select(null) },
                    modifier = Modifier.semantics { this.selected = current == null })
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.title) }, onClick = { expanded = false; select(choice.sourceId) },
                        modifier = Modifier.semantics { this.selected = current?.sourceId == choice.sourceId })
                }
            }
        } else choices.firstOrNull()?.let {
            Text(it.title, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
}

@Composable
internal fun MixedResultCount(count: Int) {
    Text("已显示 $count 部", Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ResultActionsMenu(sources: () -> Unit, help: String, sourceStatus: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    Box {
        ReferenceActionsButton("结果操作", { expanded = true })
        DropdownMenu(expanded, { expanded = false }) {
            if (sourceStatus != null) DropdownMenuItem(text = { Text("来源状态") },
                onClick = { expanded = false; sourceStatus() })
            DropdownMenuItem(text = { Text("图源设置") }, onClick = { expanded = false; sources() })
            DropdownMenuItem(text = { Text("结果说明") }, onClick = { expanded = false; showHelp = true })
        }
    }
    if (showHelp) ReferenceDialog(onDismissRequest = { showHelp = false }, title = { Text("结果说明") },
        text = { Text(help, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("关闭") } })
}

@Composable
internal fun MixedRequestControls(controller: AggregateSearchController, state: AggregateSearchState,
    feeds: List<ResultFeed> = controller.sources.map { ResultFeed(it, it) }) {
    if (state.busy) {
        RequestLoading()
        TextButton(onClick = { controller.cancel() }) { Text("取消全部请求") }
    }
    feeds.forEach { feed ->
        val result = state.results[feed.id] ?: return@forEach
        val title = resultFeedTitle(feed, feeds)
        val error = result.error ?: result.appendError
        if (error != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RequestFeedback("$title：$error", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = feedbackAttempt({ if (resumeNextPage(result)) controller.more(feed.id) else controller.refresh(feed.id) }),
                enabled = !result.busy, shape = MaterialTheme.shapes.small) { Text("重试$title") }
            if (feed.sourceId == com.flux.manga.data.BaoziEndpoint.SOURCE_ID) BaoziBrowserHelp()
        }
        result.notice?.let {
            Text("$title：$it", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = feedbackAttempt({ if (resumeNextPage(result)) controller.more(feed.id) else controller.refresh(feed.id) }),
                enabled = !result.busy, shape = MaterialTheme.shapes.small) { Text(if (resumeNextPage(result)) "继续加载$title" else "重新请求$title") }
        }
        if (result.unsupported) Text("$title 不支持此查询", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Visible-footer paging: a busy or failed source never holds up a different source. */
@Composable
internal fun MixedPagination(controller: AggregateSearchController, state: AggregateSearchState,
    scroll: androidx.compose.foundation.lazy.LazyListState,
    feeds: List<ResultFeed> = controller.sources.map { ResultFeed(it, it) }) {
    feeds.forEach { feed -> key(controller, state.query, feed.id) {
        val result = state.results[feed.id]
        AutomaticPagination(scroll, "more", result?.next, result != null && result.loaded &&
            !result.busy && !result.unsupported && result.error == null && result.appendError == null &&
            result.notice == null && result.next is SourceContinuation.Next) { controller.more(feed.id) }
    } }
    if (feeds.any { state.results[it.id]?.busy == true }) RequestLoading()
    else if (feeds.isNotEmpty() && feeds.all { state.results[it.id]?.let(::resultFeedComplete) == true } &&
        feeds.any { state.results[it.id]?.cards?.isNotEmpty() == true }) {
        Text("已显示全部结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Spacer(Modifier.height(8.dp))
}

@Composable
internal fun MixedResultEmpty(feeds: List<ResultFeed>, state: AggregateSearchState, count: Int,
    filtered: Boolean, clear: () -> Unit, emptyText: String) {
    if (count != 0) return
    val results = feeds.mapNotNull { state.results[it.id] }
    if (feeds.isNotEmpty() && (results.size < feeds.size || results.any { it.busy })) {
        ReadLayoutPlaceholder()
        return
    }
    val message = when {
        feeds.isEmpty() -> "当前没有启用可用图源，可在图源管理中开启或配置"
        results.size < feeds.size || results.any { it.busy } -> "正在读取作品…"
        results.any { it.error != null || it.appendError != null } ->
            if (results.any { it.loaded && it.error == null && it.appendError == null })
                "部分图源加载失败，其余图源暂无匹配作品"
            else "暂未获取到作品，可分别重试失败图源"
        results.any { it.notice != null } -> "请求已中断，可继续读取作品"
        results.all { it.unsupported } -> "所选来源不支持此查询"
        results.any { it.next is SourceContinuation.Next } -> "本页暂无可见作品，仍可加载后续页"
        results.any { it.unsupported } -> "部分来源不支持此查询，其他来源暂无作品"
        results.all { it.loaded } -> emptyText
        else -> "尚未读取作品"
    }
    ReferenceEmptyState(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (filtered) TextButton(onClick = clear) { Text("查看全部来源") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MixedSourceStatusDialog(controller: AggregateSearchController, state: AggregateSearchState,
    feeds: List<ResultFeed>, sources: () -> Unit, dismiss: () -> Unit) {
    ReferenceDialog(onDismissRequest = dismiss, title = { Text("来源状态") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按来源筛选，下拉刷新，滑到底部自动加载更多。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (feeds.isEmpty()) Text("暂无可用来源")
            feeds.forEach { feed ->
                val result = state.results[feed.id]
                val title = resultFeedTitle(feed, feeds)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        result == null -> "尚未请求"
                        result.busy -> "读取中 · 已加载 ${result.cards.size} 部"
                        result.unsupported -> "不支持此查询"
                        result.error != null -> "读取失败 · 保留 ${result.cards.size} 部"
                        result.appendError != null -> "后续页失败 · 已加载 ${result.cards.size} 部"
                        result.notice != null -> "请求已中断 · 保留 ${result.cards.size} 部"
                        resultFeedComplete(result) -> "已加载 ${result.cards.size} 部 · 已到末页"
                        result.loaded -> "已加载 ${result.cards.size} 部 · 还有后续页"
                        else -> "尚未请求"
                    }, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    (result?.error ?: result?.appendError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (feed.sourceId == com.flux.manga.data.BaoziEndpoint.SOURCE_ID && result?.error != null) BaoziBrowserHelp()
                    result?.notice?.let { Text(it) }
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (result?.busy == true) TextButton(onClick = { controller.cancel(feed.id) },
                            modifier = Modifier.semantics { contentDescription = "$title，取消请求" }) { Text("取消请求") }
                        else if (result != null && !result.unsupported) {
                            if (result.error != null) TextButton(onClick = feedbackAttempt({ controller.refresh(feed.id) }),
                                modifier = Modifier.semantics { contentDescription = "$title，重试读取" }) { Text("重试读取") }
                            if (result.next is SourceContinuation.Next && (result.appendError != null || result.notice != null)) {
                                val moreLabel = if (result.appendError != null) "重试后续页" else "继续读取"
                                TextButton(onClick = feedbackAttempt({ controller.more(feed.id) }),
                                    modifier = Modifier.semantics { contentDescription = "$title，$moreLabel" }) { Text(moreLabel) }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { dismiss(); sources() }) { Text("图源设置") } })
}
