package com.flux.manga.data

import androidx.room.*

@Entity(tableName = "manga", primaryKeys = ["sourceId", "mangaId"])
data class StoredManga(val sourceId: String, val mangaId: String, val title: String)

@Entity(tableName = "search_history", primaryKeys = ["sourceId", "query"])
data class SearchHistory(val sourceId: String, val query: String, val usedAt: Long)

/** Opaque session payloads, authenticated and encrypted in both worlds. */
@Entity(tableName = "source_session")
data class SourceSession(@PrimaryKey val sourceId: String, val sealedValue: ByteArray)

@Entity(tableName = "local_books", primaryKeys = ["sourceId", "mangaId"], indices = [Index(value = ["sha256"], unique = true)])
data class LocalBook(val sourceId: String = "cbz", val mangaId: String, val title: String,
                     val archiveName: String, val sha256: String, val archiveBytes: Long, val importedAt: Long,
                     @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
                     @ColumnInfo(defaultValue = "''") val defaultTitle: String = title,
                     val coverPageId: String? = null,
                     val metadataTitle: String? = null, val author: String? = null,
                     val volume: String? = null, val summary: String? = null)

@Entity(tableName = "local_pages", primaryKeys = ["sourceId", "mangaId", "ordinal"],
    foreignKeys = [ForeignKey(entity = LocalBook::class, parentColumns = ["sourceId", "mangaId"],
        childColumns = ["sourceId", "mangaId"], onDelete = ForeignKey.CASCADE)])
data class LocalPage(val sourceId: String = "cbz", val mangaId: String, val ordinal: Int,
                     val entryName: String, val mimeType: String, val width: Int, val height: Int)

@Dao
interface WorldDao : ReadingStatisticsDao, RecordExportDao {
    @Query("SELECT * FROM local_bookmarks WHERE sourceId = :source AND mangaId = :manga ORDER BY ordinal, fraction, createdAt, id")
    fun localBookmarks(source: String, manga: String): List<LocalBookmark>
    @Query("SELECT * FROM local_bookmarks WHERE sourceId = :source AND mangaId = :manga AND id = :id")
    fun localBookmark(source: String, manga: String, id: String): LocalBookmark?
    @Insert fun insertLocalBookmark(bookmark: LocalBookmark)
    @Update fun updateLocalBookmark(bookmark: LocalBookmark)
    @Query("DELETE FROM local_bookmarks WHERE sourceId = :source AND mangaId = :manga AND id = :id")
    fun deleteLocalBookmark(source: String, manga: String, id: String)
    @Query("SELECT * FROM chapter_reading WHERE sourceId = :source AND mangaId = :manga")
    fun chapterReadings(source: String, manga: String): List<ChapterReading>
    @Query("SELECT * FROM chapter_reading WHERE sourceId = :source AND mangaId = :manga AND chapterId = :chapter")
    fun chapterReading(source: String, manga: String, chapter: String): ChapterReading?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putChapterReading(record: ChapterReading)

    @Query("SELECT COUNT(*) FROM search_history") fun searchHistoryCount(): Int
    @Query("SELECT COUNT(*) FROM browse_visits") fun visitCount(): Int
    @Query("DELETE FROM search_history") fun clearSearchHistory()

    @Query("SELECT * FROM browse_visits WHERE (:beforeAt IS NULL OR visitedAt < :beforeAt OR (visitedAt = :beforeAt AND (kind, sourceId, mangaId) > (:kind, :source, :manga))) ORDER BY visitedAt DESC, kind, sourceId, mangaId LIMIT :limit")
    fun visits(beforeAt: Long?, kind: String, source: String, manga: String, limit: Int): List<BrowseVisit>
    @Query("SELECT * FROM browse_visits WHERE kind = :kind AND sourceId = :source AND mangaId = :manga")
    fun visit(kind: String, source: String, manga: String): BrowseVisit?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putVisit(visit: BrowseVisit)
    @Query("UPDATE browse_visits SET title = :title WHERE kind = 'online' AND sourceId = :source AND mangaId = :manga")
    fun updateVisitTitle(source: String, manga: String, title: String)
    @Query("UPDATE browse_visits SET title = :title WHERE kind = 'local' AND sourceId = :source AND mangaId = :manga")
    fun updateLocalVisitTitle(source: String, manga: String, title: String)
    @Query("DELETE FROM browse_visits WHERE kind = :kind AND sourceId = :source AND mangaId = :manga")
    fun deleteVisit(kind: String, source: String, manga: String)
    @Query("DELETE FROM browse_visits") fun clearVisits()
    @Query("SELECT * FROM search_history WHERE sourceId LIKE 'online:%' ORDER BY usedAt DESC, sourceId, query LIMIT 50")
    fun onlineSearchHistory(): List<SearchHistory>
    @Query("DELETE FROM search_history WHERE sourceId LIKE 'online:%'") fun clearOnlineSearchHistory()
    @Query("DELETE FROM search_history WHERE sourceId = :source AND query = :query")
    fun deleteSearchHistory(source: String, query: String)
    @Query("DELETE FROM search_history WHERE sourceId LIKE 'online:%' AND rowid NOT IN (SELECT rowid FROM search_history WHERE sourceId LIKE 'online:%' ORDER BY usedAt DESC, sourceId, query LIMIT 50)")
    fun trimOnlineSearchHistory()

