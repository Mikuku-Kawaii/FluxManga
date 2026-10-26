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

@Entity(tableName = "local_books", primaryKeys = ["sourceId", "mangaId"], indices = [Index(value = ["sha256"], unique = true)])
data class V2Book(val sourceId: String, val mangaId: String, val title: String, val archiveName: String,
    val sha256: String, val archiveBytes: Long, val importedAt: Long)
@Entity(tableName = "local_pages", primaryKeys = ["sourceId", "mangaId", "ordinal"],
    foreignKeys = [ForeignKey(entity = V2Book::class, parentColumns = ["sourceId", "mangaId"],
        childColumns = ["sourceId", "mangaId"], onDelete = ForeignKey.CASCADE)])
data class V2Page(val sourceId: String, val mangaId: String, val ordinal: Int, val entryName: String,
    val mimeType: String, val width: Int, val height: Int)
@Dao interface V2Dao {
    @Insert fun book(book: V2Book)
    @Insert fun pages(pages: List<V2Page>)
    @Insert fun history(history: SearchHistory)
    @Insert fun session(session: SourceSession)
}
@Database(entities = [StoredManga::class, SearchHistory::class, SourceSession::class, V2Book::class, V2Page::class], version = 2, exportSchema = false)
abstract class V2Database : RoomDatabase() { abstract fun data(): V2Dao }

@RunWith(AndroidJUnit4::class)
class WorldV2MigrationTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    @Test fun v2ToCurrent_bothWorldsKeepLocalMetadataPagesHistoryAndEnvelopes_favoriteDefaultsFalse(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorldDataStore(context, "test-v2-${UUID.randomUUID()}")
        for (world in PrototypeWorld.entries) {
            store.directory(world).mkdirs()
            store.keys(world, "payload").loadOrCreate(false).fill(0)
            val password = if (world == PrototypeWorld.NSFW) {
                System.loadLibrary("sqlcipher"); store.keys(world, "database").loadOrCreate(false)
            } else null
            val builder = Room.databaseBuilder(context, V2Database::class.java, store.databaseFile(world).path)
            password?.let { builder.openHelperFactory(SupportOpenHelperFactory(it)) }
            val old = builder.build()
            try {
                old.data().book(V2Book("cbz", "legacy", "v2 合成书", "00000000-0000-0000-0000-000000000001.cbz", "synthetic-hash", 2196, 123))
                old.data().pages(CbzFixtures.order.mapIndexed { i, name -> V2Page("cbz", "legacy", i + 1, name, "image/png", 96, 144) })
                old.data().history(SearchHistory("sample", "v2 历史", 100))
                old.data().session(SourceSession("fixture", byteArrayOf(1, 2, 3)))
                assertEquals(2, old.openHelper.readableDatabase.version)
            } finally { old.close(); password?.fill(0) }
            val payloadEnvelope = store.keys(world, "payload").envelopeFile.readBytes()
            val databaseEnvelope = if (world == PrototypeWorld.NSFW) store.keys(world, "database").envelopeFile.readBytes() else null
            store.prepare(world)
            assertArrayEquals(payloadEnvelope, store.keys(world, "payload").envelopeFile.readBytes())
            databaseEnvelope?.let { assertArrayEquals(it, store.keys(world, "database").envelopeFile.readBytes()) }
            val reopenedPassword = if (world == PrototypeWorld.NSFW) store.keys(world, "database").loadOrCreate(true) else null
            val current = Room.databaseBuilder(context, WorldDatabase::class.java, store.databaseFile(world).path)
            reopenedPassword?.let { current.openHelperFactory(SupportOpenHelperFactory(it)) }
            val db = current.build()
            try {
                assertEquals(10, db.openHelper.readableDatabase.version)
                val book = db.data().localBook("cbz", "legacy")!!
                assertFalse(book.favorite); assertEquals("v2 合成书", book.title)
                assertEquals("synthetic-hash", book.sha256); assertEquals(2196L, book.archiveBytes); assertEquals(123L, book.importedAt)
                assertEquals(CbzFixtures.order, db.data().localPages("cbz", "legacy").map { it.entryName })
                assertEquals(listOf("v2 历史"), db.data().history("sample"))
                assertArrayEquals(byteArrayOf(1, 2, 3), db.data().session("fixture"))
            } finally { db.close(); reopenedPassword?.fill(0) }
        }
    }
}
