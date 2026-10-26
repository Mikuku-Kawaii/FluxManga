package com.flux.manga.data

internal fun decodeReadingMode(value: String?) = ReadingMode.entries.firstOrNull { it.name == value }

/** Old progress is explicit by migration; only new, inherited progress follows defaults. */
internal fun resolveReadingMode(stored: String?, explicit: Boolean, default: ReadingMode) =
    if (explicit) decodeReadingMode(stored) ?: default else default

/** A paged reader keeps the strip anchor on the same page, including its initial emission. */
internal fun pagedFraction(currentPage: Int, nextPage: Int, fraction: Float) =
    if (currentPage == nextPage) fraction else 0f

internal val ReadingMode.description: String get() = when (this) {
    ReadingMode.LTR -> "左右逐页 · 向左滑动到下一页"
    ReadingMode.RTL -> "左右逐页 · 向右滑动到下一页"
    ReadingMode.VERTICAL -> "上下逐页 · 向上滑动到下一页，每次停在一整页"
    ReadingMode.STRIP -> "连续纵向滚动 · 页面首尾相接，保留页内位置"
}
