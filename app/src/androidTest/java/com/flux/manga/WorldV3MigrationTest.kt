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

@Dao interface V3Dao {
    @Insert fun book(book: LocalBook)
    @Insert fun page(page: LocalPage)
    @Insert fun history(history: SearchHistory)
    @Insert fun session(session: SourceSession)
}
@Database(entities = [StoredManga::class, SearchHistory::class, SourceSession::class, LocalBook::class, LocalPage::class], version = 3, exportSchema = false)
abstract class V3Database : RoomDatabase() { abstract fun data(): V3Dao }

@RunWith(AndroidJUnit4::class)
class WorldV3MigrationTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    @Test fun bothWorldsPreserveFavoritesBooksHistorySessionsAndKeys_progressStartsEmpty(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorldDataStore(context, "test-v3-${UUID.randomUUID()}")
        for (world in PrototypeWorld.entries) {
            store.directory(world).mkdirs(); store.keys(world, "payload").loadOrCreate(false).fill(0)
            val password = if (world == PrototypeWorld.NSFW) {
                System.loadLibrary("sqlcipher"); store.keys(world, "database").loadOrCreate(false)
            } else null
            val builder = Room.databaseBuilder(context, V3Database::class.java, store.databaseFile(world).path)
            password?.let { builder.openHelperFactory(SupportOpenHelperFactory(it)) }
            val old = builder.build()
            try {
                old.data().book(LocalBook(mangaId = "legacy", title = "v3 合成收藏", archiveName = "00000000-0000-0000-0000-000000000001.cbz",
                    sha256 = "synthetic-hash", archiveBytes = 1234, importedAt = 123, favorite = true))
                old.data().page(LocalPage(mangaId = "legacy", ordinal = 1, entryName = "page1.png", mimeType = "image/png", width = 96, height = 144))
                old.data().history(SearchHistory("sample", "v3 history", 123))
                old.data().session(SourceSession("fixture", byteArrayOf(3,4)))
                assertEquals(3, old.openHelper.readableDatabase.version)
            } finally { old.close(); password?.fill(0) }
            val payload = store.keys(world, "payload").envelopeFile.readBytes()
            val envelope = if (world == PrototypeWorld.NSFW) store.keys(world, "database").envelopeFile.readBytes() else null
            store.prepare(world)
            assertArrayEquals(payload, store.keys(world, "payload").envelopeFile.readBytes())
            envelope?.let { assertArrayEquals(it, store.keys(world, "database").envelopeFile.readBytes()) }
            val reopenedPassword = if (world == PrototypeWorld.NSFW) store.keys(world, "database").loadOrCreate(true) else null
            val current = Room.databaseBuilder(context, WorldDatabase::class.java, store.databaseFile(world).path)
            reopenedPassword?.let { current.openHelperFactory(SupportOpenHelperFactory(it)) }
            val db = current.build()
            try {
                assertEquals(10, db.openHelper.readableDatabase.version)
                val book = db.data().localBook("cbz", "legacy")!!
                assertTrue(book.favorite); assertEquals("synthetic-hash", book.sha256); assertEquals(1234L, book.archiveBytes)
                assertEquals("page1.png", db.data().localPages("cbz", "legacy").single().entryName)
                assertNull(db.data().progress("cbz", "legacy"))
                assertEquals(listOf("v3 history"), db.data().history("sample"))
                assertArrayEquals(byteArrayOf(3,4), db.data().session("fixture"))
            } finally { db.close(); reopenedPassword?.fill(0) }
        }
    }
}
