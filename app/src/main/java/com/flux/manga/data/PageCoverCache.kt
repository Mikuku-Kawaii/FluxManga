package com.flux.manga.data

import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Decoded previews only. Session-owned requests survive cell/Tab disposal; private memory is revoked on background. */
internal class PageCoverCache(private val scope: CoroutineScope, private val active: () -> Boolean) {
    private val lock = Any()
    private var epoch = 0L
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val capacity = DisplayBitmapStore.capacity
    private val images = DisplayBitmapStore<Pair<String, String>>()
    private val pending = mutableMapOf<Pair<String, String>, Deferred<Bitmap?>>()
    private val priorities = mutableMapOf<Pair<String, String>, ContentPriority>()
    private val failures = object : LinkedHashMap<Pair<String, String>, Pair<Long, Exception>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Pair<Long, Exception>>?) = size > 128
    }
    fun retry(source: String, manga: String) = synchronized(lock) { failures.remove(source to manga); Unit }
    private val diskPermits = Semaphore(4)

    /** Read during initial composition so a returning cell never starts with a blank frame. */
    fun peek(source: String, manga: String): Bitmap? = synchronized(lock) {
        if (active()) images.get(source to manga) else null
    }

    suspend fun get(source: String, manga: String, readCached: (suspend () -> Bitmap?)? = null,
        load: suspend () -> Bitmap): Bitmap =
        requireNotNull(getOrNull(source, manga, readCached, load))

    suspend fun getOrNull(source: String, manga: String, readCached: (suspend () -> Bitmap?)? = null,
        load: suspend () -> Bitmap?): Bitmap? {
        currentCoroutineContext().ensureActive()
        if (!active()) throw CancellationException()
        val key = source to manga
        val priority = currentCoroutineContext()[ContentPriorityContext]?.priority ?: ContentPriority.COVER
        val task = synchronized(lock) {
            images.get(key)?.let { return it }
            failures[key]?.let { (until, failure) ->
                if (android.os.SystemClock.elapsedRealtime() < until) throw failure else failures.remove(key)
            }
            if (priority < (priorities[key] ?: priority)) pending.remove(key)?.cancel()
            // A visible cell promotes a queued warm-up instead of waiting at background priority.
            pending[key]?.takeUnless { it.isCancelled } ?: run {
                val generation = epoch
                val created = scope.async(ContentPriorityContext(priority), start = CoroutineStart.LAZY) {
                    try {
                    // Disk hits must not queue behind slow cover downloads from other sources.
                    val cached = readCached?.let { diskPermits.withPermit { readDisplayCache { it() } } }
                    val bitmap = cached ?: ContentRequests.run(source, ContentPriority.COVER) { load() }
                    ensureActive()
                    synchronized(lock) {
                        ensureActive()
                        if (generation != epoch || !active()) throw CancellationException()
                        if (bitmap != null && bitmap.allocationByteCount <= capacity) images.put(key, bitmap)
                    }
                    bitmap
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        ensureActive()
                        synchronized(lock) { ensureActive(); if (generation == epoch && active()) failures[key] =
                            (android.os.SystemClock.elapsedRealtime() + 30_000L) to failure }
                        throw failure
                    }
                }
                pending[key] = created
                priorities[key] = priority
                created.invokeOnCompletion { synchronized(lock) { if (pending[key] === created) { pending.remove(key); priorities.remove(key) } } }
                created
            }
        }
        task.start()
        return task.await().also { currentCoroutineContext().ensureActive(); if (!active()) throw CancellationException() }
    }

    fun remove(source: String, manga: String) = synchronized(lock) {
        val key = source to manga
        pending.remove(key)?.cancel()
        priorities.remove(key)
        failures.remove(key)
        images.remove(key)
        changes.value++
        Unit
    }

    fun clear() = synchronized(lock) {
        pause(retainImages = false)
    }

    fun pause(retainImages: Boolean) = synchronized(lock) {
        epoch++
        val jobs = pending.values.toList()
        pending.clear()
        priorities.clear()
        failures.clear()
        jobs.forEach { it.cancel() }
        if (!retainImages) { images.evictAll(); changes.value++ }
    }
}
