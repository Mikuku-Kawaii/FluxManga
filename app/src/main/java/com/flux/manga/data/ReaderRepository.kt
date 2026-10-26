package com.flux.manga.data

import android.graphics.Bitmap
import com.flux.manga.core.*

/** Shared reader behavior; choosing an implementation never grants source access. */
internal interface ReaderRepository {
    fun close() {}
    val mangaKey: SourceMangaKey
    fun isActive(): Boolean
    fun active()
    suspend fun pages(chapterId: String): List<SourcePageResource>
    suspend fun chapters(request: SourcePageRequest): SourceChapterPage
    suspend fun completeOfflineCatalog(): List<SourceChapter>
    suspend fun offlineIds(): Set<String>
    suspend fun progress(): OnlineReadingProgress?
    suspend fun chapterProgress(chapter: String): ChapterReading?
    suspend fun save(page: SourcePageResource, mode: ReadingMode, fraction: Float, modeExplicit: Boolean = true)
    suspend fun dimensions(page: SourcePageResource): Pair<Int, Int>
    suspend fun image(page: SourcePageResource, width: Int, top: Int = 0, bottom: Int? = null): Bitmap
    suspend fun clearImages()
    /** Final reader exit, unlike a chapter change or temporary background suspension. */
    suspend fun releaseImages() = clearImages()
}
