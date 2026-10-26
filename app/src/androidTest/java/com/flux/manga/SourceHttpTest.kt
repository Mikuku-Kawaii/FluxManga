package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.core.*
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.data.WorldSourceHttpClient
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceHttpTest {
    private fun context(id: String = "http-fixture", auth: Boolean = false) =
        SourceContext(SourceWorld.SFW, id, "http-process", 1uL, if (auth) "opaque-ref" else null)
    private fun config(timeout: UInt = 10_000u, limit: UInt = 2u, interval: UInt = 0u, retries: UInt = 2u, bytes: UInt = 1024u) =
        SourceHttpConfig(timeout, limit, interval, retries, bytes)
    private suspend inline fun <reified T : Throwable> failure(crossinline call: suspend () -> Unit): T {
        try { call() } catch (error: Throwable) { if (error is T) return error; throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
    private suspend fun await(check: () -> Boolean) { withTimeout(5000) { while (!check()) delay(10) } }

    @Test fun paginationAndImageShareAuth_otherSourceHasNoCookieJar(): Unit = runBlocking {
        HttpFixture().use { server ->
            val c = context(auth = true)
            val a = SourceHttpSession(c, server.base, "Bearer fixture-token", "fixture=cookie", config())
            val other = context("other-source")
            val b = SourceHttpSession(other, server.base, null, null, config())
            try {
                val first = a.get(c, "catalog?page=1")
                assertEquals(c, first.context)
                val json = JSONObject(first.body.toString(Charsets.UTF_8))
                assertEquals(2, json.getJSONArray("ids").length())
                val last = JSONObject(a.get(c, json.getString("next")).body.toString(Charsets.UTF_8))
                assertTrue(last.isNull("next"))
                assertEquals("third", last.getJSONArray("ids").getString(0))
                assertEquals("synthetic-image-bytes", a.get(c, "image").body.toString(Charsets.UTF_8))
                b.get(other, "set-cookie")
                assertEquals("ok", b.get(other, "anonymous").body.toString(Charsets.UTF_8))
                failure<SourceException.SessionMismatch> { a.get(c.copy(generation = 2uL), "image") }
                failure<SourceException.SessionMismatch> { a.get(c.copy(world = SourceWorld.NSFW), "image") }
                failure<SourceException.SessionMismatch> { a.get(other, "image") }
                assertEquals(1, server.count("/image"))
            } finally { a.destroy(); b.destroy() }
        }
    }

    @Test fun boundedRetriesAuthFailureAndPartialSourceFailure(): Unit = runBlocking {
        HttpFixture().use { server ->
            val c = context(); val a = SourceHttpSession(c, server.base, null, null, config())
            val other = context("other"); val b = SourceHttpSession(other, server.base, null, null, config())
            try {
                a.get(c, "retry"); assertEquals(2, server.count("/retry"))
                for (path in listOf("unauthorized", "forbidden")) {
                    failure<SourceException.AuthenticationRequired> { a.get(c, path) }
                    assertEquals(1, server.count("/$path"))
                }
                val failed = async { failure<SourceException.HttpStatus> { a.get(c, "fail") } }
                val success = async { b.get(other, "anonymous") }
                assertEquals("ok", success.await().body.toString(Charsets.UTF_8))
                assertEquals(503.toUShort(), failed.await().status)
                assertEquals(3, server.count("/fail"))
                val start = System.nanoTime()
                val rate = failure<SourceException.RateLimited> { a.get(c, "rate") }
                assertEquals(1u, rate.retryAfterSeconds)
                assertEquals(3, server.count("/rate"))
                assertTrue("Retry-After was ignored", (System.nanoTime() - start) / 1_000_000 >= 1900)
                // Final 429 also holds back a following request on this source session.
                val cooldown = System.nanoTime(); a.get(c, "anonymous")
                assertTrue((System.nanoTime() - cooldown) / 1_000_000 >= 800)
            } finally { a.destroy(); b.destroy() }
        }
    }

    @Test fun totalDeadlineBodyBoundAndRedirectPolicy(): Unit = runBlocking {
        HttpFixture().use { server ->
            val c = context(); val a = SourceHttpSession(c, server.base, null, null, config(timeout = 300u))
            try {
                failure<SourceException.Timeout> { a.get(c, "slow") }
                failure<SourceException.Timeout> { a.get(c, "slow-body") }
                failure<SourceException.Timeout> { a.get(c, "rate") }
                assertEquals(1, server.count("/rate")) // Deadline must not shorten Retry-After.
                // Use a fresh session below: the first source remains in its rate cooldown.
            } finally { a.destroy() }
            val b = SourceHttpSession(c, server.base, null, null, config(timeout = 300u))
            try {
                for (path in listOf("large", "chunked")) failure<SourceException.InvalidResponse> { b.get(c, path) }
                val redirect = failure<SourceException.HttpStatus> { b.get(c, "redirect") }
                assertEquals(302.toUShort(), redirect.status)
                assertEquals(0, server.count("/anonymous"))
                failure<SourceException.InvalidRequest> { b.get(c, "https://example.invalid/credential-target") }
                assertEquals(1, server.count("/slow"))
                await { b.activity().pending == 0uL && b.activity().inFlight == 0uL }
            } finally { b.destroy() }
        }
    }

    @Test fun concurrencyCapAndMinimumRequestSpacing(): Unit = runBlocking {
        HttpFixture().use { server ->
            val c = context(); val a = SourceHttpSession(c, server.base, null, null, config(limit = 2u, interval = 80u))
            try {
                (1..5).map { async { a.get(c, "work") } }.awaitAll()
                assertEquals(5, server.count("/work"))
                assertTrue("Exceeded source session concurrency: ${server.peak}", server.peak.get() <= 2)
                server.starts.sorted().zipWithNext().forEach { (x, y) -> assertTrue((y - x) / 1_000_000 >= 60) }
                assertEquals(0uL, a.activity().pending)
            } finally { a.destroy() }
        }
    }

    @Test fun callerCancellationAndInvalidationReleaseQueuedNativeWork(): Unit = runBlocking {
        HttpFixture().use { server ->
            val c = context(); val a = SourceHttpSession(c, server.base, null, null, config(limit = 1u))
            try {
                val slow = async { a.get(c, "slow") }; await { server.count("/slow") == 1 }
                val queued = async { a.get(c, "anonymous") }; await { a.activity().pending == 2uL }
                slow.cancelAndJoin()
                assertEquals("ok", withTimeout(1500) { queued.await() }.body.toString(Charsets.UTF_8))
                await { a.activity().pending == 0uL }
                val running = async { failure<SourceException.SessionMismatch> { a.get(c, "slow") } }
                await { server.count("/slow") == 2 }
                val pending = async { failure<SourceException.SessionMismatch> { a.get(c, "anonymous") } }
                await { a.activity().pending == 2uL }; a.invalidate()
                running.await(); pending.await()
                await { a.activity().pending == 0uL && a.activity().inFlight == 0uL }
                assertEquals(1, server.count("/anonymous"))
                failure<SourceException.SessionMismatch> { a.get(c, "anonymous") }
            } finally { a.destroy() }
        }
    }

    @Test fun worldSwitchAndEntryExitCancelRequestsAndRejectLatePublication(): Unit = runBlocking {
        HttpFixture().use { server ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var session: WorldSession
            instrumentation.runOnMainSync { session = WorldSession().apply { configure(instrumentation.targetContext); resume() } }
            val owner = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            val client = WorldSourceHttpClient(session, owner, "http-fixture", server.base)
            val preparation = CompletableDeferred<Unit>()
            try {
                val pending = async { failure<CancellationException> { client.get("slow") } }
                await { server.count("/slow") == 1 }
                instrumentation.runOnMainSync { session.switch(FixtureWorldStore(instrumentation.targetContext) { preparation.await() }) }
                withTimeout(1500) { pending.await() }
                failure<CancellationException> { client.get("anonymous") }
                instrumentation.runOnMainSync { session.pause(); session.resume() }
                val entry = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
                val next = WorldSourceHttpClient(session, entry, "http-fixture", server.base)
                try {
                    val request = async { failure<CancellationException> { next.get("slow") } }
                    await { server.count("/slow") == 2 }; entry.cancel()
                    withTimeout(1500) { request.await() }
                    failure<CancellationException> { next.get("anonymous") }
                    assertEquals(0, server.count("/anonymous"))
                } finally { next.close(); entry.cancel() }
            } finally {
                client.close(); owner.cancel()
                instrumentation.runOnMainSync { session.pause() }
            }
        }
    }
}
