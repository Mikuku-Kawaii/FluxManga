package com.flux.manga.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One private reader owns its encrypted page files; covers and offline downloads are separate. */
internal class ReaderEncodedCache(private val data: WorldDataStore) : PrivateEncodedCache {
    private val prefix = "reader-session:${UUID.randomUUID()}:"
    private val keys = mutableSetOf<ResourceKey>()
    private val lock = Mutex()
    private var closed = false
    private fun owned(key: ResourceKey) = key.copy(resourceId = prefix + key.resourceId)
    override suspend fun read(key: ResourceKey, allowed: () -> Boolean): ByteArray? = lock.withLock {
        if (closed) throw CancellationException()
        data.prepareReaderResources(key.manga.world)
        data.resource(owned(key), allowed)
    }
    override suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean) = lock.withLock {
        if (closed) throw CancellationException()
        data.prepareReaderResources(key.manga.world)
        val target = owned(key)
        keys += target
        data.putResource(target, bytes, allowed)
    }
    override fun clearMemory() = data.clearMemory()
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            closed = true
            keys.forEach { data.removeReaderResource(it) }
            keys.clear()
        }
    }
}
