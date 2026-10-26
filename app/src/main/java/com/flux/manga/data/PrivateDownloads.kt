package com.flux.manga.data

import android.content.Context
import android.graphics.BitmapFactory
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest

internal data class PrivateDownloadPayload(val bytes: ByteArray, val strips: Int = 0) : Closeable {
    override fun close() { bytes.fill(0) }
    fun hash() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun encode(): ByteArray {
        require(bytes.size in 1..PrivateImagePipeline.MAX_BYTES && strips >= 0)
        return java.nio.ByteBuffer.allocate(bytes.size + 12).putInt(1).putInt(strips).putInt(bytes.size).put(bytes).array()
    }
    companion object {
        fun decode(value: ByteArray): PrivateDownloadPayload {
            val input = java.nio.ByteBuffer.wrap(value)
            require(input.int == 1); val strips = input.int; val size = input.int
            require(strips >= 0 && size in 1..PrivateImagePipeline.MAX_BYTES && size == input.remaining())
            return PrivateDownloadPayload(ByteArray(size).also { input.get(it) }, strips)
        }
    }
}
internal interface PrivateDownloadReader : ReaderRepository {
    suspend fun downloadPage(page: SourcePageResource): PrivateDownloadPayload
}
internal fun privateDownloadReader(context: Context, session: WorldSession, source: String, manga: String): PrivateDownloadReader = when {
    isJmSource(source) -> JmReaderRepository(context, session, source, manga)
    isPicaSource(source) -> PicaReaderRepository(context, session, source, manga)
    isEhSource(source) || isExSource(source) -> EhReaderRepository(context, session, source, manga)
    else -> throw IOException("此来源不支持私有下载")
}

internal class PrivateDownloadStore(context: Context) {
    private val data = WorldDataStore(context)
    val jobs = DownloadStore(data.accountImportDatabase(PrototypeWorld.NSFW))
    private fun slot(source: String, manga: String) = "private-offline-catalog-v1:" + downloadKey(source, manga, "catalog")
    suspend fun catalog(source: String, manga: String, allowed: () -> Boolean): List<SourceChapter> {
        val raw = data.session(PrototypeWorld.NSFW, slot(source, manga), allowed) ?: return emptyList()
        return try {
            val json = JSONObject(raw.toString(Charsets.UTF_8)); require(json.getInt("version") == 1 && json.getString("source") == source && json.getString("manga") == manga)
            val rows = json.getJSONArray("chapters"); require(rows.length() <= 10000)
            List(rows.length()) { index -> val row = rows.getJSONObject(index)
                SourceChapter(SourceMangaKey(SourceWorld.NSFW, source, manga), row.getString("id"), row.getString("title"), row.getLong("ordinal").toUInt(), null)
            }
        } finally { raw.fill(0) }
    }
    suspend fun saveCatalog(source: String, manga: String, chapters: List<SourceChapter>, allowed: () -> Boolean) {
        require(chapters.isNotEmpty() && chapters.size <= 10000 && chapters.map { it.id }.distinct().size == chapters.size)
        require(chapters.all { it.manga == SourceMangaKey(SourceWorld.NSFW, source, manga) })
        val rows = JSONArray(); chapters.forEach { rows.put(JSONObject().put("id", it.id).put("title", it.title).put("ordinal", it.ordinal.toLong())) }
        val raw = JSONObject().put("version", 1).put("source", source).put("manga", manga).put("chapters", rows).toString().toByteArray()
        try { require(raw.size <= 4 * 1024 * 1024); data.putSession(PrototypeWorld.NSFW, slot(source, manga), raw, allowed) }
        finally { raw.fill(0) }
    }
    suspend fun read(job: ChapterDownload, index: Int, allowed: () -> Boolean): PrivateDownloadPayload? {
        val raw = data.offlinePage(job, index, allowed) ?: return null
        return try { PrivateDownloadPayload.decode(raw) } finally { raw.fill(0) }
    }
    suspend fun save(job: ChapterDownload, index: Int, image: PrivateDownloadPayload, allowed: () -> Boolean): DownloadPage {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        require(options.outWidth in 1..100000 && options.outHeight in 1..100000 && image.strips <= options.outHeight) { "图片尺寸或切片参数无效" }
        val raw = image.encode()
        try { data.putOfflinePage(job, index, raw, allowed) } finally { raw.fill(0) }
        return DownloadPage(job.pages[index].id, image.bytes.size.toLong(), image.hash())
    }
    suspend fun valid(job: ChapterDownload, index: Int, allowed: () -> Boolean): Boolean {
        if (job.pages[index].hash.isEmpty()) return false
        return read(job, index, allowed)?.use { it.bytes.size.toLong() == job.pages[index].bytes && it.hash() == job.pages[index].hash } ?: false
    }
    suspend fun clear(job: ChapterDownload, allowed: () -> Boolean) = data.clearOfflineChapter(job, allowed)
}
