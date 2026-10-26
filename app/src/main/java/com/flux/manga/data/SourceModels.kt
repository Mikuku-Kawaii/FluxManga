package com.flux.manga.data

import com.flux.manga.core.SourceContext
import com.flux.manga.core.SourceContinuation
import com.flux.manga.core.SourceManga
import com.flux.manga.core.SourceMangaKey
import com.flux.manga.core.SourcePageResource
import com.flux.manga.core.SourceTagPage
import com.flux.manga.core.SourceSearchPage
import com.flux.manga.core.SourceWorld
import com.flux.manga.session.SessionIdentity
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException

/** Only opaque credential references cross this boundary; no Cookie/Token is stored here. */
internal fun SessionIdentity.sourceContext(sourceId: String, authRef: String? = null): SourceContext {
    require(world == PrototypeWorld.SFW) { "M3 sources are public-space only" }
    require(sourceId.isNotBlank() && generation >= 0)
    return SourceContext(SourceWorld.SFW, sourceId, processSession, generation.toULong(), authRef)
}

internal data class SourceCardPage(val cards: List<MangaCard>, val continuation: SourceContinuation)

internal data class SourceTagList(val tags: List<String>, val continuation: SourceContinuation)

/** Reuses the M2 identity/card. Remote chapter/page DTOs stay typed transport data until
 * the network reader exists; they are not disguised as CBZ entries or persisted locally. */
internal class SourceModelMapper(
    private val identity: SessionIdentity,
    private val context: SourceContext,
    private val accepts: (SessionIdentity) -> Boolean,
) {
    init { require(context == identity.sourceContext(context.sourceId, context.authRef)) }

    private fun active(reply: SourceContext = context) {
        if (!accepts(identity)) throw CancellationException("Expired source session")
        require(reply == context) { "Source response context mismatch" }
    }

    fun key(value: SourceMangaKey): MangaKey {
        active()
        require(value.world == context.world && value.sourceId == context.sourceId && value.mangaId.isNotBlank()) {
            "Source response identity mismatch"
        }
        return MangaKey(identity.world, value.sourceId, value.mangaId)
    }

    fun card(value: SourceManga): MangaCard {
        val key = key(value.key)
        val count = value.pageCount?.also { require(it <= Int.MAX_VALUE.toUInt()) { "Source page count exceeds model range" } }?.toInt()
        return MangaCard(key, value.title, count, available = true)
    }

    fun tags(reply: SourceTagPage): SourceTagList {
        active(reply.context)
        require(reply.items.all { it.isNotBlank() })
        return SourceTagList(reply.items, reply.continuation)
    }

    fun cards(reply: SourceSearchPage): SourceCardPage {
        active(reply.context)
        return SourceCardPage(reply.items.map(::card), reply.continuation)
    }

    fun pageKey(page: SourcePageResource, expected: MangaKey, chapterId: String): MangaKey {
        active(page.context)
        val key = key(page.manga)
        require(key == expected && page.chapterId == chapterId && chapterId.isNotBlank() && page.id.isNotBlank() && page.ordinal > 0u) {
            "Source page ownership mismatch"
        }
        return key
    }
}
