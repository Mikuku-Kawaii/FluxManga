package com.flux.manga.ui.pages

import com.flux.manga.core.*
import org.junit.Assert.*
import org.junit.Test

class WorkMetadataTest {
    @Test fun creatorsKeepActualRoleWithoutInferringOne() {
        assertEquals("Name", authorLabel(SourceAuthor("Name", null)))
        assertEquals("Name（writer）", authorLabel(SourceAuthor("Name", "writer")))
        assertEquals("Name（custom-role）", authorLabel(SourceAuthor("Name", "custom-role")))
    }
    @Test fun metadataTimestampNeverClaimsNewChapterUpdate() {
        val value = SourceWorkMetadata(emptyList(), null, "2026-10-01T12:30:00", SourceUpdateKind.METADATA, false)
        assertEquals("资料更新时间：2026-10-01T12:30:00", metadataUpdateLabel(value))
        assertEquals("更新时间：2026-10-01T12:30:00", metadataUpdateLabel(value.copy(updateKind = SourceUpdateKind.CONTENT)))
        assertNull(metadataUpdateLabel(value.copy(updateKind = null)))
        assertNull(metadataUpdateLabel(value.copy(updatedAt = null)))
    }
}
