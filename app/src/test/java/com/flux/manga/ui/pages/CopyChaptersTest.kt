package com.flux.manga.ui.pages

import com.flux.manga.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CopyChaptersTest {
    private val context = SourceContext(SourceWorld.SFW, "copy", "process", 1uL, null)
    private val key = SourceMangaKey(SourceWorld.SFW, "copy", "book")
    private val next = SourceContinuation.Next(SourcePageRequest.Page(2u))
    private fun page(vararg ids: String, more: SourceContinuation = SourceContinuation.End) = SourceChapterPage(context,
        ids.mapIndexed { index, id -> SourceChapter(key, id, "章节 $id", (index + 1).toUInt(), null) }, more, null)

    @Test fun pageRetryAndRefreshFailureKeepExistingChapters() = runBlocking {
        val requests = mutableListOf<Pair<String, SourcePageRequest>>()
        var call = 0
        val chapters = CopyChaptersController(this, { true }) { group, request ->
            requests += group to request
            when (++call) { 1 -> page("one", more = next); 2, 4 -> throw SourceException.Transport(); else -> page("one", "two") }
        }
        chapters.select("main"); yield(); chapters.more(); yield()
        assertEquals(listOf("one"), chapters.state.value.items.map { it.id })
        assertNotNull(chapters.state.value.appendError); assertEquals(next, chapters.state.value.next)
        chapters.more(); yield(); assertEquals(requests[1], requests[2])
        assertEquals(listOf("one", "two"), chapters.state.value.items.map { it.id })
        chapters.refresh(); yield(); assertNotNull(chapters.state.value.error)
        assertEquals(2, chapters.state.value.items.size)
    }
    @Test fun latePreviousGroupCannotReplaceSelectedGroup() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val chapters = CopyChaptersController(this, { true }) { group, _ ->
            if (group == "old") withContext(NonCancellable) { pending.await() }
            page(group)
        }
        chapters.select("old"); yield(); chapters.select("current"); yield()
        pending.complete(Unit); yield()
        assertEquals("current", chapters.state.value.groupId)
        assertEquals(listOf("current"), chapters.state.value.items.map { it.id })
        chapters.select(null); assertTrue(chapters.state.value.items.isEmpty())
    }
    @Test fun cancellationAndLifecycleResumeHaveDifferentIntent() = runBlocking {
        var calls = 0
        val chapters = CopyChaptersController(this, { true }) { _, _ ->
            if (++calls <= 2) awaitCancellation() else page("done")
        }
        chapters.select("main"); yield(); chapters.cancel(); chapters.start(); yield()
        assertEquals(1, calls); assertNotNull(chapters.state.value.notice)
        chapters.refresh(); yield(); chapters.leave(); chapters.start(); yield()
        assertEquals(3, calls); assertEquals("done", chapters.state.value.items.single().id)
        chapters.leave(); chapters.start(); yield(); assertEquals(3, calls)
    }
    @Test fun emptyNonLastPageAndExpiredSessionRemainDistinct() = runBlocking {
        var active = true
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val chapters = CopyChaptersController(this, { active }) { _, _ ->
            if (++calls == 1) page(more = next) else { pending.await(); page("late") }
        }
        chapters.select("main"); yield()
        assertTrue(chapters.state.value.loaded); assertTrue(chapters.state.value.items.isEmpty()); assertEquals(next, chapters.state.value.next)
        chapters.more(); yield(); active = false; pending.complete(Unit); yield()
        assertTrue(chapters.state.value.items.isEmpty())
        chapters.refresh(); yield(); assertEquals(2, calls)
    }
}
