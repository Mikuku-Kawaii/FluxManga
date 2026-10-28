package com.flux.manga.ui.pages

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.flux.manga.data.OnlineBookInformation
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ShelfUpdateStatus(val label: String, val newerChapters: Boolean = false)

/** Compare actual chapter identities in reading order, never local save/read timestamps. */
internal fun shelfUpdateStatus(info: OnlineBookInformation?, chapter: String?, online: Boolean = true): ShelfUpdateStatus {
    if (!online) return ShelfUpdateStatus("本地作品")
    val newer = info?.chapterGroups?.values?.any { ids ->
        if (chapter.isNullOrBlank()) ids.isNotEmpty()
        else ids.indexOf(chapter).let { index -> index >= 0 && index < ids.lastIndex }
    } == true
    if (info?.status?.trim()?.uppercase(Locale.ROOT) in setOf("已完结", "完结", "已完結", "完結", "COMPLETED", "ENDED", "FINISHED"))
        return ShelfUpdateStatus("已完结", newer)
    val date = info?.takeIf { it.contentUpdated }?.updated?.trim()?.let { raw ->
        val match = Regex("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日|(?=T|\\s|$))").find(raw)
        match?.let {
            try { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            catch (_: java.time.DateTimeException) { null }
        }
    }
    return ShelfUpdateStatus(date?.format(DateTimeFormatter.ofPattern("MM月dd日更新", Locale.CHINA))
        ?: "更新日期未知", newer)
}

internal fun shelfReadingLabel(label: String, info: OnlineBookInformation?, chapter: String?, online: Boolean = true): String =
    label + if (shelfUpdateStatus(info, chapter, online).newerChapters) " · 有未看章节" else ""

@Composable
internal fun ShelfUpdateLabel(info: OnlineBookInformation?, chapter: String?, online: Boolean = true) {
    val status = shelfUpdateStatus(info, chapter, online)
    Text(status.label, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
