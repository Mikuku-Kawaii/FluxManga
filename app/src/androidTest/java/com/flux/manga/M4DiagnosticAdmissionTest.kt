package com.flux.manga

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.storage.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only new UUID fixtures. No business files, original CBZs, or existing keys are changed. */
class M4DiagnosticAdmissionTest {
    @get:Rule val foreground = PhysicalForegroundRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store() = WorldDataStore(context, "m4-diagnostic-${UUID.randomUUID()}")
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    private fun helper(data: WorldDataStore, world: PrototypeWorld, password: ByteArray?) =
        PreservingDatabaseFactory(password).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(data.databaseFile(world).path).callback(object : SupportSQLiteOpenHelper.Callback(WORLD_DATABASE_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Existing fixture expected")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No source migration")
            }).build())

    @Test fun diagnosticAndCopyMigrationAuthenticateBothWorldsWithoutChangingOriginals(): Unit = runBlocking {
        val data = store()
        for (world in PrototypeWorld.entries) {
            data.putSession(world, "synthetic", "FAKE_TOKEN".toByteArray())
            val before = snapshot(data.directory(world))
            val stages = mutableListOf<DiagnosticStage>()
            val report = data.diagnoseProtection(world, { true }, { stages += it }, {}, migrateCopy = true)
            assertNull(report.error); assertEquals(WORLD_DATABASE_VERSION, report.version)
            assertEquals(1, report.encryptedRecords); assertTrue(report.migrationChecked)
            assertTrue(DiagnosticStage.MIGRATING in stages)
            assertFalse(report.cleanupRequired); assertFalse(data.hasDiagnosticCopy(world))
            assertEquals(before, snapshot(data.directory(world)))
        }
    }

    @Test fun committedWalIsIncludedWhileConnectionRemainsOpen(): Unit = runBlocking {
        val data = store()
        for (world in PrototypeWorld.entries) {
            data.prepare(world)
            val password = if (world == PrototypeWorld.NSFW) data.keys(world, "database").loadExistingReadOnly() else null
            val payload = data.keys(world, "payload").loadExistingReadOnly()
            try {
                helper(data, world, password).use { connection ->
                    connection.setWriteAheadLoggingEnabled(true)
                    val db = connection.writableDatabase
                    db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
                    val sealed = StorageCipher.seal("WAL_FAKE_TOKEN".toByteArray(), payload, storageIdentity(world.name, "wal-fixture", "session"))
                    db.execSQL("INSERT INTO source_session(sourceId,sealedValue) VALUES (?,?)", arrayOf("wal-fixture", sealed))
                    assertTrue(File(data.databaseFile(world).path + "-wal").length() > 0)
                    val before = snapshot(data.directory(world))
                    val report = data.diagnoseProtection(world, { true }, {}, {})
                    assertNull(report.error); assertEquals(1, report.encryptedRecords)
                    assertFalse(data.hasDiagnosticCopy(world))
                    assertEquals(before, snapshot(data.directory(world)))
                }
            } finally { password?.fill(0); payload.fill(0) }
        }
    }

    @Test fun wrongDatabasePasswordDoesNotReplaceExistingCipherDatabase(): Unit = runBlocking {
        val data = store(); val world = PrototypeWorld.NSFW
        data.remember(world, "synthetic", "retained")
        val before = snapshot(data.directory(world))
        val wrong = ByteArray(32) { 7 }
        try {
            assertThrows(Exception::class.java) { helper(data, world, wrong).use { it.writableDatabase } }
        } finally { wrong.fill(0) }
        val after = snapshot(data.directory(world))
        before.forEach { (path, hash) -> assertEquals(path, hash, after[path]) }
        assertEquals(listOf("retained"), data.history(world, "synthetic"))
    }

    @Test fun invalidEncryptedRecordReportsFailureWithoutChangingSource(): Unit = runBlocking {
        val data = store()
        for (world in PrototypeWorld.entries) {
            data.worldData(world) { it.putSession(SourceSession("synthetic", ByteArray(40) { 3 })) }
            val before = snapshot(data.directory(world))
            val report = data.diagnoseProtection(world, { true }, {}, {})
            assertNotNull(report.error); assertTrue(report.error!!.contains(StorageProblem.PAYLOAD_INVALID.title))
            assertFalse(report.cleanupRequired); assertFalse(data.hasDiagnosticCopy(world))
            assertEquals(before, snapshot(data.directory(world)))
        }
    }

    @Test fun cancelledCheckCleansOnlyOwnedCopyAndRetainsUnknownFile(): Unit = runBlocking {
        val data = store(); val world = PrototypeWorld.NSFW
        data.putSession(world, "synthetic", "FAKE_TOKEN".toByteArray())
        val before = snapshot(data.directory(world))
        val sentinel = File(data.cacheDirectory(world), "database-diagnostic/keep.txt")
        sentinel.parentFile!!.mkdirs(); sentinel.writeText("original sentinel")
        try {
            data.diagnoseProtection(world, { true }, { if (it == DiagnosticStage.CHECKING) throw CancellationException("fixture") }, {})
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(data.hasDiagnosticCopy(world)); assertTrue(data.clearDiagnosticCopy(world))
        assertEquals("original sentinel", sentinel.readText())
        assertEquals(before, snapshot(data.directory(world)))
    }
}
