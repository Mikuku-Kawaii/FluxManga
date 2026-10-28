package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface CopyAccountNetwork {
    suspend fun signIn(username: String, password: String): CopyAccount
    suspend fun verify(c: CopyAccount): CopyAccountIdentity
    suspend fun collection(c: CopyAccount, offset: Int): CopyCollectionPage
    fun credentialsChanged()
}
internal data class CopyAccountState(val loaded: Boolean = false, val source: String? = null, val username: String = "",
    val hasCredential: Boolean = false, val verified: Boolean = false, val busy: Boolean = false,
    val stage: String? = null, val error: String? = null, val notice: String? = null,
    val entries: List<CopyCollectedEntry> = emptyList(), val entriesLoaded: Boolean = false, val nextOffset: Int? = null,
    val reportedTotal: Long? = null, val task: CopyImportTask? = null)

/** A generation owns requests. Secrets never enter observable state or navigation. */
internal class CopyAccountController(private val scope: CoroutineScope, private val active: () -> Boolean,
    private val store: CopyAccountStore, private val network: CopyAccountNetwork) {
    private val mutable = MutableStateFlow(CopyAccountState()); val state = mutable.asStateFlow()
    private var account: CopyAccount? = null
    private var work: Job? = null
    @Volatile private var revision = 0L
    @Volatile private var closed = false
    private fun allowed(ticket: Long) = !closed && active() && revision == ticket
    private suspend fun check(ticket: Long) { currentCoroutineContext().ensureActive(); if (!allowed(ticket)) throw CancellationException() }
    private fun run(stage: String, action: suspend (Long) -> Unit) {
        if (state.value.busy || closed || !active()) return
        val ticket = ++revision; mutable.value = state.value.copy(busy = true, stage = stage, error = null, notice = null)
        work = scope.launch {
            try { action(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (allowed(ticket)) {
                val auth = error is SourceException.AuthenticationRequired
                if (auth) network.credentialsChanged()
                val step = state.value.stage.orEmpty().removePrefix("正在")
                val detail = copyAccountFailure(error)
                mutable.value = state.value.copy(error = if (step.isBlank()) detail else "$step：$detail", verified = state.value.verified && !auth,
                    entries = if (auth) emptyList() else state.value.entries,
                    loaded = state.value.loaded && error !is java.io.IOException)
            } } finally { if (allowed(ticket)) mutable.value = state.value.copy(busy = false, stage = null) }
        }
    }
    private fun config(): CopyAccount = account?.takeIf { state.value.verified && state.value.loaded } ?: throw SourceException.AuthenticationRequired()
    private suspend fun publish(c: CopyAccount?, ticket: Long) {
        val task = c?.let { store.task(it.sourceId) { allowed(ticket) } }; check(ticket)
        account = c
        mutable.value = state.value.copy(loaded = true, source = c?.sourceId, username = c?.username.orEmpty(),
            hasCredential = c?.hasCredential == true, task = task)
    }
    private suspend fun verify(c: CopyAccount, ticket: Long) {
        mutable.value = state.value.copy(stage = "正在验证账号身份")
        val result = network.verify(c); check(ticket)
        if (result.userId != c.userId) throw SourceException.AuthenticationRequired()
    }
    fun start() = run("正在读取并验证账号") { ticket ->
        mutable.value = state.value.copy(verified = false, entries = emptyList(), entriesLoaded = false, nextOffset = null, reportedTotal = null)
        val c = store.current { allowed(ticket) }; check(ticket); publish(c, ticket)
        if (c?.hasCredential != true) return@run
        verify(c, ticket); mutable.value = state.value.copy(verified = true)
        loadEntries(0, ticket)
        // Reopening reads the checkpoint; it never silently resumes writes.
    }
    fun signIn(username: String, password: String) {
        if (state.value.busy) return
        if (!state.value.loaded || username.trim().isEmpty() || username.trim().toByteArray().size > 256 ||
            password.isEmpty() || password.toByteArray().size > 4096) {
            mutable.value = state.value.copy(error = "请先读取账号状态，并填写有效的账号和密码"); return
        }
        run("正在登录账号") { ticket ->
            mutable.value = state.value.copy(verified = false, entries = emptyList(), entriesLoaded = false, nextOffset = null, reportedTotal = null)
            val candidate = network.signIn(username.trim(), password); check(ticket)
            verify(candidate, ticket)
            mutable.value = state.value.copy(stage = "正在保存已验证账号")
            network.credentialsChanged()
            try { store.activate(candidate) { allowed(ticket) } } finally { network.credentialsChanged() }
            check(ticket); publish(candidate, ticket)
            mutable.value = state.value.copy(verified = true, notice = "账号已验证并加密保存。")
            loadEntries(0, ticket)
        }
    }
    fun connectToken(token: String) {
        if (state.value.busy || !state.value.loaded) return
        run("正在验证会话账号") { ticket ->
            require(token.isNotBlank() && token.length <= 4096 && token.all { it.code in 33..126 }) { "请输入有效的会话 Token" }
            val identity = network.verify(CopyAccount("web-session", "", token, CopyAccountService.OFFICIAL)); check(ticket)
            val candidate = CopyAccount(identity.userId, identity.username, token, CopyAccountService.OFFICIAL)
            network.credentialsChanged()
            try { store.activate(candidate) { allowed(ticket) } } finally { network.credentialsChanged() }
            check(ticket); publish(candidate, ticket)
            mutable.value = state.value.copy(verified = true, entries = emptyList(), entriesLoaded = false,
                nextOffset = null, reportedTotal = null, notice = "会话账号已验证并加密保存。")
            loadEntries(0, ticket)
        }
    }
    suspend fun connectWebToken(token: String) {
        if (state.value.busy || closed || !active()) throw java.io.IOException("请等待当前账号操作完成")
        if (token.isBlank() || token.length > 4096 || token.any { it.code !in 33..126 }) throw java.io.IOException("请先在官网完成登录")
        val ticket = ++revision
        mutable.value = state.value.copy(busy = true, stage = "正在核对官网账号", error = null, notice = null)
        try {
            val identity = network.verify(CopyAccount("web-session", "", token, CopyAccountService.WEB)); check(ticket)
            val candidate = CopyAccount(identity.userId, identity.username, token, CopyAccountService.WEB)
            network.credentialsChanged()
            try { store.activate(candidate) { allowed(ticket) } } finally { network.credentialsChanged() }
            check(ticket); publish(candidate, ticket)
            mutable.value = state.value.copy(verified = true, entries = emptyList(), entriesLoaded = false,
                nextOffset = null, reportedTotal = null, notice = "官网账号已验证并加密保存")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw java.io.IOException(if (error is SourceException.AuthenticationRequired)
            "网页登录验证失败，请重试连接。" else copyAccountFailure(error)) }
        finally { if (allowed(ticket)) mutable.value = state.value.copy(busy = false, stage = null) }
    }
    fun logout() {
        val c = account ?: return
        if (!state.value.loaded) return
        run("正在退出登录") { ticket ->
            network.credentialsChanged()
            try { store.logout(c) { allowed(ticket) } } finally { network.credentialsChanged() }
            check(ticket)
            publish(c.withoutCredential(), ticket)
            mutable.value = state.value.copy(verified = false, entries = emptyList(), entriesLoaded = false, nextOffset = null,
                reportedTotal = null, notice = "已退出登录，书目、进度与未完成任务保留。")
        }
    }
    fun moreEntries() {
        if (!state.value.verified || (state.value.entriesLoaded && state.value.nextOffset == null)) return
        run("正在读取云收藏") { ticket -> loadEntries(if (state.value.entriesLoaded) requireNotNull(state.value.nextOffset) else 0, ticket) }
    }
    private suspend fun page(c: CopyAccount, offset: Int, ticket: Long): CopyCollectionPage {
        val result = network.collection(c, offset); check(ticket)
        require(result.context.sourceId == c.sourceId && result.context.world == SourceWorld.SFW && result.offset.toLong() == offset.toLong())
        require(result.items.size <= 21 && result.nextOffset?.toLong()?.let { it == offset.toLong() + 21 && it <= Int.MAX_VALUE } != false)
        return result
    }
    private suspend fun loadEntries(offset: Int, ticket: Long) {
        mutable.value = state.value.copy(stage = "正在读取云收藏")
        val result = page(config(), offset, ticket)
        val entries = ((if (offset == 0) emptyList() else state.value.entries) + result.items).distinctBy { it.collectionId }
        require(entries.size <= 1000)
        mutable.value = state.value.copy(entries = entries, entriesLoaded = true, nextOffset = result.nextOffset?.toInt(), reportedTotal = result.total.toLong())
    }
    fun begin(selection: Set<String>?, favorite: Boolean) {
        if (!state.value.verified || !state.value.loaded || !state.value.entriesLoaded || state.value.task?.done == false) return
        val entries = selection?.let { ids -> state.value.entries.filter { it.collectionId in ids } }
        if (entries != null && (entries.isEmpty() || entries.size != selection.size)) return
        run("正在建立导入任务") { ticket ->
            val c = config(); val task = store.begin(c, entries, favorite) { allowed(ticket) }; check(ticket)
            mutable.value = state.value.copy(task = task); batches(c, task, ticket)
        }
    }
    fun resume(rescan: Boolean = false) {
        if (!state.value.verified || !state.value.loaded) return
        run(if (rescan) "正在从头重扫去重" else "正在继续导入") { ticket ->
            val c = config()
            val task = if (rescan) store.rescan(c) { allowed(ticket) } else store.task(c.sourceId) { allowed(ticket) }
            check(ticket); batches(c, requireNotNull(task), ticket)
        }
    }
    private suspend fun batches(c: CopyAccount, initial: CopyImportTask, ticket: Long) {
        var task = initial
        while (!task.done) {
            check(ticket)
            val result = if (task.selection == null) page(c, task.offset, ticket) else null
            val items = result?.items ?: requireNotNull(task.selection).drop(task.cursor).take(21)
            task = store.commit(c, task, items, result?.nextOffset?.toInt()) { allowed(ticket) }; check(ticket)
            mutable.value = state.value.copy(task = task); yield()
        }
        mutable.value = state.value.copy(notice = "本次导入已完成，仅保存书目引用，正文仍按需读取。")
    }
    fun finish() {
        if (!state.value.verified || !state.value.loaded) return
        run("正在结束任务") { ticket -> store.finish(config()) { allowed(ticket) }; check(ticket); mutable.value = state.value.copy(task = null, notice = "任务已收起，已入库书目和进度保留。") }
    }
    fun cancel() {
        leave()
        run("正在核对已提交进度") { ticket ->
            val c = store.current { allowed(ticket) }; check(ticket); publish(c, ticket)
            mutable.value = state.value.copy(notice = "已停止，重新验证后可继续导入。")
        }
    }
    fun leave() { revision++; work?.cancel(); work = null; account = null
        mutable.value = state.value.copy(busy = false, verified = false, stage = null, loaded = false, entries = emptyList(), entriesLoaded = false, nextOffset = null) }
    fun close() { leave(); closed = true; mutable.value = CopyAccountState() }
}
internal fun copyAccountFailure(error: Exception) = when (error) {
    is SourceException.LoginRejected -> when (error.reason) {
        "account_missing" -> "账户不存在（${error.code}）。若官方 App 能登录，可能是接口不兼容。"
        "password_incorrect" -> "服务器提示密码不正确，请重新输入（${error.code}）。"
        "verification_required" -> "网站要求验证码，请先在官方入口完成验证（${error.code}）。"
        "client_restricted" -> "客户端访问受限（${error.code}），请在最新版官方 App 登录后再试。"
        else -> "服务器拒绝了本次登录（${error.code}），请核对账号或稍后重试。"
    }
    is SourceException.AuthenticationRequired -> "账号验证失败，请重新登录。"
    is SourceException.Timeout -> "请求超时，请重试。已导入书目保留。"
    is SourceException.Transport -> "无法连接拷贝漫画，请检查网络后重试。"
    is SourceException.RateLimited -> "请求过于频繁，请稍后重试。"
    is SourceException.HttpStatus -> "读取失败（HTTP ${error.status}），请重试。"
    is SourceException.InvalidResponse -> "暂不支持此返回格式，请稍后重试。"
    is java.io.IOException -> "账号或提交结果尚未确认，请重新读取核对后继续。"
    is IllegalArgumentException -> "最多预览 1000 项，更多作品可按当前范围分页导入。"
    else -> "操作未完成，已提交书目保留，请重试或重新验证账号。"
}
