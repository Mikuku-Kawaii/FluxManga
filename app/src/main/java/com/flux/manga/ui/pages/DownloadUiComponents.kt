package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.flux.manga.data.*

internal enum class DownloadQueueFilter(val label: String) {
    ALL("全部"), ACTIVE("等待 / 下载中"), PAUSED("已暂停"), FAILED("失败"), COMPLETE("已完成"), CANCELLED("已取消");
    fun accepts(status: DownloadStatus) = when (this) {
        ALL -> true
        ACTIVE -> status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
        PAUSED -> status == DownloadStatus.PAUSED
        FAILED -> status == DownloadStatus.FAILED
        COMPLETE -> status == DownloadStatus.COMPLETE
        CANCELLED -> status == DownloadStatus.CANCELLED
    }
}
internal fun downloadTitleMatches(title: String, query: String) = title.contains(query.trim(), ignoreCase = true)
internal fun filterDownloadJobs(jobs: List<ChapterDownload>, filter: DownloadQueueFilter, query: String) =
    jobs.filter { filter.accepts(it.status) && downloadTitleMatches(it.title, query) }
internal fun addDownloadSelection(selected: List<String>, visible: List<String>, limit: Int = 200) =
    (selected + visible).distinct().take(limit)
internal fun hiddenDownloadSelection(selected: List<String>, visible: List<String>) = (selected.toSet() - visible.toSet()).size
internal fun transferFraction(done: Long, total: Long?): Float? =
    if (total != null && total > 0 && done in 0..total) (done.toDouble() / total).toFloat() else null
internal fun DownloadStatus.downloadLabel() = when (this) {
    DownloadStatus.QUEUED -> "等待下载"; DownloadStatus.RUNNING -> "正在下载"; DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "下载失败"; DownloadStatus.COMPLETE -> "已完成"; DownloadStatus.CANCELLED -> "已取消"
}

@Composable
internal fun TransferPanel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    SettingsGroup(title) {
        Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Only presentation is folded; callers keep task state and launchers outside the content. */
@Composable
internal fun TransferDisclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsDisclosure(title, content = content)
}

@Composable
internal fun TransferProgress(done: Long, total: Long?, active: Boolean, text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fraction = transferFraction(done, total)
    if (fraction != null) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
}

/** Public and encrypted queues share presentation, never their storage or task owners. */
@Composable
internal fun DownloadTaskProgress(job: ChapterDownload) {
    var details by rememberSaveable(job.id) { mutableStateOf(false) }
    job.error?.let { RequestFeedback("${job.title}：$it", busy = job.status == DownloadStatus.RUNNING) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(job.status.downloadLabel(), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            color = if (job.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ details = true }) { Text(if (job.error != null) "查看原因" else "详情") }
    }
    if (job.status != DownloadStatus.COMPLETE && (job.completed > 0 || job.status == DownloadStatus.RUNNING)) {
        val fraction = transferFraction(job.completed.toLong(), job.pages.size.toLong().takeIf { it > 0 })
        if (fraction != null) Text("已保存 ${job.completed} / ${job.pages.size} 页 · ${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall)
        else if (job.status == DownloadStatus.RUNNING) Text("正在读取页数…", style = MaterialTheme.typography.bodySmall)
    }
    if (details) ReferenceSheet(onDismissRequest = { details = false }, title = { Text("下载详情") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(job.title, style = MaterialTheme.typography.titleMedium)
            Text(job.status.downloadLabel())
            Text(if (job.pages.isEmpty()) "总页数未知" else "已保存 ${job.completed} / ${job.pages.size} 页")
            Text("占用 ${android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, job.bytes)}")
            job.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ details = false }) { Text("关闭") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadTitleFilter(query: String, change: (String) -> Unit, visible: Int, total: Int,
    enabled: Boolean = true, label: String = "筛选已加载章节") {
    LibraryQueryField(query, { if (enabled) change(it) }, "download-title-filter", label, enabled)
    ReferenceActionRow(text = { modifier ->
        Text(if (query.isBlank() && visible == total) "已加载 $total 项" else "匹配 $visible / $total 项", modifier, style = MaterialTheme.typography.bodySmall)
    }, actions = {
        if (query.isNotBlank()) TextButton(onClick = { change("") }, enabled = enabled) { Text("清除筛选") }
    })
}

@Composable
internal fun DownloadSelectionSummary(selected: List<String>, visible: List<String>) {
    val hidden = hiddenDownloadSelection(selected, visible)
    Text("已选 ${selected.size} 项" + if (hidden > 0) "，其中 $hidden 项在筛选外；选中操作仍包含这些项目。" else "",
        style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadFilterChips(filter: DownloadQueueFilter, jobs: List<ChapterDownload>, enabled: Boolean,
    change: (DownloadQueueFilter) -> Unit) {
    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadQueueFilter.entries.forEach { value ->
            FilterChip(filter == value, { change(value) }, enabled = enabled,
                label = { Text("${value.label} ${jobs.count { value.accepts(it.status) }}") })
        }
    }
}

@Composable
internal fun DownloadChapterChoice(title: String, selected: Boolean, enabled: Boolean, status: String?, select: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, enabled = enabled,
            role = Role.Checkbox, onValueChange = select).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
