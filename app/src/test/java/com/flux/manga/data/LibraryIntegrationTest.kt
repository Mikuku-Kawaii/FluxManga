package com.flux.manga.data

import com.flux.manga.ui.pages.*
import com.flux.manga.ui.navigation.PrototypeWorld
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException

class LibraryIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val world = PrototypeWorld.SFW
    private fun book(source: String = "cbz", online: Boolean = false, id: String = "same") =
        ShelfBook(MangaKey(world, source, id), "Title", false, true, 4, 2, "chapter", 100L, online)
    private class Rows {
        val tags = mutableMapOf("one" to UserTag("one", "One", "one"), "two" to UserTag("two", "Two", "two"))
        val local = mutableSetOf("cbz" to "same")
        val online = mutableSetOf("account-a" to "same", "account-b" to "same")
        val links = mutableSetOf<TagBook>()
        var searches = 3; var visits = 2
        val writes = mutableListOf<String>()
        val dao = Proxy.newProxyInstance(WorldDao::class.java.classLoader, arrayOf(WorldDao::class.java)) { _, method, args ->
            val a = args.orEmpty()
            when (method.name) {
                "tag" -> tags[a[0]]
                "localBook" -> if (a[0] to a[1] in local) LocalBook(mangaId = a[1] as String, title = "T", archiveName = "owned.cbz", sha256 = "hash", archiveBytes = 20, importedAt = 1) else null
                "onlineBook" -> if (a[0] to a[1] in online) OnlineBook(a[0] as String, a[1] as String, "T", true, 1) else null
                "onlineProgress" -> null
                "linkTag" -> { writes += "link"; links.add(a[0] as TagBook); null }
                "unlinkTag" -> { writes += "unlink"; links.remove(TagBook(a[0] as String, a[1] as String, a[2] as String, a[3] as String)); null }
                "searchHistoryCount" -> searches
                "visitCount" -> visits
                "clearSearchHistory" -> { writes += "search"; searches = 0; null }
                "clearVisits" -> { writes += "visits"; visits = 0; null }
                else -> error("Unexpected DAO operation: ${method.name}")
            }
        } as WorldDao
    }
    @Test fun groupFilterSeparatesWorldKindAndAccountAndIncludesUnfavorited() {
        val books = listOf(book(), book("account-a", true), book("account-b", true), book("cbz", true), book().copy(key = MangaKey(PrototypeWorld.NSFW, "cbz", "same")))
        val snapshot = TagSnapshot(listOf(UserTag("one", "Old user label", "old")), listOf(TagBook("one", "local", "cbz", "same"), TagBook("one", "online", "account-b", "same")))
        assertEquals(listOf(books[0], books[2]), groupedShelfBooks(books, snapshot, "one", world))
        assertEquals(listOf(books[1], books[3]), groupedShelfBooks(books, snapshot, UNGROUPED, world))
        assertEquals(books.take(4), groupedShelfBooks(books, snapshot, null, world))
        assertTrue(groupedShelfBooks(books, snapshot, "deleted", world).isEmpty())
    }
    @Test fun deletedGroupsAndDanglingLinksDoNotHideUngroupedBooks() {
        val books = listOf(book())
        val snapshot = TagSnapshot(emptyList(), listOf(TagBook("old", "local", "cbz", "same")))
        assertEquals(books, groupedShelfBooks(books, snapshot, UNGROUPED, world))
        assertTrue(groupedShelfBooks(books, snapshot, "old", world).isEmpty())
    }
    @Test fun groupFilterComposesWithFavoriteHistoryAndDownloadFilters() {
        val b = book("account-a", true).copy(favorite = true, downloadedChapters = 2)
        val snapshot = TagSnapshot(listOf(UserTag("one", "One", "one")), listOf(TagBook("one", "online", "account-a", "same")))
        val result = groupedShelfBooks(listOf(book(), b), snapshot, "one", world)
        assertEquals(listOf(b), shelfResults(filterShelfDownloads(result, ShelfDownloadFilter.SAVED), true, true, ShelfFilter.READING, ShelfSort.RECENT))
    }
    @Test fun bulkAssignmentIsIdempotentAndRemovingOneGroupPreservesOthers() {
        val rows = Rows(); val books = listOf(book(), book("account-a", true)).map { GroupBookKey(it.key, it.online) }
        applyGroupMembership(rows.dao, "two", books, true)
        applyGroupMembership(rows.dao, "one", books + books, true)
        applyGroupMembership(rows.dao, "one", books, true)
        assertEquals(4, rows.links.size)
        applyGroupMembership(rows.dao, "one", books, false)
        assertEquals(setOf("two"), rows.links.map { it.tagId }.toSet())
        assertEquals(1, rows.local.size); assertEquals(2, rows.online.size)
    }
    @Test fun missingBookFailsBeforeAnyBulkAssociationWrite() {
        val rows = Rows()
        assertThrows(IllegalArgumentException::class.java) { applyGroupMembership(rows.dao, "one",
            listOf(GroupBookKey(book().key, false), GroupBookKey(book(id = "removed").key, false)), true) }
        assertTrue(rows.writes.isEmpty()); assertTrue(rows.links.isEmpty())
    }
    @Test fun deletedGroupAndForeignWorldCannotBeAssigned() {
        val rows = Rows()
        assertThrows(IllegalArgumentException::class.java) { applyGroupMembership(rows.dao, "deleted", listOf(GroupBookKey(book().key, false)), true) }
        assertThrows(IllegalArgumentException::class.java) { applyGroupMembership(rows.dao, "one", listOf(GroupBookKey(book().key.copy(world = PrototypeWorld.NSFW), false)), true) }
        assertTrue(rows.writes.isEmpty())
    }
    @Test fun cleanupRecordsAreIndependentAndNeverWriteBooksProgressTagsOrCredentials() {
        val rows = Rows()
        assertEquals(3 to 0, clearSpaceRecords(rows.dao, CleanupChoice(searches = true)))
        assertEquals(0, rows.searches); assertEquals(2, rows.visits); assertEquals(listOf("search"), rows.writes)
        assertEquals(0 to 2, clearSpaceRecords(rows.dao, CleanupChoice(visits = true)))
        assertEquals(listOf("search", "visits"), rows.writes)
        assertEquals(0 to 0, clearSpaceRecords(rows.dao, CleanupChoice()))
    }
    @Test fun cleanupSelectionStartsEmptyAndCachesDoNotClearRecords() {
        assertFalse(CleanupChoice().any); assertTrue(CleanupChoice(covers = true).any)
        val rows = Rows(); assertEquals(0 to 0, clearSpaceRecords(rows.dao, CleanupChoice(covers = true)))
        assertTrue(rows.writes.isEmpty())
    }
    @Test fun generatedCacheOnlyDeletesMatchingDirectFilesAndPreservesOriginals() {
        val root = temp.newFolder("space"); val owned = File(root, "cover.png").apply { writeBytes(ByteArray(7)) }
        val original = File(root, "original.cbz").apply { writeText("original") }
        val nested = File(root, "nested").apply { mkdir() }; File(nested, "cover.png").writeText("nested")
        val other = temp.newFolder("other-space"); File(other, "cover.png").writeText("other")
        val cache = OwnedCacheFiles(root, Regex("cover\\.png"))
        assertEquals(CacheUsage(1, 7), cache.usage())
        assertEquals(CacheCleared(1, 7, 0), cache.clear(active = {}))
        assertFalse(owned.exists()); assertTrue(original.exists()); assertTrue(File(nested, "cover.png").exists()); assertTrue(File(other, "cover.png").exists())
        assertEquals(CacheCleared(0, 0, 0), cache.clear(active = {}))
    }
    @Test fun cacheDeletionFailureReportsRemainingAndCanRetryWithoutDoubleCounting() {
        val root = temp.newFolder("failure"); File(root, "a.png").writeText("aa"); File(root, "b.png").writeText("bbb")
        val cache = OwnedCacheFiles(root, Regex("[ab]\\.png"))
        assertEquals(CacheCleared(1, 2, 1), cache.clear({}, { if (it.name == "b.png") false else it.delete() }))
        assertEquals(CacheUsage(1, 3), cache.usage()); assertEquals(CacheCleared(1, 3, 0), cache.clear(active = {}))
    }
    @Test fun expiredSessionCannotStartCacheDeletion() {
        val root = temp.newFolder("expired"); val file = File(root, "a.png").apply { writeText("a") }
        assertThrows(CancellationException::class.java) { OwnedCacheFiles(root, Regex("a\\.png")).clear(active = { throw CancellationException() }) }
        assertTrue(file.exists())
    }
    @Test fun missingCacheDirectoryDoesNotCreateOrDeleteAnything() {
        val root = File(temp.root, "missing")
        assertEquals(CacheUsage(), OwnedCacheFiles(root, Regex(".*")).usage())
        assertFalse(root.exists())
    }
}
