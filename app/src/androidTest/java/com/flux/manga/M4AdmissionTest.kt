package com.flux.manga

import android.os.UserManager
import android.provider.Settings
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.storage.PreservingDatabaseFactory
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only UUID-scoped synthetic data; never deletes a namespace or a business key. */
@RunWith(AndroidJUnit4::class)
class M4AdmissionTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun store() = WorldDataStore(context, "m4-admission-${UUID.randomUUID()}")
    private fun helper(file: File, password: ByteArray?, callback: SupportSQLiteOpenHelper.Callback) =
        PreservingDatabaseFactory(password).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.path).callback(callback).build())
    private fun key(store: WorldDataStore, world: PrototypeWorld): ByteArray? {
        store.directory(world).mkdirs()
        store.keys(world, "payload").loadOrCreate(false).fill(0)
        return if (world == PrototypeWorld.NSFW) {
            System.loadLibrary("sqlcipher"); store.keys(world, "database").loadOrCreate(false)
        } else null
    }
    @Test fun v9ToV10PreservesOnlineRowsAndMigratesOnlyKnownChapter(): Unit = runBlocking {
        val store = store()
        val schema = JSONObject(inst.context.assets.open("com.flux.manga.data.WorldDatabase/9.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        for (world in PrototypeWorld.entries) {
            val password = key(store, world)
            val old = helper(store.databaseFile(world), password, object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                        db.execSQL(sql(entity.getString("createSql")))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (j in 0 until indices.length()) db.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                    }
                    db.execSQL("INSERT INTO online_books VALUES ('fixture', 'same-id', 'M4_SYNTHETIC_TITLE', 1, 123)")
                    db.execSQL("INSERT INTO online_reading_progress VALUES ('fixture', 'same-id', 'chapter-2', 'page-3', 3, 0.25, 'STRIP', 123, 1)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            })
            try { assertEquals(9, old.writableDatabase.version) } finally { old.close(); password?.fill(0) }
            val envelope = store.keys(world, "payload").envelopeFile.readBytes()
            val originalDatabase = store.databaseFile(world).readBytes()
            val copyReport = store.diagnoseProtection(world, { true }, {}, {}, migrateCopy = true)
            assertNull(copyReport.error)
            assertEquals(9, copyReport.originalVersion); assertEquals(10, copyReport.version)
            assertTrue(copyReport.migrationChecked); assertFalse(copyReport.cleanupRequired)
            assertArrayEquals(originalDatabase, store.databaseFile(world).readBytes())
            assertArrayEquals(envelope, store.keys(world, "payload").envelopeFile.readBytes())
            store.prepare(world)
            store.worldData(world) { dao ->
                assertEquals("M4_SYNTHETIC_TITLE", dao.onlineBook("fixture", "same-id")!!.title)
                assertTrue(dao.onlineBook("fixture", "same-id")!!.favorite)
                val progress = dao.onlineProgress("fixture", "same-id")!!
                assertEquals("STRIP", progress.mode); assertTrue(progress.modeExplicit)
                val chapter = dao.chapterReading("fixture", "same-id", "chapter-2")!!
                assertEquals("page-3", chapter.pageId); assertEquals(.25f, chapter.fraction, 0f)
                assertFalse(chapter.completed)
                assertNull(dao.chapterReading("fixture", "same-id", "chapter-1"))
                assertNull(dao.onlineBook("other-source", "same-id"))
            }
            assertArrayEquals(envelope, store.keys(world, "payload").envelopeFile.readBytes())
            if (world == PrototypeWorld.NSFW) assertFalse(store.databaseFile(world).readBytes().decodeToString().contains("M4_SYNTHETIC_TITLE"))
        }
    }
    @Test fun failedUpgradeRollsBackAndKeepsOldRowsInBothEngines() {
        val store = store()
        for (world in PrototypeWorld.entries) {
            val password = key(store, world)
            val file = store.databaseFile(world)
            fun callback(version: Int) = object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE retained (value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO retained VALUES ('before')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    db.execSQL("UPDATE retained SET value = 'must-rollback'")
                    throw IllegalStateException("Injected migration failure in new UUID fixture")
                }
            }
            try {
                helper(file, password, callback(1)).use { assertEquals(1, it.writableDatabase.version) }
                val before = file.readBytes()
                try { helper(file, password, callback(2)).use { it.writableDatabase }; fail("Upgrade must fail") }
                catch (_: IllegalStateException) { }
                assertTrue(file.exists())
                assertArrayEquals(before, file.readBytes())
                helper(file, password, callback(1)).use { h ->
                    assertEquals(1, h.readableDatabase.version)
                    h.readableDatabase.query("SELECT value FROM retained").use { c -> assertTrue(c.moveToFirst()); assertEquals("before", c.getString(0)) }
                }
            } finally { password?.fill(0) }
        }
    }
    private fun rebootStore(): WorldDataStore {
        val namespace = InstrumentationRegistry.getArguments().getString("m4Fixture") ?: error("Missing fixture identity")
        require(namespace.matches(Regex("m4-reboot-[a-f0-9-]{36}")))
        return WorldDataStore(context, namespace)
    }
    @Test fun writeRebootFixture(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m4Phase") == "write")
        val store = rebootStore()
        for (world in PrototypeWorld.entries) {
            check(!store.directory(world).exists()) // Never overwrite even an earlier synthetic fixture.
            store.remember(world, "fixture", "HISTORY_${world.name}")
            store.putSession(world, "fixture", "FAKE_SESSION_${world.name}".toByteArray())
            store.putResource(ResourceKey(SampleKey(world, "fixture", "same"), "page"), "SYNTHETIC_PIXELS_${world.name}".toByteArray())
        }
        File(store.directory(PrototypeWorld.SFW), "boot-count").writeText(
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString())
    }
    @Test fun readAfterDeviceRebootAndUnlock(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m4Phase") == "read")
        assertTrue(context.getSystemService(UserManager::class.java).isUserUnlocked)
        val store = rebootStore()
        val oldBoot = File(store.directory(PrototypeWorld.SFW), "boot-count").readText().toInt()
        assertTrue(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) > oldBoot)
        for (world in PrototypeWorld.entries) {
            assertEquals(listOf("HISTORY_${world.name}"), store.history(world, "fixture"))
            assertEquals("FAKE_SESSION_${world.name}", store.session(world, "fixture")!!.decodeToString())
            assertEquals("SYNTHETIC_PIXELS_${world.name}", store.resource(ResourceKey(SampleKey(world, "fixture", "same"), "page"))!!.decodeToString())
        }
    }
}
