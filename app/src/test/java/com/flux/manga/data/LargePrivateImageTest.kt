package com.flux.manga.data

import com.flux.manga.storage.BoundedResourceInput
import com.flux.manga.storage.StorageCipher
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LargePrivateImageTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = SampleKey(PrototypeWorld.NSFW, "generated", "large")
    private val key = ResourceKey(owner, "chapter-4/noise-v1")
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val aad = "NSFW/generated/large/chapter-4/noise-v1".toByteArray()

    private fun generatedPng(): ByteArray {
        // JDK desktop oracle only; app still uses Android decoding. No new runtime dependency.
        val type = Class.forName("java.awt.image.BufferedImage")
        val number = Int::class.javaPrimitiveType
        val image = type.getConstructor(number, number, number).newInstance(1536, 1536, 1)
        val set = type.getMethod("setRGB", number, number, number, number, IntArray::class.java, number, number)
        val row = IntArray(1536); val pixels = SyntheticNoisePixels()
        try {
            for (y in 0 until 1536) {
                for (x in row.indices) row[x] = pixels.next()
                set.invoke(image, 0, y, 1536, 1, row, 0, 1536)
            }
            return ByteArrayOutputStream().also {
                Class.forName("javax.imageio.ImageIO").getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, OutputStream::class.java)
                    .invoke(null, image, "png", it)
            }.toByteArray()
        } finally { row.fill(0); type.getMethod("flush").invoke(image) }
    }
    private fun readPixels(bytes: ByteArray): Any = requireNotNull(Class.forName("javax.imageio.ImageIO")
        .getMethod("read", InputStream::class.java).invoke(null, ByteArrayInputStream(bytes)))
    private fun release(image: Any) { image.javaClass.getMethod("flush").invoke(image) }

    @Test fun generatedLargePngIsAuthenticatedReopenedAndDecodedWithoutPlaintextFiles(): Unit = runBlocking {
        val png = generatedPng()
        assertTrue(png.size > BoundedResourceInput.PUBLIC_BYTES)
        assertTrue(png.size < BoundedResourceInput.PRIVATE_BYTES)
        val file = temp.root.resolve("page.enc")
        var opens = 0; var closed = 0; var received: ByteArray? = null; var decoded: ByteArray? = null
        val cache = object : PrivateEncodedCache {
            override suspend fun read(key: ResourceKey, allowed: () -> Boolean): ByteArray? = if (!file.exists()) null else {
                val raw = file.inputStream().use { BoundedResourceInput.read(it, BoundedResourceInput.PRIVATE_BYTES + 29) { } }
                try { StorageCipher.open(raw, secret, aad) } finally { raw.fill(0) }
            }
            override suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean) {
                received = bytes; file.writeBytes(StorageCipher.seal(bytes, secret, aad))
            }
            override fun clearMemory() { }
        }
        fun reader() = PrivateImagePipeline(owner, cache, PrivateImageTransport {
            opens++; object : ByteArrayInputStream(png) { override fun close() { closed++; super.close() } }
        }, { 0 }, { true })
        repeat(2) {
            val image = reader().decode(key, { decoded = it; readPixels(it) }, ::release)
            try {
                assertEquals(1536, image.javaClass.getMethod("getHeight").invoke(image))
                assertEquals(SyntheticNoisePixels().next(), image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, 0, 0))
            } finally { release(image) }
            assertTrue(requireNotNull(decoded).all { it == 0.toByte() })
        }
        assertEquals(1, opens); assertEquals(1, closed)
        assertTrue(requireNotNull(received).all { it == 0.toByte() })
        assertEquals(png.size.toLong() + 29, file.length())
        assertEquals(listOf("page.enc"), temp.root.listFiles()!!.map { it.name })
        assertFalse(file.readBytes().copyOf(8).contentEquals(png.copyOf(8)))
        png.fill(0)
    }

    @Test fun largeV1PayloadRejectsTamperAndForeignIdentityWithoutModifyingExistingCiphertext() {
        val plain = ByteArray(5 * 1024 * 1024) { (it * 13).toByte() }
        val file = temp.root.resolve("existing.enc")
        val cipher = StorageCipher.seal(plain, secret, aad)
        file.writeBytes(cipher)
        val changed = cipher.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { StorageCipher.open(changed, secret, aad) }
        assertThrows(IOException::class.java) { StorageCipher.open(cipher, secret, "SFW/other".toByteArray()) }
        assertArrayEquals(cipher, file.readBytes())
        assertArrayEquals(plain, StorageCipher.open(cipher, secret, aad))
    }
}
