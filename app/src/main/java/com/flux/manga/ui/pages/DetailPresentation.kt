package com.flux.manga.ui.pages

import com.flux.manga.core.SourceChapter
import com.flux.manga.data.OnlineReadingProgress

internal fun featuredTags(names: List<String>): List<String> {
    val preferred = listOf("奇幻", "日常", "冒险", "科幻", "悬疑", "青春")
    return (preferred.filter { it in names } + names).distinct().take(6)
}

internal data class DetailCatalogEntry(val id: String, val title: String, val description: String = "")
internal fun chapterTitle(title: String, number: Int) = title.takeIf(String::isNotBlank) ?: "第${number}话"
internal fun List<SourceChapter>.withChapterTitles() = mapIndexed { index, chapter ->
    chapter.copy(title = chapterTitle(chapter.title, chapter.ordinal.toInt().takeIf { it > 0 } ?: (index + 1)))
}
internal fun chapterEntries(chapters: List<SourceChapter>) = chapters.withChapterTitles().map {
    DetailCatalogEntry(it.id, it.title, it.pageCount?.let { count -> "$count 页" }.orEmpty())
}
internal fun orderedCatalog(entries: List<DetailCatalogEntry>, reverse: Boolean) = if (reverse) entries.asReversed() else entries
internal fun progressSummary(progress: OnlineReadingProgress?, entries: List<DetailCatalogEntry>): String? =
    readingPosition(progress, entries)?.let { "上次阅读：$it" }

internal fun readingPosition(progress: OnlineReadingProgress?, entries: List<DetailCatalogEntry>): String? = progress?.let {
    val chapter = entries.firstOrNull { chapter -> chapter.id == it.chapterId }?.title
    (chapter?.takeIf(String::isNotBlank)?.let { name -> "$name · " } ?: "") + "第 ${it.ordinal} 页"
}

internal fun baoziChapterEntries(chapters: List<com.flux.manga.data.BaoziChapter>) = chapters.mapIndexed { index, chapter ->
    DetailCatalogEntry(chapter.id, chapterTitle(chapter.title, index + 1))
}
