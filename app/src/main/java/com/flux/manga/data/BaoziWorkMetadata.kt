package com.flux.manga.data

import com.flux.manga.core.SourceAuthor
import com.flux.manga.core.SourceUpdateKind
import com.flux.manga.core.SourceWorkMetadata
import org.jsoup.nodes.Document
import java.time.LocalDate

/** Pinned Baozi detail selectors; no directory-order or chapter-count inference. */
internal fun baoziWorkMetadata(document: Document): SourceWorkMetadata {
    var partial = false
    val author = document.selectFirst("h2.comics-detail__author")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    val authors = when {
        author == null -> emptyList()
        author.toByteArray(Charsets.UTF_8).size > 512 || author.contains("{{") -> { partial = true; emptyList() }
        else -> listOf(SourceAuthor(author, null)) // The page does not declare a delimiter or individual roles.
    }
    val status = when (document.selectFirst("div.tag-list > span.tag")?.text()?.trim()) {
        "连载中", "連載中" -> "连载中"
        "已完结", "已完結" -> "已完结"
        else -> null // An arbitrary category is not a publication status.
    }
    val raw = document.selectFirst("em")?.text()?.trim().orEmpty()
    val match = Regex("[（(]?(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s+更新[）)]?").matchEntire(raw)
    val updated = if (match == null) null else try {
        LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        "${match.groupValues[1]}年${match.groupValues[2]}月${match.groupValues[3]}日"
    } catch (_: java.time.DateTimeException) { partial = true; null }
    return SourceWorkMetadata(authors, status, updated, if (updated != null) SourceUpdateKind.CONTENT else null, partial)
}
