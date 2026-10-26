package com.flux.manga.data

import com.flux.manga.core.SourceHttpConfig
import com.flux.manga.core.SourceHttpReply
import com.flux.manga.core.SourceHttpSession
import com.flux.manga.session.WorldSession
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One source in one entry lifetime. Metadata and image requests share this instance.
 * No credential persistence, online UI, or automatic source registry is introduced here. */
internal class WorldSourceHttpClient(
    private val session: WorldSession,
    owner: CoroutineScope,
    sourceId: String,
    baseUrl: String,
    authRef: String? = null,
    authorization: String? = null,
    cookie: String? = null,
    config: SourceHttpConfig = SourceHttpConfig(15_000u, 2u, 100u, 2u, 4u * 1024u * 1024u),
) : Closeable {
    private val identity = session.identity.value
    private val context = identity.sourceContext(sourceId, authRef)
    private val closed = AtomicBoolean()
    private val ownerJob = requireNotNull(owner.coroutineContext[Job])
    private val native = SourceHttpSession(context, baseUrl, authorization, cookie, config)
    private val lifetime = Job(ownerJob)

    init {
        lifetime.invokeOnCompletion {
            if (closed.compareAndSet(false, true)) {
                native.invalidate()
                native.destroy()
            }
        }
        CoroutineScope(owner.coroutineContext + lifetime).launch {
            combine(session.identity, session.state) { _, _ -> session.accepts(identity) }
                .collect { accepted -> if (!accepted) close() }
        }
    }

    private fun active() {
        if (closed.get() || !session.accepts(identity)) throw CancellationException("Expired source session")
    }

    suspend fun get(relativePath: String): SourceHttpReply {
        currentCoroutineContext().ensureActive()
        active()
        val reply = try { native.get(context, relativePath) } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            active() // Convert invalidation races to cancellation, not an error in a new world.
            throw failure
        }
        currentCoroutineContext().ensureActive()
        active()
        check(reply.context == context) { "Source response context mismatch" }
        return reply
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            native.invalidate()
            native.destroy()
        }
        lifetime.cancel()
    }
}
