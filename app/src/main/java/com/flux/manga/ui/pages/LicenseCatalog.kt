package com.flux.manga.ui.pages

import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

internal enum class LicenseGroup(val key: String, val label: String) {
    PROJECT("project", "项目"), ADAPTATION("adaptation", "协议与适配"),
    ANDROID("android", "Android 依赖"), CARGO("cargo", "Rust 锁定依赖")
}
internal data class LicenseDocumentInfo(val name: String, val path: String, val sha256: String)
internal data class LicenseEntry(val id: String, val name: String, val group: LicenseGroup,
    val license: String, val project: String, val documents: List<LicenseDocumentInfo>)
internal data class LicenseCatalog(val snapshot: String, val entries: List<LicenseEntry>) {
    fun filter(query: String, group: LicenseGroup?): List<LicenseEntry> {
        val term = query.trim()
        return entries.filter { (group == null || it.group == group) &&
            (term.isEmpty() || it.name.contains(term, true) || it.license.contains(term, true) || it.id.contains(term, true)) }
    }
}

/** Only bundled public material. Routes select IDs from this index, never arbitrary file paths. */
internal class LicenseCatalogReader(private val open: (String) -> InputStream) {
    fun catalog(): LicenseCatalog = open("open_source/catalog.properties").bufferedReader(Charsets.UTF_8).use { reader ->
        val props = Properties().apply { load(reader) }
        fun field(key: String) = requireNotNull(props.getProperty(key)) { "Missing catalog field" }
        require(field("format") == "1")
        val entries = List(field("count").toInt()) { index ->
            val prefix = "entry.$index"
            LicenseEntry(field("$prefix.id"), field("$prefix.name"),
                LicenseGroup.entries.single { it.key == field("$prefix.group") },
                field("$prefix.license"), field("$prefix.project"),
                List(field("$prefix.count").toInt()) { doc ->
                    val hash = field("$prefix.doc.$doc.sha256")
                    require(hash.matches(Regex("[0-9a-f]{64}")))
                    val path = field("$prefix.doc.$doc.path")
                    require(path == "open_source/texts/$hash.txt")
                    LicenseDocumentInfo(field("$prefix.doc.$doc.name"), path, hash)
                }.also { require(it.isNotEmpty()) })
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        LicenseCatalog(field("snapshot"), entries)
    }

    fun text(entryId: String, documentHash: String): String {
        val document = catalog().entries.single { it.id == entryId }.documents.first { it.sha256 == documentHash }
        val bytes = open(document.path).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == document.sha256) { "License text mismatch" }
        return bytes.toString(Charsets.UTF_8)
    }
}
