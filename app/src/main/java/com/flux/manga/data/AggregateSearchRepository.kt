package com.flux.manga.data

import android.content.Context
import com.flux.manga.core.SourcePageRequest
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*

internal fun aggregateSearchSources(world: PrototypeWorld, komgaId: String?) =
    if (world == PrototypeWorld.NSFW) PrivateCatalog.feeds
    else listOfNotNull(komgaId, CopyMangaEndpoint.SOURCE_ID, BaoziEndpoint.SOURCE_ID)

/** Thin adapter over the existing source clients; no global client, credential or database owner. */
internal class AggregateSearchRepository(private val context: Context, private val session: WorldSession, komgaId: String?) {
    private val identity = session.identity.value
    private val privateCatalog = if (identity.world == PrototypeWorld.NSFW) PrivateCatalog(context, session) else null
    val sources = aggregateSearchSources(identity.world, komgaId).filter(SourceSelection(context, identity.world)::enabled)
    private val komga = komgaId?.let { KomgaAccess(context, session, it) }
    fun isActive() = session.accepts(identity)
    suspend fun search(source: String, query: String, page: SourcePageRequest): SourceCardPage {
        currentCoroutineContext().ensureActive()
        if (!isActive()) throw CancellationException("Expired search session")
        val author = authorSearchName(query)
        val keyword = author ?: query
        if (privateCatalog != null) {
            val privateQuery = if (author != null && (source == EH_SOURCE || source == PrivateCatalog.EX))
                "artist:\"${author.replace("\"", "")}\"" else keyword
            return privateCatalog.load(source, privateQuery, page)
        }
        val result = if (source == BaoziEndpoint.SOURCE_ID) {
            val client = withContext(Dispatchers.IO) { session.baoziSources.client(context) }
            client.source.search(keyword, page)
        } else if (source == CopyMangaEndpoint.SOURCE_ID) {
            val client = withContext(Dispatchers.IO) { session.copyMangaSources.client() }
            client.search(query, page)
        } else {
            val access = requireNotNull(komga)
            require(source == access.sourceId)
            access.client().search(keyword, page)
        }
        currentCoroutineContext().ensureActive()
        if (!isActive()) throw CancellationException("Expired search session")
        return result
    }
}
