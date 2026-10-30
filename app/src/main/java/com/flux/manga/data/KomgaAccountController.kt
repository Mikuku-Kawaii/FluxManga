package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface KomgaAccountNetwork {
    suspend fun verify(c: KomgaConfiguration): String
    suspend fun lists(c: KomgaConfiguration, kind: KomgaListKind, page: Int): KomgaListPage
    suspend fun entries(c: KomgaConfiguration, list: KomgaAuthorizedList, page: Int): KomgaImportPage
}
internal data class KomgaAccountState(val loaded: Boolean = false, val source: String? = null, val user: String? = null,
    val verified: Boolean = false, val busy: Boolean = false, val stage: String? = null, val error: String? = null, val notice: String? = null,
    val kind: KomgaListKind = KomgaListKind.LIBRARY, val lists: List<KomgaAuthorizedList> = emptyList(),
    val listsLoaded: Boolean = false, val nextList: Int? = null, val selected: KomgaAuthorizedList? = null,
    val entries: List<KomgaImportEntry> = emptyList(), val entriesLoaded: Boolean = false, val nextEntry: Int? = null,
    val task: KomgaImportTask? = null)

internal class KomgaAccountController(private val scope: CoroutineScope, private val active: () -> Boolean,
    private val store: KomgaAccountStore, private val network: KomgaAccountNetwork) {
    private val mutable = MutableStateFlow(KomgaAccountState()); val state = mutable.asStateFlow()
    private var configuration: KomgaConfiguration? = null
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
                mutable.value = state.value.copy(error = komgaAccountFailure(error), verified = state.value.verified && !auth,
                    loaded = state.value.loaded && error !is java.io.IOException)
            } } finally { if (allowed(ticket)) mutable.value = state.value.copy(busy = false, stage = null) }
        }
    }
    private fun config(): KomgaConfiguration = configuration?.takeIf { state.value.verified } ?: throw SourceException.AuthenticationRequired()
    fun start() = run("正在读取并验证 Komga 账号") { ticket ->
        mutable.value = state.value.copy(verified = false)
        val record = store.current { allowed(ticket) }; check(ticket); configuration = record
        val task = record?.let { store.task(it.sourceId) { allowed(ticket) } }; check(ticket)
        val changed = state.value.source != record?.sourceId
        mutable.value = (if (changed) KomgaAccountState(busy = true) else state.value).copy(loaded = true,
            source = record?.sourceId, user = record?.userId, task = task)
        if (record?.hasCredential != true) { mutable.value = state.value.copy(notice = "请先配置并验证 Komga 账号"); return@run }
        val user = network.verify(record); check(ticket)
        if (record.userId != null && record.userId != user) throw SourceException.AuthenticationRequired()
        val saved = store.activate(record, user) { allowed(ticket) }; check(ticket); configuration = saved
        mutable.value = state.value.copy(source = saved.sourceId, user = user, verified = true)
        // Read only: reopening never replays an import.
        if (!state.value.listsLoaded) loadLists(state.value.kind, 0, ticket)
    }
    fun category(kind: KomgaListKind) {
        if (!state.value.verified) return
        run("正在读取授权列表") { ticket ->
            mutable.value = state.value.copy(kind = kind, lists = emptyList(), listsLoaded = false, nextList = null,
                selected = null, entries = emptyList(), entriesLoaded = false, nextEntry = null)
            loadLists(kind, 0, ticket)
        }
    }
    fun moreLists() = run("正在读取列表目录") { ticket -> loadLists(state.value.kind, if (state.value.listsLoaded) state.value.nextList ?: 0 else 0, ticket) }
    private suspend fun loadLists(kind: KomgaListKind, page: Int, ticket: Long) {
        val c = config(); val result = network.lists(c, kind, page); check(ticket)
        val items = (if (page == 0) emptyList() else state.value.lists) + result.items
        if (items.size > 1000) throw SourceException.InvalidResponse()
        mutable.value = state.value.copy(lists = items.distinctBy { it.id }, listsLoaded = true, nextList = result.nextPage?.toInt())
    }
    fun select(list: KomgaAuthorizedList) {
        if (!state.value.verified || list !in state.value.lists) return
        run("正在读取列表作品") { ticket ->
            mutable.value = state.value.copy(selected = list, entries = emptyList(), entriesLoaded = false, nextEntry = null)
            loadEntries(list, 0, ticket)
        }
    }
    fun moreEntries() {
        val list = state.value.selected ?: return
        run("正在读取列表作品") { ticket -> loadEntries(list, if (state.value.entriesLoaded) state.value.nextEntry ?: 0 else 0, ticket) }
    }
    private suspend fun loadEntries(list: KomgaAuthorizedList, page: Int, ticket: Long) {
        val result = network.entries(config(), list, page); check(ticket)
        val entries = (if (page == 0) emptyList() else state.value.entries) + result.items
        require(entries.size <= 1000) { "Preview limit" }
        mutable.value = state.value.copy(entries = entries.distinctBy { it.bookId ?: it.seriesId }, entriesLoaded = true, nextEntry = result.nextPage?.toInt())
    }
    fun begin(selection: Set<String>?, favorite: Boolean) {
        val list = state.value.selected ?: return
        if (!state.value.verified || !state.value.entriesLoaded || state.value.task?.done == false) return
        val entries = selection?.let { ids -> state.value.entries.filter { (it.bookId ?: it.seriesId) in ids } }
        if (entries != null && entries.isEmpty()) return
        run("正在建立导入任务") { ticket ->
            val c = config(); val task = store.begin(c, list, entries, favorite) { allowed(ticket) }; check(ticket)
            mutable.value = state.value.copy(task = task); batches(c, task, ticket)
        }
    }
    fun resume(rescan: Boolean = false) {
        if (!state.value.verified) return
        run(if (rescan) "正在从首页重扫去重" else "正在继续导入") { ticket ->
            val c = config()
            val task = if (rescan) store.rescan(c) { allowed(ticket) } else store.task(c.sourceId) { allowed(ticket) }
            check(ticket); batches(c, requireNotNull(task), ticket)
        }
    }
    private suspend fun batches(c: KomgaConfiguration, initial: KomgaImportTask, ticket: Long) {
        var task = initial
        while (!task.done) {
            check(ticket)
            val result = if (task.selection == null) network.entries(c, task.list, task.page) else null
            check(ticket)
            val items = result?.items ?: requireNotNull(task.selection).drop(task.cursor).take(20)
            task = store.commit(c, task, items, result?.nextPage?.toInt()) { allowed(ticket) }; check(ticket)
            mutable.value = state.value.copy(task = task); yield()
        }
        mutable.value = state.value.copy(notice = "书目已导入，可在线阅读。")
    }
    fun finish() {
        if (!state.value.verified) return
        run("正在结束任务") { ticket -> store.finish(config()) { allowed(ticket) }; check(ticket); mutable.value = state.value.copy(task = null, notice = "任务记录已收起，已入库书目和进度保留。") }
    }
    fun cancel() {
        leave()
        run("正在核对已提交进度") { ticket ->
            val c = store.current { allowed(ticket) }; check(ticket)
            val task = c?.let { store.task(it.sourceId) { allowed(ticket) } }; check(ticket)
            mutable.value = state.value.copy(loaded = true, source = c?.sourceId, user = c?.userId, task = task,
                notice = "已停止，重新验证后可继续导入。")
        }
    }
    fun leave() { revision++; work?.cancel(); work = null; configuration = null; mutable.value = state.value.copy(busy = false, verified = false, stage = null, loaded = false) }
    fun configurationChanged() { leave(); mutable.value = KomgaAccountState() }
    fun close() { leave(); closed = true; mutable.value = KomgaAccountState() }
}
internal fun komgaAccountFailure(error: Exception) = sourceNetworkFailure(error) ?: when (error) {
    is SourceException.AuthenticationRequired -> "登录已失效，请更新账号信息。"
    is SourceException.HttpStatus -> if (error.status.toInt() == 403) "当前账号无权读取此列表，请检查服务器权限。" else "读取失败（HTTP ${error.status}），请重试。"
    is SourceException.NotFound -> "列表不可用，请选择其他列表。"
    is SourceException.Timeout -> "请求超时，已提交批次保留，可重试。"
    is SourceException.Transport -> "无法连接 Komga，请检查网络、地址和证书。"
    is SourceException.InvalidResponse -> "响应或分页不符合已支持协议，未当作空列表或跳过异常项。"
    is java.io.IOException -> "配置或提交结果尚未确认，请重新读取核对后继续。"
    is IllegalArgumentException -> "最多预览 1000 项，更多作品可选择整列表导入。"
    else -> "操作未完成，已提交书目保留，请重试或重新验证账号。"
}
