package com.flux.manga.storage

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StorageOperationTest {
    // Models commit/rollback only; native Room/SQLCipher coverage lives in WorldStorageTest.
    private class Rows {
        var committed = mapOf("NSFW:history" to "original", "SFW:progress" to "page-7")
        fun transaction(operation: StorageOperation, action: (MutableMap<String, String>) -> Unit) {
            val draft = committed.toMutableMap()
            operation.beforeCommit { action(draft) }
            committed = draft
        }
    }

    @Test fun expiredRequestCannotEnterTransaction() {
        val operation = StorageOperation(Job()) { false }
        var entered = false
        assertThrows(CancellationException::class.java) { operation.beforeCommit { entered = true } }
        assertFalse(entered)
    }
    @Test fun generationChangeBeforeCommitRollsBackWholeBatch() {
        val rows = Rows(); val before = rows.committed
        var generation = 1
        val operation = StorageOperation(Job()) { generation == 1 }
        assertThrows(CancellationException::class.java) {
            rows.transaction(operation) { it["NSFW:history"] = "new"; it["NSFW:credential"] = "synthetic"; generation++ }
        }
        assertEquals(before, rows.committed)
    }
    @Test fun jobCancellationBeforeCommitRollsBackEvenWhenGenerationStillMatches() {
        val rows = Rows(); val before = rows.committed; val job = Job()
        assertThrows(CancellationException::class.java) {
            rows.transaction(StorageOperation(job) { true }) { it.clear(); job.cancel() }
        }
        assertEquals(before, rows.committed)
    }
    @Test fun acceptedProgressCommitPreservesOtherWorldAndRemainsCommittedAfterLaterCancellation() {
        val rows = Rows(); val job = Job()
        rows.transaction(StorageOperation(job) { true }) { it["NSFW:progress"] = "page-3" }
        job.cancel()
        assertEquals("page-3", rows.committed["NSFW:progress"])
        assertEquals("page-7", rows.committed["SFW:progress"])
        assertEquals("original", rows.committed["NSFW:history"])
    }
    @Test fun storageExceptionRollsBackAndRetainsOriginalFailure() {
        val rows = Rows(); val before = rows.committed; val failure = IOException("synthetic failure")
        val caught = assertThrows(IOException::class.java) {
            rows.transaction(StorageOperation(Job()) { true }) { it.clear(); throw failure }
        }
        assertSame(failure, caught); assertEquals(before, rows.committed)
    }
    @Test fun successfulSecretReadTransfersSameBufferWithoutWipingCallerInput(): Unit = runBlocking {
        val original = byteArrayOf(7, 8, 9); val decrypted = original.copyOf()
        val result = readOwnedSecret { retain -> withContext(Dispatchers.IO) { retain(decrypted) } }
        assertSame(decrypted, result); assertArrayEquals(original, result)
        result!!.fill(0)
        assertArrayEquals(byteArrayOf(7, 8, 9), original)
    }
    @Test fun failedCloseAfterDecryptionWipesPendingSecret(): Unit = runBlocking {
        val decrypted = byteArrayOf(7, 8, 9); val failure = IOException("synthetic close failure")
        try {
            readOwnedSecret { retain -> withContext(Dispatchers.IO) { retain(decrypted); throw failure } }
            fail("Expected failure")
        } catch (caught: IOException) {
            // Coroutine stack-trace recovery may copy IOException across the dispatcher boundary.
            assertEquals(failure.message, caught.message)
        }
        assertArrayEquals(ByteArray(3), decrypted)
    }
    @Test fun cancelledDispatcherReturnWipesSecretThatNeverReachedCaller(): Unit = runBlocking {
        val decrypted = byteArrayOf(7, 8, 9)
        var published = false
        val reader = launch {
            val owner = currentCoroutineContext().job
            readOwnedSecret { retain ->
                withContext(Dispatchers.IO) { retain(decrypted).also { owner.cancel() } }
            }
            published = true
        }
        reader.join()
        assertTrue(reader.isCancelled); assertFalse(published)
        assertArrayEquals(ByteArray(3), decrypted)
    }
    @Test fun expiredSessionAfterDecryptionWipesSecret(): Unit = runBlocking {
        val decrypted = byteArrayOf(7, 8, 9); var allowed = true
        val operation = StorageOperation(currentCoroutineContext()) { allowed }
        try {
            readOwnedSecret { retain -> operation.beforeCommit { retain(decrypted).also { allowed = false } } }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertArrayEquals(ByteArray(3), decrypted)
    }
    @Test fun absentSecretIsReturnedWithoutFabricatingCredential(): Unit = runBlocking {
        assertNull(readOwnedSecret { withContext(Dispatchers.IO) { null } })
    }
}
