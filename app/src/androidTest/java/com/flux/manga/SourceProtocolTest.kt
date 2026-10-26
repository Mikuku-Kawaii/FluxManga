package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.manga.core.*
import com.flux.manga.data.SourceModelMapper
import com.flux.manga.data.sourceContext
import com.flux.manga.session.SessionIdentity
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceProtocolTest {
    private val identity = SessionIdentity(PrototypeWorld.SFW, "m3-contract-session", 9)
    private val context = identity.sourceContext("fixture-m3", "opaque-credential-reference")

    @Test fun bothPagingModesAndEmptyResult_crossRealFfi(): Unit = runBlocking {
        for (mode in SourcePagination.entries) {
            val source = FixtureMangaSource(context, mode)
            try {
                val mapper = SourceModelMapper(identity, context) { it == identity }
                assertEquals(mode, source.capabilities().pagination)
                val first = mapper.cards(source.search(context, "", SourcePageRequest.Start))
                assertEquals(2, first.cards.size)
                assertEquals(first.cards[0].title, first.cards[1].title)
                assertNotEquals(first.cards[0].key, first.cards[1].key)
                assertNull(first.cards[0].pageCount)
                assertFalse(first.cards[0].favorite)
                val next = (first.continuation as SourceContinuation.Next).request
                val last = mapper.cards(source.search(context, "", next))
                assertEquals("3", last.cards.single().key.mangaId)
                assertEquals(SourceContinuation.End, last.continuation)
                val unicode = source.search(context, " 同名 ", SourcePageRequest.Start)
                assertEquals(2, unicode.items.size)
                val empty = source.search(context, "not-present", SourcePageRequest.Start)
                assertTrue(empty.items.isEmpty())
                assertEquals(SourceContinuation.End, empty.continuation)
            } finally { source.destroy() }
        }
    }

    @Test fun detailChaptersPages_keepStableIdentityAndAuthScope(): Unit = runBlocking {
        val source = FixtureMangaSource(context, SourcePagination.CURSOR)
        try {
            val mapper = SourceModelMapper(identity, context) { it == identity }
            val manga = source.search(context, "", SourcePageRequest.Start).items.first()
            val detail = source.detail(context, manga.key)
            assertEquals(manga.key, detail.manga.key)
            assertEquals(context, detail.context)
            val first = source.chapters(context, manga.key, SourcePageRequest.Start)
            val last = source.chapters(context, manga.key, (first.continuation as SourceContinuation.Next).request)
            assertEquals(listOf(1u, 2u, 3u), (first.items + last.items).map { it.ordinal })
            assertEquals(SourceContinuation.End, last.continuation)
            val chapter = first.items.first()
            val pages = source.pages(context, chapter.manga, chapter.id, SourcePageRequest.Start)
            val finalPages = source.pages(context, chapter.manga, chapter.id, (pages.continuation as SourceContinuation.Next).request)
            assertEquals(SourceContinuation.End, finalPages.continuation)
            val key = mapper.key(manga.key)
            (pages.items + finalPages.items).forEachIndexed { index, page ->
                assertEquals(key, mapper.pageKey(page, key, chapter.id))
                assertEquals((index + 1).toUInt(), page.ordinal)
                assertEquals(context.authRef, page.context.authRef)
                assertTrue(page.uri.startsWith("fixture://"))
                assertEquals(600u, page.width)
            }
            assertThrows(IllegalArgumentException::class.java) { mapper.pageKey(pages.items.first(), key, "wrong-chapter") }
        } finally { source.destroy() }
    }

    @Test fun unsupportedAndInvalidRequests_areTypedFailures(): Unit = runBlocking {
        val source = FixtureMangaSource(context, SourcePagination.PAGE_NUMBER)
        try {
            assertFalse(source.capabilities().recommendations)
            try {
                source.recommendations(context, SourcePageRequest.Start)
                fail("Unsupported recommendation must not be an empty success")
            } catch (failure: SourceException.Unsupported) {
                assertEquals(SourceOperation.RECOMMENDATIONS, failure.operation)
            }
            for (page in listOf(SourcePageRequest.Page(0u), SourcePageRequest.Cursor("do-not-log-input"))) {
                try { source.search(context, "", page); fail("Expected invalid paging") }
                catch (failure: SourceException.InvalidRequest) { assertFalse(failure.toString().contains("do-not-log-input")) }
            }
            val key = SourceMangaKey(SourceWorld.SFW, "fixture-m3", "missing")
            try { source.detail(context, key); fail("Expected missing item") }
            catch (_: SourceException.NotFound) { }
            try { source.detail(context, key.copy(sourceId = "other")); fail("Expected source mismatch") }
            catch (_: SourceException.SessionMismatch) { }
        } finally { source.destroy() }
        assertThrows(SourceException.InvalidRequest::class.java) {
            FixtureMangaSource(context.copy(world = SourceWorld.NSFW), SourcePagination.PAGE_NUMBER)
        }
    }

    @Test fun changedSessionAndLateResponses_cannotMapIntoCurrentLibrary(): Unit = runBlocking {
        val source = FixtureMangaSource(context, SourcePagination.PAGE_NUMBER)
        var current = identity
        val mapper = SourceModelMapper(identity, context) { it == current }
        try {
            val reply = source.search(context, "", SourcePageRequest.Start)
            for (changed in listOf(context.copy(generation = 10uL), context.copy(world = SourceWorld.NSFW),
                context.copy(sourceId = "other"), context.copy(processSession = "new-process"), context.copy(authRef = null))) {
                try { source.search(changed, "", SourcePageRequest.Start); fail("Expected scope mismatch") }
                catch (_: SourceException.SessionMismatch) { }
                assertThrows(IllegalArgumentException::class.java) { mapper.cards(reply.copy(context = changed)) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                mapper.card(reply.items.first().copy(pageCount = UInt.MAX_VALUE))
            }
            current = identity.copy(generation = 10)
            assertThrows(CancellationException::class.java) { mapper.cards(reply) }
            assertThrows(IllegalArgumentException::class.java) {
                identity.copy(world = PrototypeWorld.NSFW).sourceContext("fixture-m3")
            }
        } finally { source.destroy() }
    }
}
