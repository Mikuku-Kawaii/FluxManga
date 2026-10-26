package com.flux.manga.data

import org.junit.Assert.*
import org.junit.Test

class BrowsingHistoryTest {
    @Test fun onlySubmittedNonEmptyQueriesProduceScopedRows() {
        assertNull(searchHistoryEntry("server", "  ", 1))
        assertNull(searchHistoryEntry("", "query", 1))
        assertEquals(SearchHistory("online:server", "中文 query", 42), searchHistoryEntry("server", "  中文 query  ", 42))
        assertEquals(200, searchHistoryEntry("server", "a".repeat(300), 1)!!.query.length)
    }
    @Test fun formalScopesDoNotInterpretLegacySampleKeys() {
        assertNull(historySource("sample")); assertNull(historySource("online:"))
        assertEquals("source:with:parts", historySource(onlineHistoryScope("source:with:parts")))
        assertNotEquals(onlineHistoryScope("a"), onlineHistoryScope("b"))
    }
    @Test fun visitIdentityIgnoresMetadataButDistinguishesKindAndDelimiterCollisions() {
        val row = BrowseVisit("local", "a:b", "c", "Title", 1)
        assertEquals(row.stableKey, row.copy(title = "Updated", visitedAt = 999).stableKey)
        assertNotEquals(row.stableKey, row.copy(kind = "online").stableKey)
        assertNotEquals(row.stableKey, row.copy(sourceId = "a", mangaId = "b:c").stableKey)
    }
}
