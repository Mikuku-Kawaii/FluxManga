package com.flux.manga.data

import android.content.Context
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession

/** Shared public book contract. Baozi's existing DTO/cache format stays compatible. */
internal interface PublicBookSource {
    suspend fun search(input: String, page: SourcePageRequest, tag: String? = null, updates: Boolean = false): SourceCardPage
    suspend fun recommendations(page: SourcePageRequest): SourceCardPage
    suspend fun detail(mangaId: String): BaoziBook
    suspend fun chapterPage(mangaId: String, page: SourcePageRequest): SourceChapterPage
    suspend fun pages(mangaId: String, chapterId: String): SourcePages
}

internal suspend fun publicBookSource(app: Context, session: WorldSession, source: String): PublicBookSource =
    if (isPublicWebSource(source)) session.publicWebSources.client(source)
    else session.baoziSources.client(app, source).source

internal fun isPublicBookSource(source: String) = isBaoziSource(source) || isPublicWebSource(source)

internal fun publicChapterGroups(source: String, ids: List<String>): Map<String, List<String>> =
    if (source == PublicWebSite.MANHUAGUI.sourceId) ids.groupBy { it.substringBefore('~') }
    else mapOf("default" to ids)
