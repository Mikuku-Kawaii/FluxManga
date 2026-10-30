package com.flux.manga.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class ReaderStorageFullException : java.io.IOException("存储空间不足，预加载已暂停；已缓存内容保留，请释放空间后重试")

/** One private reader owns its encrypted page files; covers and offline downloads are separate. */
internal class ReaderEncodedCache(private val data: WorldDataStore, private val sessionId: String = UUID.randomUUID().toString()) : PrivateEncodedCache {
    init { require(sessionId.matches(Regex("[0-9a-f-]{36}"))) }
    private val prefix = "reader-session:$sessionId:"
    private val worlds = mutableSetOf<com.flux.manga.ui.navigation.PrototypeWorld>()
    private val lock = Mutex()
    private var closed = false
    private fun owned(key: ResourceKey) = key.copy(resourceId = prefix + key.resourceId)
    override suspend fun read(key: ResourceKey, allowed: () -> Boolean): ByteArray? = lock.withLock {
        if (closed) throw CancellationException()
        worlds += key.manga.world
        data.prepareReaderResources(key.manga.world, sessionId)
        data.resource(owned(key), allowed)
    }
    override suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean) = lock.withLock {
        if (closed) throw CancellationException()
        worlds += key.manga.world
        data.prepareReaderResources(key.manga.world, sessionId)
        val target = owned(key)
        data.putResource(target, bytes, allowed)
    }
    override fun clearMemory() = data.clearMemory()
    suspend fun publicFile(key: ResourceKey, allowed: () -> Boolean): java.io.File? = lock.withLock {
        require(key.manga.world == com.flux.manga.ui.navigation.PrototypeWorld.SFW)
        if (closed || !allowed()) throw CancellationException()
        worlds += key.manga.world
        data.prepareReaderResources(key.manga.world, sessionId)
        data.cacheFile(owned(key)).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }
    }
    suspend fun remove(key: ResourceKey) = lock.withLock { data.removeReaderResource(owned(key)) }
    override suspend fun remove(key: ResourceKey, allowed: () -> Boolean) {
        if (!allowed()) throw CancellationException()
        remove(key)
    }
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            closed = true
            worlds.forEach { data.closeReaderResources(it, sessionId) }
            worlds.clear()
        }
    }
    suspend fun json(owner: SampleKey, slot: String, allowed: () -> Boolean): org.json.JSONObject? {
        val bytes = readDisplayCache { read(ResourceKey(owner, "metadata:$slot"), allowed) } ?: return null
        return try { readDisplayCache { org.json.JSONObject(bytes.toString(Charsets.UTF_8)) } } finally { bytes.fill(0) }
    }
    suspend fun <T> decode(owner: SampleKey, slot: String, allowed: () -> Boolean, parse: (org.json.JSONObject) -> T): T? =
        json(owner, slot, allowed)?.let { value -> readDisplayCache { parse(value) } }
    suspend fun json(owner: SampleKey, slot: String, value: org.json.JSONObject, allowed: () -> Boolean) {
        val bytes = value.toString().toByteArray()
        try { write(ResourceKey(owner, "metadata:$slot"), bytes, allowed) } finally { bytes.fill(0) }
    }
}
