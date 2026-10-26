package com.flux.manga.data

import com.flux.manga.core.SourcePageResource
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caller owns the WorldDataStore transaction. Neither a page jump nor a chapter switch implies completion. */
internal fun saveChapterProgress(dao: WorldDao, progress: OnlineReadingProgress) {
    val old = dao.chapterReading(progress.sourceId, progress.mangaId, progress.chapterId)
    dao.putOnlineProgress(progress)
    dao.putChapterReading(ChapterReading(progress.sourceId, progress.mangaId, progress.chapterId,
        progress.pageId, progress.ordinal, progress.fraction, progress.updatedAt, old?.completed == true))
}

internal fun markChapter(dao: WorldDao, source: String, manga: String, chapter: String, completed: Boolean) {
    val old = dao.chapterReading(source, manga, chapter)
        ?: ChapterReading(source, manga, chapter, null, null, 0f, 0L, false)
    dao.putChapterReading(old.copy(completed = completed))
}

internal fun chapterReadingAnchor(pages: List<SourcePageResource>, record: ChapterReading?): Pair<Int, Float> {
    if (record?.pageId == null || record.ordinal == null) return 0 to 0f
    return onlineReadingAnchor(pages, OnlineReadingProgress(record.sourceId, record.mangaId, record.chapterId,
        record.pageId, record.ordinal, record.fraction, "", record.updatedAt))
}

/** An explicit directory selection resumes; sequential navigation keeps its original page-boundary semantics. */
internal fun chapterEntryAnchor(pages: List<SourcePageResource>, record: ChapterReading?, resume: Boolean,
    atEnd: Boolean, mode: ReadingMode): Pair<Int, Float> = when {
    pages.isEmpty() -> 0 to 0f
    resume -> chapterReadingAnchor(pages, record)
    atEnd -> pages.lastIndex to if (mode == ReadingMode.STRIP) .9999f else 0f
    else -> 0 to 0f
}

internal fun chapterReadingLabel(record: ChapterReading?): String? = record?.let {
    listOfNotNull(if (it.completed) "已读（手动标记）" else null,
        it.ordinal?.let { page -> "读到第 $page 页" }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal class ChapterReadings(private val data: WorldDataStore, private val session: WorldSession,
    private val source: String, private val manga: String) {
    private val identity = session.identity.value
    fun active() = (identity.world == PrototypeWorld.SFW || isPrivateComicSource(source)) && session.accepts(identity)
    private fun check() { if (!active()) throw CancellationException("Expired chapter reading session") }
    suspend fun load(): Map<String, ChapterReading> {
        val context = currentCoroutineContext()
        return data.worldData(identity.world, ::active) { dao ->
            context.ensureActive(); check()
            dao.chapterReadings(source, manga).associateBy { it.chapterId }.also { context.ensureActive(); check() }
        }
    }
    suspend fun mark(chapter: String, completed: Boolean): Map<String, ChapterReading> {
        require(chapter.isNotBlank())
        val context = currentCoroutineContext()
        return data.worldData(identity.world, ::active) { dao ->
            context.ensureActive(); check()
            markChapter(dao, source, manga, chapter, completed)
            dao.chapterReadings(source, manga).associateBy { it.chapterId }.also { context.ensureActive(); check() }
        }
    }
}
