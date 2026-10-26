package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BaoziSourceTest {
    private val context = SourceContext(SourceWorld.SFW, BaoziEndpoint.SOURCE_ID, "synthetic", 1uL, null)
    private fun reply(html: String) = SourceHttpReply(context, html.toByteArray(), "text/html; charset=utf-8")
    private fun card(id: String = "same", title: String = "合成 &amp; 作品") =
        """<div class="comics-card"><a class="comics-card__poster" href="/comic/$id"></a><h3 class="comics-card__title">$title</h3></div>"""
    private suspend fun invalid(action: suspend () -> Any?) {
        try { action(); fail("Expected invalid response") } catch (_: SourceException.InvalidResponse) { }
    }
    @Test fun encodedQueryAndCardsKeepSourceIdentityAndSearchNeverInventsPagination() = runBlocking {
        var path = ""
        val source = BaoziSource(context, { true }) { path = it; reply((1..36).joinToString("") { n -> card("sample-$n") }) }
        val result = source.search("合成 &? 中文", SourcePageRequest.Start)
        assertEquals("search?q=%E5%90%88%E6%88%90+%26%3F+%E4%B8%AD%E6%96%87", path)
        assertEquals(36, result.cards.size)
        assertEquals("合成 & 作品", result.cards.first().title)
        assertEquals(BaoziEndpoint.SOURCE_ID, result.cards.first().key.sourceId)
        assertEquals(SourceContinuation.End, result.continuation)
        try { source.search("query", SourcePageRequest.Page(2u)); fail() } catch (_: SourceException.InvalidRequest) { }
    }
    @Test fun catalogUsesItsOwnPageAndRawCardThresholdWithoutFakeTotal() = runBlocking {
        val paths = mutableListOf<String>()
        val source = BaoziSource(context, { true }) { path ->
            paths += path
            reply(if (path.endsWith("1")) (1..36).joinToString("") { card("same") } else "<div class='pure-g'></div>")
        }
        val first = source.search("", SourcePageRequest.Start)
        assertEquals(1, first.cards.size)
        val next = (first.continuation as SourceContinuation.Next).request
        assertEquals(SourcePageRequest.Page(2u), next)
        val last = source.search("", next)
        assertTrue(last.cards.isEmpty()); assertEquals(SourceContinuation.End, last.continuation)
        assertEquals(listOf("classify?page=1", "classify?page=2"), paths)
    }
    @Test fun fallbackPosterAndOnclickLayoutUseSameSlugWithoutExecutingScript() = runBlocking {
        val html = """<div class="pure-g"><div><a class="comics-card__poster" title="合成旧布局" href="https://cn.baozimh.com/comic/old"></a></div></div>"""
        assertEquals("old", BaoziSource(context, { true }) { reply(html) }.search("q", SourcePageRequest.Start).cards.single().key.mangaId)
        val app = """<div class="comics-card"><a class="comics-card__poster" title="合成 app" onclick="send_app_msg('call_page', ['comic', 'sample'])"></a></div>"""
        assertEquals("sample", BaoziSource(context, { true }) { reply(app) }.search("q", SourcePageRequest.Start).cards.single().key.mangaId)
    }
    @Test fun challengeUnknownTemplateAndForeignLinksFailInsteadOfBecomingEmpty() = runBlocking {
        for (html in listOf("<html>unrecognized</html>", "<div class='pure-g'><script src='/cdn-cgi/challenge-platform/test'></script></div>",
            card(title = "{{ comic.title }}"), card().replace("/comic/same", "https://other.example/comic/same"),
            card().replace("/comic/same", "/comic/../account"), card().replace("/comic/same", "//cn.baozimh.com@other.example/comic/same"))) {
            invalid { BaoziSource(context, { true }) { reply(html) }.search("q", SourcePageRequest.Start) }
        }
        assertTrue(BaoziSource(context, { true }) { reply("<div class='pure-g'></div>") }.search("q", SourcePageRequest.Start).cards.isEmpty())
    }
    @Test fun detailFieldsAndCanonicalIdentityAreChecked() = runBlocking {
        val html = """<link rel="canonical" href="https://cn.baozimh.com/comic/sample"><h1 class="comics-detail__title">合成标题</h1><h2 class="comics-detail__author">合成作者</h2><p class="comics-detail__desc">第一行<br>第二行 &amp; 内容</p>"""
        val source = BaoziSource(context, { true }) { path -> assertEquals("comic/sample", path); reply(html) }
        val detail = source.detail("sample")
        assertEquals("合成作者", detail.author); assertTrue(detail.description.contains("第二行 & 内容"))
        assertEquals("sample", detail.card.key.mangaId)
        invalid { BaoziSource(context, { true }) { reply(html.replace("/comic/sample", "/comic/other")) }.detail("sample") }
        invalid { BaoziSource(context, { true }) { reply("<h1>download app</h1>") }.detail("sample") }
    }
    @Test fun wrongContextMimeAndOversizedBodyAreRejected() = runBlocking {
        val good = reply(card())
        for (bad in listOf(good.copy(context = context.copy(sourceId = "komga-other")), good.copy(contentType = "application/json"),
            good.copy(body = ByteArray(2 * 1024 * 1024 + 1)))) {
            invalid { BaoziSource(context, { true }) { bad }.search("q", SourcePageRequest.Start) }
        }
    }
    @Test fun expiredSessionAndCoroutineCancellationNeverPublishLateHtml() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var active = true
        val source = BaoziSource(context, { active }) { gate.await(); reply(card()) }
        val result = async { source.search("q", SourcePageRequest.Start) }
        yield(); active = false; gate.complete(Unit)
        try { result.await(); fail() } catch (_: CancellationException) { }
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val waiting = BaoziSource(context, { true }) { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val job = launch { waiting.search("q", SourcePageRequest.Start) }
        started.await(); job.cancelAndJoin(); cancelled.await()
    }
}
