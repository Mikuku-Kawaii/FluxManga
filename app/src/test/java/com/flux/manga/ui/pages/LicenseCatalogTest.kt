package com.flux.manga.ui.pages

import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class LicenseCatalogTest {
    private val assets = File(requireNotNull(System.getProperty("flux.licenseAssetsDir")))
    private fun reader() = LicenseCatalogReader { assets.resolve(it).inputStream() }

    @Test fun generatedCatalogContainsEverySnapshotComponentAndProjectMaterials() {
        val catalog = reader().catalog()
        assertEquals(302, catalog.entries.size)
        assertEquals(113, catalog.entries.count { it.group == LicenseGroup.ANDROID })
        assertEquals(181, catalog.entries.count { it.group == LicenseGroup.CARGO })
        assertEquals(7, catalog.entries.count { it.group == LicenseGroup.ADAPTATION })
        assertTrue(catalog.entries.single { it.id == "fluxmanga" }.documents.any { it.name == "LICENSE" })
        assertTrue(catalog.entries.single { it.id == "source:baozi-account" }.license.contains("GPL"))
        assertTrue(catalog.entries.single { it.id == "source:baozi-anonymous" }.license.contains("Apache"))
        assertTrue(catalog.entries.single { it.id == "source:jm-image-transform" }.license.contains("MIT"))
    }

    @Test fun everyBundledDocumentCanBeReadAndPassesItsRecordedHash() {
        val reader = reader()
        reader.catalog().entries.forEach { entry -> entry.documents.forEach { doc ->
            assertTrue("${entry.id}/${doc.name}", reader.text(entry.id, doc.sha256).isNotBlank())
        } }
    }

    @Test fun searchKeepsScopeAndMatchesNamesLicenseAndCaseWithoutChangingCatalog() {
        val catalog = reader().catalog()
        assertEquals(listOf("fluxmanga"), catalog.filter(" FLUXMANGA ", LicenseGroup.PROJECT).map { it.id })
        assertTrue(catalog.filter("apache", LicenseGroup.CARGO).all { it.group == LicenseGroup.CARGO })
        assertTrue(catalog.filter("apache", LicenseGroup.CARGO).isNotEmpty())
        assertTrue(catalog.filter("no-such-component-xyz", null).isEmpty())
        assertEquals(302, catalog.filter("", null).size)
    }

    @Test fun staleEntryAndDocumentIdentityCannotReadArbitraryFiles() {
        assertThrows(NoSuchElementException::class.java) { reader().text("missing", "a".repeat(64)) }
        assertThrows(NoSuchElementException::class.java) { reader().text("fluxmanga", "../../private") }
    }

    @Test fun textMismatchIsFailureInsteadOfDisplayingDifferentMaterial() {
        val broken = LicenseCatalogReader { name ->
            if (name.endsWith(".properties")) assets.resolve(name).inputStream()
            else ByteArrayInputStream("wrong text".toByteArray())
        }
        val entry = broken.catalog().entries.first()
        assertThrows(IllegalStateException::class.java) { broken.text(entry.id, entry.documents.first().sha256) }
    }

    private fun modified(change: (Properties) -> Unit): LicenseCatalogReader {
        val props = Properties().apply { load(StringReader(assets.resolve("open_source/catalog.properties").readText())) }
        change(props)
        val text = StringWriter().also { props.store(it, null) }.toString()
        return LicenseCatalogReader { ByteArrayInputStream(text.toByteArray()) }
    }

    @Test fun duplicateIdentityAndEscapingDocumentPathAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { modified { it.setProperty("entry.1.id", "fluxmanga") }.catalog() }
        assertThrows(IllegalArgumentException::class.java) { modified { it.setProperty("entry.0.doc.0.path", "../private") }.catalog() }
    }

    @Test fun missingOrUnsupportedCatalogIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { modified { it.remove("entry.0.license") }.catalog() }
        assertThrows(IllegalArgumentException::class.java) { modified { it.setProperty("format", "999") }.catalog() }
    }
}
