package com.flux.manga.data

import androidx.room.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException

/** A cumulative checkpoint, not an increment: retries cannot count the same time twice. */
@Entity(tableName = "reading_time", primaryKeys = ["sessionId", "day"],
    indices = [Index(value = ["day", "kind", "sourceId", "mangaId"])])
data class ReadingTimeRow(val sessionId: String, val day: String, val kind: String,
    val sourceId: String, val mangaId: String, val title: String, val durationMs: Long)

data class ReadingTotal(val durationMs: Long, val days: Int, val books: Int)
data class ReadingDay(val day: String, val durationMs: Long, val books: Int)
data class ReadingWork(val kind: String, val sourceId: String, val mangaId: String,
    val title: String, val durationMs: Long, val days: Int, val lastDay: String)

// Null filters are deliberate: a drill-down retains the selected range and book/day identity.
private const val TIME_FILTER = "day >= :since AND (:day IS NULL OR day = :day) AND " +
    "(:kind IS NULL OR (kind = :kind AND sourceId = :source AND mangaId = :manga))"

@Dao
interface ReadingStatisticsDao {
    @Query("SELECT * FROM reading_time WHERE sessionId = :session AND day = :day")
    fun checkpoint(session: String, day: String): ReadingTimeRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: ReadingTimeRow)

    @Query("SELECT COALESCE(SUM(durationMs), 0) AS durationMs, COUNT(DISTINCT day) AS days, " +
        "(SELECT COUNT(*) FROM (SELECT kind, sourceId, mangaId FROM reading_time WHERE " + TIME_FILTER +
        " GROUP BY kind, sourceId, mangaId)) AS books FROM reading_time WHERE " + TIME_FILTER)
    fun total(since: String, day: String?, kind: String?, source: String?, manga: String?): ReadingTotal

    @Query("SELECT day, SUM(durationMs) AS durationMs, COUNT(*) AS books FROM " +
        "(SELECT day, kind, sourceId, mangaId, SUM(durationMs) AS durationMs FROM reading_time WHERE " +
        TIME_FILTER + " GROUP BY day, kind, sourceId, mangaId) GROUP BY day ORDER BY day DESC LIMIT :limit OFFSET :offset")
    fun days(since: String, day: String?, kind: String?, source: String?, manga: String?, limit: Int, offset: Int): List<ReadingDay>

    @Query("SELECT t.kind, t.sourceId, t.mangaId, " +
        "COALESCE(CASE WHEN t.kind = 'local' THEN (SELECT title FROM local_books b WHERE b.sourceId = t.sourceId AND b.mangaId = t.mangaId) " +
        "ELSE (SELECT title FROM online_books b WHERE b.sourceId = t.sourceId AND b.mangaId = t.mangaId) END, MAX(t.title)) AS title, " +
        "SUM(t.durationMs) AS durationMs, COUNT(DISTINCT t.day) AS days, MAX(t.day) AS lastDay " +
        "FROM reading_time t WHERE " + TIME_FILTER +
        " GROUP BY t.kind, t.sourceId, t.mangaId ORDER BY durationMs DESC, t.kind, t.sourceId, t.mangaId LIMIT :limit OFFSET :offset")
    fun works(since: String, day: String?, kind: String?, source: String?, manga: String?, limit: Int, offset: Int): List<ReadingWork>
}

internal data class ReadingFilter(val since: String = "0000-01-01", val day: String? = null,
    val kind: String? = null, val source: String? = null, val manga: String? = null)
internal data class ReadingStatisticsSnapshot(val total: ReadingTotal, val days: List<ReadingDay>, val works: List<ReadingWork>)

internal fun newerReadingCheckpoint(old: ReadingTimeRow?, next: ReadingTimeRow): Boolean {
    require(next.kind in setOf("local", "online") && next.durationMs > 0)
    require(old == null || (old.sessionId == next.sessionId && old.day == next.day &&
        old.kind == next.kind && old.sourceId == next.sourceId && old.mangaId == next.mangaId))
    return old == null || next.durationMs > old.durationMs
}

internal class ReadingStatistics(private val store: WorldDataStore, private val session: WorldSession) {
    private val identity = session.identity.value
    suspend fun save(rows: List<ReadingTimeRow>) = store.worldData(identity.world, { session.accepts(identity) }) { stats ->
        rows.forEach { row ->
            val old = stats.checkpoint(row.sessionId, row.day)
            if (newerReadingCheckpoint(old, row)) {
                val title = if (row.kind == "local") stats.localBook(row.sourceId, row.mangaId)?.title
                    else stats.onlineBook(row.sourceId, row.mangaId)?.title
                stats.put(row.copy(title = title ?: old?.title ?: row.title))
            }
        }
    }
    suspend fun read(filter: ReadingFilter, limit: Int): ReadingStatisticsSnapshot {
        val result = store.worldData(identity.world, { session.accepts(identity) }) { stats ->
            with(filter) { ReadingStatisticsSnapshot(stats.total(since, day, kind, source, manga),
                stats.days(since, day, kind, source, manga, limit, 0),
                stats.works(since, day, kind, source, manga, limit, 0)) }
        }
        if (!session.accepts(identity)) throw CancellationException("Expired statistics session")
        return result
    }
}
