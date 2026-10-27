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
    val autoLoginAvailable: Boolean = false, val recoveryNotice: String? = null)

/** One WorldSession owner. Secrets never enter UI state, routes, SavedStateHandle or logs. */
internal class BaoziAccountController(private val scope: CoroutineScope, private val active: () -> Boolean,
    private val store: BaoziAccountStore, private val network: BaoziAccountNetwork,
    private val credentialsChanged: () -> Unit = {}) {
    private val mutable = MutableStateFlow(BaoziAccountUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    @Volatile private var revision = 0L
    @Volatile private var closed = false
    private var recoveryAttempted = false
    private fun allowed(ticket: Long) = !closed && active() && ticket == revision
    private fun publish(account: BaoziSavedAccount?) {
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
        mutable.value = state.value.copy(verified = false, entries = emptyList())
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
            mutable.value = state.value.copy(notice = "账号已保存；本次读取 ${result.entries.size} 本，不代表完整云书架。")
        }
    }
    suspend fun connectWebSession(username: String, token: String, browserSession: BaoziBrowserSession? = null,
        autoLogin: BaoziAutoLogin? = null) {
        val name = username.trim()
        if (state.value.busy || closed || !active()) throw java.io.IOException("请等待账号读取完成")
        if (name.isEmpty()) throw java.io.IOException("尚未取得官网登录标识，请在官网完成邮箱登录后重试")
        if (token.isEmpty()) throw java.io.IOException("请先完成网页登录，再连接书架")
        val ticket = ++revision
        mutable.value = state.value.copy(busy = true, error = null, notice = null)
        try {
            val source = baoziAccountId(name)
            browserSession?.validate(token)
            val result = snapshot(BaoziSavedAccount(source, name, token, browserSession = browserSession), ticket)
            val saved = store.activate(source, name, token, browserSession, autoLogin) { allowed(ticket) }
            credentialsChanged()
            currentCoroutineContext().ensureActive()
            if (!allowed(ticket)) throw CancellationException()
            publish(saved); showSnapshot(result)
            recoveryAttempted = false
            mutable.value = state.value.copy(reconnectRequested = false, recoveryNotice = null)
            mutable.value = state.value.copy(notice = "网页账号已验证并保存；可继续导入书架")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw java.io.IOException(failureMessage(error)) }
        finally { if (allowed(ticket)) mutable.value = state.value.copy(busy = false, stage = null) }
    }
    private fun requestReconnect() {
        if (recoveryAttempted || closed || !active()) return
        recoveryAttempted = true
        mutable.value = state.value.copy(reconnectRequested = true)
    }
    /** Public pages report only authentication / browser-gate failures, never network timeouts. */
    fun recoverBrowserSession() {
        scope.launch {
            if (closed || !active() || recoveryAttempted || state.value.busy) return@launch
            run("正在恢复包子登录") { ticket ->
                val account = store.current { allowed(ticket) }
                if (!allowed(ticket) || account?.token == null) return@run
                publish(account)
                requestReconnect()
            }
        }
    }
    suspend fun savedWebAccount(): BaoziSavedAccount? {
        val ticket = revision
        return store.current { allowed(ticket) }?.takeIf { it.token != null }
    }
    fun dismissReconnect() { mutable.value = state.value.copy(reconnectRequested = false) }
    fun reconnectFailed(reason: String) {
        if (closed || !active() || !state.value.reconnectRequested) return
        // Keep recoveryAttempted set: concurrent source failures must not restart a failed login.
        mutable.value = state.value.copy(reconnectRequested = false, recoveryNotice = reason)
    }
    fun logout() {
        val source = state.value.sourceId ?: return
        run("正在退出登录") { ticket ->
            val saved = store.logout(source) { allowed(ticket) }
            credentialsChanged()
            currentCoroutineContext().ensureActive(); if (!allowed(ticket)) return@run
            publish(saved)
            recoveryAttempted = false
            mutable.value = state.value.copy(verified = false, entries = emptyList(), reconnectRequested = false, recoveryNotice = null,
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
        mutable.value = state.value.copy(notice = "本次选中书目已处理；没有下载正文或改写远端书架。")
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
            mutable.value = state.value.copy(notice = "已停止，已提交书目保留；重新验证账号后可继续未完成任务。")
        }
    }
    fun leave() {
        revision++; work?.cancel(); work = null
        mutable.value = state.value.copy(busy = false, stage = null, verified = false, entries = emptyList(), loaded = false,
            notice = "操作已停止；重新读取后核对已提交书目，可继续未完成任务。")
    }
    fun close() { leave(); closed = true; mutable.value = BaoziAccountUiState() }

    private class UnverifiedShelf : Exception()
    private fun failureMessage(error: Exception) = when (error) {
        is UnverifiedShelf -> "没有读取到可核实的书架作品，暂时无法区分空书架与失效会话；未覆盖原账号。可重试或重新登录。"
        is SourceException.AuthenticationRequired -> "账号或会话未通过验证，请重新登录；书目与进度保留。"
        is SourceException.Timeout -> "请求超时；请重试读取，登录不会自动重发。"
        is SourceException.Transport -> "无法连接包子漫画，请检查网络后重试。"
        is SourceException.RateLimited -> "请求过于频繁，请稍后重试。"
        is SourceException.HttpStatus -> when (error.status.toInt()) {
            403 -> "网站浏览器验证尚未通过或已过期（HTTP 403）。请打开应用内网页登录，完成网站验证后连接书架；原账号与书目保留。"
            301, 302, 303, 307, 308 -> "账号地址或会话已变化，请通过应用内网页登录重新连接；不会自动向跳转地址发送旧凭据。"
            else -> "服务暂不可用（HTTP ${error.status}），未将响应当作空书架。"
        }
        is SourceException.InvalidResponse -> "书架或登录响应无法识别，请重试；未跳过异常书目。"
        is java.io.IOException -> "账号或导入状态读取 / 保存未确认，请重新读取核对；未重置数据。"
        else -> "操作未完成，请重新读取账号与任务状态后重试。"
    }
}

internal fun Exception.needsBaoziReconnect(): Boolean = this is SourceException.AuthenticationRequired ||
    (this is SourceException.HttpStatus && status.toInt() in setOf(401, 403, 301, 302, 303, 307, 308))
