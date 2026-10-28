package com.flux.manga.data

import android.content.Context
import androidx.room.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import java.io.File

@Entity(tableName = "browse_visits", primaryKeys = ["kind", "sourceId", "mangaId"],
    indices = [Index(value = ["visitedAt", "kind", "sourceId", "mangaId"],
        orders = [Index.Order.DESC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC])])
data class BrowseVisit(val kind: String, val sourceId: String, val mangaId: String, val title: String, val visitedAt: Long) {
    @get:Ignore val stableKey get() = listOf(kind, sourceId, mangaId).joinToString("") { "${it.length}:$it" }
}

internal enum class VisitAvailability(val message: String) {
    AVAILABLE(""), MISSING("本地书目或应用副本已不可用"), SOURCE_CHANGED("原图源已失效，请在设置中检查"),
    SOURCE_UNKNOWN("图源状态读取失败，可重试"), PRIVATE("当前空间真实内容尚未开放")
}
internal data class VisitItem(val visit: BrowseVisit, val availability: VisitAvailability)
internal data class VisitBatch(val items: List<VisitItem>, val more: Boolean)

internal fun onlineHistoryScope(sourceId: String) = "online:$sourceId"
internal fun historySource(scope: String): String? = scope.takeIf { it.startsWith("online:") }?.removePrefix("online:")?.takeIf { it.isNotBlank() }
internal fun searchHistoryEntry(sourceId: String, query: String, time: Long): SearchHistory? =
    query.trim().take(200).takeIf { it.isNotEmpty() && sourceId.isNotBlank() }?.let { SearchHistory(onlineHistoryScope(sourceId), it, time) }

