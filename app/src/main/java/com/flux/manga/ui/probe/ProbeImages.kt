package com.flux.manga.ui.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.core.graphics.get
import com.flux.manga.core.ImageProbeRequest
import com.flux.manga.core.ProbeSession
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ImageTransfer { BYTES, FILE }

data class ImageInspection(
    val encodedBytes: Long,
    val sha256: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val bitmapBytes: Int,
    val pixelSignature: String,
    val rustMs: Double,
    val transferMs: Double,
    val decodeMs: Double,
)

/** M0 measurement only: decode once on Android, inspect, then release; no UI bitmap cache. */
object ProbeImages {
    suspend fun inspect(
        session: ProbeSession, source: File, output: File, transfer: ImageTransfer,
    ): ImageInspection = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtimeNanos()
        val request = ImageProbeRequest(source.absolutePath, 0u)
        when (transfer) {
            ImageTransfer.BYTES -> {
                val result = session.imageBytes(request)
                val transferred = SystemClock.elapsedRealtimeNanos()
                ensureActive()
                decode(result.data.size.toLong(), result.rustMicros.toLong(), start, transferred,
                    { options -> BitmapFactory.decodeByteArray(result.data, 0, result.data.size, options) },
                    { MessageDigest.getInstance("SHA-256").digest(result.data).hex() })
            }
            ImageTransfer.FILE -> {
                val result = session.imageFile(request, output.absolutePath)
                try {
                    val info = result.info()
                    val transferred = SystemClock.elapsedRealtimeNanos()
                    ensureActive()
                    decode(info.encodedBytes.toLong(), info.rustMicros.toLong(), start, transferred,
                        { options -> BitmapFactory.decodeFile(info.path, options) },
                        { sha256(File(info.path)) })
                } finally {
                    // Deletes only the owned output after decoding (also on cancellation/error).
                    result.destroy()
                }
            }
        }
    }

    private fun decode(
        size: Long, rustMicros: Long, start: Long, transferred: Long,
        read: (BitmapFactory.Options) -> Bitmap?, digest: () -> String,
    ): ImageInspection {
        val decodeStart = SystemClock.elapsedRealtimeNanos()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(options)
        val width = options.outWidth
        val height = options.outHeight
        require(width > 0 && height > 0) { "Fixture is not a decodable image" }
        options.inSampleSize = 1
        while (maxOf(width, height) / options.inSampleSize > 2048) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bitmap = requireNotNull(read(options)) { "Fixture decode failed" }
        val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStart) / 1_000_000.0
        try {
            val pixels = listOf(bitmap[0, 0],
                bitmap[bitmap.width / 2, bitmap.height / 2],
                bitmap[bitmap.width - 1, bitmap.height - 1]).joinToString(",")
            return ImageInspection(size, digest(), width, height, bitmap.width, bitmap.height,
                bitmap.allocationByteCount, pixels, rustMicros / 1000.0,
                (transferred - start) / 1_000_000.0, decodeMs)
        } finally {
            // The probe never hands this bitmap to Compose; it is safe to recycle here.
            bitmap.recycle()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(chunk)
                if (size < 0) break
                digest.update(chunk, 0, size)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
