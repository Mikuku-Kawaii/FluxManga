package com.flux.manga.update

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.RandomAccessFile

/** BSDIFF40 control application adapted from FreeBSD bspatch, BSD-2-Clause.
 * Pinned source, full copyright and local changes: docs/third-party/update-delta/.
 * Old APK stays read-only; neither APK nor decompressed patch is loaded wholesale.
 */
internal object BsdiffPatch {
    fun apply(base: File, patch: File, output: File, targetSize: Long,
        checkpoint: () -> Unit, progress: (Long) -> Unit) {
        require(targetSize in 1..MAX_APK && base.length() in 1..MAX_APK)
        require(output.canonicalFile != base.canonicalFile && output.canonicalFile != patch.canonicalFile)
        val lengths = DataInputStream(patch.inputStream()).use { header ->
            val magic = ByteArray(8); header.readFully(magic)
            require(magic.contentEquals("BSDIFF40".toByteArray(Charsets.US_ASCII))) { "不支持的补丁格式" }
            longArrayOf(header.offset(), header.offset(), header.offset())
        }
        val controlSize = lengths[0]; val diffSize = lengths[1]
        require(patch.length() >= 32 && controlSize >= 0 && diffSize >= 0 && lengths[2] == targetSize) { "补丁头无效" }
        require(controlSize <= patch.length() - 32 && diffSize <= patch.length() - 32 - controlSize) { "补丁区间无效" }
        val extraStart = 32 + controlSize + diffSize
        section(patch, 32, controlSize, checkpoint) { control ->
            section(patch, 32 + controlSize, diffSize, checkpoint) { diff ->
                section(patch, extraStart, patch.length() - extraStart, checkpoint) { extra ->
                    RandomAccessFile(base, "r").use { old ->
                        output.outputStream().use { target ->
                            val bytes = ByteArray(65536)
                            val oldBytes = ByteArray(65536)
                            var oldPosition = 0L
                            var written = 0L
                            while (written < targetSize) {
                                checkpoint()
                                val add = control.offset(); val copy = control.offset(); val seek = control.offset()
                                require(add >= 0 && add <= targetSize - written && copy >= 0 && copy <= targetSize - written - add) { "补丁输出越界" }
                                var left = add
                                while (left > 0) {
                                    checkpoint()
                                    val count = minOf(left, bytes.size.toLong()).toInt()
                                    diff.readFully(bytes, 0, count)
                                    val end = Math.addExact(oldPosition, count.toLong())
                                    val from = maxOf(0L, oldPosition)
                                    val until = minOf(old.length(), end)
                                    if (until > from) {
                                        val overlap = (until - from).toInt()
                                        val destination = (from - oldPosition).toInt()
                                        old.seek(from); old.readFully(oldBytes, 0, overlap)
                                        for (i in 0 until overlap) bytes[destination + i] = (bytes[destination + i] + oldBytes[i]).toByte()
                                    }
                                    target.write(bytes, 0, count)
                                    oldPosition = end; written += count; left -= count
                                    progress(written)
                                }
                                left = copy
                                while (left > 0) {
                                    checkpoint()
                                    val count = minOf(left, bytes.size.toLong()).toInt()
                                    extra.readFully(bytes, 0, count); target.write(bytes, 0, count)
                                    written += count; left -= count; progress(written)
                                }
                                oldPosition = Math.addExact(oldPosition, seek)
                            }
                            checkpoint()
                            // Consume end markers so BZip2 CRC errors cannot become a ready APK.
                            require(control.read() == -1 && diff.read() == -1 && extra.read() == -1) { "补丁包含多余输出" }
                            target.fd.sync()
                        }
                    }
                }
            }
        }
    }

    // BSDiff integers use sign-magnitude little endian, not Java's signed long encoding.
    private fun DataInputStream.offset(): Long {
        val bytes = ByteArray(8); readFully(bytes)
        var value = (bytes[7].toInt() and 0x7f).toLong()
        for (i in 6 downTo 0) value = (value shl 8) or (bytes[i].toLong() and 0xff)
        return if (bytes[7].toInt() and 0x80 != 0) -value else value
    }

    private fun section(file: File, start: Long, size: Long, checkpoint: () -> Unit,
        action: (DataInputStream) -> Unit) {
        FileInputStream(file).use { raw ->
            raw.channel.position(start)
            val bounded = object : FilterInputStream(raw) {
                var remaining = size
                override fun read(): Int {
                    checkpoint()
                    if (remaining == 0L) return -1
                    return super.read().also { if (it >= 0) remaining-- }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    checkpoint()
                    if (length == 0) return 0
                    if (remaining == 0L) return -1
                    return `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                        .also { if (it > 0) remaining -= it }
                }
            }
            DataInputStream(BZip2CompressorInputStream(bounded.buffered(65536), false)).use(action)
        }
    }
}