/** Uses the same world DB and generation as pages. Never writes reading progress or book ownership. */
internal class BrowsingHistory(private val context: Context, private val session: WorldSession,
    private val data: WorldDataStore = WorldDataStore(context)) {
    private val identity = session.identity.value
    private val local = CbzLibrary(data)
    private val online = OnlineLibrary(context, session)
    private fun active() { if (!session.accepts(identity)) throw CancellationException("Expired history session") }

    suspend fun record(kind: String, source: String, manga: String) {
        active()
        // Private visits use the encrypted world DB; synthetic and unrelated sources stay excluded.
        if (identity.world != PrototypeWorld.SFW && (kind != "online" || !isPrivateComicSource(source))) return
        require(kind == "local" || kind == "online")
        data.worldData(identity.world, { session.accepts(identity) }) { dao ->
            active()
            val title = if (kind == "local") dao.localBook(source, manga)?.title else dao.onlineBook(source, manga)?.title
            val previous = dao.visit(kind, source, manga)
            dao.putVisit(BrowseVisit(kind, source, manga, title?.takeIf { it.isNotBlank() }
                ?: previous?.title ?: "作品 $manga（书名待更新）", System.currentTimeMillis()))
        }
    }
    suspend fun page(limit: Int, after: BrowseVisit? = null): VisitBatch {
        active()
        var configFailed = false
        val config = if (identity.world == PrototypeWorld.SFW) try { KomgaSettingsStore(data).load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { configFailed = true; null } else null
        var copyFailed = false
        val copy = if (identity.world == PrototypeWorld.SFW) try { CopyAccountStore(data.accountImportDatabase()).current { session.accepts(identity) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { copyFailed = true; null } else null
        var jmFailed = false
        val jm = if (identity.world == PrototypeWorld.NSFW) try { JmStore(context, session).settings().account }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { jmFailed = true; null } else null
        var picaFailed = false
        val pica = if (identity.world == PrototypeWorld.NSFW) try { PicaStore(context, session).account() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { picaFailed = true; null } else null
        var ehFailed = false
        val eh = if (identity.world == PrototypeWorld.NSFW) try { EhStore(context, session).settings().account }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ehFailed = true; null } else null
        var exFailed = false
        val ex = if (identity.world == PrototypeWorld.NSFW) try { EhStore(context, session, GallerySite.EX).settings().account }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { exFailed = true; null } else null
        val result = data.worldData(identity.world, { session.accepts(identity) }) { dao ->
            active()
            val rows = dao.visits(after?.visitedAt, after?.kind.orEmpty(), after?.sourceId.orEmpty(), after?.mangaId.orEmpty(), limit + 1)
            VisitBatch(rows.take(limit).map { row ->
                val availability = when {
                    identity.world == PrototypeWorld.NSFW && isExSource(row.sourceId) -> when {
                        exFailed -> VisitAvailability.SOURCE_UNKNOWN
                        ex != null && row.sourceId == GallerySite.EX.source(ex) -> VisitAvailability.AVAILABLE
                        else -> VisitAvailability.SOURCE_CHANGED
                    }
                    identity.world == PrototypeWorld.NSFW && isEhSource(row.sourceId) -> when {
                        ehFailed -> VisitAvailability.SOURCE_UNKNOWN
                        row.sourceId == EH_SOURCE || row.sourceId == eh?.source -> VisitAvailability.AVAILABLE
                        else -> VisitAvailability.SOURCE_CHANGED
                    }
                    identity.world == PrototypeWorld.NSFW && isPicaSource(row.sourceId) -> when {
                        picaFailed -> VisitAvailability.SOURCE_UNKNOWN
                        row.sourceId == pica?.source -> VisitAvailability.AVAILABLE
                        else -> VisitAvailability.SOURCE_CHANGED
                    }
                    identity.world == PrototypeWorld.NSFW && isJmSource(row.sourceId) -> when {
                        jmFailed -> VisitAvailability.SOURCE_UNKNOWN
                        row.sourceId == JM_SOURCE || row.sourceId == jm?.source -> VisitAvailability.AVAILABLE
                        else -> VisitAvailability.SOURCE_CHANGED
                    }
                    identity.world != PrototypeWorld.SFW -> VisitAvailability.PRIVATE
                    row.kind == "local" -> dao.localBook(row.sourceId, row.mangaId)?.let {
                        if (File(local.directory, it.archiveName).isFile) VisitAvailability.AVAILABLE else VisitAvailability.MISSING
                    } ?: VisitAvailability.MISSING
                    isBaoziSource(row.sourceId) || row.sourceId == CopyMangaEndpoint.SOURCE_ID -> VisitAvailability.AVAILABLE
                    isCopyAccount(row.sourceId) -> when {
                        copyFailed -> VisitAvailability.SOURCE_UNKNOWN
                        copy?.sourceId == row.sourceId && copy.hasCredential -> VisitAvailability.AVAILABLE
                        else -> VisitAvailability.SOURCE_CHANGED
                    }
                    configFailed -> VisitAvailability.SOURCE_UNKNOWN
                    config?.sourceId != row.sourceId -> VisitAvailability.SOURCE_CHANGED
                    else -> VisitAvailability.AVAILABLE
                }
                VisitItem(row, availability)
            }, rows.size > limit)
        }
        active(); return result
    }
    suspend fun remove(row: BrowseVisit?) = data.worldData(identity.world, { session.accepts(identity) }) {
        active()
        if (row == null) it.clearVisits() else it.deleteVisit(row.kind, row.sourceId, row.mangaId)
    }
    fun cachedCover(row: BrowseVisit): android.graphics.Bitmap? =
        if (session.accepts(identity)) session.pageCovers().peek(row.sourceId, row.mangaId) else null

    suspend fun cover(row: BrowseVisit): android.graphics.Bitmap? {
        active()
        if (identity.world == PrototypeWorld.NSFW) return if (isPrivateComicSource(row.sourceId)) online.cover(row.sourceId, row.mangaId) else null
        return if (row.kind == "local") session.pageCovers().getOrNull(row.sourceId, row.mangaId) {
            local.cover(MangaKey(identity.world, row.sourceId, row.mangaId))
        }
        else online.cover(row.sourceId, row.mangaId)
    }
    suspend fun searches(): List<SearchHistory> {
        val rows = data.worldData(identity.world, { session.accepts(identity) }) { active(); it.onlineSearchHistory() }
        active(); return rows
    }
    suspend fun submit(source: String, query: String) {
        active()
        if (identity.world != PrototypeWorld.SFW) return
        val entry = searchHistoryEntry(source, query, System.currentTimeMillis()) ?: return
        data.worldData(identity.world, { session.accepts(identity) }) { active(); it.remember(entry); it.trimOnlineSearchHistory() }
    }
    suspend fun removeSearch(row: SearchHistory?) = data.worldData(identity.world, { session.accepts(identity) }) {
        active()
        if (row == null) it.clearOnlineSearchHistory() else if (historySource(row.sourceId) != null) it.deleteSearchHistory(row.sourceId, row.query)
    }
}
