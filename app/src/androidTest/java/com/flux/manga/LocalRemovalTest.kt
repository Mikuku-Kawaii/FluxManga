package com.flux.manga

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalRemovalTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sfw = PrototypeWorld.SFW
    @Test fun removalCascadesAndCleansOwnedFiles_preservesOriginalOtherBookAndWorld(): Unit = runBlocking {
        val store = WorldDataStore(context, "test-remove-${UUID.randomUUID()}")
        val lib = CbzLibrary(store)
        val original = File(context.getExternalFilesDir(null), "removal-original.cbz").apply { writeBytes(CbzFixtures.valid()) }
        val hash = ReaderFixtures.hash(original); val modified = original.lastModified()
        val a = lib.import(sfw, CbzSource(original.name) { original.inputStream() }).key
        val b = lib.import(sfw, LocalLibraryFixtures.source("保留样本", Color.GREEN)).key
        val detail = lib.detail(a)!!
        lib.saveProgress(a, detail.chapter.pages.last().id, .4f, ReadingMode.STRIP)
        lib.setFavorite(a, true)
        lib.cover(detail)!!.recycle(); lib.cover(lib.detail(b)!!)!!.recycle()
        val copy = File(lib.directory, store.localData { it.localBook(a.sourceId, a.mangaId)!! }.archiveName)
        val otherCopy = File(lib.directory, store.localData { it.localBook(b.sourceId, b.mangaId)!! }.archiveName)
        val otherHash = ReaderFixtures.hash(otherCopy); val otherCoverHash = ReaderFixtures.hash(lib.coverFile(b))
        val privateCache = File(store.cacheDirectory(PrototypeWorld.NSFW).apply { mkdirs() }, "synthetic-sentinel").apply { writeText("private synthetic cache") }
        lib.remove(a)
        assertFalse(copy.exists()); assertFalse(lib.coverFile(a).exists())
        assertNull(lib.detail(a)); assertEquals(listOf(b), lib.cards(sfw).map { it.key })
        store.localData { assertTrue(it.localPages(a.sourceId, a.mangaId).isEmpty()); assertNull(it.progress(a.sourceId, a.mangaId)) }
        try { lib.saveProgress(a, detail.chapter.pages.first().id, 0f, ReadingMode.LTR); fail("Late write recreated progress") } catch (_: IOException) { }
        lib.remove(a) // Idempotent retry.
        assertEquals(hash, ReaderFixtures.hash(original)); assertEquals(modified, original.lastModified())
        assertEquals(otherHash, ReaderFixtures.hash(otherCopy)); assertEquals(otherCoverHash, ReaderFixtures.hash(lib.coverFile(b)))
        assertEquals("private synthetic cache", privateCache.readText())
        val reimport = lib.import(sfw, CbzSource(original.name) { original.inputStream() })
        assertFalse(reimport.duplicate); assertNotEquals(a, reimport.key)
        assertNull(lib.detail(reimport.key)!!.card.progress); assertFalse(lib.detail(reimport.key)!!.card.favorite)
    }
    @Test fun interruptedCommitRecoveredOnReopen_missingCopyAndExpiredWorldAreSafe(): Unit = runBlocking {
        val namespace = "test-remove-recovery-${UUID.randomUUID()}"
        val store = WorldDataStore(context, namespace); val lib = CbzLibrary(store)
        val (a, b) = LocalLibraryFixtures.prepare(lib)
        lib.cover(lib.detail(a)!!)!!.recycle()
        val copy = File(lib.directory, store.localData { it.localBook(a.sourceId, a.mangaId)!! }.archiveName)
        try { lib.remove(a) { false }; fail("Expired remove accepted") } catch (_: CancellationException) { }
        try { lib.remove(a.copy(world = PrototypeWorld.NSFW)); fail("Private remove accepted") } catch (e: CbzException) { assertEquals(ImportFailure.WORLD, e.failure) }
        lib.remove(a.copy(sourceId = "another-source")); assertNotNull(lib.detail(a))
        // Simulate death immediately after the SQLite commit, before file cleanup.
        store.localData { it.removeBook(a.sourceId, a.mangaId) }
        assertTrue(copy.exists()); assertTrue(lib.coverFile(a).exists())
        val reopened = CbzLibrary(WorldDataStore(context, namespace))
        assertEquals(listOf(b), reopened.cards(sfw).map { it.key })
        assertFalse(copy.exists()); assertFalse(lib.coverFile(a).exists())
        val bCopy = File(lib.directory, store.localData { it.localBook(b.sourceId, b.mangaId)!! }.archiveName)
        assertTrue(bCopy.delete()); reopened.remove(b)
        assertTrue(reopened.cards(sfw).isEmpty())
    }
    @Test fun cacheRebuildsAfterEvictionOrCorruption(): Unit = runBlocking {
        val lib = CbzLibrary(WorldDataStore(context, "test-cover-${UUID.randomUUID()}"))
        val (a, _) = LocalLibraryFixtures.prepare(lib)
        val detail = lib.detail(a)!!
        repeat(3) { index ->
            lib.cover(detail)!!.let { assertTrue(it.width <= 256 && it.height <= 256); assertEquals(Color.BLUE, it.getPixel(0, 0)); it.recycle() }
            if (index == 0) assertTrue(lib.coverFile(a).delete())
            if (index == 1) lib.coverFile(a).writeText("damaged disposable cache")
        }
    }
}

