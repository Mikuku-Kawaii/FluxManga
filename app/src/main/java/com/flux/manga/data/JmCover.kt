package com.flux.manga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.ByteArrayInputStream
import java.io.IOException

/** Encoded bytes use the private encrypted cache; decoded covers stay in the owning UI. */
internal suspend fun jmCover(data: WorldDataStore, session: WorldSession, client: JmClient, id: String): Bitmap {
    val identity = session.identity.value
    require(identity.world == PrototypeWorld.NSFW && jmId(id))
    val key = ResourceKey(SampleKey(identity.world, client.source, id), "cover-v1")
    return PrivateImagePipeline(key.manga, object : PrivateEncodedCache {
        override suspend fun read(key: ResourceKey, allowed: () -> Boolean) = data.resource(key, allowed)
        override suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean) = data.putResource(key, bytes, allowed)
        override fun clearMemory() = data.clearMemory()
    }, PrivateImageTransport {
        val bytes = client.image("media/albums/$id.jpg")
        object : ByteArrayInputStream(bytes) { override fun close() { bytes.fill(0); super.close() } }
    }, { session.state.value.visibilityEpoch }, { epoch ->
        session.accepts(identity) && session.state.value.foreground && epoch == session.state.value.visibilityEpoch
    }).decode(key, { bytes ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IOException("封面格式不受支持")
        var sample = 1
        while (opts.outWidth / sample > 640 || opts.outHeight / sample > 960) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("封面读取失败")
    }, { it.recycle() })
}
