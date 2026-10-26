package com.flux.manga.ui.pages

import com.flux.manga.core.SourcePageResource

internal data class OnlineTile(val index: Int, val page: SourcePageResource, val top: Int, val bottom: Int?) {
    val key get() = "${page.chapterId}:${page.id}:$top"
}
internal fun onlineTileStep(width: Int) = (width.toLong() * 2).coerceIn(1024, 2048).toInt()
internal fun onlineTileTop(page: SourcePageResource, fraction: Float): Int {
    val height = page.height?.toInt() ?: return 0
    val step = onlineTileStep(page.width?.toInt() ?: return 0)
    return ((fraction.coerceIn(0f, .9999f) * height).toInt() / step) * step
}
internal fun onlineTiles(pages: List<SourcePageResource>): List<OnlineTile> = pages.flatMapIndexed { index, page ->
    val width = page.width?.toInt() ?: 0
    val height = page.height?.toInt() ?: 0
    if (width <= 0 || height <= 0) listOf(OnlineTile(index, page, 0, null))
    else (0 until height step onlineTileStep(width)).map { top ->
        OnlineTile(index, page, top, minOf(height.toLong(), top.toLong() + onlineTileStep(width)).toInt())
    }
}
