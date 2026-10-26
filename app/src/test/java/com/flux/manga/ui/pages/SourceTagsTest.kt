package com.flux.manga.ui.pages

import com.flux.manga.core.*
import com.flux.manga.data.SourceTagList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourceTagsTest {
    private val next = SourceContinuation.Next(SourcePageRequest.Page(2u))
    @Test fun appendRetriesSameQueryAndPageAndKeepsExactTagNames() = runBlocking {
        val requests = mutableListOf<Pair<String, SourcePageRequest>>()
        val tags = SourceTagsController(this, { true }, "A &") { query, page ->
            requests += query to page
            when (requests.size) {
                1 -> SourceTagList(listOf("Tag", "tag"), next)
                2 -> throw SourceException.Transport()
                else -> SourceTagList(listOf("Tag", " Tag "), SourceContinuation.End)
            }
        }
        tags.start(); yield(); tags.more(); yield()
        assertNotNull(tags.state.value.appendError)
        assertEquals(listOf("Tag", "tag"), tags.state.value.tags)
        tags.more(); yield()
        assertEquals(requests[1], requests[2])
        assertEquals(listOf("Tag", "tag", " Tag "), tags.state.value.tags)
        assertEquals(SourceContinuation.End, tags.state.value.next)
    }
    @Test fun changedQueryCancelsOldPageAndCannotMixResults() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val tags = SourceTagsController(this, { true }) { query, _ ->
            if (query == "old") withContext(NonCancellable) { pending.await() }
            SourceTagList(listOf(query), SourceContinuation.End)
        }
        tags.search("old"); yield(); tags.search("new"); yield(); pending.complete(Unit); yield()
        assertEquals("new", tags.state.value.query)
        assertEquals(listOf("new"), tags.state.value.tags)
    }
    @Test fun refreshFailureRetainsRowsAndEmptyNonLastPageCanContinue() = runBlocking {
        var calls = 0
        val tags = SourceTagsController(this, { true }) { _, _ ->
            when (++calls) {
                1 -> SourceTagList(listOf("a"), next)
                2 -> throw SourceException.AuthenticationRequired()
                3 -> SourceTagList(emptyList(), next)
                else -> SourceTagList(listOf("b"), SourceContinuation.End)
            }
        }
        tags.start(); yield(); tags.refresh(); yield()
        assertEquals(listOf("a"), tags.state.value.tags)
        assertNotNull(tags.state.value.error)
        tags.refresh(); yield(); assertTrue(tags.state.value.tags.isEmpty())
        tags.more(); yield(); assertEquals(listOf("b"), tags.state.value.tags)
    }
    @Test fun cancelIsExplicitWhileLifecycleInterruptionCanResume() = runBlocking {
        var calls = 0
        val tags = SourceTagsController(this, { true }) { _, _ ->
            if (++calls < 3) awaitCancellation() else SourceTagList(listOf("a"), SourceContinuation.End)
        }
        tags.start(); yield(); tags.cancel(); tags.start(); yield(); assertEquals(1, calls)
        tags.refresh(); yield(); tags.leave(); tags.start(); yield(); assertEquals(3, calls)
        tags.leave(); tags.start(); yield(); assertEquals(3, calls)
    }
    @Test fun oldWorldCannotPublishOrMakeNewRequests() = runBlocking {
        var active = true
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val tags = SourceTagsController(this, { active }) { _, _ -> calls++; pending.await(); SourceTagList(listOf("late"), SourceContinuation.End) }
        tags.start(); yield(); active = false; pending.complete(Unit); yield()
        tags.search("new"); assertEquals(1, calls); assertTrue(tags.state.value.tags.isEmpty())
    }
}
