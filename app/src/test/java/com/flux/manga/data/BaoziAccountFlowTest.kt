package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Transactional fake shares the production codec/store/controller; no Android/real account IO. */
private class AccountDb : AccountImportDatabase {
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
private class AccountNetwork : BaoziAccountNetwork {
    var calls = 0
    var token = "synthetic-token"
    var read: suspend (String) -> BaoziShelfSnapshot = { source -> snapshot(source) }
    override suspend fun signIn(source: String, username: String, password: String): String { calls++; return token }
    override suspend fun shelf(source: String, token: String, accepts: () -> Boolean): BaoziShelfSnapshot { calls++; return read(source) }
    companion object {
        fun snapshot(source: String, count: Int = 60) = BaoziShelfSnapshot((1..count).map {
            BaoziShelfEntry(MangaCard(MangaKey(PrototypeWorld.SFW, source, "book-$it"), "合成 $it", null, true), BaoziCover.Missing)
        }, if (count == 0) BaoziShelfEvidence.EMPTY_UNCONFIRMED else BaoziShelfEvidence.CONTENT_PRESENT)
    }
}

class BaoziAccountFlowTest {
    private val source = baoziAccountId("fixture")
    private val allowed: () -> Boolean = { true }
    private fun items(count: Int) = (1..count).map { BaoziImportItem("book-$it", "合成 $it") }
    private suspend fun idle(controller: BaoziAccountController) { withTimeout(3000) { while (controller.state.value.busy) yield() } }

    @Test fun codecPreservesTaskAndRejectsDamageWithoutLeakingTokenToString() {
        val account = BaoziSavedAccount(source, "fixture", "synthetic-token",
            BaoziImportJob("job", items(40), true, 25, 20, 5))
        val bytes = BaoziAccountCodec.encode(account)
        assertEquals(account, BaoziAccountCodec.decode(bytes))
        assertFalse(account.toString().contains("synthetic-token"))
        for (invalid in listOf(bytes.copyOf(4), bytes + byteArrayOf(1), bytes.copyOf().also { it[3] = 2 })) {
            try { BaoziAccountCodec.decode(invalid); fail() } catch (_: Exception) { }
        }
        try { BaoziAccountCodec.encode(account.copy(job = account.job!!.copy(cursor = 41))); fail() }
        catch (_: IllegalArgumentException) { }
        assertNotEquals(source, baoziAccountId("Fixture")) // No unproven username alias equivalence.
    }

    @Test fun batchedImportIsIdempotentAndPreservesExistingFavoriteAndProgress() = runBlocking {
        val db = AccountDb(); val store = BaoziAccountStore(db)
        store.activate(source, "fixture", "token", allowed)
        val old = OnlineBook(source, "book-1", "原书名", false, 123)
        db.books[source to "book-1"] = old
        db.progress += source to "book-2"
        var saved = store.begin(source, items(67) + items(1), true, allowed)
        assertEquals(67, saved.job!!.items.size)
        saved = store.batch(source, saved.job!!.id, allowed)
        assertEquals(25, saved.job!!.cursor); assertEquals(23, saved.job!!.added); assertEquals(2, saved.job!!.existing)
        val reopened = BaoziAccountStore(db)
        saved = reopened.current(allowed)!!
        while (saved.job!!.remaining > 0) saved = reopened.batch(source, saved.job!!.id, allowed)
        assertEquals(67, db.books.size); assertEquals(65, saved.job!!.added)
        assertEquals(old, db.books[source to "book-1"])
        assertFalse(db.books.getValue(source to "book-2").favorite)
        assertTrue(source to "book-2" in db.progress)
        assertTrue(db.books.getValue(source to "book-3").favorite)
        assertTrue(db.secrets.containsKey("baozi-import-ref:$source:book-1"))
        saved = store.begin(source, items(67), false, allowed)
        while (saved.job!!.remaining > 0) saved = store.batch(source, saved.job!!.id, allowed)
        assertEquals(0, saved.job!!.added); assertEquals(67, saved.job!!.existing)
        assertTrue(db.books.getValue(source to "book-3").favorite)
    }

    @Test fun switchingAndLogoutKeepPerAccountTasksAndBookIdentitiesButRemoveOldToken() = runBlocking {
        val db = AccountDb(); val store = BaoziAccountStore(db); val other = baoziAccountId("another")
        store.activate(source, "fixture", "first-token", allowed)
        val job = store.begin(source, items(40), false, allowed).job!!
        store.batch(source, job.id, allowed)
        store.activate(other, "another", "second-token", allowed)
        val firstSaved = BaoziAccountCodec.decode(db.secrets.getValue("baozi-account-v1:$source"))
        assertNull(firstSaved.token); assertEquals(25, firstSaved.job!!.cursor)
        val secondJob = store.begin(other, items(1), true, allowed).job!!
        store.batch(other, secondJob.id, allowed)
        assertEquals(26, db.books.size) // Same manga ID belongs to two account instances.
        store.logout(other, allowed); assertNull(store.current(allowed)!!.token)
        val restored = store.activate(source, "fixture", "renewed-token", allowed)
        assertEquals(job.id, restored.job!!.id); assertEquals(25, restored.job!!.cursor)
        store.finishTask(source, allowed)
        assertEquals(26, db.books.size); assertNull(store.current(allowed)!!.job)
    }