    @Query("SELECT mode FROM reading_preferences WHERE id = 'default'") fun defaultReadingMode(): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putReadingPreference(value: ReadingPreference)
    @Query("SELECT * FROM user_tags ORDER BY nameKey, id") fun tags(): List<UserTag>
    @Query("SELECT * FROM tag_books") fun tagBooks(): List<TagBook>
    @Query("SELECT * FROM user_tags WHERE id = :id") fun tag(id: String): UserTag?
    @Insert fun insertTag(tag: UserTag)
    @Update fun updateTag(tag: UserTag)
    @Query("DELETE FROM user_tags WHERE id = :id") fun deleteTag(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun linkTag(link: TagBook)
    @Query("DELETE FROM tag_books WHERE tagId = :tagId AND kind = :kind AND sourceId = :source AND mangaId = :id")
    fun unlinkTag(tagId: String, kind: String, source: String, id: String)
    @Query("DELETE FROM tag_books WHERE kind = :kind AND sourceId = :source AND mangaId = :id")
    fun removeBookTags(kind: String, source: String, id: String)
    @Query("SELECT * FROM online_books ORDER BY updatedAt DESC") fun onlineBooks(): List<OnlineBook>
    @Query("SELECT * FROM online_books WHERE sourceId = :source AND mangaId = :id") fun onlineBook(source: String, id: String): OnlineBook?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putOnlineBook(book: OnlineBook)
    @Query("SELECT * FROM online_reading_progress ORDER BY updatedAt DESC") fun onlineProgressRows(): List<OnlineReadingProgress>
    @Query("SELECT * FROM online_reading_progress WHERE sourceId = :source AND mangaId = :id") fun onlineProgress(source: String, id: String): OnlineReadingProgress?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putOnlineProgress(progress: OnlineReadingProgress)
    @Query("SELECT * FROM reading_progress WHERE sourceId = :source AND mangaId = :id") fun progress(source: String, id: String): ReadingProgress?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putProgress(progress: ReadingProgress)
    @Query("SELECT * FROM local_books ORDER BY importedAt DESC, mangaId") fun localBooks(): List<LocalBook>
    @Query("SELECT * FROM local_books WHERE sourceId = :source AND mangaId = :id") fun localBook(source: String, id: String): LocalBook?
    @Query("SELECT * FROM local_books WHERE sha256 = :hash") fun localByHash(hash: String): LocalBook?
    @Query("SELECT * FROM local_pages WHERE sourceId = :source AND mangaId = :id ORDER BY ordinal") fun localPages(source: String, id: String): List<LocalPage>
    @Query("SELECT * FROM local_pages WHERE sourceId = :source AND mangaId = :id ORDER BY ordinal LIMIT 1")
    fun firstLocalPage(source: String, id: String): LocalPage?
    @Query("SELECT * FROM local_pages WHERE sourceId = :source AND mangaId = :id AND entryName = :page LIMIT 1")
    fun localPage(source: String, id: String, page: String): LocalPage?
    @Insert fun insertBook(book: LocalBook)
    @Update fun updateBook(book: LocalBook)
    @Query("DELETE FROM local_books WHERE sourceId = :source AND mangaId = :id")
    fun removeBook(source: String, id: String): Int
    @Query("UPDATE local_books SET favorite = :favorite WHERE sourceId = :source AND mangaId = :id")
    fun setFavorite(source: String, id: String, favorite: Boolean): Int
    @Insert fun insertPages(pages: List<LocalPage>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun seed(rows: List<StoredManga>)
    @Query("SELECT * FROM manga WHERE sourceId = :source ORDER BY CAST(mangaId AS INTEGER)")
    fun catalog(source: String): List<StoredManga>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun remember(row: SearchHistory)
    @Query("SELECT query FROM search_history WHERE sourceId = :source ORDER BY usedAt DESC, query LIMIT 10")
    fun history(source: String): List<String>
    @Query("DELETE FROM search_history WHERE sourceId = :source") fun clearHistory(source: String)
    @Query("DELETE FROM search_history WHERE sourceId = :source AND query NOT IN (SELECT query FROM search_history WHERE sourceId = :source ORDER BY usedAt DESC, query LIMIT 10)")
    fun trimHistory(source: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSession(row: SourceSession)
    @Query("SELECT sealedValue FROM source_session WHERE sourceId = :source") fun session(source: String): ByteArray?
    @Query("DELETE FROM source_session WHERE sourceId = :source") fun clearSession(source: String)
}

@Entity(tableName = "reading_progress", primaryKeys = ["sourceId", "mangaId"],
    foreignKeys = [ForeignKey(entity = LocalBook::class, parentColumns = ["sourceId", "mangaId"],
        childColumns = ["sourceId", "mangaId"], onDelete = ForeignKey.CASCADE)])
data class ReadingProgress(val sourceId: String, val mangaId: String, val chapterId: String,
    val pageId: String, val ordinal: Int, val fraction: Float, val mode: String, val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val modeExplicit: Boolean = true)

enum class ReadingMode(val label: String) { LTR("左至右"), RTL("右至左"), VERTICAL("上下逐页"), STRIP("条漫") }

@Entity(tableName = "reading_preferences")
data class ReadingPreference(@PrimaryKey val id: String = "default", val mode: String)

@Entity(tableName = "online_reading_progress", primaryKeys = ["sourceId", "mangaId"])
data class OnlineReadingProgress(val sourceId: String, val mangaId: String, val chapterId: String,
    val pageId: String, val ordinal: Int, val fraction: Float, val mode: String, val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val modeExplicit: Boolean = true)

@Entity(tableName = "online_books", primaryKeys = ["sourceId", "mangaId"])
data class OnlineBook(val sourceId: String, val mangaId: String, val title: String,
    val favorite: Boolean, val updatedAt: Long)

@Entity(tableName = "user_tags", indices = [Index(value = ["nameKey"], unique = true)])
data class UserTag(@PrimaryKey val id: String, val name: String, val nameKey: String)

// World is the containing database; kind distinguishes local and online identities.
@Entity(tableName = "tag_books", primaryKeys = ["tagId", "kind", "sourceId", "mangaId"],
    foreignKeys = [ForeignKey(entity = UserTag::class, parentColumns = ["id"],
        childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)])
data class TagBook(val tagId: String, val kind: String, val sourceId: String, val mangaId: String)

@Entity(tableName = "chapter_reading", primaryKeys = ["sourceId", "mangaId", "chapterId"])
data class ChapterReading(val sourceId: String, val mangaId: String, val chapterId: String,
    val pageId: String?, val ordinal: Int?, val fraction: Float, val updatedAt: Long, val completed: Boolean)

internal const val WORLD_DATABASE_VERSION = 14

@Database(entities = [StoredManga::class, SearchHistory::class, SourceSession::class, LocalBook::class, LocalPage::class, ReadingProgress::class, OnlineReadingProgress::class, OnlineBook::class, UserTag::class, TagBook::class, ReadingPreference::class, BrowseVisit::class, ChapterReading::class, LocalBookmark::class, ReadingTimeRow::class], version = WORLD_DATABASE_VERSION, exportSchema = true)
abstract class WorldDatabase : RoomDatabase() { abstract fun data(): WorldDao }

val WORLD_MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_time (sessionId TEXT NOT NULL, day TEXT NOT NULL, kind TEXT NOT NULL, sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, title TEXT NOT NULL, durationMs INTEGER NOT NULL, PRIMARY KEY(sessionId, day))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_time_day_kind_sourceId_mangaId ON reading_time(day, kind, sourceId, mangaId)")
    }
}

val WORLD_MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        listOf("metadataTitle", "author", "volume", "summary").forEach {
            db.execSQL("ALTER TABLE local_books ADD COLUMN $it TEXT")
        }
    }
}

