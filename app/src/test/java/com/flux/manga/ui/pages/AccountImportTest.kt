package com.flux.manga.ui.pages

import org.junit.Assert.*
import org.junit.Test

class AccountImportTest {
    private data class Entry(val id: String, val title: String)
    private val entries = listOf(Entry("volume-1", "Same Title"), Entry("other", "另一作品"), Entry("volume-2", "Same Title"))

    @Test fun filteringPreservesDistinctVolumesWithIdenticalTitlesAndOrder() {
        assertEquals(listOf("volume-1", "volume-2"), filterImportEntries(entries, "  SAME  ") { it.title }.map { it.id })
    }
    @Test fun emptyQueryKeepsFullPreviewAndMissingTitleDoesNotInventResults() {
        assertSame(entries, filterImportEntries(entries, "  ") { it.title })
        assertTrue(filterImportEntries(entries, "not present") { it.title }.isEmpty())
        assertEquals(listOf(entries[1]), filterImportEntries(entries, "作品") { it.title })
    }
    @Test fun selectingFilteredEntriesPreservesHiddenSelectionAndDeduplicatesIds() {
        assertEquals(listOf("other", "volume-1", "volume-2"),
            addImportSelection(listOf("other", "volume-1"), listOf("volume-1", "volume-2")))
        assertEquals(listOf("other"), addImportSelection(listOf("other"), emptyList()))
    }
    @Test fun hiddenSelectionCountsIdentitiesAndClearingQueryRevealsAll() {
        val selected = listOf("volume-1", "other", "other")
        assertEquals(1, hiddenImportSelection(selected, listOf("volume-1", "volume-2")))
        assertEquals(0, hiddenImportSelection(selected, entries.map { it.id }))
        assertEquals(2, hiddenImportSelection(selected, emptyList()))
    }
    @Test fun unknownOrInvalidTotalsNeverBecomePercentages() {
        assertNull(importFraction(12, null))
        assertNull(importFraction(0, 0))
        assertNull(importFraction(0, -1))
        assertNull(importFraction(-1, 10))
        assertNull(importFraction(11, 10))
    }
    @Test fun knownSnapshotProgressUsesProcessedCountIncludingAlreadyExistingItems() {
        assertEquals(0f, importFraction(0, 4)!!, 0f)
        assertEquals(.75f, importFraction(3, 4)!!, 0f)
        assertEquals(1f, importFraction(4, 4)!!, 0f)
    }
    @Test fun unknownAndSavedUnverifiedAccountsAreNotPresentedAsLoggedOutOrVerified() {
        assertEquals("正在读取账号", accountStatus(false, false, false, true))
        assertEquals("账号状态待核对", accountStatus(false, true, true, false))
        assertEquals("已保存账号，需验证当前会话", accountStatus(true, false, true, false))
        assertEquals("账号已验证", accountStatus(true, true, true, false))
        assertEquals("未登录", accountStatus(true, false, false, false))
    }
}
