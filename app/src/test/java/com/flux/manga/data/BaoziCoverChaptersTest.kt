package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BaoziCoverChaptersTest {
    private val context = SourceContext(SourceWorld.SFW, BaoziEndpoint.SOURCE_ID, "fixture", 1uL, null)
    private fun reply(html: String) = SourceHttpReply(context, html.toByteArray(), "text/html")
    private val title = "<h1 class='comics-detail__title'>合成作品</h1>"
    private fun chapter(slot: Int, slug: String = "sample", name: String = "第 $slot 话") =
        "<div class='comics-chapters'><a href='/comic/chapter/$slug/0_$slot.html'>$name</a></div>"

    @Test fun coverSelectorsPreferLazyImagesAndDetailsReplaceTheRegisteredLocation() = runBlocking {
        var calls = 0
        val source = BaoziSource(context, { true }) {
            calls++
            reply(if (it.startsWith("search")) """<div class="comics-card"><a class="comics-card__poster" href="/comic/sample" title="合成"></a><amp-img data-src="//static-tw.baozimh.com/cover/lazy.jpg" src="/cover/loading.jpg"></amp-img></div>"""
            else title + "<meta property='og:image' content='https://static-tw.baozicdn.com/cover/detail.jpg'>")
        }
        source.search("q", SourcePageRequest.Start)
        assertEquals("https://static-tw.baozimh.com/cover/lazy.jpg", source.cover("sample"))
        assertEquals(1, calls)
        source.detail("sample")
        assertEquals("https://static-tw.baozicdn.com/cover/detail.jpg", source.cover("sample"))
        assertEquals(2, calls)
    }
    @Test fun missingAndInvalidCoversDoNotDiscardMetadata() = runBlocking {
        for (raw in listOf(null, "https://static-tw.baozimh.com/cover/404.jpg")) {
            val source = BaoziSource(context, { true }) { reply(title + (raw?.let { "<meta name='og:image' content='$it'>" } ?: "")) }
            assertEquals("合成作品", source.detail("sample").card.title)
            try { source.cover("sample"); fail() } catch (_: SourceException.NotFound) { }
        }
        for (raw in listOf("http://static-tw.baozimh.com/cover/a.jpg", "https://evil.test/cover/a.jpg",
            "https://static-tw.baozimh.com.evil.test/cover/a.jpg", "https://u:p@static-tw.baozimh.com/cover/a.jpg",
            "https://static-tw.baozimh.com/cover/a.jpg#fragment", "https://static-tw.baozimh.com/comic/a.jpg")) {
            assertEquals(BaoziCover.Invalid, baoziCoverLocation(raw))
            val source = BaoziSource(context, { true }) { reply(title + "<meta name='og:image' content='$raw'>") }
            assertEquals("合成作品", source.detail("sample").card.title)
            try { source.cover("sample"); fail() } catch (_: SourceException.InvalidResponse) { }
        }
    }
    @Test fun referenceEvictionFallsBackToDetailsAndExpiredSessionsCannotUseCachedCovers() = runBlocking {
        var calls = 0; var active = true
        val source = BaoziSource(context, { active }) {
            calls++
            if (it.startsWith("comic/")) reply(title + "<meta name='og:image' content='/cover/restored.jpg'>")
            else reply((0..256).joinToString("") { n -> "<div class='comics-card'><a class='comics-card__poster' href='/comic/book-$n' title='Book'></a><img src='/cover/$n.jpg'></div>" })
        }
        source.search("q", SourcePageRequest.Start)
        assertEquals("https://cn.baozimh.com/cover/256.jpg", source.cover("book-256")); assertEquals(1, calls)
        assertEquals("https://cn.baozimh.com/cover/restored.jpg", source.cover("book-0")); assertEquals(2, calls)
        active = false
        try { source.cover("book-256"); fail() } catch (_: CancellationException) { }
        assertEquals(2, calls)
    }
    @Test fun fullDirectoryExcludesLatestPreviewAndPreservesUpstreamOrderingAndIdentity() = runBlocking {
        val html = title + chapter(99) + "<section><h2 class='section-title'>章节目录</h2>" + chapter(1) + chapter(2) + chapter(2) + "</section>"
        val book = BaoziSource(context, { true }) { reply(html) }.detail("sample")
        assertEquals(listOf("0_2", "0_1"), book.chapters!!.map { it.id })
        assertTrue(book.chapters.all { it.manga == book.card.key }); assertNull(book.chapterError)
        val legacy = BaoziSource(context, { true }) { reply(title + chapter(1) + chapter(2)) }.detail("sample")
        assertEquals(listOf("0_1", "0_2"), legacy.chapters!!.map { it.id })
    }
    @Test fun queryAndOnclickRepresentTheSameStableChapterWithoutScriptExecution() = runBlocking {
        val html = title + """<div class="comics-chapters"><a href="/user/page_direct?comic_id=sample&amp;section_slot=01&amp;chapter_slot=2">网页章节</a></div><div class="comics-chapters"><a onclick="send_app_msg('call_page', ['chapter', 'sample', 1, 2])">App 章节</a></div>"""
        val book = BaoziSource(context, { true }) { reply(html) }.detail("sample")
        assertEquals(listOf("1_2"), book.chapters!!.map { it.id })
        assertEquals("网页章节", book.chapters.single().title)
    }
    @Test fun missingEmptyAndInvalidCatalogsHaveDistinctStatesAndKeepDetail() = runBlocking {
        suspend fun book(body: String) = BaoziSource(context, { true }) { reply(title + body) }.detail("sample")
        assertNull(book("").chapters); assertNull(book("").chapterError)
        assertTrue(book("<section><h2 class='section-title'>章節目錄</h2></section>").chapters!!.isEmpty())
        for (body in listOf(chapter(1, "other"), chapter(1).replace("/comic/chapter/", "https://evil.test/comic/chapter/"),
            chapter(1, name = "{{title}}"), "<div class='comics-chapters'>Unknown link</div>")) {
            val result = book(body)
            assertEquals("合成作品", result.card.title); assertNull(result.chapters); assertNotNull(result.chapterError)
        }
    }
    @Test fun longDirectoryKeepsAllFetchedChaptersWithoutFetchingIndividualPages() = runBlocking {
        var calls = 0
        val book = BaoziSource(context, { true }) { calls++; reply(title + (1..501).joinToString("") { chapter(it) }) }.detail("sample")
        assertEquals(501, book.chapters!!.size); assertEquals("0_501", book.chapters.last().id); assertEquals(1, calls)
    }
}
