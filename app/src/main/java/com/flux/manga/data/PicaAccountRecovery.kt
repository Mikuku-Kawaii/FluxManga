package com.flux.manga.data

import android.content.Context
import com.flux.manga.core.SourceException
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class PicaLoginExpired : IOException("PicACG 登录已失效")
internal class PicaLoginRejected : IOException("自动登录未成功，请核对账号密码或在网页完成验证")
internal class PicaAutoLogin(val username: String, val password: String)
internal class PicaSavedSession(val account: PicaAccount, val login: PicaAutoLogin?)
internal data class PicaRecoveryState(val busy: Boolean = false, val notice: String? = null, val needsUser: Boolean = false)

// Manual connections get a new identity; token renewal preserves it. Old saved tokens need no migration.
internal fun picaConnectionId(json: JSONObject): String = json.optString("connectionId").takeIf { it.isNotBlank() }
    ?: MessageDigest.getInstance("SHA-256").digest(json.getString("token").toByteArray()).joinToString("") { "%02x".format(it) }

internal fun picaSavedSession(json: JSONObject): PicaSavedSession {
    val account = PicaAccount(json.getString("uid"), json.getString("name"), json.getString("token"), picaConnectionId(json))
    require(picaId(account.uid) && account.token.isNotBlank())
    val login = json.optJSONObject("autoLogin")?.let { PicaAutoLogin(it.getString("username"), it.getString("password")) }
    return PicaSavedSession(account, login)
}

/** One recovery per private world session. API reads share the result; no image/login/write replay. */
internal class PicaAccountRecovery(context: Context, private val session: WorldSession, owner: CoroutineScope) : Closeable {
    private val identity = session.identity.value
    private val store = PicaStore(context.applicationContext, session)
    private val lifetime = SupervisorJob(owner.coroutineContext[Job])
    private val scope = CoroutineScope(owner.coroutineContext + lifetime)
    private val lock = Mutex()
    private val revision = AtomicLong()
    private var pending: Deferred<PicaAccount>? = null
    private var nextAttempt = 0L
    private var blockedConnection: String? = null
    private val mutable = MutableStateFlow(PicaRecoveryState())
    val state = mutable.asStateFlow()
    private fun active() = lifetime.isActive && identity.world == PrototypeWorld.NSFW && session.accepts(identity) && session.state.value.foreground
    private fun allowed(ticket: Long) = active() && revision.get() == ticket
    private fun check(ticket: Long) { if (!allowed(ticket)) throw CancellationException() }
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private fun same(current: PicaAccount?, expected: PicaAccount): PicaAccount {
        if (current == null || current.uid != expected.uid || current.connectionId != expected.connectionId)
            throw IOException("PicACG 账号连接已变化，请返回后重新进入")
        return current
    }
    suspend fun current(expected: PicaAccount): PicaAccount {
        if (!active()) throw CancellationException()
        return same(store.account(), expected)
    }
    suspend fun activate(account: PicaAccount) {
        val ticket = revision.incrementAndGet()
        synchronized(this) { pending?.cancel(); pending = null }
        lock.withLock {
            check(ticket)
            val old = store.saved()
            val login = old?.login.takeIf { old?.account?.uid == account.uid }
            store.save(PicaSavedSession(account.copy(connectionId = UUID.randomUUID().toString()), login)) { allowed(ticket) }
            nextAttempt = 0; blockedConnection = null; mutable.value = PicaRecoveryState()
        }
    }
    suspend fun configure(username: String, password: String) {
        val ticket = revision.incrementAndGet()
        synchronized(this) { pending?.cancel(); pending = null }
        lock.withLock {
            check(ticket)
            val saved = store.saved() ?: throw IOException("请先网页登录 PicACG")
            val login = PicaAutoLogin(username.trim(), password)
            val verified = PicaClient(session, null, scope).use { it.signIn(login) }
            check(ticket)
            if (verified.uid != saved.account.uid) throw IOException("填写的不是当前 PicACG 账号，未保存自动登录设置")
            store.save(PicaSavedSession(verified.copy(connectionId = saved.account.connectionId), login)) { allowed(ticket) }
            nextAttempt = 0; blockedConnection = null; mutable.value = PicaRecoveryState()
        }
    }
    suspend fun forget(logout: Boolean) {
        val ticket = revision.incrementAndGet()
        synchronized(this) { pending?.cancel(); pending = null }
        lock.withLock {
            check(ticket)
            val saved = store.saved()
            store.save(if (logout) null else saved?.let { PicaSavedSession(it.account, null) }) { allowed(ticket) }
            nextAttempt = 0; blockedConnection = null; mutable.value = PicaRecoveryState()
        }
    }
    suspend fun recover(expected: PicaAccount): PicaAccount {
        if (!active()) throw CancellationException()
        val task = synchronized(this) {
            pending?.takeIf { it.isActive } ?: scope.async { renew(expected) }.also { pending = it }
        }
        val renewed = task.await()
        same(renewed, expected)
        return current(expected)
    }
    private suspend fun renew(expected: PicaAccount): PicaAccount = lock.withLock {
        val ticket = revision.get()
        check(ticket)
        val saved = store.saved() ?: throw IOException("PicACG 账号已退出")
        val current = same(saved.account, expected)
        if (current.token != expected.token) return@withLock current
        if (blockedConnection == current.connectionId) throw IOException(mutable.value.notice ?: "请重新设置 PicACG 自动登录")
        val login = saved.login ?: throw IOException("PicACG 登录已过期，请在图源管理中设置自动登录")
        if (now() < nextAttempt) throw IOException("PicACG 自动重登暂未成功，请稍后刷新重试")
        nextAttempt = now() + 60_000
        mutable.value = PicaRecoveryState(busy = true)
        try {
            val account = withTimeout(45_000) { PicaClient(session, null, scope).use { it.signIn(login) } }
            check(ticket)
            if (account.uid != current.uid) throw PicaLoginRejected()
            val renewed = account.copy(connectionId = current.connectionId)
            store.save(PicaSavedSession(renewed, login)) { allowed(ticket) }
            mutable.value = PicaRecoveryState()
            renewed
        } catch (timeout: TimeoutCancellationException) {
            if (allowed(ticket)) mutable.value = PicaRecoveryState(notice = "PicACG 自动重登超时，稍后刷新可重试")
            throw IOException("PicACG 自动重登超时，稍后刷新可重试")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val needsUser = error is PicaLoginRejected || error is PicaLoginExpired || error is SourceException.AuthenticationRequired ||
                (error is SourceException.HttpStatus && error.status.toInt() in setOf(400, 401, 403, 422))
            if (allowed(ticket)) {
                if (needsUser) blockedConnection = current.connectionId
                mutable.value = PicaRecoveryState(notice = if (needsUser) "自动登录未成功，请核对账号密码或在网页完成验证" else picaFailure(error), needsUser = needsUser)
            }
            throw error
        } finally {
            if (revision.get() == ticket) mutable.value = mutable.value.copy(busy = false)
        }
    }
    suspend fun rejected(account: PicaAccount) = lock.withLock {
        if (active() && store.account() == account) {
            blockedConnection = account.connectionId
            mutable.value = PicaRecoveryState(notice = "重新登录后仍被拒绝，请在网页检查账号", needsUser = true)
        }
    }
    override fun close() { revision.incrementAndGet(); lifetime.cancel() }
}
