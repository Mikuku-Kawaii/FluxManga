package com.flux.manga.data

import androidx.room.Dao
import androidx.room.Query

data class RecordExportCounts(val localBooks: Long, val onlineBooks: Long, val bookmarks: Long,
    val localProgress: Long, val onlineProgress: Long, val chapterReadings: Long)

@Dao
interface RecordExportDao {
    @Query("SELECT (SELECT COUNT(*) FROM local_books) AS localBooks, (SELECT COUNT(*) FROM online_books) AS onlineBooks, " +
        "(SELECT COUNT(*) FROM local_bookmarks) AS bookmarks, (SELECT COUNT(*) FROM reading_progress) AS localProgress, " +
        "(SELECT COUNT(*) FROM online_reading_progress) AS onlineProgress, (SELECT COUNT(*) FROM chapter_reading) AS chapterReadings")
    fun exportCounts(): RecordExportCounts
    @Query("SELECT * FROM local_books ORDER BY sourceId, mangaId LIMIT :limit OFFSET :offset")
    fun exportLocalBooks(limit: Int, offset: Int): List<LocalBook>
    @Query("SELECT * FROM online_books ORDER BY sourceId, mangaId LIMIT :limit OFFSET :offset")
    fun exportOnlineBooks(limit: Int, offset: Int): List<OnlineBook>
    @Query("SELECT * FROM local_bookmarks ORDER BY sourceId, mangaId, id LIMIT :limit OFFSET :offset")
    fun exportBookmarks(limit: Int, offset: Int): List<LocalBookmark>
    @Query("SELECT * FROM reading_progress ORDER BY sourceId, mangaId LIMIT :limit OFFSET :offset")
    fun exportLocalProgress(limit: Int, offset: Int): List<ReadingProgress>
    @Query("SELECT * FROM online_reading_progress ORDER BY sourceId, mangaId LIMIT :limit OFFSET :offset")
    fun exportOnlineProgress(limit: Int, offset: Int): List<OnlineReadingProgress>
    @Query("SELECT * FROM chapter_reading ORDER BY sourceId, mangaId, chapterId LIMIT :limit OFFSET :offset")
    fun exportChapters(limit: Int, offset: Int): List<ChapterReading>
}

internal class DatabaseExportRows(private val dao: RecordExportDao) : RecordExportRows {
    override fun page(table: ExportTable, offset: Int): List<ExportRecord> = when (table) {
        ExportTable.LOCAL_BOOKS -> dao.exportLocalBooks(EXPORT_PAGE_SIZE, offset).map { it.exportRecord() }
        ExportTable.ONLINE_BOOKS -> dao.exportOnlineBooks(EXPORT_PAGE_SIZE, offset).map { it.exportRecord() }
        ExportTable.BOOKMARKS -> dao.exportBookmarks(EXPORT_PAGE_SIZE, offset).map { it.exportRecord() }
        ExportTable.LOCAL_PROGRESS -> dao.exportLocalProgress(EXPORT_PAGE_SIZE, offset).map { it.exportRecord() }
        ExportTable.ONLINE_PROGRESS -> dao.exportOnlineProgress(EXPORT_PAGE_SIZE, offset).map { it.exportRecord() }
        ExportTable.CHAPTERS -> dao.exportChapters(EXPORT_PAGE_SIZE, offset).map { it.exportRecord() }
    }
}
