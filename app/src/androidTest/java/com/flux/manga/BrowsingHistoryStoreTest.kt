package com.flux.manga

import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Compiled for later device verification. Only UUID-scoped synthetic data is written. */
class BrowsingHistoryStoreTest {
    @Test fun visitsAndSearchesReopenWithoutChangingBooksProgressOrOtherSpace(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-browse-${UUID.randomUUID()}"
        val data = WorldDataStore(context, namespace)
        val session = WorldSession()
        val history = BrowsingHistory(context, session, data)
        val progress = ReadingProgress("cbz", "one", "c", "p", 3, .625f, "STRIP", 123)
        data.localData {
            it.insertBook(LocalBook(mangaId = "one", title = "合成浏览作品", archiveName = "missing.cbz", sha256 = "one", archiveBytes = 1, importedAt = 1, favorite = true))
            it.putProgress(progress)
            it.remember(SearchHistory("sample", "旧样本记录", 1))
        }
        history.record("local", "cbz", "one")
        history.record("local", "cbz", "one")
        history.record("online", "old-source", "not-in-library")
        val page = history.page(20)
        assertEquals(2, page.items.size)
        assertEquals(VisitAvailability.MISSING, page.items.first { it.visit.kind == "local" }.availability)
        assertEquals(VisitAvailability.SOURCE_CHANGED, page.items.first { it.visit.kind == "online" }.availability)
        data.localData { assertEquals(progress, it.progress("cbz", "one")); assertNull(it.onlineBook("old-source", "not-in-library")) }
        history.submit("source-a", "  words  "); history.submit("source-b", "words"); history.submit("source-a", "words")
        history.submit("source-a", "   ")
        val reopened = BrowsingHistory(context, session, WorldDataStore(context, namespace))
        assertEquals(2, reopened.searches().size)
        assertEquals(2, reopened.page(20).items.size)
        data.worldData(PrototypeWorld.NSFW) {
            it.putVisit(BrowseVisit("online", "old-source", "not-in-library", "隔离记录", 1))
            it.remember(SearchHistory("online:source-a", "words", 1))
        }
        reopened.remove(page.items.first().visit)
        assertEquals(1, reopened.page(20).items.size)
        reopened.remove(null); reopened.removeSearch(null)
        assertTrue(reopened.page(20).items.isEmpty()); assertTrue(reopened.searches().isEmpty())
        data.localData {
            assertEquals(progress, it.progress("cbz", "one")); assertTrue(it.localBook("cbz", "one")!!.favorite)
            assertEquals(listOf("旧样本记录"), it.history("sample"))
        }
        data.worldData(PrototypeWorld.NSFW) {
            assertEquals(1, it.visits(null, "", "", "", 20).size)
            assertEquals(1, it.onlineSearchHistory().size)
        }
    }
}
