package com.flux.manga

import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.storage.BoundedResourceInput
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Compile-only this round. Writes exclusively to a fresh synthetic namespace if later authorized. */
class PrivateLargeResourceTest {
    @Test fun largeEncryptedResourceReopensWithoutEvictingSmallMemoryEntry(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "m4-large-${UUID.randomUUID()}"
        val data = WorldDataStore(context, namespace)
        val small = ResourceKey(SampleKey(PrototypeWorld.NSFW, "generated", "book"), "small")
        val large = small.copy(resourceId = "large")
        val payload = ByteArray(5 * 1024 * 1024) { (it * 13).toByte() }
        data.putResource(small, byteArrayOf(1, 2, 3))
        val originalSmall = data.cacheFile(small).readBytes()
        try {
            data.putResource(large, payload)
            // If large insertion evicts the small memory entry, this would fail authentication.
            data.cacheFile(small).writeBytes(byteArrayOf(0))
            assertArrayEquals(byteArrayOf(1, 2, 3), data.resource(small))
        } finally { data.cacheFile(small).writeBytes(originalSmall); data.clearMemory() }
        val reopened = WorldDataStore(context, namespace)
        val bytes = requireNotNull(reopened.resource(large))
        try { assertArrayEquals(payload, bytes) } finally { bytes.fill(0); payload.fill(0) }
        assertEquals(5L * 1024 * 1024 + BoundedResourceInput.ENVELOPE_BYTES, data.cacheFile(large).length())
        assertArrayEquals(originalSmall, data.cacheFile(small).readBytes())
        data.clearMemory()
    }
}
