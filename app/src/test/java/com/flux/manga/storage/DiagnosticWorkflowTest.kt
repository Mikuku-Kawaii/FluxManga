package com.flux.manga.storage

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticWorkflowTest {
    @get:Rule val temp = TemporaryFolder()
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val password = ByteArray(32) { (it + 2).toByte() }
    private val directory get() = File(temp.root, "cache/database-diagnostic")
    private val main get() = File(directory, "snapshot.db")
    private val phases = mutableListOf<DiagnosticStage>()
    private val cleanup = mutableListOf<Boolean>()
    private fun workflow(inspect: suspend (File, DiagnosticSnapshot, (DiagnosticStage) -> Unit) -> Pair<Int, Int?> =
        { file, _, stage -> stage(DiagnosticStage.SESSIONS); file.readBytes()[0].toInt() to 2 }) = DiagnosticWorkflow(10, inspect)
    private suspend fun prepare(file: File): DiagnosticSnapshot {
        file.writeBytes(byteArrayOf(9, 7, 5))
        File(file.path + "-wal").writeBytes(byteArrayOf(3, 1))
        return DiagnosticSnapshot(5, password, secret, "NSFW")
    }
    private suspend fun run(flow: DiagnosticWorkflow = workflow(), migrate: ((File, ByteArray?, () -> Unit) -> Unit)? = null) =
        flow.inspect(directory, ::prepare, { phases += it }, { cleanup += it }, migrate)
    private fun wiped() {
        assertTrue(secret.all { it == 0.toByte() }); assertTrue(password.all { it == 0.toByte() })
    }
    private fun originals(): Map<File, ByteArray> = listOf("business.db", "business.db-wal", "payload", "user.cbz").associate {
        val bytes = "original-$it".toByteArray(); val file = temp.newFile(it); file.writeBytes(bytes); file to bytes
    }
    private fun preserved(originals: Map<File, ByteArray>) { originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) } }

    @Test fun pageEntryDetectsEachOwnedResidualWithoutCreatingOrChangingAnything(): Unit = runBlocking {
        val originals = originals(); val flow = workflow()
        assertFalse(flow.hasCopy(directory)); assertFalse(directory.exists())
        directory.mkdirs(); File(directory, "keep.txt").writeText("foreign")
        assertFalse(flow.hasCopy(directory))
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val file = File(main.path + suffix); val bytes = byteArrayOf(5, 4, 3); file.writeBytes(bytes)
            assertTrue(workflow().hasCopy(directory)) // fresh controller after a process interruption
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(flow.cleanup(directory)); assertFalse(file.exists())
            assertEquals("foreign", File(directory, "keep.txt").readText())
        }
        preserved(originals)
    }
    @Test fun successfulCheckWipesKeysCleansSidecarsAndPreservesOriginals(): Unit = runBlocking {
        val originals = originals(); val report = run()
        assertNull(report.error); assertEquals(9, report.version); assertEquals(2, report.encryptedRecords)
        assertEquals(5, report.snapshotBytes); assertFalse(report.migrationChecked)
        assertEquals(listOf(DiagnosticStage.PREPARING, DiagnosticStage.CHECKING, DiagnosticStage.SESSIONS, DiagnosticStage.CLEANING), phases)
        assertEquals(listOf(false), cleanup); wiped(); assertFalse(workflow().hasCopy(directory)); preserved(originals)
    }
    @Test fun partialPrepareFailureCleansFilesWithoutCallingInspector(): Unit = runBlocking {
        var opened = false
        val flow = workflow { _, _, _ -> opened = true; 10 to 0 }
        val result = flow.inspect(directory, { file ->
            file.writeText("partial"); throw IOException("secret-path-cookie")
        }, {}, { cleanup += it })
        assertFalse(opened); assertNotNull(result.error); assertFalse(result.error!!.contains("secret-path-cookie"))
        assertFalse(main.exists()); assertEquals(listOf(false), cleanup)
    }
    @Test fun authenticationFailureIsSanitizedAndWipesOwnedSecrets(): Unit = runBlocking {
        val sealed = StorageCipher.seal("private fixture".toByteArray(), secret, "NSFW/session".toByteArray())
        val flow = workflow { _, snapshot, _ ->
            StorageCipher.open(sealed, snapshot.payload, "SFW/session".toByteArray()); error("must reject")
        }
        val report = run(flow)
        assertTrue(report.error!!.startsWith(StorageProblem.PAYLOAD_INVALID.title))
        assertFalse(report.error.contains("private fixture")); wiped(); assertFalse(main.exists())
    }
    @Test fun migrationChangesOnlySnapshotAndRechecksTargetVersion(): Unit = runBlocking {
        val originals = originals(); var inspections = 0
        val flow = workflow { file, _, _ -> inspections++; file.readBytes()[0].toInt() to 2 }
        val report = run(flow) { file, key, active ->
            active(); assertSame(password, key); assertEquals(main, file)
            file.writeBytes(byteArrayOf(10, 9)); active()
        }
        assertEquals(2, inspections); assertEquals(9, report.originalVersion); assertEquals(10, report.version)
        assertTrue(report.migrationChecked); assertNull(report.error); wiped(); preserved(originals)
    }
    @Test fun unsupportedVersionNeverInvokesMigration(): Unit = runBlocking {
        var migrated = false
        val report = run(workflow { _, _, _ -> 11 to null }) { _, _, _ -> migrated = true }
        assertFalse(migrated); assertEquals(11, report.originalVersion)
        assertTrue(report.error!!.startsWith(StorageProblem.DATABASE_VERSION.title)); wiped(); assertFalse(main.exists())
    }
    @Test fun migrationFailureOrWrongFinalVersionCannotBecomeSuccess(): Unit = runBlocking {
        val originals = originals()
        val failure = run { file, _, _ -> file.appendText("partial"); throw IOException("secret-sql") }
        assertNotNull(failure.error); assertFalse(failure.migrationChecked); assertFalse(failure.error!!.contains("secret-sql"))
        val wrong = run { _, _, _ -> } // remains version 9 instead of 10
        assertTrue(wrong.error!!.startsWith(StorageProblem.DATABASE_VERSION.title))
        assertFalse(wrong.migrationChecked); assertFalse(main.exists()); preserved(originals)
    }
    @Test fun cancelledPrepareRemovesPartialCopyAndDoesNotPublish(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); var published = false
        val work = launch {
            workflow().inspect(directory, { file ->
                file.writeText("partial"); started.complete(Unit)
                try { awaitCancellation() } finally { secret.fill(0); password.fill(0) }
            }, {}, { cleanup += it })
            published = true
        }
        withTimeout(5000) { started.await(); work.cancelAndJoin() }
        assertFalse(published); assertFalse(main.exists()); assertEquals(listOf(false), cleanup); wiped()
    }
    @Test fun cancelledInspectionClosesBeforeWipingKeysAndCleaningFiles(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); var closed = false; var published = false
        val flow = workflow { _, snapshot, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally {
                assertTrue(snapshot.payload.any { it != 0.toByte() }); closed = true
            }
        }
        val work = launch { run(flow); published = true }
        withTimeout(5000) { started.await(); work.cancelAndJoin() }
        assertTrue(closed); assertFalse(published); wiped(); assertFalse(main.exists())
    }
    @Test fun cleanupFailureIsReportedAndRetryNeverRecursesIntoUnknownDirectory(): Unit = runBlocking {
        directory.mkdirs(); main.mkdirs(); File(main, "keep.txt").writeText("keep")
        var prepared = false
        val flow = workflow()
        val report = flow.inspect(directory, { prepared = true; prepare(it) }, {}, { cleanup += it })
        assertFalse(prepared); assertNotNull(report.error); assertTrue(report.cleanupRequired)
        assertEquals(listOf(true), cleanup); assertFalse(flow.cleanup(directory))
        assertEquals("keep", File(main, "keep.txt").readText())
        // Fixture owner removes its own obstruction; app cleanup never does this recursively.
        File(main, "keep.txt").delete(); main.delete()
        File(main.path + "-journal").writeText("leftover")
        assertTrue(flow.cleanup(directory)); assertFalse(flow.hasCopy(directory))
    }
    @Test fun cleanupObserverCancellationCannotSkipDeletion(): Unit = runBlocking {
        try {
            workflow().inspect(directory, ::prepare, { if (it == DiagnosticStage.CLEANING) throw CancellationException("observer") }, { cleanup += it })
            fail("observer cancellation must propagate")
        } catch (_: CancellationException) { }
        wiped(); assertFalse(main.exists()); assertEquals(listOf(false), cleanup)
    }
    @Test fun explicitCleanupWaitsForActiveInspection(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val flow = workflow { file, _, _ ->
            started.complete(Unit); finish.await(); assertTrue(file.exists()); 10 to 2
        }
        val inspect = async { run(flow) }
        withTimeout(5000) { started.await() }
        val clean = async(start = CoroutineStart.UNDISPATCHED) { flow.cleanup(directory) }
        assertFalse(clean.isCompleted); finish.complete(Unit)
        withTimeout(5000) { assertNull(inspect.await().error); assertTrue(clean.await()) }
        wiped(); assertFalse(flow.hasCopy(directory))
    }
}
