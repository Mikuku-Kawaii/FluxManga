package com.flux.manga.data

/** A successful platform handoff grants background execution only to this live owner. */
internal class DownloadBackgroundLease {
    private var revision = 0L
    var running = false
        private set
    fun begin(): Long { running = false; return ++revision }
    fun activate(ticket: Long, allowed: Boolean): Boolean {
        if (ticket != revision || !allowed) return false
        running = true
        return true
    }
    fun accepts(ticket: Long) = running && ticket == revision
    fun revoke() { revision++; running = false }
}

internal data class DownloadNotice(val text: String, val total: Int = 0, val saved: Int = 0,
    val working: Boolean = false)

/** No content identity, title, URL, credential or raw error can enter a notification. */
internal fun downloadNotice(state: DownloadQueueState): DownloadNotice {
    if (!state.loaded) return DownloadNotice("任务已停止，返回队列核对并继续")
    if (state.networkMessage != null && state.jobs.any { it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) })
        return DownloadNotice(state.networkMessage, working = true)
    val active = state.jobs.firstOrNull { it.status == DownloadStatus.RUNNING }
    val waiting = state.jobs.count { it.status == DownloadStatus.QUEUED }
    if (active != null) return DownloadNotice(
        if (active.pages.isEmpty()) "正在读取章节 · 等待 $waiting 章"
        else "当前章节 ${active.completed} / ${active.pages.size} 页 · 等待 $waiting 章",
        active.pages.size, active.completed, true)
    if (waiting > 0 || state.controlling) return DownloadNotice("正在处理队列 · 等待 $waiting 章", working = true)
    val failed = state.jobs.count { it.status == DownloadStatus.FAILED }
    val paused = state.jobs.count { it.status == DownloadStatus.PAUSED }
    val completed = state.jobs.count { it.status == DownloadStatus.COMPLETE }
    return DownloadNotice("队列已停止 · 完成 $completed / 暂停 $paused / 失败 $failed 章")
}
