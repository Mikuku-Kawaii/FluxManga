package com.flux.manga

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.security.keystore.KeyInfo
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.storage.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample = ProbeSample(7, "M0_STORAGE_SYNTHETIC_ONLY_流光_📚_2026")
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test fun createPersistentFixture() {
        val probe = fresh("persistent")
        probe.openPlain().use { db -> db.samples().insert(sample) }
        val report = JSONObject()
        probe.openEncrypted().use { handle ->
            val db = handle.database
            db.samples().insert(sample)
            assertEquals(sample, db.samples().find(7))
            report.put("cipherVersion", scalar(db, "PRAGMA cipher_version"))
            report.put("sqliteVersion", scalar(db, "SELECT sqlite_version()"))
            assertEquals("ok", scalar(db, "PRAGMA integrity_check"))
            db.openHelper.writableDatabase.query("PRAGMA cipher_integrity_check").use {
                assertFalse("cipher integrity errors", it.moveToFirst())
            }
            // Check WAL while the connection is alive, not only the checkpointed DB.
            val wal = File(probe.encryptedFile.path + "-wal")
            assertTrue(wal.length() > 0)
            assertFalse(contains(wal.readBytes(), sample.text.toByteArray()))
            report.put("encryptedWalHasNoSampleText", true)
        }
        probe.openPlain().use { assertEquals(sample, it.samples().find(7)) }
        probe.openEncrypted().use { assertEquals(sample, it.database.samples().find(7)) }
        assertTrue(probe.plainFile.readBytes().take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse(probe.encryptedFile.readBytes().take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse(contains(probe.encryptedFile.readBytes(), sample.text.toByteArray()))
        val master = keyStore.getKey(probe.keys.alias, null) as SecretKey
        assertNull(master.encoded)
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(master, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        val first = probe.keys.loadOrCreate(true)
        val second = probe.keys.loadOrCreate(true)
        try {
            assertTrue("Unwrapped secrets must match", first.contentEquals(second))
            assertEquals(32, first.size)
            assertEquals(61, probe.keys.envelopeFile.length().toInt())
            assertFalse(contains(probe.keys.envelopeFile.readBytes(), first))
        } finally { first.fill(0); second.fill(0) }
        report.put("pid", Process.myPid())
            .put("keySecurityLevel", info.securityLevel)
            .put("masterKeyExportable", false)
            .put("envelopeBytes", probe.keys.envelopeFile.length())
            .put("sameProcessReopen", true)
            .put("plainHeaderRecognized", true)
            .put("encryptedHeaderHidden", true)
        save("create", report)
    }

    @Test fun reopenPersistentFixture() {
        val previous = JSONObject(File(context.filesDir, "storage-create.json").readText())
        assertNotEquals("Run this phase in a new instrumentation process", previous.getInt("pid"), Process.myPid())
        val probe = StorageProbe(context, "persistent")
        val envelopeBefore = digest(probe.keys.envelopeFile)
        probe.openPlain().use { assertEquals(sample, it.samples().find(7)) }
        probe.openEncrypted().use {
            assertEquals(sample, it.database.samples().find(7))
            assertEquals("ok", scalar(it.database, "PRAGMA integrity_check"))
        }
        assertEquals(envelopeBefore, digest(probe.keys.envelopeFile))
        save("reopen", JSONObject().put("pid", Process.myPid())
            .put("previousPid", previous.getInt("pid"))
            .put("crossProcessReopen", true).put("wrappedKeyUnchanged", true))
    }

    @Test fun rejectsWrongPasswordAndUnkeyedReader() {
        val probe = fresh("wrong-key")
        probe.openEncrypted().use { it.database.samples().insert(sample) }
        val before = digest(probe.encryptedFile)
        // Use copies because a reader's corruption handler may remove the file it opens.
        val wrongCopy = File(probe.directory, "wrong-copy.db")
        probe.encryptedFile.copyTo(wrongCopy, overwrite = true)
        val wrongPassword = ByteArray(32) { 42 }
        val wrongDb = Room.databaseBuilder(context, ProbeDatabase::class.java, wrongCopy.path)
            .openHelperFactory(SupportOpenHelperFactory(wrongPassword)).build()
        try {
            assertThrows(android.database.sqlite.SQLiteException::class.java) { wrongDb.samples().find(7) }
        } finally { wrongDb.close(); wrongPassword.fill(0) }
        val plainCopy = File(probe.directory, "unkeyed-copy.db")
        probe.encryptedFile.copyTo(plainCopy, overwrite = true)
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            SQLiteDatabase.openDatabase(plainCopy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT * FROM probe_samples", null).use { it.moveToFirst() }
            }
        }
        assertEquals(before, digest(probe.encryptedFile))
        probe.openEncrypted().use { assertEquals(sample, it.database.samples().find(7)) }
        save("wrong-key", JSONObject().put("wrongPasswordRejected", true)
            .put("unkeyedReaderRejected", true).put("originalDatabaseUnchanged", true))
    }

    @Test fun rejectsMissingAndTamperedKeysWithoutRecreatingDatabase() {
        for (reason in ProbeKeyFailure.entries) {
            val probe = fresh("failure-${reason.ordinal}")
            probe.openEncrypted().use { it.database.samples().insert(sample) }
            val before = digest(probe.encryptedFile)
            when (reason) {
                ProbeKeyFailure.MISSING_ENVELOPE -> assertTrue(probe.keys.envelopeFile.delete())
                ProbeKeyFailure.MISSING_KEYSTORE_KEY -> keyStore.deleteEntry(probe.keys.alias)
                ProbeKeyFailure.INVALID_ENVELOPE -> {
                    val bytes = probe.keys.envelopeFile.readBytes()
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    probe.keys.envelopeFile.writeBytes(bytes)
                }
            }
            val failure = assertThrows(ProbeKeyException::class.java) { probe.openEncrypted().use {} }
            assertEquals(reason, failure.reason)
            assertEquals(before, digest(probe.encryptedFile))
            if (reason == ProbeKeyFailure.MISSING_KEYSTORE_KEY) assertFalse(keyStore.containsAlias(probe.keys.alias))
            if (reason == ProbeKeyFailure.MISSING_ENVELOPE) assertFalse(probe.keys.envelopeFile.exists())
        }
        save("key-failures", JSONObject().put("missingEnvelopeRejected", true)
            .put("missingKeystoreKeyRejected", true).put("tamperedEnvelopeRejected", true)
            .put("databasesUnchanged", true).put("noAutomaticKeyReplacement", true))
    }

    private fun fresh(name: String): StorageProbe {
        val probe = StorageProbe(context, name)
        // Only this test's isolated fixture directory and alias are reset.
        check(probe.directory.deleteRecursively())
        check(probe.directory.mkdirs())
        keyStore.deleteEntry(probe.keys.alias)
        return probe
    }

    private fun scalar(db: ProbeDatabase, sql: String): String = db.openHelper.writableDatabase.query(sql).use {
        check(it.moveToFirst()); it.getString(0)
    }
    private fun <T> ProbeDatabase.use(block: (ProbeDatabase) -> T): T =
        try { block(this) } finally { close() }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        bytes.indices.any { start -> start + needle.size <= bytes.size && needle.indices.all { bytes[start + it] == needle[it] } }
    private fun save(phase: String, report: JSONObject) {
        File(context.filesDir, "storage-$phase.json").writeText(report.toString(2))
        Log.i("FluxStorageTest", "PASS $phase: $report")
    }
}