    @Test fun cancelledOrFailedTransactionRollsBackBooksAndCheckpointTogether() = runBlocking {
        val db = AccountDb(); val store = BaoziAccountStore(db)
        store.activate(source, "fixture", "token", allowed)
        val job = store.begin(source, items(40), false, allowed).job!!
        var valid = true
        db.beforeCommit = { valid = false }
        try { store.batch(source, job.id) { valid }; fail() } catch (_: CancellationException) { }
        db.beforeCommit = null
        assertEquals(0, db.books.size); assertEquals(0, store.current(allowed)!!.job!!.cursor)
        db.beforeCommit = { throw IOException("synthetic disk failure") }
        try { store.batch(source, job.id, allowed); fail() } catch (_: IOException) { }
        db.beforeCommit = null
        assertEquals(0, db.books.size); assertEquals(0, store.current(allowed)!!.job!!.cursor)
        assertEquals(25, store.batch(source, job.id, allowed).job!!.cursor)
    }

    @Test fun unverifiedEmptyOrAuthenticationFailureNeverReplacesSavedAccount() = runBlocking {
        val db = AccountDb(); val store = BaoziAccountStore(db); val network = AccountNetwork()
        store.activate(source, "fixture", "original", allowed)
        val controller = BaoziAccountController(this, allowed, store, network)
        controller.start(); idle(controller)
        network.read = { AccountNetwork.snapshot(it, 0) }
        controller.signIn("new-user", "password"); idle(controller)
        assertEquals(source, store.current(allowed)!!.sourceId)
        assertEquals("original", store.current(allowed)!!.token)
        assertFalse(controller.state.value.verified); assertTrue(controller.state.value.loaded)
        network.read = { throw SourceException.AuthenticationRequired() }
        controller.signIn("new-user", "password"); idle(controller)
        assertEquals(source, store.current(allowed)!!.sourceId)
        assertTrue(controller.state.value.entries.isEmpty())
        controller.close()
    }

    @Test fun processRestoreRequiresVerificationAndExplicitResumeAndKeepsSelectedSnapshot() = runBlocking {
        val db = AccountDb(); val store = BaoziAccountStore(db); val network = AccountNetwork()
        store.activate(source, "fixture", "token", allowed)
        val job = store.begin(source, items(60), true, allowed).job!!
        store.batch(source, job.id, allowed)
        network.read = { AccountNetwork.snapshot(it, 1) } // Remote snapshot changed; selected job does not.
        val controller = BaoziAccountController(this, allowed, BaoziAccountStore(db), network)
        controller.start(); idle(controller)
        assertTrue(controller.state.value.verified)
        assertEquals(25, db.books.size); assertEquals(35, controller.state.value.importJob!!.remaining)
        controller.resumeImport(); idle(controller)
        assertEquals(60, db.books.size); assertEquals(0, controller.state.value.importJob!!.remaining)
        assertEquals(1, network.calls) // Resume uses verified saved selection, not rescanning.
        controller.close()
    }

    @Test fun cancelAfterCommitReconcilesWithoutRepeatingNetworkOrDoubleImport() = runBlocking {
        val db = AccountDb(); val store = BaoziAccountStore(db); val network = AccountNetwork()
        val controller = BaoziAccountController(this, allowed, store, network)
        controller.start(); idle(controller); controller.signIn("fixture", "password"); idle(controller)
        val committed = CompletableDeferred<Unit>()
        db.afterCommit = {
            if (db.books.size == 25) { db.afterCommit = null; committed.complete(Unit); awaitCancellation() }
        }
        controller.importSelected(items(60).map { it.id }.toSet(), true)
        withTimeout(3000) { committed.await() }
        val calls = network.calls
        controller.cancel(); idle(controller)
        assertEquals(calls, network.calls)
        assertEquals(25, controller.state.value.importJob!!.cursor)
        assertEquals(25, db.books.size); assertFalse(controller.state.value.verified)
        controller.resumeImport(); assertEquals(25, db.books.size)
        controller.start(); idle(controller); controller.resumeImport(); idle(controller)
        assertEquals(60, db.books.size); assertEquals(60, controller.state.value.importJob!!.added)
        controller.close()
    }

    @Test fun lateResponseAfterWorldChangeCannotSaveOrPublish() = runBlocking {
        val db = AccountDb(); val store = BaoziAccountStore(db); val network = AccountNetwork(); var active = true
        val controller = BaoziAccountController(this, { active }, store, network)
        controller.start(); idle(controller)
        network.read = { active = false; AccountNetwork.snapshot(it) }
        controller.signIn("fixture", "password")
        yield(); controller.close()
        assertNull(store.current(allowed)); assertFalse(controller.state.value.verified)
    }
}
