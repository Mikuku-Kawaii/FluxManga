package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Transactional fake shares the production codec/store/controller; no Android/real account IO. */
private class KomgaDb : AccountImportDatabase {
    val secrets = mutableMapOf<String, ByteArray>()
    val books = mutableMapOf<Pair<String, String>, OnlineBook>()
    val progress = mutableSetOf<Pair<String, String>>()
    private val lock = Mutex()
    var beforeCommit: (() -> Unit)? = null
    var afterCommit: (suspend () -> Unit)? = null
    override suspend fun <T> transaction(allowed: () -> Boolean, action: (AccountImportRows) -> T): T {
        val result = lock.withLock {
            currentCoroutineContext().ensureActive()
            if (!allowed()) throw CancellationException()
            val nextSecrets = secrets.mapValues { it.value.copyOf() }.toMutableMap()
            val nextBooks = books.toMutableMap()
            val result = action(object : AccountImportRows {
                override fun read(slot: String) = nextSecrets[slot]?.copyOf()
                override fun write(slot: String, value: ByteArray) { nextSecrets[slot] = value.copyOf() }
                override fun book(source: String, id: String) = nextBooks[source to id]
                override fun hasProgress(source: String, id: String) = source to id in progress
                override fun putBook(book: OnlineBook) { nextBooks[book.sourceId to book.mangaId] = book }
            })
            beforeCommit?.invoke()
            if (!allowed()) throw CancellationException()
            secrets.clear(); secrets.putAll(nextSecrets); books.clear(); books.putAll(nextBooks)
            result
        }
        afterCommit?.invoke()
        return result
    }
}

private fun config(key: String = "synthetic", base: String = "https://fixture.invalid/komga/", user: String? = null) =
    KomgaConfiguration("komga-${UUID.randomUUID()}", base, key, KomgaAuth.API_KEY, userId = user)
