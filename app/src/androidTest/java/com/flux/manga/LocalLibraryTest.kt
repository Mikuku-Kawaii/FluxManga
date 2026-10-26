package com.flux.manga

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.LocalLibraryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

internal object LocalLibraryFixtures {
    const val alpha = "合成检索 Alpha_100%"
    const val beta = "合成检索 Beta"
    fun source(title: String, color: Int) = CbzSource("$title.cbz") {
        CbzFixtures.archive(listOf("page1.png" to CbzFixtures.image(color = color),
            "fixture.txt" to "M2 local library $title".toByteArray())).inputStream()
    }
    suspend fun prepare(library: CbzLibrary): Pair<MangaKey, MangaKey> {
        val a = library.import(PrototypeWorld.SFW, source(alpha, Color.BLUE)).key
        val b = library.import(PrototypeWorld.SFW, source(beta, Color.GREEN)).key
        library.setFavorite(a, false); library.setFavorite(b, false)
        return a to b
    }
}

@RunWith(AndroidJUnit4::class)
class LocalLibraryTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    @Test fun favoritesPersistAndRepair_retainsIdentityAndSource_otherKeysAndExpiredWritesRejected(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorldDataStore(context, "test-library-${UUID.randomUUID()}")
        val library = CbzLibrary(store)
        val (a, b) = LocalLibraryFixtures.prepare(library)
        val before = library.detail(a)!!
        library.setFavorite(a, true); library.setFavorite(a, true)
        val reopened = CbzLibrary(WorldDataStore(context, store.directory(PrototypeWorld.SFW).parentFile!!.name))
        assertTrue(reopened.detail(a)!!.card.favorite)
        assertFalse(reopened.detail(b)!!.card.favorite)
        assertEquals(before.chapter, reopened.detail(a)!!.chapter)
        try { library.setFavorite(a, false) { false }; fail("Expired write accepted") } catch (_: CancellationException) { }
        try { library.setFavorite(a.copy(world = PrototypeWorld.NSFW), false); fail("Private favorite accepted") }
        catch (e: CbzException) { assertEquals(ImportFailure.WORLD, e.failure) }
        for (wrong in listOf(a.copy(sourceId = "another-source"), a.copy(mangaId = "missing"))) {
            try { library.setFavorite(wrong, false); fail("Wrong key accepted") } catch (_: IOException) { }
        }
        assertTrue(library.detail(a)!!.card.favorite)
        val archive = store.localData { it.localBook(a.sourceId, a.mangaId)!! }
        val copy = java.io.File(library.directory, archive.archiveName)
        val originalBytes = copy.readBytes()
        assertTrue(copy.delete())
        assertFalse(library.detail(a)!!.card.available)
        val repaired = library.import(PrototypeWorld.SFW, LocalLibraryFixtures.source(LocalLibraryFixtures.alpha, Color.BLUE))
        assertEquals(a, repaired.key)
        assertTrue(library.detail(a)!!.card.favorite)
        val repairedBook = store.localData { it.localBook(a.sourceId, a.mangaId)!! }
        assertArrayEquals(originalBytes, java.io.File(library.directory, repairedBook.archiveName).readBytes())
        library.setFavorite(a, false)
        assertFalse(reopened.detail(a)!!.card.favorite)
    }

    @Test fun titleFilterIsLiteralUnicodeCaseInsensitive_andComposesWithFavorites(): Unit = runBlocking {
        val library = CbzLibrary(WorldDataStore(InstrumentationRegistry.getInstrumentation().targetContext, "test-search-${UUID.randomUUID()}"))
        val (a, b) = LocalLibraryFixtures.prepare(library)
        library.setFavorite(a, true)
        val cards = library.cards(PrototypeWorld.SFW)
        val snapshot = LocalLibraryState(loading = false, cards = cards)
        assertEquals(listOf(b, a), snapshot.visibleCards.map { it.key })
        for (query in listOf("合成检索", "  合成检索  ")) assertEquals(2, snapshot.copy(query = query).visibleCards.size)
        for (query in listOf("ALPHA", "_100%", "%")) assertEquals(listOf(a), snapshot.copy(query = query).visibleCards.map { it.key })
        assertTrue(snapshot.copy(query = "100_").visibleCards.isEmpty())
        assertEquals(listOf(a), snapshot.copy(favoritesOnly = true).visibleCards.map { it.key })
        assertTrue(snapshot.copy(query = "Beta", favoritesOnly = true).visibleCards.isEmpty())
        assertEquals(listOf(b, a), snapshot.copy(query = "   ").visibleCards.map { it.key })
    }
}

