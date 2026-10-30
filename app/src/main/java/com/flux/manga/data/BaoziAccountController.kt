package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface BaoziAccountNetwork {
    suspend fun signIn(source: String, username: String, password: String): String
    suspend fun shelf(source: String, token: String, accepts: () -> Boolean): BaoziShelfSnapshot
    suspend fun shelf(account: BaoziSavedAccount, accepts: () -> Boolean): BaoziShelfSnapshot =
        shelf(account.sourceId, requireNotNull(account.token), accepts)
}
internal data class BaoziAccountUiState(val loaded: Boolean = false, val sourceId: String? = null,
    val username: String = "", val hasCredential: Boolean = false, val verified: Boolean = false,
    val busy: Boolean = false, val stage: String? = null, val error: String? = null, val notice: String? = null,
    val entries: List<BaoziShelfEntry> = emptyList(), val snapshotRevision: Long = 0,
    val importJob: BaoziImportJob? = null, val reconnectRequested: Boolean = false,
    val autoLoginAvailable: Boolean = false, val recoveryNotice: String? = null, val recoveryNeedsUser: Boolean = false,
    val recoveryVersion: Long = 0)

internal class BaoziVerificationRequired : java.io.IOException("网站验证已过期")
internal class BaoziLoginExpired : java.io.IOException("账号登录已过期")
internal class BaoziLoginRejected : java.io.IOException("自动登录未成功，请核对密码或使用官网登录")