private fun list(id: String = "list1", kind: KomgaListKind = KomgaListKind.READ_LIST) = KomgaAuthorizedList(id, "合成列表", kind, true, false)
private fun entry(n: Int, series: String = "s$n") = KomgaImportEntry(series, "合成 $series", "b$n", n.toUInt())
private fun context(c: KomgaConfiguration) = SourceContext(SourceWorld.SFW, c.sourceId, "test", 1uL, "opaque")
private class KomgaNetwork : KomgaAccountNetwork {
    var user = "userA"; var reads = 0; var verifies = 0
    var fetch: suspend (KomgaConfiguration, KomgaAuthorizedList, Int) -> KomgaImportPage = { c, _, page ->
        KomgaImportPage(context(c), (page * 20 until minOf(page * 20 + 20, 45)).map { entry(it) }, if (page < 2) (page + 1).toUInt() else null)
    }
    override suspend fun verify(c: KomgaConfiguration): String { verifies++; return user }
    override suspend fun lists(c: KomgaConfiguration, kind: KomgaListKind, page: Int) = KomgaListPage(context(c), listOf(list(kind = kind)), null)
    override suspend fun entries(c: KomgaConfiguration, list: KomgaAuthorizedList, page: Int): KomgaImportPage { reads++; return fetch(c, list, page) }
}
class KomgaAccountFlowTest {
    @Test fun versionedConfigurationAndTaskRoundTripRejectDamageAndRedactCredentials() {
        val c = KomgaConfiguration("komga-${UUID.randomUUID()}", "https://fixture.invalid/proxy/", "secret", KomgaAuth.BASIC, "user@example.invalid", "u1")
        val restored = KomgaAccountCodec.configuration(KomgaAccountCodec.configuration(c))
        assertTrue(sameKomgaCredential(c, restored)); assertFalse(c.toString().contains("secret"))
        val task = KomgaImportTask("job", list(), true, listOf(entry(0), entry(1)))
        assertEquals(task, KomgaAccountCodec.task(KomgaAccountCodec.task(task)))
        try { KomgaAccountCodec.task(byteArrayOf(1, 2)); fail() } catch (_: Exception) { }
    }
    @Test fun legacyIdentitySurvivesVerificationCredentialRotationAndLogout() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val old = config()
        store.save(old) { true }
        val first = store.activate(config(), "userA") { true }
        assertEquals(old.sourceId, first.sourceId)
        val rotated = store.activate(config("rotated"), "userA") { true }
        assertEquals(first.sourceId, rotated.sourceId)
        store.logout(rotated) { true }; assertFalse(store.current { true }!!.hasCredential)
        assertEquals(first.sourceId, store.activate(config("new"), "userA") { true }.sourceId)
        val second = store.activate(config(), "userB") { true }; assertNotEquals(first.sourceId, second.sourceId)
        assertEquals(first.sourceId, store.activate(config(), "userA") { true }.sourceId)
        assertNotEquals(first.sourceId, store.activate(config(base = "https://other.invalid/"), "userA") { true }.sourceId)
    }
    @Test fun multipleVolumesAndListsDeduplicateSeriesKeepEveryReferenceAndOriginalState() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val c = store.activate(config(), "userA") { true }
        db.books[c.sourceId to "s0"] = OnlineBook(c.sourceId, "s0", "原书名", false, 7)
        db.progress += c.sourceId to "progress"
        val items = listOf(entry(0), entry(1, "s0"), entry(2, "progress"), entry(3))
        var task = store.begin(c, list(), items, true) { true }
        task = store.commit(c, task, items, null) { true }
        assertEquals(1, task.added); assertEquals(3, task.existing); assertTrue(task.done)
        assertEquals("原书名", db.books[c.sourceId to "s0"]!!.title); assertFalse(db.books[c.sourceId to "s0"]!!.favorite)
        assertFalse(db.books[c.sourceId to "progress"]!!.favorite); assertTrue(db.books[c.sourceId to "s3"]!!.favorite)
        task = store.begin(c, list("another"), items, false) { true }; store.commit(c, task, items, null) { true }
        assertEquals(3, db.books.size); assertEquals(8, db.secrets.keys.count { it.startsWith("komga-import-ref:") })
        assertTrue(db.progress.contains(c.sourceId to "progress"))
        assertTrue(db.secrets.filterKeys { it.startsWith("komga-import-ref:") }.values.none { it.toString(Charsets.UTF_8).contains("synthetic") })
    }
    @Test fun rollbackKeepsPageAndBooksTogetherAndAccountChangeRejectsLateBatch() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val c = store.activate(config(), "userA") { true }
        val task = store.begin(c, list(), null, false) { true }
        var allowed = true; db.beforeCommit = { allowed = false }
        try { store.commit(c, task, listOf(entry(0)), 1) { allowed }; fail() } catch (_: CancellationException) { }
        db.beforeCommit = null
        assertEquals(task, store.task(c.sourceId) { true }); assertTrue(db.books.isEmpty())
        store.activate(config(), "userB") { true }
        try { store.commit(c, task, listOf(entry(0)), 1) { true }; fail() } catch (_: CancellationException) { }
        assertTrue(db.books.isEmpty()); assertEquals(task, store.task(c.sourceId) { true })
    }
    @Test fun explicitResumeLoadsCheckpointAndNeverReplaysOnReopen() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val c = store.activate(config(), "userA") { true }
        val network = KomgaNetwork(); var task = store.begin(c, list(), null, false) { true }
        task = store.commit(c, task, (0..19).map { entry(it) }, 1) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = KomgaAccountController(scope, { true }, store, network)
        controller.start(); assertEquals(0, network.reads); assertEquals(task, controller.state.value.task)
        controller.resume(); assertEquals(45, db.books.size); assertTrue(controller.state.value.task!!.done); assertEquals(2, network.reads)
        controller.leave(); controller.start(); assertEquals(2, network.reads)
        controller.resume(true); assertEquals(45, db.books.size); assertEquals(0, controller.state.value.task!!.added)
        assertEquals(45, controller.state.value.task!!.existing); scope.cancel()
    }
    @Test fun failedPageRetainsPartialSuccessAndCanRetryWithoutResettingProgress() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val c = store.activate(config(), "userA") { true }
        store.begin(c, list(), null, false) { true }
        val network = KomgaNetwork(); val original = network.fetch; var fail = true
        network.fetch = { cfg, l, page -> if (page == 1 && fail) throw SourceException.HttpStatus(503u) else original(cfg,l,page) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val controller = KomgaAccountController(scope, { true }, store, network)
        controller.start(); controller.resume(); assertEquals(20, db.books.size); assertEquals(1, controller.state.value.task!!.page); assertNotNull(controller.state.value.error)
        fail = false; controller.resume(); assertEquals(45, db.books.size); assertEquals(45, controller.state.value.task!!.added); scope.cancel()
    }
    @Test fun cancelledAfterCommitReloadsExactPositionWithoutRepeatingNetwork() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val c = store.activate(config(), "userA") { true }
        store.begin(c, list(), null, false) { true }
        val network = KomgaNetwork(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = KomgaAccountController(scope, { true }, store, network); controller.start()
        db.afterCommit = { if (db.books.size == 20) { db.afterCommit = null; awaitCancellation() } }
        controller.resume(); assertEquals(20, db.books.size)
        controller.cancel(); assertEquals(1, network.reads); assertEquals(1, controller.state.value.task!!.page); assertFalse(controller.state.value.verified)
        controller.start(); controller.resume(); assertEquals(45, db.books.size); assertEquals(45, controller.state.value.task!!.added); scope.cancel()
    }
    @Test fun authenticationFailureAndWorldChangeCannotImportOrPublishLateResults() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val c = store.activate(config(), "userA") { true }
        val network = KomgaNetwork(); network.user = "userB"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var active = true
        val controller = KomgaAccountController(scope, { active }, store, network)
        controller.start(); assertFalse(controller.state.value.verified); assertEquals("userA", store.current { true }!!.userId)
        network.user = "userA"; controller.start()
        val pending = CompletableDeferred<KomgaImportPage>()
        network.fetch = { _, _, _ -> withContext(NonCancellable) { pending.await() } }
        controller.select(controller.state.value.lists.first()); active = false; controller.close()
        pending.complete(KomgaImportPage(context(c), listOf(entry(1)), null)); yield()
        assertFalse(controller.state.value.verified); assertTrue(controller.state.value.entries.isEmpty()); assertTrue(db.books.isEmpty()); scope.cancel()
    }
    @Test fun selectedSnapshotResumesIndependentlyOfChangedServerList() = runBlocking {
        val db = KomgaDb(); val store = KomgaAccountStore(db); val c = store.activate(config(), "userA") { true }
        var task = store.begin(c, list(), (0..44).map { entry(it) }, false) { true }
        task = store.commit(c, task, task.selection!!.take(20), null) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val network = KomgaNetwork()
        val controller = KomgaAccountController(scope, { true }, store, network); controller.start(); controller.resume()
        assertEquals(0, network.reads); assertEquals(45, db.books.size); assertTrue(controller.state.value.task!!.done); scope.cancel()
    }
}
