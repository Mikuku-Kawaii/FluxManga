package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BaoziAccountSourceTest {
    private val context = SourceContext(SourceWorld.SFW, "baozimh-account-11111111-2222-3333-4444-555555555555", "fixture", 1uL, "opaque-instance")
    private fun reply(html: String) = SourceHttpReply(context, html.toByteArray(), "text/html; charset=utf-8")
    private fun row(id: String, title: String = "合成作品", cover: String = "//static-tw.baozimh.com/cover/$id.jpg") =
        "<div class='bookshelf-items'><h4><a href='/comic/$id'>$title</a></h4><amp-img src='$cover'></amp-img></div>"

    @Test fun shelfMapsAccountIdentityAndDeduplicatesWithoutClaimingCompleteness() = runBlocking {
        val source = BaoziAccountSource(context, { true }) { reply(row("book-2") + row("book-1") + row("book-2")) }
        val result = source.bookshelf()
        assertEquals(listOf("book-2", "book-1"), result.entries.map { it.card.key.mangaId })
        assertTrue(result.entries.all { it.card.key.sourceId == context.sourceId && it.card.key.sourceId != BaoziEndpoint.SOURCE_ID })
        assertEquals(BaoziShelfEvidence.CONTENT_PRESENT, result.evidence)
        assertFalse(result.completenessVerified)
        assertEquals(BaoziCover.Url("https://static-tw.baozimh.com/cover/book-2.jpg"), result.entries.first().cover)
    }

    @Test fun emptyAndLoginWallAndChallengeAreNotSuccessfulEmptyAccounts() = runBlocking {
        val empty = BaoziAccountSource(context, { true }) { reply("<html><body></body></html>") }.bookshelf()
        assertEquals(BaoziShelfEvidence.EMPTY_UNCONFIRMED, empty.evidence)
        assertFalse(empty.completenessVerified)
        try {
            BaoziAccountSource(context, { true }) { reply("<form><input type='password'></form>") }.bookshelf(); fail()
        } catch (_: SourceException.AuthenticationRequired) { }
        try {
            BaoziAccountSource(context, { true }) { reply("<script src='/challenge-platform/test'></script>") }.bookshelf(); fail()
        } catch (_: SourceException.InvalidResponse) { }
    }

    @Test fun malformedItemNeverSilentlyDisappearsAndInvalidCoverStaysLocal() = runBlocking {
        val invalid = listOf(row("valid") + "<div class='bookshelf-items'>broken</div>", row("../escape"),
            row("bad").replace("/comic/bad", "https://elsewhere.test/comic/bad"),
            row("bad").replace("/comic/bad", "/comic/bad?account=wrong"), row("blank", ""), row("bad", "{{title}}"))
        for (html in invalid) {
            try { BaoziAccountSource(context, { true }) { reply(html) }.bookshelf(); fail() }
            catch (_: SourceException.InvalidResponse) { }
        }
        val badCover = BaoziAccountSource(context, { true }) { reply(row("book", cover = "https://elsewhere.test/cover/1.jpg")) }.bookshelf()
        assertEquals(1, badCover.entries.size)
        assertEquals(BaoziCover.Invalid, badCover.entries.single().cover)
    }

    @Test fun responsesRejectForeignAccountWorldGenerationAndWrongMimeOrOversize() = runBlocking {
        for (bad in listOf(reply(row("book")).copy(context = context.copy(generation = 2uL)),
            reply(row("book")).copy(context = context.copy(authRef = "another-account")),
            reply(row("book")).copy(context = context.copy(sourceId = "other-instance")),
            reply(row("book")).copy(context = context.copy(world = SourceWorld.NSFW)),
            reply(row("book")).copy(contentType = "application/json"),
            reply("x").copy(body = ByteArray(2 * 1024 * 1024 + 1)))) {
            try { BaoziAccountSource(context, { true }) { bad }.bookshelf(); fail() }
            catch (_: SourceException.InvalidResponse) { }
        }
        try { BaoziAccountSource(context.copy(sourceId = BaoziEndpoint.SOURCE_ID), { true }) { reply("") }; fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun largeSnapshotIsBoundedAndDoesNotDiscardOverflow() = runBlocking {
        val bounded = BaoziAccountSource(context, { true }) { reply((1..1000).joinToString("") { row("book-$it") }) }.bookshelf()
        assertEquals(1000, bounded.entries.size)
        assertFalse(bounded.completenessVerified)
        try {
            BaoziAccountSource(context, { true }) { reply((1..1001).joinToString("") { row("book-$it") }) }.bookshelf(); fail()
        } catch (_: SourceException.InvalidResponse) { }
    }

    @Test fun lateAndCancelledResultsNeverPublishAndTransportFailureIsNotEmpty() = runBlocking {
        var active = true
        val stale = BaoziAccountSource(context, { active }) { active = false; reply(row("book")) }
        try { stale.bookshelf(); fail() } catch (_: CancellationException) { }
        val started = CompletableDeferred<Unit>(); val ended = CompletableDeferred<Unit>()
        val blocked = BaoziAccountSource(context, { true }) {
            started.complete(Unit)
            try { awaitCancellation() } finally { ended.complete(Unit) }
        }
        val task = launch { blocked.bookshelf(); fail() }
        started.await(); task.cancelAndJoin(); ended.await()
        try { BaoziAccountSource(context, { true }) { throw SourceException.Timeout() }.bookshelf(); fail() }
        catch (_: SourceException.Timeout) { }
    }
}
