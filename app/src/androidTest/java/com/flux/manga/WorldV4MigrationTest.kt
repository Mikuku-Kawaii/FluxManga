package com.flux.manga

import androidx.room.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

// Frozen v4/v7 shape: never reuse the current progress entity in a legacy database.
@Entity(tableName = "reading_progress", primaryKeys = ["sourceId", "mangaId"],
    foreignKeys = [ForeignKey(entity = LocalBook::class, parentColumns = ["sourceId", "mangaId"],
        childColumns = ["sourceId", "mangaId"], onDelete = ForeignKey.CASCADE)])
data class LegacyReadingProgress(val sourceId: String, val mangaId: String, val chapterId: String,
    val pageId: String, val ordinal: Int, val fraction: Float, val mode: String, val updatedAt: Long)

@Dao interface V4Dao {
    @Insert fun book(book: LocalBook)
    @Insert fun progress(progress: LegacyReadingProgress)
    @Insert fun session(session: SourceSession)
}
@Database(entities = [StoredManga::class, SearchHistory::class, SourceSession::class, LocalBook::class, LocalPage::class, LegacyReadingProgress::class],
    version = 4, exportSchema = false)
abstract class V4Database : RoomDatabase() { abstract fun data(): V4Dao }

/** Prepared for deferred device verification. Only UUID-scoped synthetic stores are used. */
@RunWith(AndroidJUnit4::class)
class WorldV4MigrationTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    @Test fun migrationPreservesLocalProgressAndKeys_onlineRowsNeedNoCbzParent(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorldDataStore(context, "test-v4-${UUID.randomUUID()}")
        for (world in PrototypeWorld.entries) {
            store.directory(world).mkdirs()
            store.keys(world, "payload").loadOrCreate(false).fill(0)
            val password = if (world == PrototypeWorld.NSFW) {
                System.loadLibrary("sqlcipher"); store.keys(world, "database").loadOrCreate(false)
            } else null
            val builder = Room.databaseBuilder(context, V4Database::class.java, store.databaseFile(world).path)
            password?.let { builder.openHelperFactory(SupportOpenHelperFactory(it)) }
            val old = builder.build()
            val localProgress = ReadingProgress("cbz", "legacy", "cbz-1", "p.png", 3, .4f, "STRIP", 123)
            try {
                old.data().book(LocalBook(mangaId = "legacy", title = "合成旧书", archiveName = "legacy.cbz",
                    sha256 = "synthetic-v4-hash", archiveBytes = 1234, importedAt = 123, favorite = true))
                old.data().progress(LegacyReadingProgress("cbz", "legacy", "cbz-1", "p.png", 3, .4f, "STRIP", 123))
                old.data().session(SourceSession("fixture", byteArrayOf(4, 5)))
            } finally { old.close(); password?.fill(0) }
            val envelope = store.keys(world, "payload").envelopeFile.readBytes()
            val dbEnvelope = if (world == PrototypeWorld.NSFW) store.keys(world, "database").envelopeFile.readBytes() else null
            store.prepare(world)
            assertArrayEquals(envelope, store.keys(world, "payload").envelopeFile.readBytes())
            dbEnvelope?.let { assertArrayEquals(it, store.keys(world, "database").envelopeFile.readBytes()) }
            val secret = if (world == PrototypeWorld.NSFW) store.keys(world, "database").loadOrCreate(true) else null
            val current = Room.databaseBuilder(context, WorldDatabase::class.java, store.databaseFile(world).path)
            secret?.let { current.openHelperFactory(SupportOpenHelperFactory(it)) }
            val db = current.build()
            try {
                assertEquals(10, db.openHelper.readableDatabase.version)
                assertEquals(localProgress, db.data().progress("cbz", "legacy"))
                assertTrue(db.data().localBook("cbz", "legacy")!!.favorite)
                assertArrayEquals(byteArrayOf(4, 5), db.data().session("fixture"))
                val online = OnlineReadingProgress("komga-a", "legacy", "chapter", "2", 2, 0f, "RTL", 456)
                db.data().putOnlineProgress(online)
                assertEquals(online, db.data().onlineProgress("komga-a", "legacy"))
                assertNull(db.data().onlineProgress("komga-b", "legacy"))
                assertEquals(localProgress, db.data().progress("cbz", "legacy"))
            } finally { db.close(); secret?.fill(0) }
        }
    }
}
