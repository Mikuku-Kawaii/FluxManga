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
internal fun progressSummary(progress: OnlineReadingProgress?, entries: List<DetailCatalogEntry>): String? = progress?.let {
    val name = entries.firstOrNull { entry -> entry.id == it.chapterId }?.title.orEmpty()
    "上次阅读：" + (if (name.isBlank()) "" else "$name · ") + "第 ${it.ordinal} 页"
}

private const val chapterNumeral = """(?:[0-9]+(?:\.[0-9]+)?|[零〇一二两兩三四五六七八九十百千万萬]+)"""
private val numberedChapter = Regex("""(?:第\s*)?($chapterNumeral)\s*[话話章回集]""")
private val plainChapterNumber = Regex(chapterNumeral)

internal fun readingPosition(progress: OnlineReadingProgress?, entries: List<DetailCatalogEntry>,
    hasMoreChapters: Boolean = false, ordinalAt: (Int) -> Int = { it + 1 }): String? = progress?.let {
    val page = "第${it.ordinal}页"
    if (entries.size <= 1 && !hasMoreChapters) return@let page
    val index = entries.indexOfFirst { entry -> entry.id == it.chapterId }
    if (index < 0) return@let page
    val title = java.text.Normalizer.normalize(entries[index].title.trim(), java.text.Normalizer.Form.NFKC)
    val number = numberedChapter.find(title)?.groupValues?.get(1) ?: plainChapterNumber.matchEntire(title)?.value
        ?: ordinalAt(index).toString()
    "第${number}话·$page"
}

internal fun baoziChapterEntries(chapters: List<com.flux.manga.data.BaoziChapter>) = chapters.mapIndexed { index, chapter ->
    DetailCatalogEntry(chapter.id, chapterTitle(chapter.title, index + 1))
}
