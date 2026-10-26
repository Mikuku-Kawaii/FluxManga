package com.flux.manga

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Deterministic, high-entropy grayscale PNG. No downloaded or private manga content. */
internal object ProbeFixtures {
    fun write(file: File, width: Int, height: Int) {
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use {
                it.writeInt(width); it.writeInt(height)
                it.write(byteArrayOf(8, 0, 0, 0, 0)) // 8-bit grayscale, no interlace
            }
            chunk(output, "IHDR", header.toByteArray())
            val idat = object : OutputStream() {
                private val pending = ByteArrayOutputStream(64 * 1024)
                override fun write(value: Int) { pending.write(value); flushChunk(false) }
                override fun write(data: ByteArray, offset: Int, length: Int) {
                    pending.write(data, offset, length); flushChunk(false)
                }
                private fun flushChunk(force: Boolean) {
                    if (pending.size() >= 64 * 1024 || (force && pending.size() > 0)) {
                        chunk(output, "IDAT", pending.toByteArray()); pending.reset()
                    }
                }
                override fun close() { flushChunk(true) }
            }
            val deflater = Deflater(1)
            try {
                DeflaterOutputStream(idat, deflater).use { compressed ->
                    val random = Random(20261015L)
                    val row = ByteArray(width)
                    repeat(height) {
                        random.nextBytes(row)
                        compressed.write(0) // PNG filter None
                        compressed.write(row)
                    }
                }
            } finally { deflater.end() }
            chunk(output, "IEND", byteArrayOf())
        }
    }

    private fun chunk(output: DataOutputStream, type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(data) }
        output.writeInt(data.size); output.write(name); output.write(data); output.writeInt(crc.value.toInt())
    }
}
