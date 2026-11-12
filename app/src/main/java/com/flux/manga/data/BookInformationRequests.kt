package com.flux.manga.data

import kotlinx.coroutines.*

/** Generation-owned in-flight metadata only; completed results stay in the existing store. */
internal class BookInformationRequests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Pending(val task: Deferred<BookInformationResult>, var readers: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    suspend fun read(key: String, load: suspend () -> BookInformationResult): BookInformationResult {
        val entry = synchronized(pending) {
            pending.getOrPut(key) { Pending(scope.async(start = CoroutineStart.LAZY) { load() }) }.also { it.readers++ }
        }
        try { return entry.task.await() }
        finally { synchronized(pending) {
            if (--entry.readers == 0) {
                if (pending[key] === entry) pending.remove(key)
                entry.task.cancel()
            }
        } }
    }
    fun clear() = synchronized(pending) { pending.values.forEach { it.task.cancel() }; pending.clear() }
    fun close() { clear(); scope.cancel() }
}
