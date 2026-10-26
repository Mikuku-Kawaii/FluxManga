package com.flux.manga.ui.pages

import com.flux.manga.core.*
import org.junit.Assert.*
import org.junit.Test

class OnlineReaderGeometryTest {
    private val context = SourceContext(SourceWorld.SFW, "komga-a", "test", 1uL, "auth")
    private fun page(width: UInt?, height: UInt?) = SourcePageResource(
        SourceMangaKey(SourceWorld.SFW, "komga-a", "book"), "chapter", "1", 1u,
        "https://example.test/image", "image/png", width, height, context)

    @Test fun longImageTilesHaveNoGapOrOverlap_andRelativeAnchorSurvivesViewportChange() {
        val page = page(600u, 12001u)
        val tiles = onlineTiles(listOf(page))
        assertEquals(0, tiles.first().top)
        assertEquals(12001, tiles.last().bottom)
        tiles.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top) }
        assertEquals(6000, onlineTileTop(page, .51f))
        assertEquals(10800, onlineTileTop(page, .99999f))
        assertEquals(tiles.size, tiles.map { it.key }.distinct().size)
    }
    @Test fun missingDimensionsUseOneResolvablePlaceholder_andWideImageStepStaysBounded() {
        assertNull(onlineTiles(listOf(page(null, null))).single().bottom)
        assertNull(onlineTiles(listOf(page(0u, 12000u))).single().bottom)
        assertEquals(2048, onlineTileStep(Int.MAX_VALUE))
        assertEquals(1024, onlineTileStep(1))
    }
}