val WORLD_MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_books ADD COLUMN defaultTitle TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE local_books ADD COLUMN coverPageId TEXT")
        db.execSQL("UPDATE local_books SET defaultTitle = title")
    }
}

val WORLD_MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_bookmarks (id TEXT NOT NULL, sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, pageId TEXT NOT NULL, ordinal INTEGER NOT NULL, fraction REAL NOT NULL, mode TEXT NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceId, mangaId) REFERENCES local_books(sourceId, mangaId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_bookmarks_sourceId_mangaId ON local_bookmarks(sourceId, mangaId)")
    }
}

val WORLD_MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chapter_reading (sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, chapterId TEXT NOT NULL, pageId TEXT, ordinal INTEGER, fraction REAL NOT NULL, updatedAt INTEGER NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(sourceId, mangaId, chapterId))")
        db.execSQL("INSERT INTO chapter_reading (sourceId, mangaId, chapterId, pageId, ordinal, fraction, updatedAt, completed) SELECT sourceId, mangaId, chapterId, pageId, ordinal, fraction, updatedAt, 0 FROM online_reading_progress")
    }
}

val WORLD_MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS browse_visits (kind TEXT NOT NULL, sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, title TEXT NOT NULL, visitedAt INTEGER NOT NULL, PRIMARY KEY(kind, sourceId, mangaId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_browse_visits_visitedAt_kind_sourceId_mangaId ON browse_visits (visitedAt DESC, kind ASC, sourceId ASC, mangaId ASC)")
    }
}

