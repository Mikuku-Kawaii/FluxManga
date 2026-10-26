package com.flux.manga.storage

import java.io.IOException
import java.io.InputStream

/** Caller owns/closes the stream and wipes the returned buffer. Temporary blocks are always wiped. */
internal object BoundedResourceInput {
    const val PUBLIC_BYTES = 4 * 1024 * 1024
    const val PRIVATE_BYTES = 32 * 1024 * 1024
    const val ENVELOPE_BYTES = 29
    private const val BLOCK_BYTES = 64 * 1024

    fun read(input: InputStream, maximum: Int, checkActive: () -> Unit): ByteArray {
        require(maximum in 1..PRIVATE_BYTES + ENVELOPE_BYTES)
        val blocks = ArrayList<Pair<ByteArray, Int>>()
        var result: ByteArray? = null
        var completed = false
        try {
            var total = 0
            while (true) {
                checkActive()
                // One extra byte proves overflow without trusting length/available metadata.
                val block = ByteArray(minOf(BLOCK_BYTES, maximum - total + 1))
                blocks.add(block to 0)
                var used = 0
                var end = false
                while (used < block.size) {
                    checkActive()
                    val count = input.read(block, used, block.size - used)
                    if (count < 0) { end = true; break }
                    if (count == 0) throw IOException("图片读取未取得数据，请重试")
                    used += count
                    if (used > maximum - total) throw IOException("图片超过读取容量")
                }
                blocks[blocks.lastIndex] = block to used
                total += used
                if (end) break
            }
            checkActive()
            val bytes = ByteArray(total)
            result = bytes
            var offset = 0
            for ((block, used) in blocks) {
                checkActive()
                block.copyInto(bytes, offset, 0, used)
                offset += used
            }
            checkActive()
            completed = true
            return bytes
        } finally {
            blocks.forEach { it.first.fill(0) }
            if (!completed) result?.fill(0)
        }
    }
}
