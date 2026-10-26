package com.flux.manga.data

import com.flux.manga.core.*
import java.io.*

internal data class OfflineCatalog(val source: String, val manga: String, val complete: Boolean,
    val chapters: List<SourceChapter>)

internal object OfflineCatalogCodec {
    fun encode(value: OfflineCatalog): ByteArray {
        require(value.source.isNotBlank() && value.manga.isNotBlank() && value.chapters.size <= 10000)
        require(value.chapters.map { it.id }.distinct().size == value.chapters.size)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeUTF(value.source); out.writeUTF(value.manga); out.writeBoolean(value.complete)
            out.writeInt(value.chapters.size)
            value.chapters.forEach {
                require(it.manga == SourceMangaKey(SourceWorld.SFW, value.source, value.manga))
                require(it.id.isNotBlank() && it.id.length <= 512 && it.title.length <= 4096)
                out.writeUTF(it.id); out.writeUTF(it.title); out.writeLong(it.ordinal.toLong()); out.writeLong(it.pageCount?.toLong() ?: -1)
            }
        }
        return buffer.toByteArray().also { require(it.size <= 4 * 1024 * 1024) }
    }
    fun decode(bytes: ByteArray): OfflineCatalog {
        require(bytes.size <= 4 * 1024 * 1024)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val source = input.readUTF(); val manga = input.readUTF(); val complete = input.readBoolean()
            val count = input.readInt(); require(count in 0..10000)
            val chapters = List(count) {
                val id = input.readUTF(); val title = input.readUTF(); val ordinal = input.readLong(); val pages = input.readLong()
                require(ordinal in 0..UInt.MAX_VALUE.toLong() && pages in -1..UInt.MAX_VALUE.toLong())
                SourceChapter(SourceMangaKey(SourceWorld.SFW, source, manga), id, title, ordinal.toUInt(), pages.takeIf { it >= 0 }?.toUInt())
            }
            require(input.available() == 0)
            OfflineCatalog(source, manga, complete, chapters).also { encode(it).fill(0) }
        }
    }
}

internal class OfflineCatalogStore(private val db: AccountImportDatabase) {
    private fun slot(source: String, manga: String) = "offline-catalog:" + downloadKey(source, manga, "catalog")
    suspend fun load(source: String, manga: String, allowed: () -> Boolean): OfflineCatalog? = db.transaction(allowed) { rows ->
        rows.read(slot(source, manga))?.let { bytes ->
            try { OfflineCatalogCodec.decode(bytes).also { require(it.source == source && it.manga == manga) } }
            finally { bytes.fill(0) }
        }
    }
    suspend fun save(value: OfflineCatalog, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        val key = slot(value.source, value.manga)
        val old = rows.read(key)?.let { try { OfflineCatalogCodec.decode(it) } finally { it.fill(0) } }
        // A cancelled refresh must not replace the last complete catalog with its first page.
        if (old?.complete != true || value.complete) {
            val bytes = OfflineCatalogCodec.encode(value)
            try { rows.write(key, bytes) } finally { bytes.fill(0) }
        }
    }
}

internal fun offlineChapters(source: String, manga: String, catalog: OfflineCatalog?, jobs: List<ChapterDownload>): List<SourceChapter> {
    require(catalog == null || (catalog.source == source && catalog.manga == manga))
    val known = catalog?.chapters.orEmpty()
    val ids = known.map { it.id }.toSet()
    return known + jobs.filter { it.source == source && it.manga == manga && it.status == DownloadStatus.COMPLETE && it.chapter !in ids }
        .mapIndexed { index, it -> SourceChapter(SourceMangaKey(SourceWorld.SFW, source, manga), it.chapter, it.title,
            (known.size + index + 1).toUInt(), it.pages.size.toUInt()) }
}

internal data class DownloadBookStatus(val source: String, val manga: String, val completed: Int,
    val pending: Int, val bytes: Long, val firstChapter: String?)
internal fun downloadBookStatuses(jobs: List<ChapterDownload>) = jobs.filter { it.status != DownloadStatus.CANCELLED }
    .groupBy { it.source to it.manga }.map { (key, rows) ->
        val complete = rows.filter { it.status == DownloadStatus.COMPLETE }
        DownloadBookStatus(key.first, key.second, complete.size, rows.size - complete.size, rows.sumOf { it.bytes }, complete.firstOrNull()?.chapter)
    }

/** Partial snapshots and download insertion order cannot establish chapter adjacency. */
internal fun completeReaderCatalog(catalog: OfflineCatalog?, source: String, manga: String): List<SourceChapter> {
    if (catalog?.complete != true) throw IOException("离线目录不完整，无法确定相邻章节；请从目录选择，联网时可缓存完整目录")
    require(catalog.source == source && catalog.manga == manga && catalog.chapters.all {
        it.manga == SourceMangaKey(SourceWorld.SFW, source, manga)
    }) { "离线目录身份不匹配" }
    return catalog.chapters
}
