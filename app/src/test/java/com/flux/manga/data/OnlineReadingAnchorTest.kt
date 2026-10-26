package com.flux.manga.data

import com.flux.manga.core.*
import org.junit.Assert.*
import org.junit.Test

class OnlineReadingAnchorTest {
    private val context = SourceContext(SourceWorld.SFW, CopyMangaEndpoint.SOURCE_ID, "fixture", 7uL, null)
    private val key = SourceMangaKey(SourceWorld.SFW, context.sourceId, "work")
    private val pages = listOf("first", "second").mapIndexed { index, id -> SourcePageResource(key, "ch", "ch:uuid:$id", (index + 1).toUInt(), "copymanga-page:ch:uuid:$id", null, null, null, context) }
    private val progress = OnlineReadingProgress(key.sourceId, key.mangaId, "ch", "ch:uuid:second", 2, .42f, "STRIP", 123L, true)
    @Test fun reopenRestoresStablePageAfterOrderChangesWithoutMutatingStoredProgress() {
        assertEquals(1 to .42f, onlineReadingAnchor(pages, progress))
        assertEquals(0 to .42f, onlineReadingAnchor(pages.reversed(), progress))
        assertEquals("STRIP", progress.mode); assertTrue(progress.modeExplicit); assertEquals(2, progress.ordinal)
    }
    @Test fun foreignIdentityAndMissingPagesNeverReuseOldFraction() {
        for (old in listOf(progress.copy(sourceId = "komga-fixture"), progress.copy(mangaId = "other"),
            progress.copy(chapterId = "other"), progress.copy(pageId = "missing"))) {
            assertEquals(0 to 0f, onlineReadingAnchor(pages, old))
        }
        assertEquals(0 to 0f, onlineReadingAnchor(pages, null))
        assertEquals(1 to 0f, onlineReadingAnchor(pages, progress.copy(fraction = Float.NaN)))
    }
}
