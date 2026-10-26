package com.flux.manga.data

import com.flux.manga.ui.navigation.PrototypeWorld
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun tagName(input: String): String = input.trim().also {
    require(it.isNotEmpty()) { "请输入分组名称" }
    require(it.codePointCount(0, it.length) <= 40 && it.none(Char::isISOControl)) { "分组名称最多 40 字，不能包含换行或控制字符" }
}
internal fun tagNameKey(name: String) = tagName(name).lowercase(Locale.ROOT)
internal data class TagSnapshot(val tags: List<UserTag>, val links: List<TagBook>)

/** User-owned metadata only. Source tags never enter these tables. */
internal class UserTags(private val store: WorldDataStore, private val world: PrototypeWorld,
                        private val allowed: () -> Boolean) {
    private suspend fun <T> transaction(action: (WorldDao) -> T): T {
        val job = currentCoroutineContext()
        fun checkSession() {
            job.ensureActive()
            if (!allowed()) throw CancellationException("Expired tag session")
        }
        checkSession()
        return store.worldData(world, allowed) { dao ->
            checkSession()
            action(dao).also { checkSession() } // Roll back if invalidated before commit.
        }
    }
    suspend fun snapshot() = transaction { TagSnapshot(it.tags(), it.tagBooks()) }
    suspend fun save(id: String, input: String, creating: Boolean) {
        val name = tagName(input)
        val normalized = tagNameKey(name)
        transaction { dao ->
            require(dao.tags().none { it.nameKey == normalized && it.id != id }) { "当前空间已有同名分组" }
            val row = UserTag(id, name, normalized)
            if (dao.tag(id) != null) dao.updateTag(row)
            else {
                require(creating) { "分组已不存在，请返回分组管理" }
                dao.insertTag(row) // Stable ID makes a retried create idempotent.
            }
        }
    }
    suspend fun delete(id: String) = transaction { it.deleteTag(id) }
    suspend fun associate(id: String, key: MangaKey, online: Boolean, selected: Boolean) =
        associateMany(id, listOf(GroupBookKey(key, online)), selected)
    suspend fun associateMany(id: String, books: List<GroupBookKey>, selected: Boolean) {
        require(books.all { it.key.world == world && (world == PrototypeWorld.SFW || it.online && isPrivateComicSource(it.key.sourceId)) }) { "作品空间或来源不匹配" }
        transaction { applyGroupMembership(it, id, books, selected) }
    }
}

internal data class GroupBookKey(val key: MangaKey, val online: Boolean)
/** Validate the whole requested set before writing; caller owns the database transaction. */
internal fun applyGroupMembership(dao: WorldDao, id: String, books: List<GroupBookKey>, selected: Boolean) {
    require(dao.tag(id) != null) { "分组已不存在，请刷新" }
    val distinct = books.distinct()
    require(distinct.size <= 10000) { "一次最多编辑 10000 部作品" }
    distinct.forEach { (key, online) ->
        require(key.world == PrototypeWorld.SFW || online && isPrivateComicSource(key.sourceId)) { "作品空间或来源不匹配" }
        val exists = if (online) dao.onlineBook(key.sourceId, key.mangaId) != null || dao.onlineProgress(key.sourceId, key.mangaId) != null
            else dao.localBook(key.sourceId, key.mangaId) != null
        require(exists) { "书目已不存在，请刷新书库" }
    }
    distinct.forEach { (key, online) ->
        val kind = if (online) "online" else "local"
        if (selected) dao.linkTag(TagBook(id, kind, key.sourceId, key.mangaId))
        else dao.unlinkTag(id, kind, key.sourceId, key.mangaId)
    }
}
