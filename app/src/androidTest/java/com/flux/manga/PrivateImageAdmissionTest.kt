package com.flux.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.ResourceKey
import com.flux.manga.data.SampleKey
import com.flux.manga.data.WorldDataStore
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Native encoded-image storage/decoder boundary, not a substitute for a private source reader. */
@RunWith(AndroidJUnit4::class)
class PrivateImageAdmissionTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun store() = WorldDataStore(inst.targetContext, "m4-png-${UUID.randomUUID()}")
    private fun key(world: PrototypeWorld, source: String = "synthetic", page: String = "chapter-1/page-1") =
        ResourceKey(SampleKey(world, source, "same-id"), page)
    private fun png(top: Int, bottom: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 96) for (x in 0 until 64) bitmap.setPixel(x, y, if (y < 48) top else bottom)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
    }
    @Test fun encryptedPngReopensAndDecodesWithWorldSourceAndPageIsolation(): Unit = runBlocking {
        val data = store()
        val public = png(Color.RED, Color.YELLOW)
        val private = png(Color.GREEN, Color.BLUE)
        try {
            data.putResource(key(PrototypeWorld.SFW), public)
            data.putResource(key(PrototypeWorld.NSFW), private)
            assertArrayEquals(public, data.cacheFile(key(PrototypeWorld.SFW)).readBytes())
            val encrypted = data.cacheFile(key(PrototypeWorld.NSFW)).readBytes()
            assertFalse(private.contentEquals(encrypted))
            assertNull(BitmapFactory.decodeByteArray(encrypted, 0, encrypted.size))
            data.clearMemory() // Force the authenticated disk path.
            for ((world, top, bottom) in listOf(
                Triple(PrototypeWorld.SFW, Color.RED, Color.YELLOW),
                Triple(PrototypeWorld.NSFW, Color.GREEN, Color.BLUE))) {
                val bytes = requireNotNull(data.resource(key(world)))
                try {
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inMutable = true }))
                    try {
                        assertEquals(64, bitmap.width); assertEquals(96, bitmap.height)
                        assertEquals(top, bitmap.getPixel(12, 12)); assertEquals(bottom, bitmap.getPixel(12, 80))
                    } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
                    val decoder = requireNotNull(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false))
                    try {
                        val region = requireNotNull(decoder.decodeRegion(Rect(0, 48, 64, 96), BitmapFactory.Options().apply { inMutable = true }))
                        try { assertEquals(bottom, region.getPixel(12, 12)) }
                        finally {
                            // Region decoders may return immutable pixels despite inMutable.
                            // Release ownership; recycle is not a guarantee of pixel zeroization.
                            if (region.isMutable) region.eraseColor(Color.TRANSPARENT)
                            region.recycle()
                        }
                    } finally { decoder.recycle() }
                } finally { bytes.fill(0); assertTrue(bytes.all { it == 0.toByte() }) }
                assertNull(data.resource(key(world, source = "other-source")))
                assertNull(data.resource(key(world, page = "chapter-2/page-1")))
            }
            assertArrayEquals(encrypted, data.cacheFile(key(PrototypeWorld.NSFW)).readBytes())
        } finally { data.clearMemory(); public.fill(0); private.fill(0) }
    }
    @Test fun authenticatedPngRejectsReplayAndTamperWithoutPlaintextFallback(): Unit = runBlocking {
        val data = store()
        val bytes = png(Color.CYAN, Color.MAGENTA)
        val original = key(PrototypeWorld.NSFW)
        val different = key(PrototypeWorld.NSFW, page = "chapter-2/page-1")
        try {
            data.putResource(original, bytes); data.clearMemory()
            val ciphertext = data.cacheFile(original).readBytes()
            data.cacheFile(different).writeBytes(ciphertext) // Only this new UUID fixture.
            try { data.resource(different); fail("Cross-page replay accepted") } catch (_: IOException) { }
            assertArrayEquals(ciphertext, data.cacheFile(different).readBytes())
            val damaged = ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            data.cacheFile(original).writeBytes(damaged)
            try { data.resource(original); fail("Damaged image authenticated") } catch (_: IOException) { }
            assertArrayEquals(damaged, data.cacheFile(original).readBytes())
        } finally { data.clearMemory(); bytes.fill(0) }
    }
    @Test fun sessionPauseEvictsSharedEncodedImageCache(): Unit = runBlocking {
        val data = store()
        val bytes = png(Color.GREEN, Color.BLUE)
        val resource = key(PrototypeWorld.NSFW)
        val session = WorldSession().also { it.configure(inst.targetContext) }
        try {
            data.putResource(resource, bytes)
            // Removing only our new file makes an old memory hit observable after pause.
            assertTrue(data.cacheFile(resource).delete())
            val hit = requireNotNull(data.resource(resource))
            try { assertArrayEquals(bytes, hit) } finally { hit.fill(0) }
            inst.runOnMainSync { session.pause() }
            assertNull(data.resource(resource))
        } finally { data.clearMemory(); bytes.fill(0) }
    }
}
