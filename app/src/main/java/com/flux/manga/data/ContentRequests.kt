package com.flux.manga.data

import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class ContentPriority { READER, DETAIL, NEIGHBOR, COVER, CHAPTER, REFRESH, PRELOAD }
internal class ContentPriorityContext(val priority: ContentPriority) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ContentPriorityContext>
}

/** Network work only. Disk cache hits never queue here; running transports keep their cancellation contract. */
internal object ContentRequests {
    private class Lease : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Lease> }
    private data class Waiting(val source: String, val priority: ContentPriority, val ready: CompletableDeferred<Unit>)
    private val lock = Any()
    private val waiting = mutableListOf<Waiting>()
    private val running = mutableListOf<Waiting>()
    private fun Waiting.background() = priority >= ContentPriority.CHAPTER
    private fun drain() {
        while (running.size < 4) {
            val next = waiting.filter { candidate ->
                running.count { it.source == candidate.source } < 2 &&
                    (!candidate.background() || (running.count { it.background() } < 2 &&
                        running.none { it.source == candidate.source && it.background() }))
            }.minWithOrNull(compareBy<Waiting> { it.priority.ordinal }
                .thenBy { candidate -> running.count { it.source == candidate.source } }) ?: return
            waiting.remove(next)
            running += next
            next.ready.complete(Unit)
        }
    }
    suspend fun <T> run(source: String, default: ContentPriority, block: suspend () -> T): T {
        if (currentCoroutineContext()[Lease] != null) return block()
        val provider = SourceKind.of(source)?.takeUnless { it == SourceKind.KOMGA }?.name ?: source
        val ticket = Waiting(provider, currentCoroutineContext()[ContentPriorityContext]?.priority ?: default, CompletableDeferred())
        synchronized(lock) { waiting += ticket; drain() }
        try { ticket.ready.await(); currentCoroutineContext().ensureActive(); return withContext(Lease()) { block() } }
        finally { synchronized(lock) {
            if (!waiting.remove(ticket)) running.remove(ticket)
            drain()
        } }
    }
}
