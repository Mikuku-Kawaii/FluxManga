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
    private val lock = Mutex()
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
        try {
            return withContext(Dispatchers.IO) {
                // Cached pages must not wait behind an unrelated speculative network request.
                check()
                val cached = cache.read(key, allowed)
                if (cached != null) {
                    try {
                        check()
                        if (cached.isEmpty() || cached.size > MAX_BYTES) throw IOException("图片缓存容量无效")
                        owned = decode(cached)
                        check()
                        return@withContext requireNotNull(owned)
                    } finally {
                        cached.fill(0)
                        if (!allowed()) cache.clearMemory()
                    }
                }
                lock.withLock {
                    check()
                    var bytes: ByteArray? = null
                    try {
                        bytes = cache.read(key, allowed)
                        check()
                        if (bytes == null) {
                            bytes = transport.open(key).use { input ->
                                BoundedResourceInput.read(input, MAX_BYTES, ::check)
                            }
                            if (bytes.isEmpty()) throw IOException("图片为空，请重试")
                            check()
                            // Validate/decode before committing a corrupt or unsupported image.
                            owned = decode(requireNotNull(bytes))
                            check()
                            cache.write(key, requireNotNull(bytes), allowed)
                        } else {
                            if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw IOException("图片缓存容量无效")
                            owned = decode(bytes)
                        }
                        check()
                        requireNotNull(owned)
                    } finally {
                        bytes?.fill(0)
                        // A paused request may have been inside a cache operation when the
                        // session cleared memory. Do not let that operation repopulate it.
                        if (!allowed()) cache.clearMemory()
                    }
                }
            }
        } catch (failure: Throwable) {
            owned?.let(release)
            if (!allowed()) throw CancellationException("Expired private image request").also { it.initCause(failure) }
            throw failure
        }
    }
}
