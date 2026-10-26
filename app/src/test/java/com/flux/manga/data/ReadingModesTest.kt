package com.flux.manga.data

import org.junit.Assert.*
import org.junit.Test

class ReadingModesTest {
    @Test fun legacyAndExplicitChoicesWinOverEveryDefault() {
        ReadingMode.entries.forEach { stored -> ReadingMode.entries.forEach { default ->
            assertEquals(stored, resolveReadingMode(stored.name, true, default))
        } }
        assertTrue(ReadingProgress("cbz", "old", "c", "p", 2, .4f, "RTL", 1).modeExplicit)
        assertTrue(OnlineReadingProgress("source", "old", "c", "p", 2, .4f, "STRIP", 1).modeExplicit)
    }
    @Test fun newOrInheritedProgressUsesTheCurrentSpaceDefault() {
        ReadingMode.entries.forEach { default ->
            assertEquals(default, resolveReadingMode(null, false, default))
            assertEquals(default, resolveReadingMode("RTL", false, default))
        }
    }
    @Test fun unknownStoredModesHaveDefinedFallbackWithoutOverwritingThem() {
        assertNull(decodeReadingMode("future-mode"))
        assertEquals(ReadingMode.VERTICAL, resolveReadingMode("future-mode", true, ReadingMode.VERTICAL))
        assertEquals(ReadingMode.STRIP, decodeReadingMode("STRIP"))
    }
    @Test fun pagedRecreationAndModeChangesKeepAnchorUntilActualPageMove() {
        val fraction = .625f
        assertEquals(fraction, pagedFraction(4, 4, fraction))
        assertEquals(0f, pagedFraction(4, 5, fraction))
        assertEquals(0f, pagedFraction(4, 3, fraction))
    }
}
