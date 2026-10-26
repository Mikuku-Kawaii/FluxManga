package com.flux.manga.ui.pages

import com.flux.manga.core.*
import com.flux.manga.data.OnlineReadingProgress
import org.junit.Assert.*
import org.junit.Test

class DetailPresentationTest {
    private fun progress(chapter: String) = OnlineReadingProgress("source", "book", chapter, "page-id", 8, .25f, "STRIP", 10)
    @Test fun topicsAreOnlyAvailableNamesWithoutSynonymOrCaseMerging() {
        val available = listOf("自定义题材", "奇幻", "奇幻", "科幻", "科幻小说", "SciFi", "scifi", "其他")
        val chosen = featuredTags(available)
        assertEquals(listOf("奇幻", "科幻", "自定义题材", "科幻小说", "SciFi", "scifi"), chosen)
        assertTrue(chosen.all { it in available }); assertFalse("日常" in chosen)
        assertTrue(featuredTags(emptyList()).isEmpty())
    }
    @Test fun reversedDisplayPreservesServerOrderAndIdsForReading() {
        val original = listOf(DetailCatalogEntry("z", "第 2 话"), DetailCatalogEntry("a", "第 10 话"), DetailCatalogEntry("c", "序章"))
        assertEquals(listOf("c", "a", "z"), orderedCatalog(original, true).map { it.id })
        assertEquals(listOf("z", "a", "c"), original.map { it.id })
        assertEquals(original, orderedCatalog(original, false))
    }
    @Test fun unknownChapterDoesNotLeakIdOrInventChapterNumberOrPageTotal() {
        assertEquals("上次阅读：第 8 页", progressSummary(progress("private-path-token"), emptyList()))
        assertNull(progressSummary(null, emptyList()))
    }
    @Test fun progressUsesTheMatchingChapterTitleOnly() {
        val chapters = listOf(DetailCatalogEntry("c1", "开端"), DetailCatalogEntry("c2", "第十话"))
        assertEquals("上次阅读：第十话 · 第 8 页", progressSummary(progress("c2"), chapters))
        assertEquals(progressSummary(progress("c2"), chapters), progressSummary(progress("c2"), orderedCatalog(chapters, true)))
    }
    @Test fun chapterMappingRetainsUnknownPageCountsAndDoesNotInferReadStatus() {
        val key = SourceMangaKey(SourceWorld.SFW, "source", "book")
        val chapters = listOf(SourceChapter(key, "1", "卷一", 1u, null), SourceChapter(key, "2", "卷二", 2u, 24u))
        assertEquals(listOf(DetailCatalogEntry("1", "卷一", ""), DetailCatalogEntry("2", "卷二", "24 页")), chapterEntries(chapters))
    }
}
