package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WorldBaoziAccountNetwork(private val session: WorldSession) : BaoziAccountNetwork {
    private val identity = session.identity.value
    private val requestLock = Mutex()
    private var nextStart = 0L
    private suspend fun <T> request(source: String, token: String?, browserSession: BaoziBrowserSession? = null,
        block: suspend (BaoziAccountClient, SourceContext) -> T): T = withContext(Dispatchers.IO) {
        requestLock.withLock {
            ensureActive(); if (!session.accepts(identity)) throw CancellationException()
            delay(((nextStart - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
            ensureActive(); if (!session.accepts(identity)) throw CancellationException()
            nextStart = System.nanoTime() + 500_000_000
            val context = identity.sourceContext(source, "$source:account")
            val config = SourceHttpConfig(15_000u, 1u, 500u, 1u, 2u * 1024u * 1024u)
            val client = if (browserSession == null) BaoziAccountClient(context, token, config)
                else BaoziAccountClient.withBrowserSession(context, requireNotNull(token), browserSession.cookie,
                    browserSession.userAgent, browserSession.origin, config)
            try {
                block(client, context).also { ensureActive(); if (!session.accepts(identity)) throw CancellationException() }
            } finally { client.invalidate(); client.destroy() }
        }
    }
    override suspend fun signIn(source: String, username: String, password: String): String = request(source, null) { client, context ->
        client.signIn(context, username, password)
    }
    override suspend fun shelf(source: String, token: String, accepts: () -> Boolean): BaoziShelfSnapshot = request(source, token) { client, context ->
        BaoziAccountSource(context, accepts) { client.bookshelf(context) }.bookshelf()
    }
    override suspend fun shelf(account: BaoziSavedAccount, accepts: () -> Boolean): BaoziShelfSnapshot =
        request(account.sourceId, account.token, account.browserSession) { client, context ->
            BaoziAccountSource(context, accepts) { client.bookshelf(context) }.bookshelf()
        }
}
