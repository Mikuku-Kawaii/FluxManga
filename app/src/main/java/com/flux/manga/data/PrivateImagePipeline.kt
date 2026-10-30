package com.flux.manga.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import com.flux.manga.storage.BoundedResourceInput

/** These buffers are owned by the caller. Cache implementations must copy/seal on write. */
internal interface PrivateEncodedCache {
    suspend fun read(key: ResourceKey, allowed: () -> Boolean): ByteArray?
    suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean)
    suspend fun remove(key: ResourceKey, allowed: () -> Boolean) { }
    fun clearMemory()
}
internal fun interface PrivateImageTransport {
    suspend fun open(key: ResourceKey): InputStream
}

/** No plaintext files, global session, URL resolver or source-admission exception. */
internal class PrivateImagePipeline<E>(
    private val owner: SampleKey,
    private val cache: PrivateEncodedCache,
    private val transport: PrivateImageTransport,
    private val epoch: () -> E,
    private val active: (E) -> Boolean,
) {
    private val locks = java.util.concurrent.ConcurrentHashMap<ResourceKey, Mutex>()
    companion object { const val MAX_BYTES = BoundedResourceInput.PRIVATE_BYTES }

    suspend fun <T> decode(key: ResourceKey, decode: (ByteArray) -> T, release: (T) -> Unit): T {
        require(key.manga == owner) { "Foreign image identity" }
        val ticket = epoch()
        val caller = currentCoroutineContext()
        val allowed = { caller.isActive && active(ticket) }
        var owned: T? = null
        fun check() {
            caller.ensureActive()
            if (!active(ticket)) throw CancellationException("Expired private image request")
        }
        suspend fun cached(): Boolean {
            val bytes = readDisplayCache { cache.read(key, allowed) } ?: return false
            try {
                check()
                try {
                    if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw IOException("图片缓存容量无效")
                    owned = decode(bytes)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: IOException) {
                    cache.remove(key, allowed)
                    android.util.Log.w("FluxCache", "Invalid encoded image removed; fetching again")
                    return false
                }
                check()
                return true
            } finally { bytes.fill(0); if (!allowed()) cache.clearMemory() }
        }
        try {
            return withContext(Dispatchers.IO) {
                check()
                if (cached()) return@withContext requireNotNull(owned)
                locks.getOrPut(key) { Mutex() }.withLock {
                    check()
                    if (!cached()) {
                        val bytes = ContentRequests.run(owner.sourceId, ContentPriority.READER) {
                            transport.open(key).use { input -> BoundedResourceInput.read(input, MAX_BYTES, ::check) }
                        }
                        try {
                            if (bytes.isEmpty()) throw IOException("图片为空，请重试")
                            check()
                            owned = decode(bytes)
                            check()
                            // Reader/offline writes remain strict; only disposable cover caches are optional.
                            cache.write(key, bytes, allowed)
                        } finally { bytes.fill(0); if (!allowed()) cache.clearMemory() }
                    }
                    check()
                    requireNotNull(owned)
                }
            }
        } catch (failure: Throwable) {
            owned?.let(release)
            if (!allowed()) throw CancellationException("Expired private image request").also { it.initCause(failure) }
            throw failure
        }
    }
}
