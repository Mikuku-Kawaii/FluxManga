package com.flux.manga.storage

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Checks belong inside Room's transaction callback, before it is allowed to commit. */
internal class StorageOperation(private val caller: CoroutineContext, private val allowed: () -> Boolean) {
    fun check() {
        caller.ensureActive()
        if (!allowed()) throw CancellationException("Expired storage session")
    }

    fun <T> beforeCommit(action: () -> T): T {
        check()
        return action().also { check() }
    }
}

/** Retain immediately after decryption, before DB close or a cancellable dispatcher return. */
internal suspend fun readOwnedSecret(read: suspend (retain: (ByteArray) -> ByteArray) -> ByteArray?): ByteArray? {
    var pending: ByteArray? = null
    try {
        return read { bytes ->
            check(pending == null) { "A secret read owns one result" }
            bytes.also { pending = it }
        }
    } catch (failure: Throwable) {
        pending?.fill(0)
        throw failure
    }
}
