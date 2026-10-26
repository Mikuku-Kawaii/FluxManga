package com.flux.manga.data

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Decoded previews only. Shared requests survive Tab disposal, never a session/background boundary. */
internal class PageCoverCache(private val scope: CoroutineScope, private val active: () -> Boolean) {
    private val lock = Any()
    private var epoch = 0L
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val capacity = 32 * 1024 * 1024
    private val images = object : LruCache<Pair<String, String>, Bitmap>(capacity) {
        override fun sizeOf(key: Pair<String, String>, value: Bitmap) = value.allocationByteCount
        // Do not recycle: a visible composable may still own the evicted bitmap.
    }
    private val pending = mutableMapOf<Pair<String, String>, Deferred<Bitmap?>>()
    private val permits = Semaphore(2)

    /** Read during initial composition so a returning cell never starts with a blank frame. */
    fun peek(source: String, manga: String): Bitmap? = synchronized(lock) {
        if (active()) images.get(source to manga) else null
    }

    suspend fun get(source: String, manga: String, load: suspend () -> Bitmap): Bitmap =
        requireNotNull(getOrNull(source, manga, load))

    suspend fun getOrNull(source: String, manga: String, load: suspend () -> Bitmap?): Bitmap? {
        currentCoroutineContext().ensureActive()
        if (!active()) throw CancellationException()
        val key = source to manga
        val task = synchronized(lock) {
            images.get(key)?.let { return it }
            pending[key] ?: run {
                val generation = epoch
                val created = scope.async(start = CoroutineStart.LAZY) {
                    val bitmap = permits.withPermit { load() }
                    ensureActive()
                    synchronized(lock) {
                        ensureActive()
                        if (generation != epoch || !active()) throw CancellationException()
                        if (bitmap != null && bitmap.allocationByteCount <= capacity) images.put(key, bitmap)
                    }
                    bitmap
                }
                pending[key] = created
                created.invokeOnCompletion { synchronized(lock) { if (pending[key] === created) pending.remove(key) } }
                created
            }
        }
        task.start()
        return task.await().also { currentCoroutineContext().ensureActive(); if (!active()) throw CancellationException() }
    }

    fun remove(source: String, manga: String) = synchronized(lock) {
        val key = source to manga
        pending.remove(key)?.cancel()
        images.remove(key)
        changes.value++
        Unit
    }

    fun clear() = synchronized(lock) {
        epoch++
        val jobs = pending.values.toList()
        pending.clear(); images.evictAll()
        jobs.forEach { it.cancel() }
        changes.value++
    }
}
