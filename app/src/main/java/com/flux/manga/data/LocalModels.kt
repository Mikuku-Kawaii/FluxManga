package com.flux.manga.data

import com.flux.manga.ui.navigation.PrototypeWorld

data class MangaKey(val world: PrototypeWorld, val sourceId: String, val mangaId: String)
// Online catalogs may omit a total; null must not be presented as a zero-page book.
data class MangaCard(val key: MangaKey, val title: String, val pageCount: Int?, val available: Boolean, val favorite: Boolean = false, val progress: ReadingProgress? = null,
                     val importedAt: Long? = null, val localMetadata: ComicInfo? = null, val author: String = "")
data class MangaDetail(val card: MangaCard, val chapter: Chapter, val archiveBytes: Long, val sha256: String,
    val defaultTitle: String = card.title, val coverPageId: String? = null,
    val metadata: ComicInfo = ComicInfo())
data class Chapter(val manga: MangaKey, val id: String, val pages: List<PageResource>)
data class PageResource(val manga: MangaKey, val chapterId: String, val id: String, val ordinal: Int,
                        val entryName: String, val mimeType: String, val width: Int, val height: Int)
