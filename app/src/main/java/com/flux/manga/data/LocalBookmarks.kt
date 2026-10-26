package com.flux.manga.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Bookmarks are independent of progress. Removing a local book removes only its own bookmarks. */
@Entity(tableName = "local_bookmarks", indices = [Index(value = ["sourceId", "mangaId"])],
    foreignKeys = [ForeignKey(entity = LocalBook::class, parentColumns = ["sourceId", "mangaId"],
        childColumns = ["sourceId", "mangaId"], onDelete = ForeignKey.CASCADE)])
data class LocalBookmark(@PrimaryKey val id: String, val sourceId: String, val mangaId: String,
    val pageId: String, val ordinal: Int, val fraction: Float, val mode: String, val note: String,
    val createdAt: Long, val updatedAt: Long)

internal const val BOOKMARK_NOTE_LIMIT = 2000

internal class LocalBookmarks(private val data: WorldDataStore, private val session: WorldSession,
    private val manga: String) {
    private val identity = session.identity.value
    fun active() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    private suspend fun <T> access(action: (WorldDao) -> T): T {
        val context = currentCoroutineContext()
        fun check() { context.ensureActive(); if (!active()) throw CancellationException("Expired bookmark session") }
        check()
        val result = data.localData { dao ->
            check()
            if (dao.localBook("cbz", manga) == null) throw IOException("本地书目已不存在")
            action(dao).also { check() } // Check before the containing transaction commits.
        }
        check()
        return result
    }
    suspend fun load(): List<LocalBookmark> = access { it.localBookmarks("cbz", manga) }
    suspend fun find(id: String): LocalBookmark? = access { it.localBookmark("cbz", manga, id) }

    /** Stable draft IDs make retry after an unknown commit outcome idempotent. */
    suspend fun save(value: LocalBookmark, creating: Boolean): List<LocalBookmark> = access { dao ->
        require(value.sourceId == "cbz" && value.mangaId == manga)
        require(value.note.length <= BOOKMARK_NOTE_LIMIT && value.fraction.isFinite() && value.fraction in 0f..0.9999f)
        require(decodeReadingMode(value.mode) != null)
        val page = dao.localPages("cbz", manga).firstOrNull { it.entryName == value.pageId }
            ?: throw IOException("书签对应页面已不存在，请重新打开书目")
        val old = dao.localBookmark("cbz", manga, value.id)
        if (!creating && old == null) throw IOException("此书签已被删除，请取消编辑并刷新")
        val now = System.currentTimeMillis()
        if (old == null) dao.insertLocalBookmark(value.copy(ordinal = page.ordinal, createdAt = now, updatedAt = now))
        else dao.updateLocalBookmark(old.copy(note = value.note, updatedAt = now))
        dao.localBookmarks("cbz", manga)
    }
    suspend fun delete(id: String): List<LocalBookmark> = access { dao ->
        dao.deleteLocalBookmark("cbz", manga, id)
        dao.localBookmarks("cbz", manga)
    }
}
