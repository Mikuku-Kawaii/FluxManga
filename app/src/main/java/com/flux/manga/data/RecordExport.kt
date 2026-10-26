package com.flux.manga.data

/** Versioned metadata only. Table names and fields are an explicit allowlist. */
internal enum class ExportTable(val field: String) {
    LOCAL_BOOKS("localBooks"), ONLINE_BOOKS("onlineBooks"), BOOKMARKS("bookmarks"),
    LOCAL_PROGRESS("localProgress"), ONLINE_PROGRESS("onlineProgress"), CHAPTERS("chapterReadings")
}
internal const val EXPORT_PAGE_SIZE = 128
internal const val RECORD_EXPORT_VERSION = 1
internal typealias ExportRecord = Map<String, Any?>

internal interface RecordExportRows {
    fun page(table: ExportTable, offset: Int): List<ExportRecord>
}

/** Production uses Android JsonWriter; tests can inspect fields without Android stubs. */
internal interface RecordExportSink {
    fun begin(header: ExportRecord)
    fun beginArray(name: String)
    fun record(fields: ExportRecord)
    fun endArray()
    fun finish(counts: Map<String, Long>)
}

internal fun writeRecordExport(world: String, exportedAt: Long, rows: RecordExportRows,
    sink: RecordExportSink, check: () -> Unit): Map<String, Long> {
    require(world == "SFW" || world == "NSFW")
    check()
    sink.begin(linkedMapOf("format" to "fluxmanga-records", "version" to RECORD_EXPORT_VERSION,
        "world" to world, "exportedAt" to exportedAt, "databaseVersion" to WORLD_DATABASE_VERSION,
        "encrypted" to false, "includesContent" to false, "supportsRestore" to false))
    val counts = linkedMapOf<String, Long>()
    ExportTable.entries.forEach { table ->
        check(); sink.beginArray(table.field)
        var offset = 0
        while (true) {
            check()
            val page = rows.page(table, offset)
            require(page.size <= EXPORT_PAGE_SIZE)
            page.forEach { check(); sink.record(it) }
            offset = Math.addExact(offset, page.size)
            if (page.size < EXPORT_PAGE_SIZE) break
        }
        sink.endArray(); counts[table.field] = offset.toLong()
    }
    check()
    sink.finish(counts)
    return counts
}

internal fun LocalBook.exportRecord(): ExportRecord = linkedMapOf(
    "kind" to "local", "sourceId" to sourceId, "mangaId" to mangaId, "title" to title,
    "defaultTitle" to defaultTitle, "favorite" to favorite, "sha256" to sha256,
    "archiveBytes" to archiveBytes, "importedAt" to importedAt, "coverPageId" to coverPageId,
    "metadataTitle" to metadataTitle, "author" to author, "volume" to volume, "summary" to summary)
internal fun OnlineBook.exportRecord(): ExportRecord = linkedMapOf(
    "kind" to "online", "sourceId" to sourceId, "mangaId" to mangaId, "title" to title,
    "favorite" to favorite, "updatedAt" to updatedAt)
internal fun LocalBookmark.exportRecord(): ExportRecord = linkedMapOf(
    "kind" to "local", "id" to id, "sourceId" to sourceId, "mangaId" to mangaId,
    "pageId" to pageId, "ordinal" to ordinal, "fraction" to fraction, "mode" to mode,
    "note" to note, "createdAt" to createdAt, "updatedAt" to updatedAt)
internal fun ReadingProgress.exportRecord(): ExportRecord = linkedMapOf(
    "kind" to "local", "sourceId" to sourceId, "mangaId" to mangaId, "chapterId" to chapterId,
    "pageId" to pageId, "ordinal" to ordinal, "fraction" to fraction, "mode" to mode,
    "modeExplicit" to modeExplicit, "updatedAt" to updatedAt)
internal fun OnlineReadingProgress.exportRecord(): ExportRecord = linkedMapOf(
    "kind" to "online", "sourceId" to sourceId, "mangaId" to mangaId, "chapterId" to chapterId,
    "pageId" to pageId, "ordinal" to ordinal, "fraction" to fraction, "mode" to mode,
    "modeExplicit" to modeExplicit, "updatedAt" to updatedAt)
internal fun ChapterReading.exportRecord(): ExportRecord = linkedMapOf(
    "kind" to "online", "sourceId" to sourceId, "mangaId" to mangaId, "chapterId" to chapterId,
    "pageId" to pageId, "ordinal" to ordinal, "fraction" to fraction,
    "completed" to completed, "updatedAt" to updatedAt)
