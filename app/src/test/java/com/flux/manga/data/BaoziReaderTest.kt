package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BaoziReaderTest {
    private val context = SourceContext(SourceWorld.SFW, BaoziEndpoint.SOURCE_ID, "fixture", 1uL, null)
    private fun reply(html: String) = SourceHttpReply(context, html.toByteArray(), "text/html")
    private val detail = "<h1 class='comics-detail__title'>合成</h1>" + (1..3).joinToString("") { "<div class='comics-chapters'><a href='/comic/chapter/sample/0_$it.html'>第 $it 话</a></div>" }
    private fun images(order: List<Int> = listOf(2, 1)) = "<div class='comic-contain'>" + order.joinToString("") { "<img data-src='https://s1.baozicdn.com/comic/sample/$it.jpg'>" } + "<img src='https://static-tw.baozimh.com/cover/sample.jpg'></div>"
    @Test fun bodyUsesRequestedChapterDomOrderAndStableIdentityAndRestoresProgress() = runBlocking {
        var metadataCalls = 0; var bodyCalls = 0; var order = listOf(2, 1, 2)
        val source = BaoziSource(context, { true }, chapterGet = { manga, chapter ->
            assertEquals("sample", manga); assertEquals("0_1", chapter); bodyCalls++; reply(images(order))
        }) { metadataCalls++; reply(detail) }
        val pages = source.pages("sample", "0_1").items
        assertEquals(2, pages.size); assertEquals(listOf(1u, 2u), pages.map { it.ordinal })
        assertEquals("https://s1.baozimh.com/comic/sample/2.jpg", source.pageUrl(pages.first()))
        assertEquals(1, metadataCalls); assertEquals(1, bodyCalls)
        val old = OnlineReadingProgress(context.sourceId, "sample", "0_1", pages.last().id, 2, .42f, "STRIP", 123, true)
        order = listOf(1, 2)
        val reopened = source.pages("sample", "0_1").items
        assertEquals(0 to .42f, onlineReadingAnchor(reopened, old)); assertEquals("STRIP", old.mode)
        assertEquals(0 to 0f, onlineReadingAnchor(reopened, old.copy(sourceId = "other")))
        assertEquals(1, metadataCalls)
    }
    @Test fun pageReferencesRejectForeignContextTamperingAndEvictedChapters() = runBlocking {
        val source = BaoziSource(context, { true }, chapterGet = { _, _ -> reply(images()) }) { reply(detail) }
        val page = source.pages("sample", "0_1").items.first()
        assertEquals(source.pageUrl(page), source.pageUrl(page.copy(width = 100u, height = 200u)))
        for (bad in listOf(page.copy(context = context.copy(generation = 2uL)), page.copy(uri = "https://evil.test"),
            page.copy(manga = page.manga.copy(sourceId = "other")), page.copy(ordinal = 99u))) {
            try { source.pageUrl(bad); fail() } catch (_: SourceException.InvalidRequest) { }
        }
        source.pages("sample", "0_2"); source.pages("sample", "0_3")
        try { source.pageUrl(page); fail() } catch (_: SourceException.InvalidRequest) { }
    }
    @Test fun foreignChapterDownloadWallAndBadImageOriginNeverBecomeReadable() = runBlocking {
        var calls = 0
        val source = BaoziSource(context, { true }, chapterGet = { _, _ -> calls++; reply("<div class='comic-contain'><img src='/img/download.jpg'></div>") }) { reply(detail) }
        try { source.pages("sample", "0_99"); fail() } catch (_: SourceException.NotFound) { }
        assertEquals(0, calls)
        try { source.pages("sample", "0_1"); fail() } catch (_: SourceException.InvalidResponse) { }
        for (url in listOf("https://evil.test/1.jpg", "http://s1.baozimh.com/1.jpg", "https://u:p@s1.baozimh.com/1.jpg", "https://s1.baozimh.com/1.jpg#fragment")) {
            try { baoziPageUrl(url); fail() } catch (_: SourceException.InvalidResponse) { }
        }
    }
    @Test fun cancelledBodyAndExpiredSessionsCannotPublishPageReferences() = runBlocking {
        val started = CompletableDeferred<Unit>(); val ended = CompletableDeferred<Unit>()
        val source = BaoziSource(context, { true }, chapterGet = { _, _ -> started.complete(Unit); try { awaitCancellation() } finally { ended.complete(Unit) } }) { reply(detail) }
        val work = launch { source.pages("sample", "0_1") }; started.await(); work.cancelAndJoin(); ended.await()
        var active = true
        val expired = BaoziSource(context, { active }, chapterGet = { _, _ -> active = false; reply(images()) }) { reply(detail) }
        try { expired.pages("sample", "0_1"); fail() } catch (_: CancellationException) { }
    }
    @Test fun categoriesAndUpdatesUseTheirActualProtocolsAndUnknownTotals() = runBlocking {
        val paths = mutableListOf<String>()
        val source = BaoziSource(context, { true }) { paths += it; reply((1..36).joinToString("") { n -> "<div class='comics-card'><a class='comics-card__poster' href='/comic/book-$n' title='Book'></a></div>" }) }
        val latest = source.search("", SourcePageRequest.Start, updates = true)
        assertEquals(SourceContinuation.End, latest.continuation); assertEquals("list/new", paths.last())
        val tagged = source.search("", SourcePageRequest.Page(2u), tag = BaoziCategories.tags.getValue("冒险"))
        assertEquals("classify?page=2&type=mouxian", paths.last())
        assertEquals(SourceContinuation.Next(SourcePageRequest.Page(3u)), tagged.continuation)
        try { source.search("", SourcePageRequest.Start, tag = "unknown"); fail() } catch (_: SourceException.InvalidRequest) { }
        assertEquals(2, paths.size)
    }

    @Test fun detailKeepsActualTagsWithoutInventingTheWholeCategoryDirectory() = runBlocking {
        val source = BaoziSource(context, { true }) { reply(detail + "<div class='tag-list'><span class='tag'>连载中</span><span class='tag'>冒险</span><span class='tag'>自定义原文</span><span class='tag'>冒险</span></div>") }
        assertEquals(listOf("冒险", "自定义原文"), source.detail("sample").tags)
        val missing = BaoziSource(context, { true }) { reply(detail) }
        assertNull(missing.detail("sample").tags)
    }
    @Test fun publicReaderKeepsAccountIdentityAndCannotReuseAnotherAccountsPageReference() = runBlocking {
        val first = context.copy(sourceId = baoziAccountId("first"))
        val second = context.copy(sourceId = baoziAccountId("second"))
        fun source(ctx: SourceContext) = BaoziSource(ctx, { true }, chapterGet = { _, _ -> reply(images()).copy(context = ctx) }) { reply(detail).copy(context = ctx) }
        val a = source(first); val b = source(second)
        val page = a.pages("sample", "0_1").items.first()
        val other = b.pages("sample", "0_1").items.first()
        assertEquals(first.sourceId, page.manga.sourceId)
        assertEquals(second.sourceId, other.manga.sourceId)
        assertEquals(page.id, other.id)
        try { b.pageUrl(page); fail() } catch (_: SourceException.InvalidRequest) { }
        val old = OnlineReadingProgress(first.sourceId, "sample", "0_1", other.id, 2, .4f, "STRIP", 123)
        assertEquals(0 to 0f, onlineReadingAnchor(listOf(other), old))
    }
}
