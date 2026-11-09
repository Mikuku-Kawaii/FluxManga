package com.flux.manga.ui.pages

import android.app.Application
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*

internal data class TagFilteredItems<T>(val items: List<T>, val notice: String?, val incomplete: Boolean = false)

/** Discovery only. Titles match locally; unknown tags never hide a book on their own. */
internal class TagBlacklistFilter(private val app: Application, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(app)
    private val store = TagBlacklist(app, session)
    private val information = OnlineBookInformationStore(data, session)

    suspend fun <T> items(source: String, items: List<T>, id: (T) -> String, title: (T) -> String,
        tags: (T) -> List<String>? = { null }): TagFilteredItems<T> {
        val cards = items.map { MangaCard(MangaKey(identity.world, source, id(it)), title(it), null, true, sourceTags = tags(it)) }
        val result = apply(SourceCardPage(cards, com.flux.manga.core.SourceContinuation.End))
        val kept = result.cards.map { it.key.mangaId }.toSet()
        return TagFilteredItems(items.filter { id(it) in kept }, result.notice, result.filterIncomplete)
    }

    suspend fun apply(page: SourceCardPage): SourceCardPage = withContext(Dispatchers.IO) {
        val tagRules = store.read()
        val blocked = if (tagRules.enabled) tagRules.tags.map(::normalizedBlockedTag).toSet() else emptySet()
        val titleRules = store.readTitles()
        val keywords = if (titleRules.enabled) titleRules.keywords.map(::normalizedBlockedTag).filter(String::isNotEmpty) else emptyList()
        if ((blocked.isEmpty() && keywords.isEmpty()) || page.cards.isEmpty()) return@withContext page
        val candidates = if (keywords.isEmpty()) page.cards else page.cards.filterNot { card ->
            val title = normalizedBlockedTag(card.title)
            keywords.any { title.contains(it) }
        }
        if (blocked.isEmpty()) return@withContext page.copy(cards = candidates, unfilteredCards = page.cards)
        val unknown = candidates.filter { it.sourceTags == null }.map { it.key }
        val saved = if (unknown.isEmpty()) emptyMap() else try { information.readTags(unknown) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyMap() }
        // Reuse previous tag caches with one configuration read per page, never one per book.
        val cacheOwner = if (unknown.any { it !in saved }) try { catalogConfiguration(data, session, includeBlacklist = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } else null
        val cards = candidates.mapNotNull { card ->
            currentCoroutineContext().ensureActive()
            if (!session.accepts(identity) || !session.state.value.foreground) throw CancellationException()
            val tags = card.sourceTags ?: saved[card.key] ?: if (cacheOwner == null) null else try {
                val key = card.key
                CatalogPageCache(app, session, "discovery-tags:${key.sourceId.length}:${key.sourceId}${key.mangaId}",
                    includeBlacklist = false).read(configuration = cacheOwner)?.getJSONArray("tags")?.let { rows ->
                    List(rows.length()) { rows.getString(it) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val blockedWork = tags?.any { tag ->
                val value = normalizedBlockedTag(tag)
                value in blocked || (':' in value && value.substringAfter(':').trim() in blocked)
            } == true
            if (blockedWork) null else card.copy(sourceTags = tags)
        }
        page.copy(cards = cards, filterIncomplete = false, unfilteredCards = page.cards)
    }
}
