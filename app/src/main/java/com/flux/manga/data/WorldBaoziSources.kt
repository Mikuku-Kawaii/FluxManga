package com.flux.manga.data

import android.content.Context
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.Closeable
import kotlinx.coroutines.*

internal data class BaoziPublicBrowser(val origin: String, val cookie: String, val userAgent: String) {
    override fun toString() = "BaoziPublicBrowser(redacted)"
}
internal data class BaoziTransport(val http: SourceHttpSession, val context: SourceContext, val browser: BaoziPublicBrowser?)

/** TSID stays in the account channel. Public reads share only browser-verification cookies. */
internal class WorldBaoziSources(private val session: WorldSession) : Closeable {
    private var transport: BaoziTransport? = null
    @Volatile private var revision = 0L
    private val clients = mutableMapOf<String, WorldBaoziClient>()

    private suspend fun transport(app: Context): BaoziTransport {
        val ticket = revision
        val identity = session.identity.value
        if (identity.world != PrototypeWorld.SFW || !session.accepts(identity)) throw CancellationException()
        val account = BaoziAccountStore(WorldDataStore(app).accountImportDatabase()).current {
            ticket == revision && session.accepts(identity)
        }
        val browser = account?.browserSession?.let {
            BaoziPublicBrowser(it.origin, it.cookie.split(';').map(String::trim)
                .filter { part -> part.substringBefore('=').startsWith("__Host-gk_") }.joinToString("; "), it.userAgent)
        }
        return synchronized(this) {
            if (ticket != revision || !session.accepts(identity)) throw CancellationException()
            transport?.takeIf { it.browser == browser } ?: run {
                resetTransport()
                val context = identity.sourceContext(BaoziEndpoint.SOURCE_ID)
                BaoziTransport(SourceHttpSession.baozi(context, browser?.origin ?: BaoziEndpoint.PUBLIC_URL,
                    SourceHttpConfig(15_000u, 2u, 500u, 2u, 2u * 1024u * 1024u)), context, browser).also { transport = it }
            }
        }
    }

    suspend fun client(app: Context, sourceId: String = BaoziEndpoint.SOURCE_ID): WorldBaoziClient {
        require(isBaoziSource(sourceId))
        val application = app.applicationContext
        val identity = session.identity.value
        val initial = transport(application)
        return synchronized(this) {
            if (!session.accepts(identity)) throw CancellationException()
            clients.getOrPut(sourceId) {
                WorldBaoziClient(session, sourceId, initial.browser?.origin ?: BaoziEndpoint.PUBLIC_URL,
                    { transport(application) }, { path ->
                        suspend fun fetch(): SourceHttpReply {
                            val current = transport(application)
                            val reply = if (current.browser == null) current.http.get(current.context, path)
                                else current.http.baoziPublicPage(current.context, path, current.browser.cookie, current.browser.userAgent)
                            if (reply.body.size <= 2 * 1024 * 1024) {
                                val html = reply.body.toString(Charsets.UTF_8)
                                if (html.contains("__gatekeeper_challenge", true) || html.contains("cf-chl-", true) || html.contains("challenge-platform", true))
                                    throw SourceException.HttpStatus(403u)
                            }
                            return reply
                        }
                        val before = revision
                        try { fetch() }
                        catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (!session.accepts(identity) || !session.state.value.foreground) throw CancellationException()
                            val renewed = if (before != revision) true else if (failure.needsBaoziReconnect())
                                withContext(Dispatchers.Main.immediate) { session.baoziAccount(application).recoverAndAwait() } else false
                            if (!renewed) throw failure
                            // Replay the exact read once, retaining its query, cursor and visible page.
                            fetch()
                        }
                    })
            }
        }
    }

    /** Replace transport credentials without disposing current read models or parser caches. */
    @Synchronized fun credentialsChanged() { revision++; resetTransport() }
    @Synchronized override fun close() {
        revision++
        clients.values.forEach { it.close() }; clients.clear()
        resetTransport()
    }
    private fun resetTransport() {
        transport?.http?.let { it.invalidate(); it.destroy() }; transport = null
    }
}

internal class WorldBaoziClient(private val session: WorldSession, sourceId: String, origin: String,
    private val transport: suspend () -> BaoziTransport,
    metadata: suspend (String) -> SourceHttpReply) : Closeable {
    private val identity = session.identity.value
    private val context = identity.sourceContext(sourceId)
    @Volatile private var closed = false
    private fun active() { if (closed || !session.accepts(identity)) throw CancellationException() }
    private fun rekey(reply: SourceHttpReply): SourceHttpReply {
        active()
        if (reply.context != identity.sourceContext(BaoziEndpoint.SOURCE_ID)) throw CancellationException()
        return reply.copy(context = context)
    }
    val source = BaoziSource(context, { !closed && identity.world == PrototypeWorld.SFW && session.accepts(identity) },
        metadataOrigin = origin,
        chapterGet = { manga, chapter ->
            active(); val current = transport()
            rekey(current.http.baoziChapter(current.context, manga, chapter))
        }) { path -> active(); rekey(metadata(path)) }

    suspend fun pageFile(page: SourcePageResource, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive(); active()
        val current = transport()
        val file = current.http.baoziPageFile(current.context, source.pageUrl(page), directory)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch (failure: Exception) { file.close(); throw failure }
    }
    suspend fun coverFile(id: String, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive(); active()
        val url = source.cover(id)
        val current = transport()
        val file = current.http.baoziCoverFile(current.context, url, directory)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch (failure: Exception) { file.close(); throw failure }
    }
    override fun close() { if (!closed) { closed = true; source.clear() } }
}
