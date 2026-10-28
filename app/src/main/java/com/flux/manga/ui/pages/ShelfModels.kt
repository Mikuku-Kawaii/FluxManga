package com.flux.manga.ui.pages

import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld

internal enum class ShelfFilter(val label: String) { ALL("全部"), READING("在读"), UNREAD("未读"), READ("已读") }
internal enum class ShelfSort(val label: String) { RECENT("阅读时间"), TITLE("书名排序"), IMPORTED("本地导入时间") }
internal enum class ShelfSortDirection(val label: String) { ASCENDING("正序"), DESCENDING("倒序") }
internal val ShelfSort.defaultDirection get() = if (this == ShelfSort.TITLE) ShelfSortDirection.ASCENDING else ShelfSortDirection.DESCENDING
internal enum class ShelfLayout { GRID, LIST }
internal data class ShelfBook(val key: MangaKey, val title: String, val favorite: Boolean, val available: Boolean,
    val pageCount: Int?, val ordinal: Int?, val chapterId: String?, val readAt: Long?,
    val online: Boolean, val local: MangaCard? = null, val remote: OnlineShelfItem? = null,
    val importedAt: Long? = null, val downloadedChapters: Int = 0, val pendingDownloads: Int = 0) {
    // Length prefixes avoid source/id separator collisions, including online vs local.
    val stableKey get() = "${key.world}/${if (online) "online" else "local"}/${key.sourceId.length}:${key.sourceId}${key.mangaId.length}:${key.mangaId}"
    val information get() = remote?.information
    val hasProgress get() = ordinal != null && ordinal > 0
    val validProgress get() = hasProgress && !chapterId.isNullOrBlank() && (pageCount == null || ordinal!! <= pageCount)
    // Existing progress records the current page, not an independent finished-book flag.
    val status get() = when {
        !hasProgress -> ShelfFilter.UNREAD
        !online && pageCount != null && pageCount > 0 && ordinal == pageCount -> ShelfFilter.READ
        else -> ShelfFilter.READING
    }
    val ratio: Float? get() = if (!online && validProgress && pageCount != null && pageCount > 0)
        ordinal!!.toFloat() / pageCount else null
    val positionText get() = when {
        !hasProgress -> "尚未开始"
        !validProgress -> "阅读位置待核对"
        online -> "章节 $chapterId · 第 $ordinal 页"
        else -> "第 $ordinal / $pageCount 页${if (status == ShelfFilter.READ) " · 已到末页" else ""}"
    }
}

internal fun MangaCard.shelfBook() = ShelfBook(key, title, favorite, available, pageCount,
    progress?.ordinal, progress?.chapterId, progress?.updatedAt, false, local = this, importedAt = importedAt)
internal fun OnlineShelfItem.shelfBook() = ShelfBook(MangaKey(PrototypeWorld.SFW, book.sourceId, book.mangaId),
    title, book.favorite, available, null, progress?.ordinal, progress?.chapterId, progress?.updatedAt, true, remote = this)
internal fun latestReadable(books: List<ShelfBook>) = books.filter { it.available && it.validProgress }
    .maxWithOrNull(compareBy<ShelfBook> { it.readAt ?: 0L }.thenBy { it.stableKey })
internal val ShelfBook.sourceFilterKey get() = "${if (online) "online" else "local"}:${key.sourceId}"
internal fun sourceBooks(books: List<ShelfBook>, source: String?) =
    if (source == null) books else books.filter { it.sourceFilterKey == source }
internal fun readingDay(book: ShelfBook, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    book.readAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: "日期未知"
internal fun shelfResults(books: List<ShelfBook>, favoritesOnly: Boolean, historyOnly: Boolean,
                          filter: ShelfFilter, sort: ShelfSort, query: String = "",
                          field: LocalSearchField = LocalSearchField.ALL,
                          direction: ShelfSortDirection = sort.defaultDirection): List<ShelfBook> {
    val byValue = when (sort) {
        ShelfSort.TITLE -> compareBy<ShelfBook, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        ShelfSort.RECENT -> compareBy<ShelfBook> { it.readAt }
        ShelfSort.IMPORTED -> compareBy<ShelfBook> { if (it.online) null else it.importedAt }
    }
    val order = compareBy<ShelfBook> { when (sort) {
        ShelfSort.TITLE -> false
        ShelfSort.RECENT -> it.readAt == null
        ShelfSort.IMPORTED -> it.online || it.importedAt == null
    } }.then(if (direction == ShelfSortDirection.DESCENDING) byValue.reversed() else byValue).thenBy { it.stableKey }
    return books.filter { book -> (!favoritesOnly || book.favorite) && (!historyOnly || book.hasProgress) &&
        (filter == ShelfFilter.ALL || book.status == filter) && (if (book.online) matchesOnlineInformation(book.title, book.information, query, field)
        else matchesLocalMetadata(book.title, if (book.key.world == PrototypeWorld.SFW) book.local?.localMetadata else null, query, field)) }
        .sortedWith(order)
}

internal fun shelfColumns(widthDp: Float, fontScale: Float, layout: ShelfLayout): Int =
    if (layout == ShelfLayout.LIST) 1 else ((widthDp - 44f + 12f) / (100f * fontScale.coerceAtLeast(1f) + 12f)).toInt().coerceIn(1, 6)
internal fun shelfPreferenceKey(world: PrototypeWorld, field: String) = "${world.name}.$field"

internal enum class ShelfDownloadFilter(val label: String) { ALL("全部下载状态"), SAVED("有已下载章节"), PENDING("下载待完成") }
internal fun filterShelfDownloads(books: List<ShelfBook>, filter: ShelfDownloadFilter) = books.filter {
    when(filter) { ShelfDownloadFilter.ALL -> true; ShelfDownloadFilter.SAVED -> it.downloadedChapters>0; ShelfDownloadFilter.PENDING -> it.pendingDownloads>0 }
}
internal fun withShelfDownloads(books: List<ShelfBook>, jobs: List<ChapterDownload>): List<ShelfBook> {
    val statuses=downloadBookStatuses(jobs).associateBy { it.source to it.manga }
    return books.map { book ->
        val status=if(book.online && book.key.world==PrototypeWorld.SFW) statuses[book.key.sourceId to book.key.mangaId] else null
        book.copy(downloadedChapters=status?.completed ?: 0,pendingDownloads=status?.pending ?: 0)
    }
}

internal fun matchesOnlineInformation(title: String, info: OnlineBookInformation?, query: String, field: LocalSearchField): Boolean {
    val values = when (field) {
        LocalSearchField.ALL -> listOf(title, info?.author.orEmpty(), info?.summary.orEmpty()) + info?.tags.orEmpty()
        LocalSearchField.TITLE -> listOf(title)
        LocalSearchField.AUTHOR -> listOf(info?.author.orEmpty())
        LocalSearchField.SUMMARY -> listOf(info?.summary.orEmpty())
        LocalSearchField.VOLUME -> emptyList()
    }
    return query.isBlank() || values.any { it.contains(query.trim(), ignoreCase = true) }
}
