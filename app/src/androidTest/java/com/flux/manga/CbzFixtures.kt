package com.flux.manga

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object CbzFixtures {
    fun image(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, color: Int = Color.BLUE): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(format, 90, out); bitmap.recycle(); out.toByteArray()
        }
    }
    fun archive(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name).apply {
                time = 0
                if (stored) { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
            }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        } }
        out.toByteArray()
    }
    fun valid(): ByteArray = SyntheticCbz.valid()
    val order = listOf("chapter2/page1.png", "chapter2/page2.png", "chapter2/page02.jpg", "chapter2/page10.png", "chapter10/page1.webp")
}
