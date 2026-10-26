package com.flux.manga

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalCoverLookupTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private val world = PrototypeWorld.SFW
    private fun store() = WorldDataStore(InstrumentationRegistry.getInstrumentation().targetContext,
        "test-cover-lookup-${UUID.randomUUID()}")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test fun keyLookupMatchesNaturalFirstPageAndDetail_rebuildsCacheWithoutChangingProgress(): Unit = runBlocking {
        val store = store()
        val library = CbzLibrary(store)
        val bytes = CbzFixtures.archive(listOf(
            "page10.png" to CbzFixtures.image(color = Color.RED),
            "page2.png" to CbzFixtures.image(color = Color.BLUE)))
        val originalHash = hash(bytes)
        val key = library.import(world, CbzSource("isolated-cover.cbz") { bytes.inputStream() }).key
        library.saveProgress(key, "page10.png", .25f, ReadingMode.STRIP)
        library.setFavorite(key, true)
        val detail = library.detail(key)!!
        assertEquals("page2.png", detail.chapter.pages.first().entryName)
        val expected = library.cover(detail)!!
        try {
            repeat(3) { phase ->
                if (phase == 1) assertTrue(library.coverFile(key).delete())
                if (phase == 2) library.coverFile(key).writeText("corrupt disposable test cache")
                val actual = CbzLibrary(store).cover(key)!!
                try {
                    assertTrue(expected.sameAs(actual))
                    assertEquals(Color.BLUE, actual.getPixel(0, 0))
                } finally { actual.recycle() }
                assertEquals(detail, library.detail(key))
            }
            assertArrayEquals(originalHash, hash(bytes))
            assertArrayEquals(originalHash, hash(library.directory.listFiles()!!.single().readBytes()))
        } finally { expected.recycle() }
    }

    @Test fun missingOrRemovedBookCannotServeCachedCover_foreignWorldIsRejected(): Unit = runBlocking {
        val store = store()
        val library = CbzLibrary(store)
        val bytes = CbzFixtures.valid()
        val source = CbzSource("isolated-missing-cover.cbz") { bytes.inputStream() }
        val key = library.import(world, source).key
        library.cover(key)!!.recycle()
        val copy = File(library.directory, store.localData { it.localBook(key.sourceId, key.mangaId)!! }.archiveName)
        assertTrue(copy.delete())
        try { library.cover(key); fail("Must reject missing archive even with cached cover") }
        catch (failure: CbzException) { assertEquals(ImportFailure.MISSING, failure.failure) }
        assertEquals(key, library.import(world, source).key)
        library.cover(key)!!.recycle()
        val repairedCopy = File(library.directory,
            store.localData { it.localBook(key.sourceId, key.mangaId)!! }.archiveName)
        // Isolated namespace: model interruption after deletion commits but before file cleanup.
        store.localData { it.removeBook(key.sourceId, key.mangaId) }
        assertTrue(repairedCopy.exists())
        assertNull(library.cover(key))
        assertFalse(repairedCopy.exists())
        assertFalse(library.coverFile(key).exists())
        try { library.cover(key.copy(world = PrototypeWorld.NSFW)); fail("Must reject private world") }
        catch (failure: CbzException) { assertEquals(ImportFailure.WORLD, failure.failure) }
    }
}
