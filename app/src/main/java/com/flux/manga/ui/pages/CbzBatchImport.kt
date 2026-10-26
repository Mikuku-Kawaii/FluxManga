package com.flux.manga.ui.pages

import android.net.Uri
import com.flux.manga.data.MangaKey

// Bounds the picker handoff and result list; archives themselves are processed one at a time.
const val MAX_CBZ_BATCH = 100

enum class CbzBatchStatus(val label: String) {
    WAITING("未开始"), IMPORTING("正在复制并校验"), PREVIEW("等待确认元数据"), IMPORTED("已导入"),
    DUPLICATE("已在书库，未重复导入"), REPAIRED("已修复应用副本"),
    FAILED("失败 / 待核对"), INTERRUPTED("已中断，提交结果待核对"),
}

/** URIs remain in the entry ViewModel only after delivery; no persistent queue or grants. */
data class CbzBatchItem(val uri: Uri, val name: String, val status: CbzBatchStatus = CbzBatchStatus.WAITING,
    val key: MangaKey? = null, val message: String? = null) {
    val retryable get() = status in setOf(CbzBatchStatus.WAITING, CbzBatchStatus.FAILED, CbzBatchStatus.INTERRUPTED)
}

fun cbzBatchSummary(items: List<CbzBatchItem>): String = buildString {
    append("共 ${items.size} 项：新导入 ${items.count { it.status == CbzBatchStatus.IMPORTED }}")
    append("，已有 ${items.count { it.status == CbzBatchStatus.DUPLICATE }}")
    append("，修复 ${items.count { it.status == CbzBatchStatus.REPAIRED }}")
    append("，失败 ${items.count { it.status == CbzBatchStatus.FAILED }}")
    append("，中断 ${items.count { it.status == CbzBatchStatus.INTERRUPTED }}")
    append("，未开始 ${items.count { it.status == CbzBatchStatus.WAITING }}")
}
