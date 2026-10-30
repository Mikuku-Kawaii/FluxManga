package com.flux.manga.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Read failures change presentation only; the caller keeps its cached data and form inputs. */
internal fun LazyListScope.readContentLayout(hasContent: Boolean, busy: Boolean, error: String?,
    refresh: () -> Unit, detail: Boolean = false, cancel: (() -> Unit)? = null): Boolean {
    if (!busy && error != null && !hasContent) {
        item("read-failure") { ReadFailure(error, refresh) }
        return false
    }
    if (hasContent && error != null && !busy) item("read-refresh-error") {
        Column { RequestFeedback("刷新失败，已保留上次内容：$error", persistent = true)
            TextButton(onClick = feedbackAttempt(refresh)) { Text("重试刷新") } }
    }
    if (!hasContent) {
        item("read-layout") {
            Column {
                ReadLayoutPlaceholder(detail)
                if (busy && cancel != null) TextButton(onClick = cancel) { Text("取消请求") }
            }
        }
        return false
    }
    return true
}

@Composable
internal fun ReadFailure(reason: String, refresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("加载失败", style = MaterialTheme.typography.titleMedium)
        Text(reason, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        ReferenceButton(onClick = feedbackAttempt(refresh)) { Text("刷新") }
    }
}

/** The same cover/title/actions/grid geometry is present before the first response. */
@Composable
internal fun ReadLayoutPlaceholder(detail: Boolean = false) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "正在加载内容" },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (detail) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReadPlaceholder(Modifier.width(112.dp).aspectRatio(.7f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadPlaceholder(Modifier.fillMaxWidth().height(28.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth(.8f).height(22.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth(.6f).height(16.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth().height(48.dp))
                }
            }
            ReadPlaceholder(Modifier.fillMaxWidth().height(48.dp))
            ReadPlaceholder(Modifier.fillMaxWidth().height(64.dp))
            repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { ReadPlaceholder(Modifier.weight(1f).height(44.dp)) }
            } }
        } else {
            repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadPlaceholder(Modifier.fillMaxWidth().aspectRatio(.7f))
                    ReadPlaceholder(Modifier.fillMaxWidth().height(18.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth(.6f).height(14.dp))
                } }
            } }
        }
    }
}

@Composable
private fun ReadPlaceholder(modifier: Modifier) {
    Spacer(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), RoundedCornerShape(8.dp)))
}

@Composable
internal fun ReadTagPlaceholder(columns: Int) {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "正在加载标签" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(columns) { ReadPlaceholder(Modifier.weight(1f).height(48.dp)) }
        } }
    }
}
