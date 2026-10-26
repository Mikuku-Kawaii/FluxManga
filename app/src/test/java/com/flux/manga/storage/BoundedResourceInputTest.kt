package com.flux.manga.storage

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BoundedResourceInputTest {
    private fun buffers() = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())

    @Test fun arbitraryShortReadsPreserveBytesAndWipeEveryTemporaryBlock() {
        val plain = ByteArray(170_123) { (it * 31).toByte() }
        val blocks = buffers()
        val input = object : ByteArrayInputStream(plain) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                blocks.add(b); return super.read(b, off, minOf(len, 137))
            }
            override fun available() = Int.MAX_VALUE // never trusted for allocation
        }
        val result = BoundedResourceInput.read(input, plain.size) { }
        assertArrayEquals(plain, result)
        assertTrue(blocks.all { it.size <= 64 * 1024 && it.all { byte -> byte == 0.toByte() } })
    }
    @Test fun exactLimitAndEmptyAreValidButOneByteOverIsRejected() {
        for (size in listOf(0, 31, 32)) {
            assertEquals(size, BoundedResourceInput.read(ByteArrayInputStream(ByteArray(size)), 32) { }.size)
        }
        val blocks = buffers()
        val input = object : ByteArrayInputStream(ByteArray(100)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { blocks.add(b); return super.read(b, off, len) }
        }
        assertThrows(IOException::class.java) { BoundedResourceInput.read(input, 32) { } }
        assertEquals(67, input.available()) // at most limit+1 consumed
        assertTrue(blocks.all { it.all { byte -> byte == 0.toByte() } })
    }
    @Test fun stalledInputFailsWithoutSpin() {
        var reads = 0
        val input = object : InputStream() {
            override fun read() = error("bulk only")
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return 0 }
        }
        assertThrows(IOException::class.java) { BoundedResourceInput.read(input, 32) { } }
        assertEquals(1, reads)
    }
    @Test fun failureAndCancellationWipePartiallyFilledBlocksAndLeaveStreamOwnershipToCaller() {
        for (cancelled in listOf(false, true)) {
            val blocks = buffers(); var calls = 0; var closed = false
            val input = object : InputStream() {
                override fun read() = error("bulk only")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    blocks.add(b)
                    if (++calls == 3) throw if (cancelled) CancellationException("fixture") else IOException("fixture")
                    b.fill(7, off, off + len); return len
                }
                override fun close() { closed = true }
            }
            assertThrows(if (cancelled) CancellationException::class.java else IOException::class.java) {
                BoundedResourceInput.read(input, 300_000) { }
            }
            assertFalse(closed)
            assertTrue(blocks.all { it.all { byte -> byte == 0.toByte() } })
            input.close(); assertTrue(closed)
        }
    }
    @Test fun cancellationDuringMergeAlsoClearsReceivedBlocks() {
        val blocks = buffers(); var ended = false; var afterEnd = 0
        val input = object : ByteArrayInputStream(ByteArray(100_000) { 55 }) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                blocks.add(b); return super.read(b, off, len).also { if (it < 0) ended = true }
            }
        }
        assertThrows(CancellationException::class.java) {
            BoundedResourceInput.read(input, 150_000) { if (ended && ++afterEnd == 3) throw CancellationException("merge") }
        }
        assertTrue(blocks.all { it.all { byte -> byte == 0.toByte() } })
    }
    @Test fun configuredLimitsIncludeOnlyPrivateEnvelopeOverhead() {
        assertEquals(4 * 1024 * 1024, BoundedResourceInput.PUBLIC_BYTES)
        assertEquals(32 * 1024 * 1024, BoundedResourceInput.PRIVATE_BYTES)
        for (maximum in listOf(0, -1, BoundedResourceInput.PRIVATE_BYTES + 30, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) {
                BoundedResourceInput.read(ByteArrayInputStream(byteArrayOf()), maximum) { }
            }
    }
}
