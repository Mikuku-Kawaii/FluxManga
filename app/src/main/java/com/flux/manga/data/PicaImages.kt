package com.flux.manga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flux.manga.session.WorldSession
import java.io.ByteArrayInputStream
import java.io.IOException

/** Keep catalog image addresses in the existing per-account encrypted resource cache. */
internal suspend fun rememberPicaCover(data: WorldDataStore, session: WorldSession, source: String, book: PicaBook) {
    val identity = session.identity.value
    require(isPicaSource(source) && picaId(book.id))
    val allowed = { session.accepts(identity) && session.state.value.foreground }
    val key = ResourceKey(SampleKey(identity.world, source, book.id), "cover-url-v1")
    val bytes = book.cover.toByteArray()
    try {
        val old = data.resource(key, allowed)
        try { if (old == null || !old.contentEquals(bytes)) data.putResource(key, bytes, allowed) }
        finally { old?.fill(0) }
    } finally { bytes.fill(0) }
}

internal fun privateCache(data: WorldDataStore) = object : PrivateEncodedCache {
    override suspend fun read(key: ResourceKey, allowed: () -> Boolean) = data.resource(key, allowed)
    override suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean) = data.putResource(key, bytes, allowed)
    override fun clearMemory() = data.clearMemory()
}
internal fun privateImageInput(bytes: ByteArray) = object : ByteArrayInputStream(bytes) {
    override fun close() { bytes.fill(0); super.close() }
}
internal suspend fun picaCover(data: WorldDataStore, session: WorldSession, client: PicaClient, id: String, book: PicaBook? = null): Bitmap {
    val identity = session.identity.value
    require(isPicaSource(client.source) && picaId(id) && (book == null || book.id == id))
    val key = ResourceKey(SampleKey(identity.world, client.source, id), "cover-v1")
    return PrivateImagePipeline(key.manga, privateCache(data), PrivateImageTransport {
        val saved = if (book == null) data.resource(key.copy(resourceId = "cover-url-v1")) {
            session.accepts(identity) && session.state.value.foreground
        } else null
        val address = try { book?.cover ?: saved?.toString(Charsets.UTF_8) }
            finally { saved?.fill(0) }
        val item = if (address == null) client.detail(id) else book
        if (item != null) rememberPicaCover(data, session, client.source, item)
        privateImageInput(client.image(address ?: requireNotNull(item).cover))
    }, { session.state.value.visibilityEpoch }, { epoch -> session.accepts(identity) && session.state.value.foreground && epoch == session.state.value.visibilityEpoch })
        .decode(key, { bytes ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("封面格式不受支持")
            var sample = 1
            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 960) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("封面读取失败")
        }, { it.recycle() })
}
