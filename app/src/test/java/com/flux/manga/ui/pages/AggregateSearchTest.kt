package com.flux.manga.ui.pages

import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AggregateSearchTest {
    private val komga = "komga-test"
    private val copy = CopyMangaEndpoint.SOURCE_ID
    private val sources = listOf(komga, copy)
    private fun page(source: String, id: String = "same", next: SourceContinuation = SourceContinuation.End) =
        SourceCardPage(listOf(MangaCard(MangaKey(PrototypeWorld.SFW, source, id), "同名作品", null, true)), next)
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { withTimeout(5_000) { scope.block() } } finally { scope.cancel() }
    }
    private suspend fun AggregateSearchController.await(predicate: (AggregateSearchState) -> Boolean) = state.first(predicate)

    @Test fun bothSourcesStartBeforeEitherCompletesAndFastResultsDoNotWaitForSlowSource() = scenario {
        val started = mutableSetOf<String>()
        val gates = sources.associateWith { CompletableDeferred<Unit>() }
        val engine = AggregateSearchController(this, sources, { true }) { source, _, _ ->
            started += source; gates.getValue(source).await(); page(source)
        }
        assertTrue(engine.state.value.results.isEmpty()) // Constructing/restoring an entry does not search.
        assertTrue(engine.submit("query"))
        repeat(3) { yield() }
        assertEquals(sources.toSet(), started)
        assertFalse(engine.submit("query")) // no duplicate in-flight submission
        gates.getValue(copy).complete(Unit)
        val partial = engine.await { it.loaded == 1 }
        assertTrue(partial.results.getValue(komga).busy)
        assertEquals(copy, partial.results.getValue(copy).cards.single().key.sourceId)
        gates.getValue(komga).complete(Unit)
        val complete = engine.await { it.loaded == 2 }
        assertEquals(2, complete.results.values.flatMap { it.cards }.map { it.key }.distinct().size)
    }

    @Test fun failedSourceRetryDoesNotRepeatSuccessfulRequestOrDropItsResults() = scenario {
        val calls = mutableMapOf<String, Int>()
        val engine = AggregateSearchController(this, sources, { true }) { source, _, _ ->
            calls[source] = (calls[source] ?: 0) + 1
            if (source == komga && calls[source] == 1) throw SourceException.AuthenticationRequired()
            page(source)
        }
        engine.submit("query")
        val partial = engine.await { !it.busy && it.failures == 1 && it.loaded == 1 }
        val good = partial.results.getValue(copy).cards
        assertNotNull(partial.results.getValue(komga).error)
        engine.refresh(komga)
        val complete = engine.await { it.loaded == 2 && !it.busy }
        assertEquals(good, complete.results.getValue(copy).cards)
        assertEquals(1, calls[copy]); assertEquals(2, calls[komga])
    }

    @Test fun cursorsAndAppendRetriesRemainPerSourceIncludingEmptyNonLastPage() = scenario {
        val cursor = SourcePageRequest.Cursor("komga-next")
        val copyPage = SourcePageRequest.Page(2u)
        val requests = mutableListOf<Pair<String, SourcePageRequest>>()
        var fail = true
        val engine = AggregateSearchController(this, sources, { true }) { source, _, request ->
            requests += source to request
            when {
                request is SourcePageRequest.Start && source == komga -> SourceCardPage(emptyList(), SourceContinuation.Next(cursor))
                request is SourcePageRequest.Start -> page(source, next = SourceContinuation.Next(copyPage))
                source == copy && fail -> { fail = false; throw SourceException.Transport() }
                else -> page(source, "next")
            }
        }
        engine.submit("query"); engine.await { it.loaded == 2 && !it.busy }
        engine.more(copy); engine.await { it.failures == 1 && !it.busy }
        assertEquals("same", engine.state.value.results.getValue(copy).cards.single().key.mangaId)
        engine.more(copy); engine.await { it.failures == 0 && it.results.getValue(copy).cards.size == 2 }
        engine.more(komga); engine.await { it.results.getValue(komga).cards.size == 1 }
        assertEquals(listOf(SourcePageRequest.Start, copyPage, copyPage), requests.filter { it.first == copy }.map { it.second })
        assertEquals(listOf(SourcePageRequest.Start, cursor), requests.filter { it.first == komga }.map { it.second })
    }

    @Test fun changingQueryCancelsBothOldTasksAndLateCompletionCannotOverwriteNewQuery() = scenario {
        val release = CompletableDeferred<Unit>()
        var oldStarted = 0
        val engine = AggregateSearchController(this, sources, { true }) { source, query, _ ->
            if (query == "old") { oldStarted++; withContext(NonCancellable) { release.await() } }
            page(source, query)
        }
        try {
            engine.submit("old"); repeat(3) { yield() }; assertEquals(2, oldStarted)
            engine.submit("new"); engine.await { it.query == "new" && it.loaded == 2 }
            release.complete(Unit); repeat(3) { yield() }
            assertTrue(engine.state.value.results.values.all { it.cards.single().key.mangaId == "new" })
        } finally { release.complete(Unit) }
    }

    @Test fun singleCancelLeavesOtherSourceRunningAndLeavingNeverAutoResubmits() = scenario {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val engine = AggregateSearchController(this, sources, { true }) { source, _, _ ->
            calls++; release.await(); page(source)
        }
        engine.submit("query"); repeat(3) { yield() }
        engine.cancel(copy)
        engine.await { it.results.getValue(copy).notice != null }
        assertTrue(engine.state.value.results.getValue(komga).busy)
        release.complete(Unit); engine.await { it.loaded == 1 && !it.busy }
        assertTrue(engine.state.value.results.getValue(copy).cards.isEmpty())
        engine.leave(); repeat(3) { yield() }
        assertEquals(2, calls)
        engine.refresh(copy); engine.await { it.loaded == 2 }
        assertEquals(3, calls)
    }

    @Test fun cancelAllThenRetryAndExpiredSessionCannotPublishOrStartMoreWork() = scenario {
        val release = CompletableDeferred<Unit>()
        var active = true
        var calls = 0
        val engine = AggregateSearchController(this, sources, { active }) { source, _, _ ->
            calls++; release.await(); page(source)
        }
        engine.submit("query"); repeat(3) { yield() }; engine.cancel()
        engine.await { !it.busy && it.results.values.all { row -> row.notice != null } }
        engine.refresh(komga); repeat(3) { yield() }
        active = false; release.complete(Unit); repeat(3) { yield() }
        assertTrue(engine.state.value.results.values.all { it.cards.isEmpty() })
        assertFalse(engine.submit("next")); engine.more(copy); engine.refresh(copy)
        assertEquals(3, calls)
    }

    @Test fun unavailableKomgaDoesNotProduceFakeEmptyResultAndHistoryTracksExactSourceSet() = scenario {
        val engine = AggregateSearchController(this, listOf(copy), { true }) { source, _, _ -> page(source) }
        assertFalse(engine.submit("  "))
        engine.submit("query"); engine.await { it.loaded == 1 }
        assertEquals(setOf(copy), engine.state.value.results.keys)
        val scope = aggregateSearchScope(sources)
        assertEquals(scope, aggregateSearchScope(sources.reversed()))
        assertNotEquals(scope, aggregateSearchScope(listOf("komga-replaced", copy)))
        assertNotEquals(scope, aggregateSearchScope(listOf(copy)))
        assertNotEquals(scope, copy)
        assertEquals(scope, historySource(searchHistoryEntry(scope, "query", 1)!!.sourceId))
    }
    @Test fun baoziHtmlFailureAndRetryAreIsolatedFromBothExistingSources() = scenario {
        val baozi = BaoziEndpoint.SOURCE_ID
        val context = SourceContext(SourceWorld.SFW, baozi, "fixture", 1uL, null)
        var calls = 0
        val source = BaoziSource(context, { true }) {
            calls++
            val html = if (calls == 1) "<html>unknown challenge</html>" else """<div class="comics-card"><a class="comics-card__poster" href="/comic/same" title="同名作品"></a></div>"""
            SourceHttpReply(context, html.toByteArray(), "text/html")
        }
        val all = sources + baozi
        val engine = AggregateSearchController(this, all, { true }) { id, query, request ->
            if (id == baozi) source.search(query, request) else page(id, next = SourceContinuation.Next(SourcePageRequest.Page(2u)))
        }
        engine.submit("query"); engine.await { it.loaded == 2 && it.failures == 1 && !it.busy }
        assertTrue(engine.state.value.results.getValue(baozi).error!!.contains("包子"))
        engine.refresh(baozi)
        engine.await { it.loaded == 3 && !it.busy }
        assertEquals(3, engine.state.value.results.values.flatMap { it.cards }.map { it.key }.distinct().size)
        assertEquals(SourceContinuation.End, engine.state.value.results.getValue(baozi).next)
        assertTrue(engine.state.value.results.getValue(komga).next is SourceContinuation.Next)
        assertEquals(2, calls)
    }

    @Test fun mixedDisplayInterleavesWithoutMergingSameNameOrIdentityAcrossSources() {
        val first = page(komga).cards + page(komga, "second").cards
        val second = page(copy).cards
        val cards = mixedCards(sources, mapOf(komga to DiscoveryFeedState(cards = first), copy to DiscoveryFeedState(cards = second)))
        assertEquals(listOf(komga, copy, komga), cards.map { it.key.sourceId })
        assertEquals(listOf("same", "same", "second"), cards.map { it.key.mangaId })
        assertEquals(first, mixedCards(sources, mapOf(komga to DiscoveryFeedState(cards = first))))
    }
    @Test fun discoveryEmptyQueryRequiresExplicitOptInAndKeepsIndependentFailures() = scenario {
        val controller = AggregateSearchController(this, sources, { true }) { source, query, _ ->
            assertEquals("", query)
            if (source == komga) throw SourceException.Timeout()
            page(source)
        }
        assertFalse(controller.submit(""))
        assertTrue(controller.submit("", allowEmpty = true))
        val result = controller.await { it.loaded == 1 && it.failures == 1 }
        assertEquals(copy, mixedCards(sources, result.results).single().key.sourceId)
    }
}
