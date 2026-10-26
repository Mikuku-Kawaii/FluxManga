package com.flux.manga

import androidx.room.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@Dao interface LegacyWorldDao {
    @Insert fun manga(row: StoredManga)
    @Insert fun history(row: SearchHistory)
    @Insert fun session(row: SourceSession)
}
@Database(entities = [StoredManga::class, SearchHistory::class, SourceSession::class], version = 1, exportSchema = false)
abstract class LegacyWorldDatabase : RoomDatabase() { abstract fun data(): LegacyWorldDao }

@RunWith(AndroidJUnit4::class)
class WorldMigrationTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    @Test fun v1ToCurrent_preservesCatalogHistoryAndOpaqueSessions_inBothWorlds(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorldDataStore(context, "test-migration-${UUID.randomUUID()}")
        for (world in PrototypeWorld.entries) {
            store.directory(world).mkdirs()
            store.keys(world, "payload").loadOrCreate(false).fill(0)
            val password = if (world == PrototypeWorld.NSFW) {
                System.loadLibrary("sqlcipher"); store.keys(world, "database").loadOrCreate(false)
            } else null
            val builder = Room.databaseBuilder(context, LegacyWorldDatabase::class.java, store.databaseFile(world).path)
            password?.let { builder.openHelperFactory(SupportOpenHelperFactory(it)) }
            try {
                val db = builder.build()
                try {
                    db.data().manga(StoredManga("sample", "1", "v1-preserved-${world.name}"))
                    db.data().history(SearchHistory("sample", "legacy-${world.name}", 17))
                    db.data().session(SourceSession("fixture", byteArrayOf(10, 20, 30)))
                    assertEquals(1, db.openHelper.readableDatabase.version)
                } finally { db.close() }
            } finally { password?.fill(0) }
            val envelope = store.keys(world, "payload").envelopeFile.readBytes()
            store.prepare(world)
            assertEquals("v1-preserved-${world.name}", store.catalog(world).first().title)
            assertEquals(listOf("legacy-${world.name}"), store.history(world, "sample"))
            assertArrayEquals(envelope, store.keys(world, "payload").envelopeFile.readBytes())
            val reopenedPassword = if (world == PrototypeWorld.NSFW) store.keys(world, "database").loadOrCreate(true) else null
            val current = Room.databaseBuilder(context, WorldDatabase::class.java, store.databaseFile(world).path)
            reopenedPassword?.let { current.openHelperFactory(SupportOpenHelperFactory(it)) }
            try {
                val db = current.build()
                try {
                    assertEquals(10, db.openHelper.readableDatabase.version)
                    assertArrayEquals(byteArrayOf(10, 20, 30), db.data().session("fixture"))
                    assertTrue(db.data().localBooks().isEmpty())
                } finally { db.close() }
            } finally { reopenedPassword?.fill(0) }
        }
        val imported = CbzLibrary(store).import(PrototypeWorld.SFW, CbzSource("after-migration.cbz") { CbzFixtures.valid().inputStream() })
        assertEquals(5, CbzLibrary(store).detail(imported.key)!!.card.pageCount)
    }
}
