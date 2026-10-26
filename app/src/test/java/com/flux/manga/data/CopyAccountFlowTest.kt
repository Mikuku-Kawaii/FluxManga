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
private class CopyDb : AccountImportDatabase {
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

private fun copyConfig(user: String = "userA", token: String = "synthetic") = CopyAccount(user, "合成账号", token)
private fun copyEntry(n: Int, manga: String = "m$n") = CopyCollectedEntry(manga, "合成 $manga", n.toString(), false)
private fun copyContext(c: CopyAccount) = SourceContext(SourceWorld.SFW, c.sourceId, "test", 1uL, "opaque")
private class CopyNetwork : CopyAccountNetwork {
    var user = "userA"; var reads = 0; var verifies = 0; var signIns = 0; var changes = 0
    var login: suspend () -> CopyAccount = { copyConfig() }
    var fetch: suspend (CopyAccount, Int) -> CopyCollectionPage = { c, offset ->
        CopyCollectionPage(copyContext(c), (offset until minOf(offset + 21, 45)).map { copyEntry(it) }, offset.toUInt(), 45u,
            if (offset < 42) (offset + 21).toUInt() else null)
    }
    override suspend fun signIn(username: String, password: String): CopyAccount { signIns++; return login() }
    override suspend fun verify(c: CopyAccount): CopyAccountIdentity { verifies++; return CopyAccountIdentity(user, "合成账号") }
    override suspend fun collection(c: CopyAccount, offset: Int): CopyCollectionPage { reads++; return fetch(c, offset) }
    override fun credentialsChanged() { changes++ }
}
class CopyAccountFlowTest {
    @Test fun legacyRecordKeepsItsServiceAndSourceIdentity() {
        val bytes = java.io.ByteArrayOutputStream().also { output -> java.io.DataOutputStream(output).use {
            it.writeInt(1); it.writeUTF("userA"); it.writeUTF("合成账号"); it.writeUTF("synthetic")
        } }.toByteArray()
        val restored = CopyAccountCodec.account(bytes)
        assertEquals(CopyAccountService.LEGACY, restored.service)
        assertEquals(copyConfig().sourceId, restored.sourceId)
        assertTrue(sameCopyAccount(copyConfig(), restored))
    }
    @Test fun officialAccountRoundTripDoesNotCollideWithLegacyUserId() {
        val official = CopyAccount("userA", "合成账号", "synthetic", CopyAccountService.OFFICIAL)
        val restored = CopyAccountCodec.account(CopyAccountCodec.account(official))
        assertTrue(sameCopyAccount(official, restored))
        assertNotEquals(copyConfig().sourceId, restored.sourceId)
        assertEquals(CopyAccountService.OFFICIAL, restored.withoutCredential().service)
    }
    @Test fun codecRoundTripAndAccountIdentityKeepSecretsOutOfUiAndKeys() {
        val c = copyConfig(); val restored = CopyAccountCodec.account(CopyAccountCodec.account(c))
        assertTrue(sameCopyAccount(restored, c)); assertFalse(c.toString().contains("synthetic"))
        assertEquals(c.sourceId, copyConfig(token = "rotated").sourceId)
        assertNotEquals(c.sourceId, copyConfig("userB").sourceId)
        assertTrue(isCopySource(c.sourceId)); assertFalse(isCopyAccount(CopyMangaEndpoint.SOURCE_ID))
        val t = CopyImportTask("job", true, listOf(copyEntry(0)))
        assertEquals(t, CopyAccountCodec.task(CopyAccountCodec.task(t)))
        try { CopyAccountCodec.task(byteArrayOf(1)); fail() } catch (_: Exception) { }
        try { CopyAccountCodec.account(CopyAccountCodec.account(c) + 1.toByte()); fail() } catch (_: Exception) { }
    }
    @Test fun duplicateMembershipsPreserveBooksFavoritesProgressAndScopeReferences() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); val c = store.activate(copyConfig()) { true }
        db.books[c.sourceId to "m0"] = OnlineBook(c.sourceId, "m0", "原书名", false, 7)
        db.progress += c.sourceId to "progress"
        val entries = listOf(copyEntry(0), copyEntry(1, "m0"), copyEntry(2, "progress"), copyEntry(3))
        val task = store.begin(c, entries, true) { true }; val done = store.commit(c, task, entries, null) { true }
        assertEquals(1, done.added); assertEquals(3, done.existing); assertTrue(done.done)
        assertEquals("原书名", db.books[c.sourceId to "m0"]!!.title); assertFalse(db.books[c.sourceId to "m0"]!!.favorite)
        assertFalse(db.books[c.sourceId to "progress"]!!.favorite); assertTrue(db.books[c.sourceId to "m3"]!!.favorite)
        assertEquals(4, db.secrets.keys.count { it.startsWith("copy-import-ref:") }); assertTrue(db.progress.contains(c.sourceId to "progress"))
        assertTrue(db.secrets.filterKeys { it.startsWith("copy-import-ref:") }.values.none { it.toString(Charsets.UTF_8).contains("synthetic") })
    }
    @Test fun rollbackAndAccountSwitchRejectLateBatchAndReturningAccountKeepsTask() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); val c = store.activate(copyConfig()) { true }
        val task = store.begin(c, null, true) { true }; var allowed = true
        db.beforeCommit = { allowed = false }
        try { store.commit(c, task, listOf(copyEntry(0)), 21) { allowed }; fail() } catch (_: CancellationException) { }
        db.beforeCommit = null
        assertEquals(task, store.task(c.sourceId) { true }); assertTrue(db.books.isEmpty())
        store.activate(copyConfig("userB")) { true }
        try { store.commit(c, task, listOf(copyEntry(0)), 21) { true }; fail() } catch (_: CancellationException) { }
        val returned = store.activate(copyConfig(token = "rotated")) { true }
        assertEquals(task, store.task(returned.sourceId) { true }); store.logout(returned) { true }
        assertFalse(store.current { true }!!.hasCredential); assertEquals(task, store.task(c.sourceId) { true })
    }
    @Test fun loginRequiresIndependentIdentityVerificationAndFailedCandidatePreservesAccount() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); val old = store.activate(copyConfig("old")) { true }
        val network = CopyNetwork(); network.user = "old"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val controller = CopyAccountController(scope, { true }, store, network)
        controller.start(); network.user = "different"; controller.signIn("name", "secret")
        assertEquals(1, network.signIns); assertTrue(sameCopyAccount(store.current { true }, old)); assertFalse(controller.state.value.verified)
        network.user = "userA"; network.fetch = { c, offset -> CopyCollectionPage(copyContext(c), emptyList(), offset.toUInt(), 0u, null) }
        controller.signIn("name", "secret")
        assertEquals("userA", store.current { true }!!.userId); assertTrue(controller.state.value.verified)
        assertTrue(controller.state.value.entriesLoaded); assertTrue(controller.state.value.entries.isEmpty())
        controller.logout(); assertFalse(store.current { true }!!.hasCredential); assertTrue(db.books.isEmpty()); scope.cancel()
    }
    @Test fun fullRangeIsPagedAndReopeningNeverAutomaticallyResumesWrites() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); val c = store.activate(copyConfig()) { true }
        var task = store.begin(c, null, true) { true }; task = store.commit(c, task, (0..20).map { copyEntry(it) }, 21) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val network = CopyNetwork()
        val controller = CopyAccountController(scope, { true }, store, network); controller.start()
        assertEquals(21, db.books.size); assertEquals(task, controller.state.value.task)
        controller.resume(); assertEquals(45, db.books.size); assertTrue(controller.state.value.task!!.done)
        controller.leave(); controller.start(); assertEquals(45, db.books.size)
        controller.resume(true); assertEquals(0, controller.state.value.task!!.added); assertEquals(45, controller.state.value.task!!.existing); scope.cancel()
    }
    @Test fun failedAppendAndImportKeepPreviewAndPartialSuccessThenRetryExactPage() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); store.activate(copyConfig()) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val network = CopyNetwork(); val original = network.fetch; var fail = true
        network.fetch = { c, offset -> if (offset == 21 && fail) throw SourceException.HttpStatus(503u) else original(c, offset) }
        val controller = CopyAccountController(scope, { true }, store, network); controller.start(); controller.moreEntries()
        assertEquals(21, controller.state.value.entries.size); assertEquals(21, controller.state.value.nextOffset)
        controller.begin(null, true); assertEquals(21, db.books.size); assertEquals(21, controller.state.value.task!!.offset)
        fail = false; controller.resume(); assertEquals(45, db.books.size); assertEquals(45, controller.state.value.task!!.added); scope.cancel()
    }
    @Test fun cancellationAfterCommitReloadsDurableCheckpoint() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); store.activate(copyConfig()) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val network = CopyNetwork()
        val controller = CopyAccountController(scope, { true }, store, network); controller.start()
        db.afterCommit = { if (db.books.size == 21) { db.afterCommit = null; awaitCancellation() } }
        controller.begin(null, true); assertEquals(21, db.books.size)
        controller.cancel(); assertEquals(21, controller.state.value.task!!.offset); assertFalse(controller.state.value.verified)
        controller.start(); controller.resume(); assertEquals(45, db.books.size); assertEquals(45, controller.state.value.task!!.added); scope.cancel()
    }
    @Test fun selectedSnapshotSurvivesChangedServerAndWorldChangeRejectsLateResults() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); val c = store.activate(copyConfig()) { true }
        val task = store.begin(c, (0..44).map { copyEntry(it) }, true) { true }
        store.commit(c, task, task.selection!!.take(21), null) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val network = CopyNetwork(); var active = true
        val controller = CopyAccountController(scope, { active }, store, network); controller.start()
        val reads = network.reads; controller.resume(); assertEquals(reads, network.reads); assertEquals(45, db.books.size)
        val pending = CompletableDeferred<CopyCollectionPage>()
        network.fetch = { _, _ -> withContext(NonCancellable) { pending.await() } }
        controller.moreEntries(); active = false; controller.close()
        pending.complete(CopyCollectionPage(copyContext(c), listOf(copyEntry(60)), 21u, 60u, 42u)); yield()
        assertFalse(controller.state.value.verified); assertTrue(controller.state.value.entries.isEmpty()); scope.cancel()
    }
    @Test fun emptyNonFinalPageContinuesAndWrongAccountPageNeverCommits() = runBlocking {
        val db = CopyDb(); val store = CopyAccountStore(db); store.activate(copyConfig()) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val network = CopyNetwork(); val original = network.fetch
        network.fetch = { c, offset -> if (offset == 21) CopyCollectionPage(copyContext(c), emptyList(), 21u, 45u, 42u) else original(c, offset) }
        val controller = CopyAccountController(scope, { true }, store, network); controller.start(); controller.begin(null, true)
        assertEquals(24, db.books.size); assertTrue(controller.state.value.task!!.done)
        controller.finish(); network.fetch = { _, _ -> original(copyConfig("userB"), 0) }
        controller.begin(null, true); assertEquals(24, db.books.size); assertEquals(0, controller.state.value.task!!.cursor); assertNotNull(controller.state.value.error); scope.cancel()
    }
}
