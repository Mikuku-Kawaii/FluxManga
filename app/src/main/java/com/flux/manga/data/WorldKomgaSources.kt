package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owned by WorldSession; a generation shares each source's connection pool and rate limit.
 * Entry coroutines cancel their requests, while replacing configuration or leaving the world
 * invalidates the whole source. KomgaSettingsStore owns encrypted user configuration;
 * this registry owns only the active clients. */
internal class WorldKomgaSources(private val session: WorldSession) : Closeable {
    private val clients = mutableMapOf<String, WorldKomgaClient>()

    @Synchronized fun configure(configuration: KomgaConfiguration): WorldKomgaClient {
        require(configuration.sourceId.isNotBlank())
        check(session.accepts(session.identity.value))
        // Build first: a rejected URL/key must not destroy the previously usable source.
        val client = WorldKomgaClient(session, configuration)
        clients.put(configuration.sourceId, client)?.close()
        return client
    }

    @Synchronized fun get(sourceId: String): WorldKomgaClient? = clients[sourceId]
    @Synchronized override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}

internal class WorldKomgaClient(session: WorldSession, configuration: KomgaConfiguration) : Closeable {
    private val worldSession = session
    private val identity = session.identity.value
    private val context = identity.sourceContext(configuration.sourceId, UUID.randomUUID().toString())
    private val closed = AtomicBoolean()
    private val limits = SourceHttpConfig(15_000u, 2u, 100u, 2u, 4u * 1024u * 1024u)
    private val source = if (configuration.auth == KomgaAuth.SESSION) KomgaSource.withSession(context, configuration.baseUrl, configuration.apiKey, limits)
    else if (configuration.auth == KomgaAuth.API_KEY) KomgaSource(context, configuration.baseUrl, configuration.apiKey, limits)
    else KomgaSource.withBasic(context, configuration.baseUrl, "Basic " + java.util.Base64.getEncoder()
        .encodeToString((configuration.username + ":" + configuration.apiKey).toByteArray(Charsets.UTF_8)), limits)
    private val mapper = SourceModelMapper(identity, context, session::accepts)

    private fun active() {
        if (closed.get() || !worldSession.accepts(identity)) throw CancellationException("Expired source session")
    }
    private suspend fun <T> request(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        active()
        val result = try { block() } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            active()
            throw failure
        }
        currentCoroutineContext().ensureActive()
        active()
        return result
    }
    suspend fun accountId() = request { source.accountId(context) }
    suspend fun lists(kind: KomgaListKind, page: Int) = request {
        source.authorizedLists(context, kind, page.toUInt()).also { check(it.context == context) }
    }
    suspend fun entries(list: KomgaAuthorizedList, page: Int) = request {
        source.listEntries(context, list.kind, list.id, page.toUInt()).also { check(it.context == context) }
    }
    suspend fun connect() = request { source.connect(context) }
    suspend fun search(query: String, page: SourcePageRequest = SourcePageRequest.Start): SourceCardPage =
        request { mapper.cards(source.search(context, query, page)) }
    suspend fun recommendations(page: SourcePageRequest = SourcePageRequest.Start): SourceCardPage =
        request { mapper.cards(source.recommendations(context, page)) }
    suspend fun updates(page: SourcePageRequest = SourcePageRequest.Start): SourceCardPage =
        request { mapper.cards(source.updates(context, page)) }
    suspend fun tags(query: String, page: SourcePageRequest = SourcePageRequest.Start): SourceTagList =
        request { mapper.tags(source.tags(context, query, page)) }
    suspend fun tagged(tag: String, page: SourcePageRequest = SourcePageRequest.Start): SourceCardPage =
        request { mapper.cards(source.tagged(context, tag, page)) }
    suspend fun detail(key: SourceMangaKey): SourceDetail = request { source.detail(context, key) }
    suspend fun chapters(key: SourceMangaKey, page: SourcePageRequest = SourcePageRequest.Start): SourceChapterPage =
        request { source.chapters(context, key, page) }
    suspend fun pages(key: SourceMangaKey, chapterId: String): SourcePages =
        request { source.pages(context, key, chapterId, SourcePageRequest.Start) }
    suspend fun pageBytes(key: SourceMangaKey, chapterId: String, number: UInt): SourceHttpReply =
        request { source.pageBytes(context, key, chapterId, number) }
    suspend fun pageFile(key: SourceMangaKey, chapterId: String, number: UInt, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive(); active()
        val file = source.pageFile(context, key, chapterId, number, directory)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch (failure: Exception) { file.close(); throw failure }
    }
    suspend fun coverFile(key: SourceMangaKey, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive(); active()
        val file = source.coverFile(context, key, directory)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch (failure: Exception) { file.close(); throw failure }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) { source.invalidate(); source.destroy() }
    }
}
