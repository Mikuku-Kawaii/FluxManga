package com.flux.manga.ui.pages

import android.app.Application
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Discovery only: never removes library rows or changes favorites/progress. */
internal class TagBlacklistFilter(private val app: Application, private val session: WorldSession) {
    private val store = TagBlacklist(app, session)
    private val information = OnlineBookInformationLoader(app, session)
    companion object { private val requests = Semaphore(2) }

    suspend fun <T> items(source: String, items: List<T>, id: (T) -> String, title: (T) -> String): List<T> {
        val world = session.identity.value.world
        val cards = items.map { MangaCard(MangaKey(world, source, id(it)), title(it), null, true) }
        val kept = apply(SourceCardPage(cards, com.flux.manga.core.SourceContinuation.End)).cards.map { it.key.mangaId }.toSet()
        return items.filter { id(it) in kept }
    }

    suspend fun apply(page: SourceCardPage): SourceCardPage {
        val blocked = store.read().map(::normalizedBlockedTag).toSet()
        if (blocked.isEmpty() || page.cards.isEmpty()) return page
        val cards = coroutineScope {
            page.cards.map { card -> async {
                requests.withPermit {
                    val key = card.key
                    val cache = CatalogPageCache(app, session, "discovery-tags:${key.sourceId.length}:${key.sourceId}${key.mangaId}")
                    val saved = cache.read()?.getJSONArray("tags")
                    val tags = if (saved != null) List(saved.length()) { saved.getString(it) } else {
                        val result = information.load(key.sourceId, key.mangaId, loadCover = false, loadChapters = false)
                        if (result.metadataError != null || !result.info.detailsReady)
                            throw IOException("标签读取失败，暂未显示此来源的作品，请重试")
                        result.info.tags.also { cache.write(JSONObject().put("tags", JSONArray(it))) }
                    }
                    card.takeUnless { tags.any { tag ->
                        val value = normalizedBlockedTag(tag)
                        value in blocked || (':' in value && value.substringAfter(':').trim() in blocked)
                    } }
                }
            } }.awaitAll().filterNotNull()
        }
        // Keep the source cursor even when every item on this page is blocked.
        return page.copy(cards = cards)
    }
}