/** One WorldSession owner. Secrets never enter UI state, routes, SavedStateHandle or logs. */
internal class BaoziAccountController(private val scope: CoroutineScope, private val active: () -> Boolean,
    private val store: BaoziAccountStore, private val network: BaoziAccountNetwork,
    private val credentialsChanged: () -> Unit = {}, private val clearBrowserSession: suspend () -> Unit = {}) {
    private val mutable = MutableStateFlow(BaoziAccountUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    @Volatile private var revision = 0L
    @Volatile private var closed = false
    private var recovery: CompletableDeferred<Boolean>? = null
    private var recoveryCheck: Job? = null
    private var retrySignal: Job? = null
    private var recoveryRequired = false
    private var nextRecoveryAt = 0L
    private fun now() = System.nanoTime() / 1_000_000
    private fun allowed(ticket: Long) = !closed && active() && ticket == revision
    private fun publish(account: BaoziSavedAccount?) {
        if (account?.sourceId != state.value.sourceId || account?.token == null) {
            mutable.value = state.value.copy(entries = emptyList())
        }
        mutable.value = state.value.copy(loaded = true, sourceId = account?.sourceId, username = account?.username.orEmpty(),
            hasCredential = account?.token != null, importJob = account?.job,
            autoLoginAvailable = account?.autoLogin != null)
    }
    private fun run(stage: String, block: suspend (Long) -> Unit) {
        if (state.value.busy || closed || !active()) return
        val ticket = ++revision
        mutable.value = state.value.copy(busy = true, stage = stage, error = null, notice = null)
        work = scope.launch {
            try { block(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (allowed(ticket)) mutable.value = state.value.copy(error = "${state.value.stage?.removePrefix("正在") ?: stage.removePrefix("正在")}：${failureMessage(failure)}",
                    verified = false, entries = emptyList(), loaded = if (failure is java.io.IOException) false else state.value.loaded)
                if (allowed(ticket) && failure.needsBaoziReconnect() && state.value.hasCredential)
                    requestReconnect()
            } finally {
                if (allowed(ticket)) mutable.value = state.value.copy(busy = false, stage = null)
            }
        }
    }
    private suspend fun snapshot(account: BaoziSavedAccount, ticket: Long): BaoziShelfSnapshot {
        if (account.token == null) throw SourceException.AuthenticationRequired()
        if (allowed(ticket)) mutable.value = state.value.copy(stage = "正在验证书架会话")
        val result = network.shelf(account) { allowed(ticket) }
        currentCoroutineContext().ensureActive()
        if (!allowed(ticket)) throw CancellationException()
        if (result.evidence != BaoziShelfEvidence.CONTENT_PRESENT || result.entries.isEmpty()) throw UnverifiedShelf()
        require(result.entries.all { it.card.key.sourceId == account.sourceId })
        return result
    }
    private fun showSnapshot(result: BaoziShelfSnapshot) {
        mutable.value = state.value.copy(verified = true, entries = result.entries,
            snapshotRevision = state.value.snapshotRevision + 1)
    }
    fun start() = run("正在读取并验证账号") { ticket ->
        val account = store.current { allowed(ticket) }
        currentCoroutineContext().ensureActive(); if (!allowed(ticket)) return@run
        publish(account)
        mutable.value = state.value.copy(verified = false)
        if (account?.token != null) showSnapshot(snapshot(account, ticket))
    }
    fun signIn(username: String, password: String) {
        if (state.value.busy) return
        if (!state.value.loaded || username.trim().isEmpty() || username.trim().toByteArray().size > 256 ||
            password.isEmpty() || password.toByteArray().size > 4096) {
            mutable.value = state.value.copy(error = "请先读取账号状态，并填写有效的账号和密码")
            return
        }
        run("正在提交账号登录") { ticket ->
            val name = username.trim(); val source = baoziAccountId(name)
            // UI rejects another submit while this candidate is pending; old saved state is kept on failure.
            mutable.value = state.value.copy(verified = false, entries = emptyList())
            val token = network.signIn(source, name, password)
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) return@run
            val candidate = BaoziSavedAccount(source, name, token)
            val result = snapshot(candidate, ticket)
            mutable.value = state.value.copy(stage = "正在保存已验证账号")
            val saved = store.activate(source, name, token) { allowed(ticket) }
            credentialsChanged()
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) return@run
            publish(saved); showSnapshot(result)
            mutable.value = state.value.copy(notice = "已登录，读取到 ${result.entries.size} 本。")
        }
    }
    suspend fun connectWebSession(username: String, token: String, browserSession: BaoziBrowserSession? = null,
        autoLogin: BaoziAutoLogin? = null, recoveryOnly: Boolean = false) {
        val name = username.trim()
        if (state.value.busy || closed || !active()) throw java.io.IOException("请等待账号读取完成")
        if (name.isEmpty()) throw java.io.IOException("尚未取得官网登录标识，请在官网完成邮箱登录后重试")
        if (token.isEmpty()) throw java.io.IOException("请先完成网页登录，再连接书架")
        val ticket = ++revision
        mutable.value = state.value.copy(busy = true, error = null, notice = null)
        try {
            val source = baoziAccountId(name)
            if (recoveryOnly) {
                val existing = store.current { allowed(ticket) }
                if (existing == null || existing.sourceId != source || existing.token == null) throw BaoziLoginRejected()
            }
            browserSession?.validate(token)
            val result = snapshot(BaoziSavedAccount(source, name, token, browserSession = browserSession), ticket)
            val saved = store.activate(source, name, token, browserSession, autoLogin) { allowed(ticket) }
            credentialsChanged()
            currentCoroutineContext().ensureActive()
            if (!allowed(ticket)) throw CancellationException()
            publish(saved); showSnapshot(result)
            recoveryRequired = false; nextRecoveryAt = 0L
            retrySignal?.cancel(); retrySignal = null
            mutable.value = state.value.copy(reconnectRequested = false, recoveryNotice = null, recoveryNeedsUser = false,
                recoveryVersion = state.value.recoveryVersion + 1)
            recovery?.complete(true); recovery = null
            mutable.value = state.value.copy(notice = "已登录，可导入书架。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (error is BaoziLoginRejected) throw error
            if (error is SourceException.HttpStatus && error.status.toInt() == 403) throw BaoziVerificationRequired()
            if (error is SourceException.AuthenticationRequired || error is SourceException.HttpStatus && error.status.toInt() == 401)
                throw BaoziLoginExpired()
            throw java.io.IOException(failureMessage(error))
        }
        finally { if (allowed(ticket)) mutable.value = state.value.copy(busy = false, stage = null) }
    }
    private fun requestReconnect() {
        recoveryRequired = true
        if (recovery != null || closed || !active() || state.value.recoveryNeedsUser || now() < nextRecoveryAt) return
        recovery = CompletableDeferred()
        mutable.value = state.value.copy(reconnectRequested = true, recoveryNotice = null)
    }
    /** Concurrent read requests await one browser restoration, then each replays its own read once. */
    suspend fun recoverAndAwait(): Boolean = withContext(Dispatchers.Main.immediate) {
        recoveryRequired = true
        if (closed || !active() || state.value.recoveryNeedsUser) return@withContext false
        var pending = recovery
        if (pending == null) {
            if (now() < nextRecoveryAt) { retryRecovery(); return@withContext false }
            pending = CompletableDeferred<Boolean>().also { recovery = it }
            val attempt = pending
            recoveryCheck = scope.launch {
                try {
                    state.first { !it.busy }
                    val account = store.current { !closed && active() && recovery === attempt }
                    ensureActive()
                    if (recovery !== attempt) return@launch
                    if (account?.token == null) {
                        reconnectFailed("请先完成一次包子网页登录。", requiresUser = true)
                    } else {
                        publish(account)
                        mutable.value = state.value.copy(reconnectRequested = true, recoveryNotice = null)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reconnectFailed("已保存会话暂时无法读取，原账号保留。") }
            }
        }
        val request = requireNotNull(pending)
        val result = withTimeoutOrNull(60_000) { request.await() }
        if (result == null && recovery === pending) reconnectFailed("自动恢复超时，请稍后重试。")
        result == true
    }

    fun recoverBrowserSession() {
        scope.launch { recoverAndAwait() }
    }
    /** Only real foreground/network/source-entry events schedule a cooled retry, never a failure loop. */
    fun retryRecovery() {
        if (!recoveryRequired || closed || !active() || state.value.recoveryNeedsUser || recovery != null || retrySignal?.isActive == true) return
        retrySignal = scope.launch {
            delay((nextRecoveryAt - now()).coerceAtLeast(0))
            recoverAndAwait()
        }
    }
    suspend fun savedWebAccount(): BaoziSavedAccount? {
        val ticket = revision
        return store.current { allowed(ticket) }?.takeIf { it.token != null }
    }
    fun dismissReconnect() {
        if (recovery != null) reconnectFailed("恢复已暂停，下次进入包子或网络恢复后重试。")
    }
    fun pauseRecovery() { retrySignal?.cancel(); retrySignal = null; dismissReconnect() }
    fun reconnectFailed(reason: String, requiresUser: Boolean = false) {
        if (closed || recovery == null) return
        recoveryCheck?.cancel(); recoveryCheck = null
        recoveryRequired = true; nextRecoveryAt = now() + 60_000
        mutable.value = state.value.copy(reconnectRequested = false, recoveryNotice = reason, recoveryNeedsUser = requiresUser)
        recovery?.complete(false); recovery = null
    }
    fun logout() {
        val source = state.value.sourceId ?: return
        run("正在退出登录") { ticket ->
            val saved = store.logout(source) { allowed(ticket) }
            recoveryRequired = false; retrySignal?.cancel(); recoveryCheck?.cancel()
            recovery?.complete(false); recovery = null
            mutable.value = state.value.copy(reconnectRequested = false)
            clearBrowserSession()
            credentialsChanged()
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) return@run
            publish(saved)
            mutable.value = state.value.copy(verified = false, entries = emptyList(), reconnectRequested = false, recoveryNotice = null,
                recoveryNeedsUser = false,
                notice = "已退出登录并删除自动登录凭据，书目、进度和待办导入任务已保留。")
        }
    }
    fun importSelected(ids: Set<String>, favorite: Boolean) {
        val current = state.value
        val source = current.sourceId ?: return
        if (!current.verified || ids.isEmpty() || ids.any { id -> current.entries.none { it.card.key.mangaId == id } }) return
        val items = current.entries.filter { it.card.key.mangaId in ids }.map { BaoziImportItem(it.card.key.mangaId, it.card.title) }
        run("正在保存选中书目") { ticket ->
            val saved = store.begin(source, items, favorite) { allowed(ticket) }
            importBatches(saved, ticket)
        }
    }
    fun resumeImport() {
        if (!state.value.verified) return
        val source = state.value.sourceId ?: return
        run("正在继续导入") { ticket ->
            val saved = store.current { allowed(ticket) } ?: throw SourceException.AuthenticationRequired()
            if (saved.sourceId != source) throw CancellationException()
            // Resuming the persisted selected snapshot, not silently replacing it with today's shelf.
            importBatches(saved, ticket)
        }
    }
    private suspend fun importBatches(initial: BaoziSavedAccount, ticket: Long) {
        var saved = initial
        while ((saved.job?.remaining ?: 0) > 0) {
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) throw CancellationException()
            saved = store.batch(saved.sourceId, requireNotNull(saved.job).id) { allowed(ticket) }
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) throw CancellationException()
            publish(saved); yield()
        }
        publish(saved)
        mutable.value = state.value.copy(notice = "选中书目已导入。")
    }
    fun finishTask() {
        val source = state.value.sourceId ?: return
        run("正在结束导入任务") { ticket ->
            val saved = store.finishTask(source) { allowed(ticket) }
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) return@run
            publish(saved); mutable.value = state.value.copy(notice = "任务已结束，已导入书目和进度保留。")
        }
    }
    fun cancel() {
        leave()
        run("正在核对已提交进度") { ticket ->
            val saved = store.current { allowed(ticket) }
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) return@run
            publish(saved)
            mutable.value = state.value.copy(notice = "已停止，重新验证账号后可继续导入。")
        }
    }
    fun leave() {
        revision++; work?.cancel(); work = null
        mutable.value = state.value.copy(busy = false, stage = null, verified = false, loaded = false,
            notice = "已停止，可重新读取并继续导入。")
    }
    fun close() {
        retrySignal?.cancel(); recoveryCheck?.cancel(); recovery?.complete(false); recovery = null
        leave(); closed = true; mutable.value = BaoziAccountUiState()
    }

    private class UnverifiedShelf : Exception()
    private fun failureMessage(error: Exception) = sourceNetworkFailure(error) ?: when (error) {
        is UnverifiedShelf -> "书架验证失败，可能为空书架或登录已过期。请重试或重新登录。"
        is SourceException.AuthenticationRequired -> "登录已失效，请重新登录。"
        is SourceException.Timeout -> "请求超时，请重试。"
        is SourceException.Transport -> "无法连接包子漫画，请检查网络后重试。"
        is SourceException.RateLimited -> "请求过于频繁，请稍后重试。"
        is SourceException.HttpStatus -> when (error.status.toInt()) {
            403 -> "请打开网页登录，完成网站验证（HTTP 403）。"
            301, 302, 303, 307, 308 -> "登录地址已变化，请通过网页登录重新连接。"
            else -> "服务暂不可用（HTTP ${error.status}），请稍后重试。"
        }
        is SourceException.InvalidResponse -> "无法读取书架或登录信息，请重试。"
        is java.io.IOException -> "账号或导入状态未确认，请刷新核对。"
        else -> "操作未完成，请刷新后重试。"
    }
}

internal fun Exception.needsBaoziReconnect(): Boolean = this is SourceException.AuthenticationRequired ||
    (this is SourceException.HttpStatus && status.toInt() in setOf(401, 403, 301, 302, 303, 307, 308))
