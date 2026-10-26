package com.flux.manga.data

import org.junit.Assert.*
import org.junit.Test

class JmImageRulesTest {
    @Test fun fixedUpstreamVectorsCoverThresholdsAndHexCharacterRule() {
        val vectors = requireNotNull(javaClass.getResourceAsStream("/jm-strip-counts.tsv"))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") || it.isBlank() }
        assertEquals(32, vectors.size)
        vectors.forEach { line ->
            val (scramble, chapter, stem, expected) = line.split('\t')
            assertEquals(line, expected.toInt(), JmImageRules.strips(scramble.toLong(), chapter.toLong(), stem))
        }
    }
    @Test fun metadataThresholdIsNotHardcodedAndOldChaptersDoNotNeedHashRule() {
        assertEquals(0, JmImageRules.strips(600000, 500000, "00001"))
        assertEquals(10, JmImageRules.strips(0, 268849, "00001"))
    }
    @Test fun invalidMetadataIsRejectedBeforePlanning() {
        listOf("", " ", "../page", "page\\1", "a\n1", "a".repeat(257)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { JmImageRules.strips(0, 500000, name) }
        }
        assertThrows(IllegalArgumentException::class.java) { JmImageRules.strips(-1, 500000, "00001") }
        assertThrows(IllegalArgumentException::class.java) { JmImageRules.strips(0, -1, "00001") }
    }
}
