package com.flux.manga.ui.pages

import com.flux.manga.data.*
import com.flux.manga.ui.navigation.TagTarget

/** Native tag IDs stay at their own source. Other sources search the human-readable label. */
internal data class MixedTagQuery(val target: TagTarget, val keyword: Boolean)

internal fun mixedTagQueries(sources: List<String>, label: String, targets: List<TagTarget>): List<MixedTagQuery> {
    fun feedId(source: String) = when {
        isJmSource(source) -> JM_SOURCE
        isPicaSource(source) -> PrivateCatalog.PICA
        isEhSource(source) -> EH_SOURCE
        isExSource(source) -> PrivateCatalog.EX
        isCopySource(source) -> CopyMangaEndpoint.SOURCE_ID
        isBaoziSource(source) -> BaoziEndpoint.SOURCE_ID
        else -> source
    }
    val native = targets.map { it.copy(sourceId = feedId(it.sourceId)) }.distinct()
    return sources.flatMap { source ->
        val matches = native.filter { it.sourceId == source }
        when {
            matches.isNotEmpty() -> matches.map { MixedTagQuery(it, keyword = false) }
            source == BaoziEndpoint.SOURCE_ID && label in BaoziCategories.tags ->
                listOf(MixedTagQuery(TagTarget(source, label), keyword = false))
            else -> listOf(MixedTagQuery(TagTarget(source, label), keyword = true))
        }
    }
}
