package com.flux.manga.ui.pages

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.flux.manga.data.isFinished
import com.flux.manga.data.isReadToEnd
import com.flux.manga.data.OnlineBookInformation

internal data class ShelfUpdateStatus(val label: String, val newerChapters: Boolean = false)

/** Compare actual chapter identities in reading order, never local save/read timestamps. */
internal fun shelfUpdateStatus(info: OnlineBookInformation?, chapter: String?, online: Boolean = true): ShelfUpdateStatus {
    if (!online) return ShelfUpdateStatus("本地作品")
    val newer = info?.isReadToEnd != true && info?.chapterGroups?.values?.any { ids ->
        if (chapter.isNullOrBlank()) ids.isNotEmpty()
        else ids.indexOf(chapter).let { index -> index >= 0 && index < ids.lastIndex }
    } == true
    if (info?.isFinished == true)
        return ShelfUpdateStatus("已完结", newer)
    val date = info?.takeIf { it.contentUpdated }?.updated?.let { updateDate(it, "更新") }
        ?.takeUnless { it == "日期未提供" }
    return ShelfUpdateStatus(date
        ?: "更新日期未知", newer)
}

internal fun shelfReadingLabel(label: String, info: OnlineBookInformation?, chapter: String?, online: Boolean = true): String =
    label + if (shelfUpdateStatus(info, chapter, online).newerChapters) " · 有未看章节" else ""

@Composable
internal fun ShelfUpdateLabel(info: OnlineBookInformation?, chapter: String?, online: Boolean = true) {
    val status = shelfUpdateStatus(info, chapter, online)
    Text(status.label, style = MaterialTheme.typography.bodySmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = ReadingSurfaceStyle.muted)
}
