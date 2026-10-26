package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KomgaSourceTest {
    private val context = SourceContext(SourceWorld.SFW, "komga-fixture", "process", 1uL, "opaque-ref")
    private val key = SourceMangaKey(SourceWorld.SFW, "komga-fixture", "series1")
    private fun source(server: KomgaHttpFixture, apiKey: String = "synthetic-key") =
        KomgaSource(context, server.base, apiKey, SourceHttpConfig(10000u, 2u, 0u, 2u, 65536u))
    private suspend inline fun <reified T: Throwable> failure(crossinline block: suspend () -> Unit): T {
        try { block() } catch(error: Throwable) { if(error is T) return error; throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
    private suspend fun await(check: () -> Boolean) { withTimeout(5000) { while(!check()) delay(10) } }

    @Test fun authenticatedMetadataPaginationAndImageUseSameSource(): Unit = runBlocking {
        KomgaHttpFixture().use { server ->
            val client = source(server)
            try {
                assertEquals(context, client.connect(context))
                assertFalse(client.capabilities().recommendations)
                val query = "合成 \" title & ?"
                val first = client.search(context, query, SourcePageRequest.Start)
                assertEquals(2, first.items.size)
                assertEquals(first.items[0].title, first.items[1].title)
                assertNotEquals(first.items[0].key, first.items[1].key)
                assertNull(first.items[0].pageCount)
                val next = (first.continuation as SourceContinuation.Next).request
                assertEquals(SourcePageRequest.Page(2u), next)
                assertEquals(SourceContinuation.End, client.search(context, query, next).continuation)
                assertTrue(client.search(context, "empty", SourcePageRequest.Start).items.isEmpty())
                val detail = client.detail(context, key)
                assertEquals("合成简介", detail.description)
                assertEquals(listOf("Tag", "tag", " 中文 / &? "), detail.tags)
                val chapters = client.chapters(context, key, SourcePageRequest.Start)
                assertEquals(listOf("book2", "book10"), chapters.items.map { it.id })
                assertEquals(listOf(1u, 2u), chapters.items.map { it.ordinal })
                val pages = client.pages(context, key, "book2", SourcePageRequest.Start)
                assertEquals(listOf(1u,2u), pages.items.map { it.ordinal })
                assertNull(pages.items[0].width)
                assertEquals(10u, pages.items[1].width)
                assertEquals("${server.base}api/v1/books/book2/pages/1", pages.items[0].uri)
                assertTrue(pages.items.all { it.context == context && it.manga == key })
                assertEquals("synthetic-page-bytes", client.pageBytes(context, key, "book2", 1u).body.toString(Charsets.UTF_8))
                assertTrue(server.requests.all { it.validAuth })
                assertEquals(query, JSONObject(server.requests.first { it.method == "POST" }.body).getString("fullTextSearch"))
                failure<SourceException.Unsupported> { client.recommendations(context, SourcePageRequest.Start) }
            } finally { client.destroy() }
        }
    }

    @Test fun updatesBindingKeepsEmptyNonLastPageAndSourceContext(): Unit = runBlocking {
        KomgaHttpFixture().use { server ->
            val client = source(server)
            try {
                assertTrue(client.capabilities().updates)
                val first = client.updates(context, SourcePageRequest.Start)
                assertTrue(first.items.isEmpty())
                assertEquals(context, first.context)
                val next = (first.continuation as SourceContinuation.Next).request
                val last = client.updates(context, next)
                assertEquals("series2", last.items.single().key.mangaId)
                assertEquals(context.sourceId, last.items.single().key.sourceId)
                assertEquals(SourceContinuation.End, last.continuation)
                server.mode = "wrong-page"
                failure<SourceException.InvalidResponse> { client.updates(context, SourcePageRequest.Start) }
                server.mode = "forbidden"
                failure<SourceException.AuthenticationRequired> { client.updates(context, SourcePageRequest.Start) }
            } finally { client.destroy() }
        }
    }

    @Test fun tagBindingsPreserveLiteralNamesAndPaging(): Unit = runBlocking {
        KomgaHttpFixture().use { server ->
            val client = source(server)
            try {
                assertTrue(client.capabilities().tags)
                val tag = " 标签 / \" &? "
                val first = client.tags(context, tag, SourcePageRequest.Start)
                assertEquals(context, first.context)
                assertEquals(listOf("Tag", "tag"), first.items)
                val next = (first.continuation as SourceContinuation.Next).request
                assertEquals(SourceContinuation.End, client.tags(context, tag, next).continuation)
                val books = client.tagged(context, tag, SourcePageRequest.Start)
                assertEquals(2, books.items.size)
                assertEquals(context.sourceId, books.items[0].key.sourceId)
                val body = JSONObject(server.requests.first { it.method == "POST" }.body)
                assertEquals(tag, body.getJSONObject("condition").getJSONArray("allOf").getJSONObject(0).getJSONObject("tag").getString("value"))
                assertEquals(SourceContinuation.End, client.tagged(context, tag, (books.continuation as SourceContinuation.Next).request).continuation)
                failure<SourceException.SessionMismatch> { client.tags(context.copy(generation = 99uL), "", SourcePageRequest.Start) }
            } finally { client.destroy() }
        }
    }

    @Test fun invalidResponsesAndWrongOwnershipNeverBecomeContent(): Unit = runBlocking {
        KomgaHttpFixture().use { server ->
            val client = source(server)
            try {
                for (mode in listOf("malformed", "html")) {
                    server.mode = mode
                    val error = failure<SourceException.InvalidResponse> { client.connect(context) }
                    assertFalse(error.toString().contains("secret-server-response"))
                }
                server.mode = "wrong-page"
                failure<SourceException.InvalidResponse> { client.search(context, "", SourcePageRequest.Start) }
                server.mode = "wrong-id"
                failure<SourceException.InvalidResponse> { client.detail(context, key) }
                server.mode = "wrong-parent"
                failure<SourceException.InvalidResponse> { client.chapters(context, key, SourcePageRequest.Start) }
                val count = server.requests.size
                failure<SourceException.InvalidResponse> { client.pages(context, key, "book2", SourcePageRequest.Start) }
                assertEquals(count + 1, server.requests.size) // No page request after mismatching book owner.
                server.mode = "duplicate-page"
                failure<SourceException.InvalidResponse> { client.pages(context, key, "book2", SourcePageRequest.Start) }
                val before = server.requests.size
                failure<SourceException.SessionMismatch> { client.detail(context.copy(generation = 2uL), key) }
                failure<SourceException.SessionMismatch> { client.detail(context, key.copy(sourceId = "other")) }
                failure<SourceException.InvalidRequest> { client.detail(context, key.copy(mangaId = "../escape")) }
                failure<SourceException.InvalidRequest> { client.search(context, "", SourcePageRequest.Page(0u)) }
                failure<SourceException.InvalidRequest> { client.pages(context, key, "book2", SourcePageRequest.Page(2u)) }
                assertEquals(before, server.requests.size)
            } finally { client.destroy() }
        }
    }

    @Test fun apiKeyIsolationAndReadOnlyPostRetry(): Unit = runBlocking {
        KomgaHttpFixture().use { server -> KomgaHttpFixture("different-key").use { other ->
            val client = source(server); val second = source(other, "different-key"); val wrong = source(other)
            try {
                failure<SourceException.AuthenticationRequired> { wrong.connect(context) }
                assertEquals(1, other.requests.size)
                assertEquals(context, second.connect(context))
                server.mode = "forbidden"
                failure<SourceException.AuthenticationRequired> { client.connect(context) }
                assertEquals(1, server.requests.size)
                server.mode = "retry"
                client.search(context, "retry", SourcePageRequest.Start)
                val posts = server.requests.filter { it.method == "POST" }
                assertEquals(2, posts.size)
                assertEquals(posts[0].body, posts[1].body)
                assertTrue(posts.all { it.validAuth })
                client.invalidate()
                failure<SourceException.SessionMismatch> { client.connect(context) }
            } finally { client.destroy(); second.destroy(); wrong.destroy() }
        } }
    }

    @Test fun registryReuseEntryCancellationReplacementAndWorldInvalidation(): Unit = runBlocking {
        KomgaHttpFixture().use { server ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var session: WorldSession
            instrumentation.runOnMainSync { session = WorldSession().apply { configure(instrumentation.targetContext); resume() } }
            fun configure() = session.komgaSources.configure(KomgaConfiguration("komga-fixture", server.base, "synthetic-key"))
            val first = configure()
            try {
                first.connect()
                assertSame(first, session.komgaSources.get("komga-fixture"))
                assertEquals(2, first.search("").cards.size)
                server.mode = "slow"
                val entry = async { first.connect() }
                await { server.requests.size == 3 }; entry.cancelAndJoin()
                server.mode = "normal"
                first.connect() // Cancelling one entry preserves shared source session.
                val replacement = configure()
                failure<CancellationException> { first.connect() }
                replacement.connect()
                server.mode = "slow"
                val before = server.requests.size
                val pending = async { failure<CancellationException> { replacement.connect() } }
                await { server.requests.size > before }
                val preparation = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync { session.switch(FixtureWorldStore(instrumentation.targetContext) { preparation.await() }) }
                withTimeout(1500) { pending.await() }
                assertNull(session.komgaSources.get("komga-fixture"))
                failure<CancellationException> { replacement.search("") }
                instrumentation.runOnMainSync { session.pause(); session.resume() }
                server.mode = "normal"
                val renewed = configure()
                assertNotEquals(first, renewed)
                renewed.connect()
                instrumentation.runOnMainSync { session.pause(1000); session.resume(32001) }
                failure<CancellationException> { renewed.connect() }
                assertNull(session.komgaSources.get("komga-fixture"))
            } finally { session.komgaSources.close(); instrumentation.runOnMainSync { session.pause() } }
        }
    }
    @Test fun basicAuthorizedListBindingsKeepBookSeriesIdentityAndReaderAuth(): Unit = runBlocking {
        KomgaHttpFixture(basic = true).use { server ->
            val client = KomgaSource.withBasic(context, server.base, "Basic dXNlcjpwYXNz", SourceHttpConfig(10000u, 1u, 0u, 0u, 65536u))
            try {
                assertEquals("synthetic-user", client.accountId(context))
                assertEquals("lib", client.authorizedLists(context, KomgaListKind.LIBRARY, 0u).items.single().id)
                val list = client.authorizedLists(context, KomgaListKind.READ_LIST, 0u).items.single()
                val page = client.listEntries(context, list.kind, list.id, 0u)
                assertEquals(context, page.context); assertEquals("series1", page.items.single().seriesId)
                assertEquals("book2", page.items.single().bookId); assertNull(page.nextPage)
                assertEquals("synthetic-page-bytes", client.pageBytes(context, key, "book2", 1u).body.toString(Charsets.UTF_8))
                assertTrue(server.requests.all { it.validAuth })
            } finally { client.destroy() }
        }
    }
}
