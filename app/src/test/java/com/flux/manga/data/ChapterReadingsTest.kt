package com.flux.manga.data

import com.flux.manga.core.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ChapterReadingsTest {
    private val progress = OnlineReadingProgress("source-a", "same", "ch1", "page2", 2, .6f, "STRIP", 123L, true)
    private class Rows {
        val records = mutableMapOf<Triple<String, String, String>, ChapterReading>()
        val latest = mutableMapOf<Pair<String, String>, OnlineReadingProgress>()
        val dao = Proxy.newProxyInstance(WorldDao::class.java.classLoader, arrayOf(WorldDao::class.java)) { _, method, args ->
            val a = args.orEmpty()
            when (method.name) {
                "chapterReading" -> records[Triple(a[0], a[1], a[2])]
                "putChapterReading" -> { val r = a[0] as ChapterReading; records[Triple(r.sourceId, r.mangaId, r.chapterId)] = r; null }
                "putOnlineProgress" -> { val r = a[0] as OnlineReadingProgress; latest[r.sourceId to r.mangaId] = r; null }
                else -> error("Unexpected DAO operation: ${method.name}")
            }
        } as WorldDao
    }
    @Test fun switchingChaptersRetainsBothAnchorsAndExistingWorkMode() {
        val rows = Rows()
        saveChapterProgress(rows.dao, progress)
        val next = progress.copy(chapterId = "ch2", pageId = "end", ordinal = 98)
        saveChapterProgress(rows.dao, next)
        assertEquals(2, rows.records.size)
        assertEquals(.6f, rows.records[Triple("source-a", "same", "ch1")]!!.fraction)
        assertEquals(next, rows.latest["source-a" to "same"])
        assertTrue(rows.records.values.none { it.completed })
    }
    @Test fun explicitMarkAndUndoNeverMoveResumeOrCreateFakePage() {
        val rows = Rows()
        saveChapterProgress(rows.dao, progress)
        markChapter(rows.dao, "source-a", "same", "ch2", true)
        val marked = rows.records[Triple("source-a", "same", "ch2")]!!
        assertTrue(marked.completed); assertNull(marked.pageId); assertNull(marked.ordinal)
        assertEquals("已读（手动标记）", chapterReadingLabel(marked))
        markChapter(rows.dao, "source-a", "same", "ch2", false)
        assertEquals(marked.copy(completed = false), rows.records[Triple("source-a", "same", "ch2")])
        assertEquals(progress, rows.latest["source-a" to "same"])
    }
    @Test fun readingAfterMarkKeepsCompletionAndUndoKeepsAnchor() {
        val rows = Rows()
        markChapter(rows.dao, "source-a", "same", "ch1", true)
        saveChapterProgress(rows.dao, progress)
        val marked = rows.records.values.single()
        assertTrue(marked.completed); assertEquals("page2", marked.pageId)
        markChapter(rows.dao, "source-a", "same", "ch1", false)
        assertEquals(marked.copy(completed = false), rows.records.values.single())
        assertEquals(progress, rows.latest.values.single())
    }
    @Test fun identicalIdsAcrossSourcesAndWorksStayIndependent() {
        val rows = Rows()
        listOf(progress, progress.copy(sourceId = "source-b"), progress.copy(mangaId = "other")).forEach { saveChapterProgress(rows.dao, it) }
        markChapter(rows.dao, "source-a", "same", "ch1", true)
        assertEquals(3, rows.records.size)
        assertEquals(1, rows.records.values.count { it.completed })
        assertEquals(3, rows.latest.size)
    }
    private val context = SourceContext(SourceWorld.SFW, "source-a", "test", 1uL, null)
    private val key = SourceMangaKey(SourceWorld.SFW, "source-a", "same")
    private val pages = listOf("page1", "page2").mapIndexed { i, id -> SourcePageResource(key, "ch1", id, (i + 1).toUInt(), "test:$id", null, null, null, context) }
    private val record = ChapterReading("source-a", "same", "ch1", "page2", 2, .6f, 123L, false)
    @Test fun resumeUsesStablePageIdentityAndPreservesFractionOnReorder() {
        assertEquals(1 to .6f, chapterReadingAnchor(pages, record))
        assertEquals(0 to .6f, chapterReadingAnchor(pages.reversed(), record))
    }
    @Test fun missingPageForeignIdentityAndMarkOnlyNeverReusePosition() {
        for (r in listOf(null, record.copy(sourceId = "source-b"), record.copy(mangaId = "other"),
            record.copy(chapterId = "other"), record.copy(pageId = "missing"), record.copy(pageId = null, ordinal = null, completed = true))) {
            assertEquals(0 to 0f, chapterReadingAnchor(pages, r))
        }
        assertEquals(1 to 0f, chapterReadingAnchor(pages, record.copy(fraction = Float.NaN)))
    }
    @Test fun progressWithoutExplicitCompletionIsNotLabelledRead() {
        assertEquals("读到第 2 页", chapterReadingLabel(record))
        assertNull(chapterReadingLabel(null))
        assertEquals("已读（手动标记） · 读到第 2 页", chapterReadingLabel(record.copy(completed = true)))
    }
    @Test fun directoryResumeAndSequentialChapterBoundariesRemainDistinct() {
        assertEquals(1 to .6f, chapterEntryAnchor(pages, record, resume = true, atEnd = false, ReadingMode.STRIP))
        for (mode in ReadingMode.entries) {
            assertEquals(0 to 0f, chapterEntryAnchor(pages, record, resume = false, atEnd = false, mode))
            assertEquals(1 to (if (mode == ReadingMode.STRIP) .9999f else 0f),
                chapterEntryAnchor(pages, record, resume = false, atEnd = true, mode))
        }
        assertEquals(0 to 0f, chapterEntryAnchor(emptyList(), record, resume = false, atEnd = true, ReadingMode.STRIP))
    }
}
