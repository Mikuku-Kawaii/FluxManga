package com.flux.manga.ui.pages

import com.flux.manga.data.CopyMangaEndpoint
import com.flux.manga.ui.navigation.DiscoverySection
import org.junit.Assert.*
import org.junit.Test

class DiscoverySourcesTest {
    @Test fun copyPreviewDoesNotDependOnKomgaConfigurationButPrivateWorldHasNoSource() {
        for (status in listOf(DiscoveryStatus.LOADING, DiscoveryStatus.UNCONFIGURED, DiscoveryStatus.FAILED)) {
            val state = DiscoveryState(status)
            assertEquals(CopyMangaEndpoint.SOURCE_ID, DiscoverySource.COPY.id(state))
            assertNull(DiscoverySource.KOMGA.id(state))
            assertEquals(com.flux.manga.data.BaoziEndpoint.SOURCE_ID, DiscoverySource.BAOZI.id(state))
        }
        for (source in DiscoverySource.entries) assertNull(source.id(DiscoveryState(DiscoveryStatus.PRIVATE, "komga-old")))
    }
    @Test fun configuredInstanceIsResolvedWithoutReusingOldSourceIdentity() {
        val selection = DiscoverySource.KOMGA
        assertEquals("komga-old", selection.id(DiscoveryState(DiscoveryStatus.READY, "komga-old")))
        assertNull(selection.id(DiscoveryState(DiscoveryStatus.FAILED, "komga-old")))
        assertEquals("komga-new", selection.id(DiscoveryState(DiscoveryStatus.READY, "komga-new")))
        assertEquals("拷贝漫画", discoverySourceName(CopyMangaEndpoint.SOURCE_ID))
        assertEquals("图源", discoverySourceName("removed-source"))
    }
    @Test fun defaultFeedsUseSupportedCapabilitiesAndNeverInventUpdatesOrRecommendations() {
        for (section in listOf(DiscoverySection.RECOMMENDATIONS, DiscoverySection.UPDATES)) {
            val chosen = DiscoverySource.defaultFor(section)
            assertTrue(chosen.supports(section))
            if (section == DiscoverySection.RECOMMENDATIONS) {
                assertTrue(DiscoverySource.BAOZI.supports(section)); assertFalse(DiscoverySource.KOMGA.supports(section))
            } else { assertTrue(DiscoverySource.BAOZI.supports(section)); assertTrue(DiscoverySource.COPY.supports(section)) }
        }
        assertEquals(DiscoverySource.KOMGA, DiscoverySource.defaultFor(null))
        assertTrue(DiscoverySource.entries.none { it.supports(DiscoverySection.VISITS) })
    }
}
