package com.flux.manga.data

import com.flux.manga.storage.StorageCipher
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Desktop decoder is an oracle for transport/cache tests, not Android/GPU acceptance. */
class PrivateImagePipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = SampleKey(PrototypeWorld.NSFW, "synthetic", "same-id")
    private val key = ResourceKey(owner, "chapter-1/page-1")
    private var epoch = 1
    private var visible = true
    private var opens = 0
    private var closed = 0
    private var received: ByteArray? = null
    private var decoded: ByteArray? = null
    private var clears = 0
    private var invalidateOnWrite = false
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private fun aad(key: ResourceKey) = listOf(key.manga.world.name, key.manga.sourceId, key.manga.mangaId, key.resourceId)
        .joinToString("") { "${it.length}:$it" }.toByteArray()
    private fun file(key: ResourceKey) = File(temporary.root, MessageDigest.getInstance("SHA-256").digest(aad(key)).joinToString("") { "%02x".format(it) })
    private val cache = object : PrivateEncodedCache {
        override suspend fun read(key: ResourceKey, allowed: () -> Boolean) = file(key).takeIf { it.exists() }?.readBytes()?.let { StorageCipher.open(it, secret, aad(key)) }
        override suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean) {
            received = bytes
            file(key).writeBytes(StorageCipher.seal(bytes, secret, aad(key)))
            if (invalidateOnWrite) { epoch++; assertFalse(allowed()) }
        }
        override fun clearMemory() { clears++ }
    }
    private fun png(): ByteArray {
        // Android's compile bootclasspath excludes java.desktop; the local JDK test runtime
        // supplies this independent PNG oracle. No reflection is used in application code.
        val type = Class.forName("java.awt.image.BufferedImage")
        val image = type.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(64, 160, 1)
        val set = type.getMethod("setRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        for (y in 0 until 160) for (x in 0 until 64) set.invoke(image, x, y, if (y < 80) 0xff2200 else 0x0044ff)
        return ByteArrayOutputStream().also {
            Class.forName("javax.imageio.ImageIO").getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, OutputStream::class.java)
                .invoke(null, image, "png", it)
        }.toByteArray()
    }
    private fun pipeline(transport: PrivateImageTransport = PrivateImageTransport {
        opens++
        object : ByteArrayInputStream(png()) { override fun close() { closed++; super.close() } }
    }) = PrivateImagePipeline(owner, cache, transport, { epoch }, { visible && it == epoch })
    private class HostPixels(private val image: Any) {
        val height get() = image.javaClass.getMethod("getHeight").invoke(image) as Int
        fun getRGB(x: Int, y: Int) = image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, x, y) as Int
        fun getSubimage(x: Int, y: Int, w: Int, h: Int) = HostPixels(requireNotNull(image.javaClass.getMethod("getSubimage",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, x, y, w, h)))
        fun flush() { image.javaClass.getMethod("flush").invoke(image) }
    }
    private fun pixels(bytes: ByteArray): HostPixels {
        decoded = bytes
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", InputStream::class.java)
            .invoke(null, ByteArrayInputStream(bytes)) ?: throw IOException("Invalid test image")
        return HostPixels(image)
    }
    private suspend fun rejects(type: Class<out Throwable>, action: suspend () -> Unit) {
        try { action(); fail("Expected ${type.simpleName}") } catch (e: Exception) { if (!type.isInstance(e)) throw e }
    }
    @Test fun generatedImageSealsReopensAndFeedsReaderRegionWithoutPlaintextFile(): Unit = runBlocking {
        val image = pipeline().decode(key, ::pixels) { it.flush() }
        assertEquals(0xffff2200.toInt(), image.getRGB(3, 3))
        assertEquals(0xff0044ff.toInt(), image.getSubimage(0, 80, 64, 80).getRGB(3, 3))
        image.flush()
        assertEquals(1, opens); assertEquals(1, closed)
        assertTrue(requireNotNull(received).all { it == 0.toByte() })
        assertTrue(requireNotNull(decoded).all { it == 0.toByte() })
        assertFalse(png().contentEquals(file(key).readBytes()))
        assertEquals(listOf(file(key).name), temporary.root.listFiles()!!.map { it.name })
        val reopened = pipeline().decode(key, ::pixels) { it.flush() }
        assertEquals(160, reopened.height); reopened.flush()
        assertEquals(1, opens) // Fresh pipeline reads encrypted disk, not a hidden memory cache.
    }
    @Test fun foreignWorldAndSourceAreRejectedBeforeTransportOrCache(): Unit = runBlocking {
        for (other in listOf(owner.copy(world = PrototypeWorld.SFW), owner.copy(sourceId = "other"), owner.copy(mangaId = "other"))) {
            rejects(IllegalArgumentException::class.java) { pipeline().decode(key.copy(manga = other), ::pixels) { it.flush() } }
        }
        assertEquals(0, opens); assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
    @Test fun tamperAndCrossChapterReplayNeverFetchReplacement(): Unit = runBlocking {
        pipeline().decode(key, ::pixels) { it.flush() }.flush()
        val other = key.copy(resourceId = "chapter-2/page-1")
        file(other).writeBytes(file(key).readBytes())
        rejects(IOException::class.java) { pipeline().decode(other, ::pixels) { it.flush() } }
        val damaged = file(key).readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file(key).writeBytes(damaged)
        rejects(IOException::class.java) { pipeline().decode(key, ::pixels) { it.flush() } }
        assertArrayEquals(damaged, file(key).readBytes()); assertEquals(1, opens)
    }
    @Test fun interruptedStreamClosesAndRetryStartsWithoutPartialCache(): Unit = runBlocking {
        val broken = pipeline(PrivateImageTransport {
            object : InputStream() {
                override fun read() = throw IOException("Synthetic interruption")
                override fun close() { closed++ }
            }
        })
        rejects(IOException::class.java) { broken.decode(key, ::pixels) { it.flush() } }
        assertFalse(file(key).exists()); assertEquals(1, closed)
        pipeline().decode(key, ::pixels) { it.flush() }.flush()
        assertTrue(file(key).exists())
    }
    @Test fun emptyOversizeAndInvalidPayloadAreNotCached(): Unit = runBlocking {
        for (size in listOf(0, PrivateImagePipeline.MAX_BYTES + 1, 16)) {
            val reader = pipeline(PrivateImageTransport { object : ByteArrayInputStream(ByteArray(size)) { override fun close() { closed++ } } })
            rejects(IOException::class.java) { reader.decode(key, ::pixels) { it.flush() } }
            assertFalse(file(key).exists())
        }
        assertEquals(3, closed)
    }
    @Test fun lateTransportFromPreviousVisibilityCannotPublishOrCache(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val reader = pipeline(PrivateImageTransport {
            started.complete(Unit)
            finish.await()
            object : ByteArrayInputStream(png()) { override fun close() { closed++ } }
        })
        val work = async { reader.decode(key, ::pixels) { it.flush() } }
        started.await(); visible = false; epoch++; visible = true; finish.complete(Unit)
        rejects(CancellationException::class.java) { work.await() }
        assertFalse(file(key).exists()); assertEquals(1, closed); assertNull(decoded)
    }
    @Test fun cancellationAfterStreamReadWipesBufferAndClosesOwner(): Unit = runBlocking {
        val reader = pipeline(PrivateImageTransport {
            object : ByteArrayInputStream(png()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    received = b
                    return super.read(b, off, len).also { visible = false }
                }
                override fun close() { closed++ }
            }
        })
        rejects(CancellationException::class.java) { reader.decode(key, ::pixels) { it.flush() } }
        assertEquals(1, closed); assertTrue(requireNotNull(received).all { it == 0.toByte() }); assertFalse(file(key).exists())
    }
    @Test fun invalidationDuringDecodeDisposesUnpublishedPixels(): Unit = runBlocking {
        var released = 0
        rejects(CancellationException::class.java) {
            pipeline().decode<HostPixels>(key, { bytes -> pixels(bytes).also { epoch++ } }) { released++; it.flush() }
        }
        assertEquals(1, released); assertFalse(file(key).exists())
        assertTrue(requireNotNull(decoded).all { it == 0.toByte() })
    }
    @Test fun invalidationDuringCacheCommitCannotLeaveLateMemoryOrPublishPixels(): Unit = runBlocking {
        invalidateOnWrite = true
        var released = 0
        rejects(CancellationException::class.java) {
            pipeline().decode(key, ::pixels) { released++; it.flush() }
        }
        assertEquals(1, released); assertEquals(1, clears)
        assertTrue(requireNotNull(received).all { it == 0.toByte() })
        // An already committed encrypted cache is retained, never replaced by plaintext.
        assertArrayEquals(png(), StorageCipher.open(file(key).readBytes(), secret, aad(key)))
    }
    @Test fun obsoleteTransportFailureBecomesCancellationInsteadOfReaderError(): Unit = runBlocking {
        val reader = pipeline(PrivateImageTransport { epoch++; throw IOException("Late synthetic failure") })
        rejects(CancellationException::class.java) { reader.decode(key, ::pixels) { it.flush() } }
        assertFalse(file(key).exists())
    }
    @Test fun concurrentTilesSerializeAndReuseEncryptedPage(): Unit = runBlocking {
        val started = AtomicInteger(); val peak = AtomicInteger()
        val reader = pipeline()
        coroutineScope { (1..4).map {
            async { reader.decode(key, { bytes ->
                val now = started.incrementAndGet(); peak.updateAndGet { maxOf(it, now) }
                try { pixels(bytes) } finally { started.decrementAndGet() }
            }) { it.flush() }.flush() }
        }.awaitAll() }
        assertEquals(1, peak.get()); assertEquals(1, opens)
    }
}
