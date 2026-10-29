package com.flux.manga.ui.pages

import com.flux.manga.core.*
import com.flux.manga.data.ReadingMode
import com.flux.manga.data.isCopySource
import kotlin.math.max
import kotlin.math.min

internal data class ReaderOptions(val volume: Boolean = false, val invertVolume: Boolean = false,
    val doubleTap: Boolean = true, val continuous: Boolean = true, val spread: Boolean = false)

/** Capture DOWN/UP as one gesture. Repeats and a released/replaced owner cannot turn pages. */
internal class ReaderVolumeDispatcher {
    var owner: ((Int) -> Unit)? = null
        set(value) { field = value; held.clear() }
    private val held = mutableSetOf<Int>()
    fun reset() { held.clear() }
    fun dispatch(key: Int, action: Int, repeat: Int, cancelled: Boolean): Boolean {
        if (key != 24 && key != 25) return false
        val callback = owner ?: return false
        return when(action) {
            0 -> { if (repeat == 0 && !cancelled) held.add(key); key in held }
            1 -> if (held.remove(key)) { if (!cancelled) callback(if (key == 25) 1 else -1); true } else false
            else -> false
        }
    }
}
internal fun readerSpreadEnabled(option: Boolean, mode: ReadingMode, width: Float, height: Float) =
    option && mode in setOf(ReadingMode.LTR, ReadingMode.RTL) && width >= 600f && width > height
internal fun spreadIndices(group: Int, count: Int, spread: Boolean, rtl: Boolean): List<Int> {
    val size = if (spread) 2 else 1
    val indices = (group * size until min(count, group * size + size)).toList()
    return if (rtl) indices.reversed() else indices
}
internal fun settledReaderAnchor(current: Int, group: Int, spread: Boolean): Int {
    val size = if (spread) 2 else 1
    return if (current / size == group) current else group * size
}
internal data class ReaderTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)
internal fun readerTransform(scale: Float, x: Float, y: Float, width: Float, height: Float,
    imageWidth: Float = width, imageHeight: Float = height): ReaderTransform {
    if (!scale.isFinite() || !x.isFinite() || !y.isFinite() || width <= 0 || height <= 0) return ReaderTransform()
    val s = scale.coerceIn(1f, 3f)
    val fit = min(width / imageWidth.coerceAtLeast(1f), height / imageHeight.coerceAtLeast(1f))
    val maxX = max(0f, (imageWidth * fit * s - width) / 2)
    val maxY = max(0f, (imageHeight * fit * s - height) / 2)
    return ReaderTransform(s, x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}

internal data class ReaderDirectory(val chapters: List<SourceChapter>, val next: SourceContinuation)
/** Uses source order, never title/ID guesses; bounded and resumable after each successful page. */
internal suspend fun adjacentReaderChapter(key: SourceMangaKey, current: String, direction: Int,
    initial: ReaderDirectory, fetch: suspend (SourcePageRequest) -> SourceChapterPage,
    publish: (ReaderDirectory) -> Unit = {}, active: () -> Unit = {}): SourceChapter? {
    require(direction == 1 || direction == -1)
    var directory = initial
    val seen = mutableSetOf<SourcePageRequest>()
    repeat(128) {
        active()
        require(directory.chapters.size <= 10000 && directory.chapters.all { it.manga == key }) { "章节目录身份不匹配或过大" }
        require(directory.chapters.map { it.id }.distinct().size == directory.chapters.size) { "章节目录包含重复标识" }
        val index = directory.chapters.indexOfFirst { it.id == current }
        if (index >= 0) {
            directory.chapters.getOrNull(index + direction)?.let { candidate ->
                // Copy's flattened catalog resets its one-based ordinal for each group.
                // Preserve that boundary, including when the next group arrives on a new page.
                val ordinal = directory.chapters[index].ordinal
                if (isCopySource(key.sourceId) &&
                    (if (direction > 0) candidate.ordinal <= ordinal else candidate.ordinal >= ordinal)) return null
                return candidate
            }
            if (direction < 0) return null
        }
        val request = (directory.next as? SourceContinuation.Next)?.request
        if (request == null) {
            require(index >= 0) { "当前章节不在完整目录中，请返回详情刷新" }
            return null
        }
        require(seen.add(request)) { "章节分页重复，请重试目录" }
        val page = fetch(request); active()
        require(page.items.all { it.manga == key }) { "章节来源不匹配" }
        val merged = (directory.chapters + page.items).distinctBy { it.id }
        require(merged.size <= 10000) { "章节目录超过 10000 项，请从目录选择" }
        require(merged.size > directory.chapters.size || page.continuation is SourceContinuation.End) { "章节分页没有进展，请重试" }
        directory = ReaderDirectory(merged, page.continuation)
        publish(directory)
    }
    error("章节分页达到本次上限，请继续重试或从目录选择")
}

/** A deliberate extra drag retries preparation at the end of the buffered strip. */
internal class ReaderBoundaryDrag(private val threshold: Float) {
    private var distance = 0f
    private var fired = false
    fun drag(unusedY: Float, allowed: Boolean): Boolean {
        if (!allowed || unusedY >= 0) { distance = 0f; return false }
        if (fired) return false
        distance += -unusedY
        if (distance < threshold) return false
        fired = true; return true
    }
    fun reset() { distance = 0f; fired = false }
}

/** Keep the old display and durable anchor until save + target validation + session check succeed. */
internal suspend fun prepareReaderChapter(key: SourceMangaKey, id: String, save: suspend () -> Boolean,
    fetch: suspend () -> List<SourcePageResource>, release: suspend () -> Unit,
    active: () -> Unit): List<SourcePageResource>? {
    active()
    if (!save()) return null
    active()
    val pages = fetch()
    active()
    require(pages.isNotEmpty() && pages.all { it.manga == key && it.chapterId == id }) { "章节正文为空或身份不匹配" }
    release(); active()
    return pages
}
