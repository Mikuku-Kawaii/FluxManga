package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flux.manga.data.ReadingMode
import com.flux.manga.data.description
import com.flux.manga.core.SourceContinuation
import kotlinx.coroutines.launch

internal enum class ReaderTool { MODE, PAGE, OPTIONS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSaveFeedback(saved: Boolean, error: String?, retry: () -> Unit,
    tag: String, forceBack: (() -> Unit)? = null) {
    if (saved && error == null) return
    RequestFeedback(error ?: "正在保存进度…", Modifier.testTag(tag)
        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, failure = error != null)
    if (error != null) FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = feedbackAttempt(retry)) { Text("重试保存") }
        if (forceBack != null) TextButton(onClick = forceBack, shape = MaterialTheme.shapes.small) { Text("仍然返回（本次进度可能丢失）") }
    }
}

/** The header remains available when the scrollable tool panel is hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderHeader(tools: Boolean, leaving: Boolean, back: () -> Unit, toggle: () -> Unit,
    enabled: Boolean = true, backTag: String = "online-reader-back", toolsTag: String = "online-reader-tools") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = back, enabled = !leaving, modifier = Modifier.compactBackTarget().size(48.dp).testTag(backTag).semantics { contentDescription = "返回" }) { SettingsChevron(back = true) }
        Spacer(Modifier.width(4.dp))
        Text("阅读", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (enabled) IconButton(onClick = toggle, enabled = !leaving,
            colors = IconButtonDefaults.iconButtonColors(containerColor = if (tools)
                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.size(48.dp).testTag(toolsTag).semantics {
                contentDescription = if (tools) "收起工具" else "阅读工具"
                stateDescription = if (tools) "已展开" else "已收起"
            }) {
            ReferenceIcon(ReferenceSymbol.OPTIONS)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderPageControls(page: Int, total: Int, mode: ReadingMode, enabled: Boolean,
    previous: () -> Unit, next: () -> Unit, canPrevious: Boolean, canNext: Boolean,
    showTool: (ReaderTool) -> Unit, local: Boolean = false, chapters: (() -> Unit)? = null) {
    @Composable fun Previous() {
        IconButton(onClick = previous, enabled = enabled && canPrevious,
            modifier = Modifier.testTag(if (local) "reader-prev" else "online-reader-prev").semantics { contentDescription = "上一页" }) { ReferenceIcon(ReferenceSymbol.PREVIOUS) }
    }
    @Composable fun Next() {
        IconButton(onClick = next, enabled = enabled && canNext,
            modifier = Modifier.testTag(if (local) "reader-next" else "online-reader-next").semantics { contentDescription = "下一页" }) { ReferenceIcon(ReferenceSymbol.NEXT) }
    }
    @Composable fun Position(modifier: Modifier) {
        TextButton(onClick = { showTool(ReaderTool.PAGE) }, enabled = enabled,
            shape = MaterialTheme.shapes.small,
            modifier = modifier.testTag(if (local) "reader-position" else "online-reader-position")
                .semantics { contentDescription = "第 ${page + 1} / $total 页，跳转页码" }) {
            Text("${page + 1} / $total", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f) {
            Column {
                Position(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Previous(); Next() }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Previous(); Position(Modifier.weight(1f)); Next()
        }
    }
    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { showTool(ReaderTool.MODE) }, enabled = enabled, shape = MaterialTheme.shapes.small) { Text("阅读方式") }
        if (chapters != null) TextButton(onClick = chapters, enabled = enabled, shape = MaterialTheme.shapes.small) { Text("章节目录") }
        TextButton(onClick = { showTool(ReaderTool.OPTIONS) }, enabled = enabled, shape = MaterialTheme.shapes.small) { Text("阅读选项") }
    }
}

/** All mutations remain callbacks to the original reader and preference owners. */
@Composable
internal fun ReaderToolDialog(tool: ReaderTool, identity: String, page: Int, total: Int, mode: ReadingMode,
    options: ReaderOptions, enabled: Boolean, online: Boolean, dismiss: () -> Unit,
    selectMode: (ReadingMode) -> Unit, jumpTo: (Int) -> Unit, changeOptions: (ReaderOptions) -> Unit,
    optionsSummary: String = "阅读操作按空间即时保存，保留作品的阅读方式与进度。") {
    val keyboard = LocalSoftwareKeyboardController.current
    val close: () -> Unit = { keyboard?.hide(); dismiss() }
    var input by rememberSaveable(identity, tool) { mutableStateOf((page + 1).toString()) }
    val target = input.toIntOrNull()?.takeIf { it in 1..total }
    val jump: () -> Unit = { if (enabled && target != null) { jumpTo(target - 1); close() } }
    ReferenceDialog(onDismissRequest = close, sheet = tool != ReaderTool.PAGE,
        title = { Text(when (tool) { ReaderTool.MODE -> "阅读方式"; ReaderTool.PAGE -> "跳转页码"; ReaderTool.OPTIONS -> "阅读选项" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tool) {
                    ReaderTool.MODE -> {
                        Text("仅调整当前作品，保留阅读位置。", style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadingMode.entries.forEach { value ->
                                SettingsChoiceRow(value.label, mode == value, { selectMode(value); close() },
                                    Modifier.testTag(if (online) "online-reader-mode-${value.name}" else "reader-mode-${value.name}"),
                                    value.description, enabled)
                            }
                        }
                    }
                    ReaderTool.PAGE -> {
                        Text("当前第 ${page + 1} / $total 页")
                        OutlinedTextField(input, { input = it.filter(Char::isDigit).take(6) },
                            modifier = Modifier.fillMaxWidth().testTag(if (online) "online-reader-jump-input" else "reader-jump-input"),
                            label = { Text("页码（1–$total）") }, singleLine = true, enabled = enabled, shape = MaterialTheme.shapes.small,
                            isError = input.isNotEmpty() && target == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { jump() }))
                        if (input.isNotEmpty() && target == null) RequestFeedback("请输入范围内的页码", color = MaterialTheme.colorScheme.error)
                    }
                    ReaderTool.OPTIONS -> {
                        Text(optionsSummary, style = MaterialTheme.typography.bodySmall)
                        ReaderOptionControls(options, changeOptions, online, enabled)
                        SettingsDisclosure("操作说明") {
                        Text("双击放大或还原，也可捏合缩放、拖动查看。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("宽屏双页用于至少 600dp 的横向阅读区域，竖屏与条漫保持原方式。",
                            style = MaterialTheme.typography.bodySmall)
                        if (online) Text("缺少相邻章节时停止连读。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (tool == ReaderTool.PAGE) ReferenceDialogConfirm("跳转", onClick = jump, enabled = enabled && target != null,
                modifier = Modifier.testTag(if (online) "online-reader-jump" else "reader-jump"))
            else TextButton(onClick = close) { Text("关闭") }
        }, dismissButton = if (tool == ReaderTool.PAGE) ({ TextButton(onClick = close) { Text("取消") } }) else null)
}

/** Filters only the already loaded directory; pagination and chapter changes remain explicit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderChapterDialog(state: OnlineReaderState, offlineOnly: Boolean, readingState: ChapterReadingState,
    enabled: Boolean, dismiss: () -> Unit, select: (String) -> Unit, more: () -> Unit,
    restart: () -> Unit, adjacent: (Int) -> Unit, refreshReadings: () -> Unit, mark: ((Boolean) -> Unit)?) {
    val current = state.pages.getOrNull(state.index)?.chapterId.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(state.chapters, query) { state.chapters.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    val currentIndex = visible.indexOfFirst { it.id == current }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motionEnabled = LocalReferenceMotionEnabled.current
    val keyboard = LocalSoftwareKeyboardController.current
    val close: () -> Unit = { keyboard?.hide(); dismiss() }
    ReferenceSheet(onDismissRequest = close, title = { Text("章节目录") },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } }, text = {
            LazyColumn(Modifier.heightIn(max = 440.dp).testTag("reader-chapter-list"), state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item("directory-tools") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReferenceSearchField(query, { query = it }, "筛选已加载章节",
                        modifier = Modifier.fillMaxWidth().testTag("reader-chapter-query"),
                        trailingIcon = if (query.isNotEmpty()) ({ TextButton(onClick = { query = "" }) { Text("清空") } }) else null)
                    Text(if (query.isBlank()) "已加载 ${state.chapters.size} 章" else "匹配 ${visible.size} / ${state.chapters.size} 章",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (currentIndex >= 0) TextButton(onClick = { keyboard?.hide(); scope.launch {
                        if (motionEnabled) list.animateScrollToItem(currentIndex + 1) else list.scrollToItem(currentIndex + 1)
                    } }) { Text("定位当前章") }
                    SettingsDisclosure("章节操作") {
                        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { close(); restart() }, enabled = enabled) { Text("本章从头阅读") }
                            TextButton(onClick = { close(); adjacent(-1) }, enabled = enabled) { Text("上一章") }
                            TextButton(onClick = { close(); adjacent(1) }, enabled = enabled) { Text("下一章") }
                        }
                        if (mark != null) {
                            TextButton(onClick = { mark(readingState.records[current]?.completed != true) },
                                enabled = enabled && readingState.loaded && !readingState.busy) {
                                Text(if (readingState.records[current]?.completed == true) "取消本章已读标记" else "标记本章已读")
                            }
                        }
                    }
                    if (mark != null && (readingState.error != null || readingState.busy)) ChapterReadingStatus(readingState, refreshReadings)
                    if (visible.isEmpty() && !state.chapterBusy && state.chapterError == null) Text(
                        if (query.isNotBlank()) "已加载章节中没有匹配项，可清除筛选或继续加载。" else "暂无已加载章节。")
                } }
                items(visible, key = { "chapter:${it.id}" }) { chapter ->
                    Column {
                        TextButton(onClick = { select(chapter.id); close() },
                            enabled = enabled && (!offlineOnly || chapter.id in state.offlineIds),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.textButtonColors(containerColor = if (chapter.id == current)
                                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = chapter.id == current }) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(chapter.title, style = MaterialTheme.typography.bodyLarge)
                                val labels = buildList {
                                    if (chapter.id == current) add("当前")
                                    if (readingState.records[chapter.id]?.completed == true) add("已读")
                                    if (offlineOnly) add(if (chapter.id in state.offlineIds) "已下载" else "未下载")
                                }
                                if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item("directory-status") {
                    state.chapterError?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (state.chapterBusy) { RequestLoading(); Text("正在读取章节…") }
                    if (state.chapterNext is SourceContinuation.Next) TextButton(onClick = feedbackAttempt(more),
                        enabled = enabled && !state.chapterBusy) { Text(if (state.chapterError == null) "加载更多章节" else "重试章节") }
                    if (offlineOnly) Text("离线模式仅阅读已下载章节。", style = MaterialTheme.typography.bodySmall)
                }
            }
        })
}
