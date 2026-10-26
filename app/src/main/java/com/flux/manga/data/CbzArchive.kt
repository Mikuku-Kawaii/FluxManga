package com.flux.manga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

enum class ImportFailure(val userMessage: String) {
    NOT_CBZ("请选择 .cbz 文件"), CORRUPT("归档损坏、加密或图片无法解码，请重新选择有效 CBZ"),
    EMPTY("归档中没有可用图片"), UNSUPPORTED("首版仅支持 JPEG、PNG 和 WebP 图片"),
    LIMIT("归档超过首版大小、页数或图片尺寸限制"), PERMISSION("无法读取原文件，请重新选择并授予访问权限"),
    NO_SPACE("存储空间不足，请释放空间后重新导入"), IO("导入失败，请检查文件与存储后重试"),
    MISSING("应用内 CBZ 副本缺失或大小改变，请重新选择原文件导入"), WORLD("CBZ 导入仅开放给表世界"),
}
class CbzException(val failure: ImportFailure) : java.io.IOException(failure.userMessage)

internal data class ArchivePage(val name: String, val mime: String, val width: Int, val height: Int)

/** Locale-independent numeric runs; ties use digit width, then the original full name. */
internal val naturalPageOrder = Comparator<String> { left, right ->
    val a = Regex("[0-9]+|[^0-9]+").findAll(left).map { it.value }.toList()
    val b = Regex("[0-9]+|[^0-9]+").findAll(right).map { it.value }.toList()
    var comparison = 0
    for (i in 0 until minOf(a.size, b.size)) {
        val x = a[i]; val y = b[i]
        comparison = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
            val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 }
                ?: nx.compareTo(ny).takeIf { it != 0 } ?: x.length.compareTo(y.length)
        } else x.lowercase(Locale.ROOT).compareTo(y.lowercase(Locale.ROOT))
        if (comparison != 0) break
    }
    if (comparison != 0) comparison else a.size.compareTo(b.size).takeIf { it != 0 } ?: left.compareTo(right)
}

internal object CbzArchive {
    const val MAX_ARCHIVE = 256L * 1024 * 1024
    private const val MAX_ENTRY = 32L * 1024 * 1024
    private const val MAX_EXPANDED = 512L * 1024 * 1024
    fun scan(file: File, checkpoint: () -> Unit): List<ArchivePage> {
        ZipFile(file).use { zip ->
            if (zip.size() > 4096) throw CbzException(ImportFailure.LIMIT)
            val names = HashSet<String>()
            val pages = mutableListOf<ArchivePage>()
            var expanded = 0L
            val entries = zip.entries()
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                checkpoint()
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith('/') || '\\' in name || '\u0000' in name || ':' in name ||
                    name.split('/').any { it == ".." || it == "." } || !names.add(name)) throw CbzException(ImportFailure.CORRUPT)
                if (entry.isDirectory) continue
                if (entry.size < 0 || entry.size > MAX_ENTRY) throw CbzException(ImportFailure.LIMIT)
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count; expanded += count
                        if (size > MAX_ENTRY || expanded > MAX_EXPANDED) throw CbzException(ImportFailure.LIMIT)
                        crc.update(buffer, 0, count)
                    }
                }
                if (size != entry.size || crc.value != entry.crc) throw CbzException(ImportFailure.CORRUPT)
                val lower = name.lowercase(Locale.ROOT)
                if (lower.startsWith("__macosx/") || lower.substringAfterLast('/') == ".ds_store" ||
                    lower.endsWith(".xml") || lower.endsWith(".txt")) continue
                val expected = when (lower.substringAfterLast('.', "")) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> throw CbzException(ImportFailure.UNSUPPORTED)
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType != expected) throw CbzException(ImportFailure.CORRUPT)
                if (options.outWidth > 100_000 || options.outHeight > 100_000 || options.outWidth.toLong() * options.outHeight > 100_000_000) throw CbzException(ImportFailure.LIMIT)
                checkpoint()
                // Probe every image with bounded output, never decode all full-resolution pages.
                decode(zip, name, options.outWidth, options.outHeight)?.recycle() ?: throw CbzException(ImportFailure.CORRUPT)
                pages += ArchivePage(name, expected, options.outWidth, options.outHeight)
                if (pages.size > 2000) throw CbzException(ImportFailure.LIMIT)
            }
            if (pages.isEmpty()) throw CbzException(ImportFailure.EMPTY)
            return pages.sortedWith { a, b -> naturalPageOrder.compare(a.name, b.name) }
        }
    }
    fun decode(zip: ZipFile, name: String, width: Int, height: Int): Bitmap? {
        var sample = 1
        while (width / sample > 256 || height / sample > 256) sample *= 2
        return zip.getInputStream(zip.getEntry(name) ?: return null).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