val WORLD_MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_progress ADD COLUMN modeExplicit INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE online_reading_progress ADD COLUMN modeExplicit INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_preferences (id TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(id))")
    }
}

val WORLD_MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS user_tags (id TEXT NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_user_tags_nameKey ON user_tags(nameKey)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tag_books (tagId TEXT NOT NULL, kind TEXT NOT NULL, sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, PRIMARY KEY(tagId, kind, sourceId, mangaId), FOREIGN KEY(tagId) REFERENCES user_tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}

val WORLD_MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS online_books (sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, title TEXT NOT NULL, favorite INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(sourceId, mangaId))")
    }
}

val WORLD_MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS online_reading_progress (sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, chapterId TEXT NOT NULL, pageId TEXT NOT NULL, ordinal INTEGER NOT NULL, fraction REAL NOT NULL, mode TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(sourceId, mangaId))")
    }
}

val WORLD_MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_progress (sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, chapterId TEXT NOT NULL, pageId TEXT NOT NULL, ordinal INTEGER NOT NULL, fraction REAL NOT NULL, mode TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(sourceId, mangaId), FOREIGN KEY(sourceId, mangaId) REFERENCES local_books(sourceId, mangaId) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}

val WORLD_MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_books ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
    }
}

val WORLD_MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_books (sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, title TEXT NOT NULL, archiveName TEXT NOT NULL, sha256 TEXT NOT NULL, archiveBytes INTEGER NOT NULL, importedAt INTEGER NOT NULL, PRIMARY KEY(sourceId, mangaId))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_local_books_sha256 ON local_books(sha256)")
        db.execSQL("CREATE TABLE IF NOT EXISTS local_pages (sourceId TEXT NOT NULL, mangaId TEXT NOT NULL, ordinal INTEGER NOT NULL, entryName TEXT NOT NULL, mimeType TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, PRIMARY KEY(sourceId, mangaId, ordinal), FOREIGN KEY(sourceId, mangaId) REFERENCES local_books(sourceId, mangaId) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}

internal val WORLD_MIGRATIONS = arrayOf(WORLD_MIGRATION_1_2, WORLD_MIGRATION_2_3,
    WORLD_MIGRATION_3_4, WORLD_MIGRATION_4_5, WORLD_MIGRATION_5_6, WORLD_MIGRATION_6_7,
    WORLD_MIGRATION_7_8, WORLD_MIGRATION_8_9, WORLD_MIGRATION_9_10, WORLD_MIGRATION_10_11, WORLD_MIGRATION_11_12, WORLD_MIGRATION_12_13, WORLD_MIGRATION_13_14)
