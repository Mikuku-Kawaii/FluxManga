package com.flux.manga.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class UpdateTransferTest {
    private val bytes = ByteArray(150000) { (it % 241).toByte() }
    private val artifact = UpdateArtifact(2, "test", 31, setOf("arm64-v8a"), bytes.size.toLong(), sha256(bytes), "https://example.invalid/app.apk", "")
    private fun withFile(block: (File) -> Unit) {
        val root = Files.createTempDirectory("flux-update-test").toFile()
        try { block(File(root, "update.part")) } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }
    private fun response(status: Int, body: ByteArray, range: String? = null, close: () -> Unit = {}): UpdateResponse = object : UpdateResponse {
        override val status = status
        override val body = ByteArrayInputStream(body)
        override val contentLength = body.size.toLong()
        override val contentRange = range
        override fun close() { this.body.close(); close.invoke() }
    }
    @Test fun downloadsAndClosesFullResponseThenValidatesHash() = withFile { file ->
        var closed = false
        UpdateTransfer(UpdateTransport { _, offset -> assertEquals(0L, offset); response(200, bytes, close = { closed = true }) })
            .download(artifact, file, {}, {})
        assertTrue(closed); assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun cancelledReadKeepsBytesAndResumesOnlyAtExactOffset() = withFile { file ->
        var cancel = false
        val transfer = UpdateTransfer(UpdateTransport { _, offset ->
            if (offset == 0L) response(200, bytes)
            else response(206, bytes.copyOfRange(offset.toInt(), bytes.size), "bytes $offset-${bytes.size - 1}/${bytes.size}")
        })
        assertThrows(CancellationException::class.java) {
            transfer.download(artifact, file, { if (cancel) throw CancellationException() }, { if (it > 0) cancel = true })
        }
        assertTrue(file.length() in 1 until artifact.size)
        transfer.download(artifact, file, {}, {})
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun ignoredRangeRestartsInsteadOfAppendingDuplicateBytes() = withFile { file ->
        file.writeBytes(bytes.copyOfRange(0, 123))
        UpdateTransfer(UpdateTransport { _, offset -> assertEquals(123L, offset); response(200, bytes) }).download(artifact, file, {}, {})
        assertEquals(artifact.size, file.length()); verifyTarget(file, artifact)
    }
    @Test fun wrongRangeDoesNotMutatePreviousCheckpoint() = withFile { file ->
        file.writeBytes(bytes.copyOfRange(0, 123))
        assertThrows(IllegalArgumentException::class.java) {
            UpdateTransfer(UpdateTransport { _, _ -> response(206, bytes, "bytes 0-${bytes.size - 1}/${bytes.size}") }).download(artifact, file, {}, {})
        }
        assertEquals(123L, file.length())
    }
    @Test fun noDiskSpaceDoesNotOpenNetworkOrDestroyCheckpoint() = withFile { file ->
        file.writeBytes(bytes.copyOfRange(0, 123))
        assertThrows(IllegalArgumentException::class.java) {
            UpdateTransfer(UpdateTransport { _, _ -> error("must not open network") }).download(artifact, file, {}, {}, { 0 })
        }
        assertEquals(123L, file.length())
    }
    @Test fun truncatedAndChangedBodiesNeverPassVerification() = withFile { file ->
        assertThrows(IllegalArgumentException::class.java) {
            UpdateTransfer(UpdateTransport { _, _ -> response(200, bytes.copyOf(100)) }).download(artifact, file, {}, {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateTransfer(UpdateTransport { _, _ -> response(200, ByteArray(bytes.size)) }).download(artifact, file, {}, {})
        }
    }
    @Test fun completeCheckpointIsRehashedAndCorruptCheckpointIsDownloadedAgain() = withFile { file ->
        file.writeBytes(bytes)
        val transfer = UpdateTransfer(UpdateTransport { _, _ -> error("no network") })
        transfer.download(artifact, file, {}, {})
        file.writeBytes(ByteArray(bytes.size))
        UpdateTransfer(UpdateTransport { _, offset ->
            assertEquals(0L, offset)
            response(200, bytes)
        }).download(artifact, file, {}, {})
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun ioFailureRetainsWrittenPrefixClosesResponseAndNextRequestResumes() = withFile { file ->
        var closed = false
        val failed = object : UpdateResponse {
            override val status = 200
            override val contentRange: String? = null
            override val contentLength = -1L
            override val body = object : java.io.InputStream() {
                var offset = 0
                override fun read(): Int { if (offset >= 65536) throw IOException("offline"); return bytes[offset++].toInt() and 255 }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (offset >= 65536) throw IOException("offline")
                    val n = minOf(len, 65536 - offset); bytes.copyInto(b, off, offset, offset + n); offset += n; return n
                }
            }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { UpdateTransfer(UpdateTransport { _, _ -> failed }).download(artifact, file, {}, {}) }
        assertTrue(closed); assertEquals(65536L, file.length())
        UpdateTransfer(UpdateTransport { _, offset -> response(206, bytes.copyOfRange(offset.toInt(), bytes.size), "bytes $offset-${bytes.size - 1}/${bytes.size}") })
            .download(artifact, file, {}, {})
        verifyTarget(file, artifact)
    }
    @Test fun manifestFailureAndOversizeAreNotLatestVersion() {
        assertThrows(IllegalArgumentException::class.java) { readUpdateManifest(UpdateTransport { _, _ -> response(503, byteArrayOf()) }, artifact.url) }
        assertThrows(IllegalArgumentException::class.java) { readUpdateManifest(UpdateTransport { _, _ -> response(200, ByteArray(MAX_MANIFEST + 1)) }, artifact.url) }
        assertThrows(IOException::class.java) { readUpdateManifest(UpdateTransport { _, _ -> throw IOException() }, artifact.url) }
    }
}
