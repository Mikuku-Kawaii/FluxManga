package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.flux.manga.core.SourceContinuation

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceBrowseHeading(title: String, back: () -> Unit,
    browseLabel: String, browse: () -> Unit, sources: () -> Unit, help: String, backTag: String = "source-browse-back") {
    var menu by remember { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    SettingsHeading(title, "", { if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else back() },
        backModifier = Modifier.testTag(backTag), actions = {
            Box {
                ReferenceActionsButton("浏览操作", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(browseLabel) }, onClick = { menu = false; keyboard?.hide(); focus.clearFocus(); browse() })
                    DropdownMenuItem(text = { Text("图源设置") }, onClick = { menu = false; sources() })
                    DropdownMenuItem(text = { Text("浏览说明") }, onClick = { menu = false; showHelp = true })
                }
            }
        })
    if (showHelp) ReferenceDialog(onDismissRequest = { showHelp = false }, title = { Text("浏览说明") },
        text = { Text(help, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("关闭") } })
}

@Composable
internal fun SourceBrowseQuery(query: String, change: (String) -> Unit, enabled: Boolean, submit: () -> Unit, tag: String) {
    ReferenceSubmitSearchField(query, { change(it.take(200)) }, "搜索作品", submit,
        modifier = Modifier.fillMaxWidth().testTag(tag), submitEnabled = enabled)
}

@Composable
internal fun SourceBrowseStatus(state: DiscoveryFeedState, refresh: () -> Unit, cancel: () -> Unit,
    emptyText: String) {
    if (state.busy) {
        if (state.cards.isEmpty()) ReadLayoutPlaceholder()
        Text(if (state.loaded) "正在加载，保留已有作品…" else "正在读取作品…",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = cancel) { Text("取消请求") }
    }
    state.error?.let {
        RequestFeedback(it, busy = state.busy, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = feedbackAttempt(refresh), enabled = !state.busy) { Text("重试读取") }
    }
    state.notice?.let {
        Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = feedbackAttempt(refresh), enabled = !state.busy) { Text("重新读取") }
    }
    if (state.unsupported) Text("此来源不支持当前查询")
    if (state.loaded && !state.busy && state.error == null && state.appendError == null && state.notice == null &&
        !state.unsupported && state.cards.isEmpty()) Text(
        if (state.next is SourceContinuation.Next) "本页暂无作品，仍可加载后续页" else emptyText)
}

@Composable
internal fun SourceBrowsePagination(state: DiscoveryFeedState, more: () -> Unit, tag: String,
    scroll: androidx.compose.foundation.lazy.LazyListState, footerKey: String = "pagination") {
    AutomaticPagination(scroll, footerKey, state.next,
        state.loaded && !state.busy && !state.unsupported && state.error == null &&
            state.appendError == null && state.notice == null && state.next is SourceContinuation.Next, more)
    if (state.error != null && !state.busy) return
    state.appendError?.let { RequestFeedback(it, busy = state.busy, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (state.next is SourceContinuation.Next && !state.unsupported && (state.appendError != null || state.notice != null)) TextButton(onClick = feedbackAttempt(more), enabled = !state.busy,
        modifier = Modifier.testTag(tag)) {
        Text(if (state.appendError != null) "重试后续页" else "继续读取")
    } else if (resultFeedComplete(state) && state.cards.isNotEmpty()) Text("已显示全部结果",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (state.busy && state.loaded) RequestLoading()
    else Spacer(Modifier.height(8.dp))
}
