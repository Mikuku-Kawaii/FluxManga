package com.flux.manga.ui.pages

import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import org.junit.Assert.*
import org.junit.Test

class UserTagsTest {
    @Test fun namesTrimAndCaseFoldWithoutChangingDisplayOrSourceIdentities() {
        assertEquals("Sci-Fi", tagName("  Sci-Fi "))
        assertEquals(tagNameKey(" SCI-FI "), tagNameKey("sci-fi"))
        assertEquals("奇幻", tagName("奇幻"))
        assertEquals("😀".repeat(40), tagName("😀".repeat(40)))
    }
    @Test fun invalidNamesDoNotBecomePersistedEmptyOrMultilineLabels() {
        listOf(" ", "\n", "a\nb", "a\u0000b", "字".repeat(41)).forEach {
            assertThrows(IllegalArgumentException::class.java) { tagName(it) }
        }
    }
    private fun book(source: String, online: Boolean, favorite: Boolean = false) =
        ShelfBook(MangaKey(PrototypeWorld.SFW, source, "same"), "Same title", favorite, true, 10, null, null, null, online)

    @Test fun associationsNeverMergeSameIdsAcrossKindsSourcesOrWorlds() {
        val local = book("cbz", false)
        val sameSourceOtherKind = book("cbz", true)
        val remoteA = book("server-a", true)
        val remoteB = book("server-b", true)
        val privateCopy = local.copy(key = local.key.copy(world = PrototypeWorld.NSFW))
        val rows = listOf(local, sameSourceOtherKind, remoteA, remoteB, privateCopy)
        val links = listOf(TagBook("one", "local", "cbz", "same"), TagBook("one", "online", "server-b", "same"),
            TagBook("other", "online", "server-a", "same"))
        assertEquals(listOf(local, remoteB), taggedShelfBooks(rows, links, "one", PrototypeWorld.SFW))
        assertTrue(taggedShelfBooks(rows, links, "deleted", PrototypeWorld.SFW).isEmpty())
    }
    @Test fun tagResultsIncludeUnfavoritedBooksAndClearQueryRetainsTagBoundary() {
        val unread = book("cbz", false)
        val reading = book("server-a", true, true).copy(ordinal = 3, chapterId = "chapter", readAt = 1)
        val links = listOf(TagBook("one", "local", "cbz", "same"), TagBook("two", "online", "server-a", "same"))
        val tagged = taggedShelfBooks(listOf(unread, reading), links, "one", PrototypeWorld.SFW)
        assertTrue(shelfResults(tagged, false, false, ShelfFilter.ALL, ShelfSort.TITLE, "missing").isEmpty())
        assertEquals(listOf(unread), shelfResults(tagged, false, false, ShelfFilter.ALL, ShelfSort.TITLE, ""))
        assertTrue(shelfResults(tagged, false, false, ShelfFilter.READING, ShelfSort.TITLE).isEmpty())
    }
    @Test fun searchComposesSourceTagFavoriteAndReadingFilters() {
        val local = book("cbz", false)
        val online = book("server", true, true).copy(ordinal = 3, chapterId = "chapter", readAt = 1)
        val links = listOf(TagBook("one", "local", "cbz", "same"), TagBook("one", "online", "server", "same"))
        val source = sourceBooks(listOf(local, online), online.sourceFilterKey)
        val tagged = taggedShelfBooks(source, links, "one", PrototypeWorld.SFW)
        assertEquals(listOf(online), shelfResults(tagged, true, false, ShelfFilter.READING, ShelfSort.TITLE, "same"))
        assertTrue(shelfResults(tagged, true, false, ShelfFilter.UNREAD, ShelfSort.TITLE, "same").isEmpty())
    }
}
