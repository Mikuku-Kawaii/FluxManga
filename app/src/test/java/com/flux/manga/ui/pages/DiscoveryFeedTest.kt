package com.flux.manga.ui.pages

import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFeedTest {
    private val next = SourceContinuation.Next(SourcePageRequest.Page(2u))
    private fun card(id: String) = MangaCard(MangaKey(PrototypeWorld.SFW, "source", id), "同名", null, true)
    private fun page(vararg ids: String, continuation: SourceContinuation = SourceContinuation.End) =
        SourceCardPage(ids.map(::card), continuation)

    @Test fun appendFailureRetriesSamePageAndKeepsSourceIdentity() = runBlocking {
        val requests = mutableListOf<SourcePageRequest>()
        val feed = DiscoveryFeedController(this, { true }) { request ->
            requests += request
            when (requests.size) {
                1 -> page("a", continuation = next)
                2 -> throw SourceException.Transport()
                else -> page("a", "b")
            }
        }
        feed.start(); yield(); feed.more(); yield()
        assertEquals(listOf("a"), feed.state.value.cards.map { it.key.mangaId })
        assertNotNull(feed.state.value.appendError)
        assertEquals(next, feed.state.value.next)
        feed.more(); yield()
        assertEquals(listOf("a", "b"), feed.state.value.cards.map { it.key.mangaId })
        assertEquals(requests[1], requests[2])
        assertEquals(SourceContinuation.End, feed.state.value.next)
        assertNull(feed.state.value.appendError)
    }

    @Test fun refreshFailureRetainsContentAndEmptyNonLastPageCanAdvance() = runBlocking {
        var calls = 0
        val feed = DiscoveryFeedController(this, { true }) {
            when (++calls) {
                1 -> page("a", continuation = next)
                2 -> throw SourceException.AuthenticationRequired()
                3 -> page(continuation = next)
                else -> page("b")
            }
        }
        feed.start(); yield(); feed.refresh(); yield()
        assertEquals("a", feed.state.value.cards.single().key.mangaId)
        assertNotNull(feed.state.value.error)
        feed.refresh(); yield()
        assertTrue(feed.state.value.cards.isEmpty())
        assertEquals(next, feed.state.value.next)
        feed.more(); yield()
        assertEquals("b", feed.state.value.cards.single().key.mangaId)
    }

    @Test fun unsupportedRecommendationDoesNotBlockUpdatesOrRetryAsFailure() = runBlocking {
        var calls = 0
        val recommendations = DiscoveryFeedController(this, { true }) {
            calls++; throw SourceException.Unsupported(SourceOperation.RECOMMENDATIONS)
        }
        val updates = DiscoveryFeedController(this, { true }) { page("a") }
        recommendations.start(); updates.start(); yield()
        assertTrue(recommendations.state.value.unsupported)
        assertNull(recommendations.state.value.error)
        assertEquals(1, updates.state.value.cards.size)
        recommendations.refresh(); yield(); assertEquals(1, calls)
    }

    @Test fun explicitCancelAndLateResultNeverOverwriteRetry() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val feed = DiscoveryFeedController(this, { true }) {
            if (++calls == 1) { withContext(NonCancellable) { pending.await() }; page("late") }
            else page("current")
        }
        feed.start(); yield(); feed.cancel()
        assertFalse(feed.state.value.busy)
        assertNotNull(feed.state.value.notice)
        feed.start(); yield(); assertEquals(1, calls) // explicit cancellation is not auto-retried
        feed.refresh(); yield(); pending.complete(Unit); yield()
        assertEquals("current", feed.state.value.cards.single().key.mangaId)
    }

    @Test fun expiredSessionCannotPublishOrStartAnotherRequest() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        var active = true
        var calls = 0
        val feed = DiscoveryFeedController(this, { active }) { calls++; pending.await(); page("late") }
        feed.start(); yield(); active = false; pending.complete(Unit); yield()
        assertTrue(feed.state.value.cards.isEmpty())
        feed.refresh(); yield(); assertEquals(1, calls)
    }

    @Test fun lifecycleCancellationResumesOnlyInterruptedInitialLoad() = runBlocking {
        var calls = 0
        val feed = DiscoveryFeedController(this, { true }) {
            if (++calls == 1) awaitCancellation() else page("a")
        }
        feed.start(); yield(); feed.leave(); feed.start(); yield()
        assertEquals(2, calls)
        feed.leave(); feed.start(); yield()
        assertEquals(2, calls) // detail return keeps the list, no automatic refresh
    }
    @Test fun changingSourceQueryCancelsPendingPageAndKeepsNewResultOnly() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        var query = "old"
        val requests = mutableListOf<Pair<String, SourcePageRequest>>()
        val feed = DiscoveryFeedController(this, { true }, ::copyMangaFailure) { request ->
            val current = query
            requests += current to request
            if (current == "old") withContext(NonCancellable) { pending.await() }
            page(current)
        }
        feed.start(); yield()
        query = "new"; feed.restart(); yield()
        pending.complete(Unit); yield()
        assertEquals(listOf("new"), feed.state.value.cards.map { it.key.mangaId })
        assertTrue(requests.all { it.second == SourcePageRequest.Start })
        feed.leave(); feed.start(); yield()
        assertEquals(2, requests.size)
    }

    @Test fun copyErrorsDoNotAskForKomgaCredentials() = runBlocking {
        val feed = DiscoveryFeedController(this, { true }, ::copyMangaFailure) {
            throw SourceException.AuthenticationRequired()
        }
        feed.start(); yield()
        assertTrue(requireNotNull(feed.state.value.error).contains("图源管理"))
        assertTrue(requireNotNull(feed.state.value.error).contains("账号"))
        assertFalse(requireNotNull(feed.state.value.error).contains("API Key"))
        assertFalse(copyMangaFailure(SourceException.InvalidResponse()).contains("Komga"))
    }
    @Test fun alternatingSourcesRetainIndependentPagesAndCannotPublishCancelledAppend() = runBlocking {
        val pending = CompletableDeferred<SourceCardPage>()
        var calls = 0
        val copy = DiscoveryFeedController(this, { true }, ::copyMangaFailure) {
            if (++calls == 1) SourceCardPage(listOf(MangaCard(MangaKey(PrototypeWorld.SFW, CopyMangaEndpoint.SOURCE_ID, "same"), "拷贝", null, true)), next)
            else withContext(NonCancellable) { pending.await() }
        }
        val komga = DiscoveryFeedController(this, { true }) {
            SourceCardPage(listOf(MangaCard(MangaKey(PrototypeWorld.SFW, "komga-test", "same"), "Komga", null, true)), SourceContinuation.End)
        }
        copy.start(); yield(); copy.more(); yield()
        copy.leave(); komga.start(); yield()
        pending.complete(page("stale")); yield()
        assertEquals("komga-test", komga.state.value.cards.single().key.sourceId)
        komga.leave(); copy.start(); yield()
        assertEquals(2, calls)
        assertEquals(CopyMangaEndpoint.SOURCE_ID, copy.state.value.cards.single().key.sourceId)
        assertEquals(next, copy.state.value.next)
        assertFalse(copy.state.value.busy)
    }
}
