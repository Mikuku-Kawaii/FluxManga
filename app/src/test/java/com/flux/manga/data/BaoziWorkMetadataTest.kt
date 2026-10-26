package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BaoziWorkMetadataTest {
    @Test fun literalAuthorAndStatusDoNotInventIndividualCreatorsOrRoles() {
        val value = baoziWorkMetadata(Jsoup.parse("<h2 class='comics-detail__author'>甲 / 乙</h2><div class='tag-list'><span class='tag'>連載中</span><span class='tag'>冒险</span></div>"))
        assertEquals(listOf(SourceAuthor("甲 / 乙", null)), value.authors)
        assertEquals("连载中", value.status)
        assertNull(value.updatedAt); assertFalse(value.partial)
        assertEquals("已完结", baoziWorkMetadata(Jsoup.parse("<div class='tag-list'><span class='tag'>已完結</span></div>")).status)
    }
    @Test fun categoryAndAbsentDataNeverBecomePublicationStatus() {
        for (html in listOf("", "<div class='tag-list'><span class='tag'>冒险</span></div>", "<span class='tag'>已完结</span>")) {
            val value = baoziWorkMetadata(Jsoup.parse(html))
            assertTrue(value.authors.isEmpty()); assertNull(value.status); assertNull(value.updatedAt); assertFalse(value.partial)
        }
    }
    @Test fun updateRequiresExplicitDateAndDoesNotAssumeTimezoneOrChapter() {
        val value = baoziWorkMetadata(Jsoup.parse("<em>(2024年02月29日 更新)</em>"))
        assertEquals("2024年02月29日", value.updatedAt)
        assertEquals(SourceUpdateKind.CONTENT, value.updateKind)
        assertFalse(value.partial)
        for (text in listOf("2024年02月29日", "刚刚更新", "<script>bad</script>")) {
            assertNull(baoziWorkMetadata(Jsoup.parse("<em>$text</em>")).updatedAt)
        }
    }
    @Test fun malformedDateAndOversizeAuthorDegradeWithoutLosingStatus() {
        val value = baoziWorkMetadata(Jsoup.parse("<h2 class='comics-detail__author'>${"中".repeat(200)}</h2><div class='tag-list'><span class='tag'>已完结</span></div><em>(2025年02月29日 更新)</em>"))
        assertTrue(value.authors.isEmpty()); assertTrue(value.partial)
        assertNull(value.updatedAt); assertEquals("已完结", value.status)
    }
    @Test fun detailKeepsIdentityTagsAndChaptersWithOneRequest() = runBlocking {
        val context = SourceContext(SourceWorld.SFW, BaoziEndpoint.SOURCE_ID, "fixture", 8uL, null)
        var calls = 0
        val html = """<link rel='canonical' href='https://cn.baozimh.com/comic/test'><h1 class='comics-detail__title'>Synthetic</h1>
            <h2 class='comics-detail__author'>Creator</h2><div class='tag-list'><span class='tag'>连载中</span><span class='tag'>冒险</span></div>
            <em>(2026年10月01日 更新)</em><div class='comics-chapters'><a href='/comic/chapter/test/0_1.html'>第1话</a></div>"""
        val source = BaoziSource(context, { true }) { calls++; SourceHttpReply(context, html.toByteArray(), "text/html") }
        val book = source.detail("test")
        assertEquals(1, calls); assertEquals("test", book.card.key.mangaId)
        assertEquals(listOf("冒险"), book.tags); assertEquals(1, book.chapters!!.size)
        assertEquals("Creator", book.metadata.authors.single().name)
        assertEquals("2026年10月01日", book.metadata.updatedAt)
    }
}
