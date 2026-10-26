package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed public endpoint from the pinned upstream. No mirror discovery; account access uses the same fixed endpoint. */
internal object CopyMangaEndpoint {
    const val SOURCE_ID = "copymanga-api2024-v3"
    const val BASE_URL = "https://api.2024manga.com/"
}

/** Lazy generation-owned client; metadata pages share one pool and limiter. */
internal class WorldCopyMangaSources(private val session: WorldSession) : Closeable {
    private var value: WorldCopyMangaClient? = null
    @Synchronized fun client(): WorldCopyMangaClient {
        check(session.accepts(session.identity.value))
        return value ?: WorldCopyMangaClient(session).also { value = it }
    }
    @Volatile var credentialRevision = 0L; private set
    private var account: WorldCopyMangaClient? = null
    private var credential: CopyAccount? = null
    @Synchronized fun account(c: CopyAccount, expectedRevision: Long = credentialRevision): WorldCopyMangaClient {
        if (expectedRevision != credentialRevision) throw CancellationException("Copy credentials changed")
        check(session.accepts(session.identity.value) && c.hasCredential)
        if (!sameCopyAccount(credential, c)) {
            // Pool initialization must not invalidate concurrent readers of the same credential.
            // Only an explicit credential change or world shutdown advances the revision.
            account?.close(); account = null; credential = null
            account = WorldCopyMangaClient(session, c.sourceId, c.token, c.service)
            credential = c
        }
        return requireNotNull(account)
    }
    @Synchronized fun closeAccount() { credentialRevision++; account?.close(); account = null; credential = null }
    @Synchronized override fun close() { value?.close(); value = null; closeAccount() }
}

internal class WorldCopyMangaClient(private val session: WorldSession, sourceId: String = CopyMangaEndpoint.SOURCE_ID, token: String? = null,
    service: CopyAccountService = CopyAccountService.LEGACY) : Closeable {
    private val identity = session.identity.value
    private val context = identity.sourceContext(sourceId, if (isCopyAccount(sourceId)) "$sourceId:account" else null)
    private val closed = AtomicBoolean()
    private val config = SourceHttpConfig(15_000u, 2u, 100u, 2u, 4u * 1024u * 1024u)
    private val source = if (isCopyAccount(sourceId) && service == CopyAccountService.WEB) CopyMangaSource.withWebAccount(context, requireNotNull(token), config)
        else if (isCopyAccount(sourceId) && service == CopyAccountService.OFFICIAL) CopyMangaSource.withOfficialAccount(context, token, config)
        else if (isCopyAccount(sourceId)) CopyMangaSource.withAccount(context, token, config)
        else CopyMangaSource(context, CopyMangaEndpoint.BASE_URL, config)
    private val mapper = SourceModelMapper(identity, context, session::accepts)
    private fun active() {
        if (identity.world != com.flux.manga.ui.navigation.PrototypeWorld.SFW || closed.get() || !session.accepts(identity)) throw CancellationException("Expired source session")
    }
    private suspend fun <T> request(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); active()
        val result = try { block() } catch (failure: Exception) {
            currentCoroutineContext().ensureActive(); active(); throw failure
        }
        currentCoroutineContext().ensureActive(); active(); return result
    }
    suspend fun signIn(username: String, encoded: String, salt: UInt): CopyLoginCandidate = request { source.signIn(context, username, encoded, salt) }
    suspend fun accountInfo(): CopyAccountIdentity = request { source.accountInfo(context) }
    suspend fun collection(offset: Int): CopyCollectionPage = request {
        require(offset >= 0)
        source.cloudCollection(context, offset.toUInt()).also { check(it.context == context) }
    }
    suspend fun themes(): List<CopyTheme> = request { source.themes(context) }
    suspend fun themed(theme: String, page: SourcePageRequest): SourceCardPage = request { mapper.cards(source.themed(context, theme, page)) }
    suspend fun recommendations(page: SourcePageRequest): SourceCardPage = request { mapper.cards(source.recommendations(context, page)) }
    suspend fun updates(page: SourcePageRequest): SourceCardPage = request { mapper.cards(source.updates(context, page)) }
    suspend fun search(query: String, page: SourcePageRequest): SourceCardPage = request {
        mapper.cards(source.search(context, authorSearchName(query)?.let { "author:$it" } ?: query, page))
    }
    suspend fun detail(id: String): SourceDetail = request {
        source.detail(context, SourceMangaKey(context.world, context.sourceId, id)).also {
            check(it.context == context); mapper.key(it.manga.key)
        }
    }
    suspend fun book(id: String): CopyMangaBook = request {
        source.book(context, SourceMangaKey(context.world, context.sourceId, id)).also {
            check(it.detail.context == context); mapper.key(it.detail.manga.key)
            it.groups?.forEach { group -> check(group.manga == it.detail.manga.key) }
        }
    }
    suspend fun chapters(id: String, group: String, page: SourcePageRequest): SourceChapterPage = request {
        val key = SourceMangaKey(context.world, context.sourceId, id)
        source.groupChapters(context, key, group, page).also { result ->
            check(result.context == context); check(result.items.all { it.manga == key })
        }
    }
    suspend fun allChapters(id: String, page: SourcePageRequest): SourceChapterPage = request {
        val key = SourceMangaKey(context.world, context.sourceId, id)
        source.chapters(context, key, page).also { result ->
            check(result.context == context && result.items.all { it.manga == key })
        }
    }
    suspend fun pages(id: String, chapter: String): SourcePages = request {
        val key = SourceMangaKey(context.world, context.sourceId, id)
        source.pages(context, key, chapter, SourcePageRequest.Start).also { result ->
            check(result.context == context && result.items.all { it.context == context && it.manga == key && it.chapterId == chapter })
        }
    }
    suspend fun pageFile(page: SourcePageResource, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive(); active()
        val file = source.pageFile(context, page, directory)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch (failure: Exception) { file.close(); throw failure }
    }
    suspend fun coverFile(id: String, directory: String): SourceImageFile {
        currentCoroutineContext().ensureActive(); active()
        val file = source.coverFile(context, SourceMangaKey(context.world, context.sourceId, id), directory)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch (failure: Exception) { file.close(); throw failure }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) { source.invalidate(); source.destroy() }
    }
}

/** Resolve the active encrypted account again; stale routes must not become anonymous requests. */
internal suspend fun copyMangaClient(context: android.content.Context, session: WorldSession, source: String): WorldCopyMangaClient {
    val identity = session.identity.value
    fun checkActive() { if (identity.world != com.flux.manga.ui.navigation.PrototypeWorld.SFW || !session.accepts(identity)) throw CancellationException() }
    checkActive()
    if (source == CopyMangaEndpoint.SOURCE_ID) return session.copyMangaSources.client()
    require(isCopyAccount(source))
    val revision = session.copyMangaSources.credentialRevision
    val c = CopyAccountStore(WorldDataStore(context).accountImportDatabase()).current { session.accepts(identity) }
    currentCoroutineContext().ensureActive(); checkActive()
    if (c?.sourceId != source || !c.hasCredential) throw SourceException.AuthenticationRequired()
    return session.copyMangaSources.account(c, revision)
}
