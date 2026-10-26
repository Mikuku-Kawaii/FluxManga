package com.flux.manga.data

import com.flux.manga.core.SourcePageResource

/** Restore only the same source/work/chapter/page; a missing page starts at the first page. */
internal fun onlineReadingAnchor(pages: List<SourcePageResource>, progress: OnlineReadingProgress?): Pair<Int, Float> {
    if (progress == null) return 0 to 0f
    val index = pages.indexOfFirst { it.manga.sourceId == progress.sourceId && it.manga.mangaId == progress.mangaId &&
        it.chapterId == progress.chapterId && it.id == progress.pageId }
    return if (index < 0) 0 to 0f else index to (if (progress.fraction.isFinite()) progress.fraction.coerceIn(0f, .9999f) else 0f)
}
