package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.Closeable
import kotlinx.coroutines.*
import android.content.Context

internal data class BaoziPublicBrowser(val origin: String, val cookie: String, val userAgent: String) {
    override fun toString() = "BaoziPublicBrowser(redacted)"
}

/** Account-owned book identities share the anonymous public-content transport/limiter.
 * TSID is exclusively owned by WorldBaoziAccountNetwork and never enters this channel. */
internal class WorldBaoziSources(private val session: WorldSession) : Closeable {
    private var transport: SourceHttpSession? = null
    private var browser: BaoziPublicBrowser? = null
    @Volatile private var revision = 0L
    private val clients = mutableMapOf<String, WorldBaoziClient>()
    suspend fun client(app: Context, sourceId: String = BaoziEndpoint.SOURCE_ID): WorldBaoziClient {
        val ticket = revision
        val identity = session.identity.value
        if (identity.world != PrototypeWorld.SFW || !session.accepts(identity)) throw CancellationException()
        val account = BaoziAccountStore(WorldDataStore(app).accountImportDatabase()).current {
            ticket == revision && session.accepts(identity)
        }
        val profile = account?.browserSession?.let {
            BaoziPublicBrowser(it.origin, it.cookie.split(';').map(String::trim)
                .filter { part -> part.substringBefore('=').startsWith("__Host-gk_") }.joinToString("; "), it.userAgent)
        }
        return resolve(sourceId, profile, ticket) {
            session.recoverBaoziAccount(app.applicationContext, identity)
        }
    }
    @Synchronized private fun resolve(sourceId: String, profile: BaoziPublicBrowser?, ticket: Long,
        reconnect: () -> Unit): WorldBaoziClient {
        if (ticket != revision) throw CancellationException()
        require(isBaoziSource(sourceId))
        val identity = session.identity.value
        if (identity.world != PrototypeWorld.SFW || !session.accepts(identity)) throw CancellationException()
        val baseContext = identity.sourceContext(BaoziEndpoint.SOURCE_ID)
        if (browser != profile) { reset(); browser = profile }
        val http = transport ?: SourceHttpSession.baozi(baseContext, profile?.origin ?: BaoziEndpoint.PUBLIC_URL,
            SourceHttpConfig(15_000u, 2u, 500u, 2u, 2u * 1024u * 1024u)).also { transport = it }
        return clients.getOrPut(sourceId) { WorldBaoziClient(session, sourceId, http, baseContext, profile, reconnect) }
    }
    @Synchronized override fun close() {
        revision++
        reset()
    }
    private fun reset() {
        clients.values.forEach { it.close() }; clients.clear()
        transport?.let { it.invalidate(); it.destroy() }; transport = null
        browser = null
    }
}
internal class WorldBaoziClient(private val session: WorldSession, sourceId: String,
    private val transport: SourceHttpSession, private val transportContext: SourceContext,
    private val browser: BaoziPublicBrowser?, private val reconnect: () -> Unit) : Closeable {
    private val identity = session.identity.value
    private val context = identity.sourceContext(sourceId)
    @Volatile private var closed = false
    private fun rekey(reply: SourceHttpReply): SourceHttpReply {
        if (reply.context != transportContext || closed || !session.accepts(identity)) throw CancellationException()
        return reply.copy(context = context)
    }
    val source = BaoziSource(context, { !closed && identity.world == PrototypeWorld.SFW && session.accepts(identity) },
        metadataOrigin = browser?.origin ?: BaoziEndpoint.PUBLIC_URL,
        authenticationRequired = reconnect,
        chapterGet = { manga, chapter -> rekey(transport.baoziChapter(transportContext, manga, chapter)) }) {
        try {
            rekey(if (browser == null) transport.get(transportContext, it)
                else transport.baoziPublicPage(transportContext, it, browser.cookie, browser.userAgent))
        } catch (failure: Exception) {
            if (!closed && session.accepts(identity) && failure.needsBaoziReconnect()) reconnect()
            throw failure
        }
    }
    suspend fun pageFile(page: SourcePageResource, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive()
        val file = transport.baoziPageFile(transportContext, source.pageUrl(page), directory)
        try {
            currentCoroutineContext().ensureActive()
            if (closed || !session.accepts(identity)) throw CancellationException("Expired Baozi session")
            return file
        } catch (failure: Exception) { file.close(); throw failure }
    }
    suspend fun coverFile(id: String, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive()
        val url = source.cover(id)
        val file = transport.baoziCoverFile(transportContext, url, directory)
        try {
            currentCoroutineContext().ensureActive()
            if (closed || !session.accepts(identity)) throw CancellationException("Expired Baozi session")
            return file
        } catch (failure: Exception) { file.close(); throw failure }
    }
    override fun close() { if (!closed) { closed = true; source.clear() } }
}
