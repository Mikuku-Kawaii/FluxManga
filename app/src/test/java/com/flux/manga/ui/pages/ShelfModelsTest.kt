package com.flux.manga.ui.pages

import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import org.junit.Assert.*
import org.junit.Test

class ShelfModelsTest {
    @Test fun recentSortMixesUnreadAndReadTimestampsWithoutBoxedTypeMismatch() {
        val unread = book("unread")
        val read = book("read", 2, time = 42L)
        for (rows in listOf(listOf(unread, read), listOf(read, unread))) {
            assertEquals(listOf(read, unread), shelfResults(rows, false, false, ShelfFilter.ALL, ShelfSort.RECENT))
        }
        assertEquals(read, latestReadable(listOf(read.copy(readAt = null), read)))
    }
    private fun book(id: String, ordinal: Int? = null, count: Int? = 24, favorite: Boolean = true,
                     available: Boolean = true, time: Long = 1, online: Boolean = false) =
        ShelfBook(MangaKey(PrototypeWorld.SFW, if (online) "source" else "cbz", id), id, favorite,
            available, count, ordinal, ordinal?.let { "chapter" }, ordinal?.let { time }, online)

    @Test fun localStatusAndPercentageUseActualPageCount() {
        assertEquals(ShelfFilter.UNREAD, book("new").status)
        assertEquals(ShelfFilter.READING, book("reading", 8).status)
        assertEquals(8f / 24, book("reading", 8).ratio!!, .0001f)
        assertEquals(ShelfFilter.READ, book("last", 24).status)
        assertEquals(1f, book("last", 24).ratio!!, 0f)
        assertNull(book("new").ratio)
    }
    @Test fun unknownOnlineTotalNeverImpliesCompletion() {
        val remote = book("online", 999, count = null, online = true)
        assertEquals(ShelfFilter.READING, remote.status)
        assertNull(remote.ratio)
        assertTrue(remote.positionText.contains("chapter"))
        assertTrue(remote.validProgress)
    }
    @Test fun continuationSkipsMissingCopiesInvalidPositionsAndUnconfiguredSources() {
        val eligible = book("available", 3, time = 5)
        val unavailable = book("missing", 4, available = false, time = 100)
        val invalid = book("changed", 40, time = 101)
        val oldSource = book("remote", 8, available = false, time = 102, online = true)
        assertEquals(eligible, latestReadable(listOf(eligible, unavailable, invalid, oldSource, book("unread"))))
        assertNull(latestReadable(listOf(unavailable, invalid)))
        assertNull(invalid.ratio)
    }
    @Test fun continuationIsIndependentOfFavoritesAndPrefersNewestAcrossSources() {
        val local = book("local", 5, time = 10)
        val remote = book("remote", 2, count = null, favorite = false, time = 11, online = true)
        assertEquals(remote, latestReadable(listOf(local, remote)))
    }
    @Test fun filterOnlyChangesResultsNotFavoriteTotalOrUnfavoritedHistory() {
        val rows = listOf(book("new"), book("reading", 2), book("done", 24), book("not favorite", 3, favorite = false))
        assertEquals(3, rows.count { it.favorite })
        assertEquals(listOf("reading"), shelfResults(rows, true, false, ShelfFilter.READING, ShelfSort.RECENT).map { it.title })
        assertEquals(3, shelfResults(rows, false, true, ShelfFilter.ALL, ShelfSort.RECENT).size)
        assertEquals(1, shelfResults(rows, false, false, ShelfFilter.ALL, ShelfSort.TITLE, "NOT FAVORITE").size)
        assertEquals(4, rows.size)
    }
    @Test fun stableIdentitySeparatesWorldSourceAndLocalOnlineKinds() {
        val original = book("b:c")
        val variants = listOf(original, original.copy(key = original.key.copy(world = PrototypeWorld.NSFW)),
            original.copy(online = true), original.copy(key = MangaKey(PrototypeWorld.SFW, "cbz:b", "c")))
        assertEquals(4, variants.map { it.stableKey }.toSet().size)
    }
    @Test fun columnsAdaptInsteadOfShrinkingFontAndListAlwaysUsesOneColumn() {
        assertEquals(3, shelfColumns(392f, 1f, ShelfLayout.GRID))
        assertEquals(2, shelfColumns(320f, 1f, ShelfLayout.GRID))
        assertEquals(1, shelfColumns(392f, 2f, ShelfLayout.GRID))
        assertEquals(1, shelfColumns(800f, 1f, ShelfLayout.LIST))
    }
    @Test fun viewPreferencesAreNamespacedPerWorld() {
        assertNotEquals(shelfPreferenceKey(PrototypeWorld.SFW, "layout"), shelfPreferenceKey(PrototypeWorld.NSFW, "layout"))
        assertNotEquals(shelfPreferenceKey(PrototypeWorld.SFW, "layout"), shelfPreferenceKey(PrototypeWorld.SFW, "sort"))
    }
    @Test fun importSortUsesImportTimeNotReadingTimeAndKeepsAllUnfavoritedBooks() {
        val rows = (1..8).map { book("import-$it", favorite = false, time = (9 - it).toLong()).copy(importedAt = it.toLong()) }
        val online = book("online", 3, online = true, time = 1000).copy(importedAt = 1000)
        val result = shelfResults(listOf(online) + rows, false, false, ShelfFilter.ALL, ShelfSort.IMPORTED)
        assertEquals(rows.reversed() + online, result)
        assertEquals(9, result.size) // No former six-item home cap; new/unread/unfavorited books remain reachable.
        assertEquals(online, latestReadable(listOf(online) + rows))
    }
    @Test fun unknownImportDatesStayUnknownAndCardMappingPreservesTheDatabaseTimestamp() {
        val dated = book("dated").copy(importedAt = 12)
        val unknown = book("unknown", time = Long.MAX_VALUE)
        assertEquals(listOf(dated, unknown),
            shelfResults(listOf(unknown, dated), false, false, ShelfFilter.ALL, ShelfSort.IMPORTED))
        val card = com.flux.manga.data.MangaCard(dated.key, "import", 2, true, importedAt = 12)
        assertEquals(12L, card.shelfBook().importedAt)
        assertNull(card.copy(importedAt = null).shelfBook().importedAt)
    }
    @Test fun sourceSearchKeepsSameNamedWorksSeparateAndDoesNotFallBackWhenSourceDisappears() {
        val local = book("same")
        val online = book("same", online = true)
        val other = online.copy(key = online.key.copy(sourceId = "other-server"))
        val sameSourceOtherKind = local.copy(online = true)
        val rows = listOf(local, online, other, sameSourceOtherKind)
        assertEquals(rows, sourceBooks(rows, null))
        assertEquals(listOf(local), sourceBooks(rows, local.sourceFilterKey))
        assertEquals(listOf(online), sourceBooks(rows, online.sourceFilterKey))
        assertTrue(sourceBooks(rows, "online:removed").isEmpty())
    }
    @Test fun historyGroupsUseLocalCalendarDayAndDoNotInventDates() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val before = book("a", 1, time = java.time.Instant.parse("2026-10-19T15:59:59Z").toEpochMilli())
        val after = book("b", 1, time = java.time.Instant.parse("2026-10-19T16:00:00Z").toEpochMilli())
        assertEquals("2026-10-19", readingDay(before, zone))
        assertEquals("2026-10-20", readingDay(after, zone))
        assertEquals("日期未知", readingDay(book("new"), zone))
    }
}
