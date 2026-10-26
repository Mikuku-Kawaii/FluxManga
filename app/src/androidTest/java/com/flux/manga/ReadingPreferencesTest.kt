package com.flux.manga

import androidx.room.*
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@Entity(tableName = "online_reading_progress", primaryKeys = ["sourceId", "mangaId"])
data class LegacyOnlineReadingProgress(val sourceId: String, val mangaId: String, val chapterId: String,
    val pageId: String, val ordinal: Int, val fraction: Float, val mode: String, val updatedAt: Long)

@Database(entities = [StoredManga::class, SearchHistory::class, SourceSession::class, LocalBook::class,
    LocalPage::class, LegacyReadingProgress::class, LegacyOnlineReadingProgress::class, OnlineBook::class,
    UserTag::class, TagBook::class], version = 7, exportSchema = false)
abstract class V7ReadingDatabase : RoomDatabase() { abstract fun data(): V4Dao }

/** Deferred device checks. All writes use a UUID namespace, never the user's business store. */
class ReadingPreferencesTest {
    @Test fun legacyMigrationAndWorldDefaultsPreserveRowsAndKeys(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-reading-default-${UUID.randomUUID()}"
        val store = WorldDataStore(context, namespace)
        for (world in PrototypeWorld.entries) {
            store.directory(world).mkdirs()
            store.keys(world, "payload").loadOrCreate(false).fill(0)
            val password = if (world == PrototypeWorld.NSFW) {
                System.loadLibrary("sqlcipher"); store.keys(world, "database").loadOrCreate(false)
            } else null
            val builder = Room.databaseBuilder(context, V7ReadingDatabase::class.java, store.databaseFile(world).path)
            password?.let { builder.openHelperFactory(SupportOpenHelperFactory(it)) }
            val old = builder.build()
            try {
                old.data().book(LocalBook(mangaId = "old", title = "合成旧书", archiveName = "old.cbz",
                    sha256 = "isolated-test-hash", archiveBytes = 1, importedAt = 100, favorite = true))
                old.data().progress(LegacyReadingProgress("cbz", "old", "c", "p", 3, .625f, "RTL", 100))
                old.openHelper.writableDatabase.execSQL("INSERT INTO online_reading_progress VALUES ('source', 'old', 'c', 'p', 4, 0.375, 'STRIP', 101)")
            } finally { old.close(); password?.fill(0) }
            val envelope = store.keys(world, "payload").envelopeFile.readBytes()
            val dbEnvelope = if (world == PrototypeWorld.NSFW) store.keys(world, "database").envelopeFile.readBytes() else null
            assertEquals(ReadingMode.LTR, store.readingDefault(world)) // Opens and migrates v7.
            store.saveReadingDefault(world, if (world == PrototypeWorld.SFW) ReadingMode.VERTICAL else ReadingMode.RTL) { true }
            store.worldData(world) { dao ->
                val local = dao.progress("cbz", "old")!!
                val online = dao.onlineProgress("source", "old")!!
                assertTrue(local.modeExplicit); assertTrue(online.modeExplicit)
                assertEquals("RTL", local.mode); assertEquals(.625f, local.fraction); assertEquals(100L, local.updatedAt)
                assertEquals("STRIP", online.mode); assertEquals(.375f, online.fraction); assertEquals(101L, online.updatedAt)
                assertTrue(dao.localBook("cbz", "old")!!.favorite)
            }
            assertArrayEquals(envelope, store.keys(world, "payload").envelopeFile.readBytes())
            dbEnvelope?.let { assertArrayEquals(it, store.keys(world, "database").envelopeFile.readBytes()) }
        }
        val reopened = WorldDataStore(context, namespace)
        assertEquals(ReadingMode.VERTICAL, reopened.readingDefault(PrototypeWorld.SFW))
        assertEquals(ReadingMode.RTL, reopened.readingDefault(PrototypeWorld.NSFW))
        try { reopened.saveReadingDefault(PrototypeWorld.SFW, ReadingMode.STRIP) { false }; fail("Expired write") }
        catch (_: CancellationException) { }
        assertEquals(ReadingMode.VERTICAL, reopened.readingDefault(PrototypeWorld.SFW))
        val library = CbzLibrary(reopened)
        val key = ReaderFixtures.prepare(library)
        library.saveProgress(key, "page3.png", .625f, ReadingMode.VERTICAL, modeExplicit = false)
        reopened.saveReadingDefault(PrototypeWorld.SFW, ReadingMode.RTL) { true }
        val progress = library.detail(key)!!.card.progress!!
        assertFalse(progress.modeExplicit); assertEquals(.625f, progress.fraction)
        assertEquals(ReadingMode.RTL, resolveReadingMode(progress.mode, progress.modeExplicit, reopened.readingDefault(PrototypeWorld.SFW)))
        library.saveProgress(key, progress.pageId, progress.fraction, ReadingMode.STRIP)
        val selected = library.detail(key)!!.card.progress!!
        assertTrue(selected.modeExplicit)
        assertEquals(ReadingMode.STRIP, resolveReadingMode(selected.mode, selected.modeExplicit, ReadingMode.RTL))
    }
}
