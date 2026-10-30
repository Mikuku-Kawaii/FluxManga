package com.flux.manga.data

import com.flux.manga.core.SourcePageResource
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DownloadImage(val file: File, private val release: () -> Unit) : Closeable { override fun close() = release() }
internal interface DownloadNetwork {
    suspend fun pages(job: ChapterDownload): List<SourcePageResource>
    suspend fun image(job: ChapterDownload, page: SourcePageResource, directory: File): DownloadImage
}
internal data class DownloadQueueState(val loaded: Boolean = false, val jobs: List<ChapterDownload> = emptyList(),
    val controlling: Boolean = false, val error: String? = null, val networkMessage: String? = null)
internal class DownloadController(private val scope: CoroutineScope, private val active: () -> Boolean,
    private val store: DownloadStore, private val files: DownloadFiles, private val network: DownloadNetwork) {
    private val mutable=MutableStateFlow(DownloadQueueState()); val state=mutable.asStateFlow()
    private val networkBlock = MutableStateFlow<String?>(null)
    @Volatile private var request: Deferred<*>? = null
    private class NetworkPolicyCancellation : CancellationException()
    private class NetworkWait : Exception()
    fun networkChanged(message: String?) {
        networkBlock.value = message
        mutable.value = state.value.copy(networkMessage = message)
        if (message != null) request?.cancel(NetworkPolicyCancellation())
    }
    private suspend fun <T> networkCall(block: suspend () -> T): T {
        networkBlock.filter { it == null }.first()
        var produced: T? = null
        try {
            return coroutineScope {
                val pending = async(start = CoroutineStart.LAZY) { block().also { produced = it } }; request = pending
                if(networkBlock.value != null) pending.cancel(NetworkPolicyCancellation())
                try { pending.await() }
                catch (_: NetworkPolicyCancellation) { throw NetworkWait() }
                finally { if (request === pending) request = null }
            }
        } catch(e: Exception) { (produced as? Closeable)?.close(); throw e }
    }
    private var worker: Job?=null; private var control: Job?=null
    @Volatile private var epoch=0L; @Volatile private var closed=false
    private fun allowed(e: Long)=!closed && active() && epoch==e
    private suspend fun check(e: Long) { currentCoroutineContext().ensureActive(); if(!allowed(e)) throw CancellationException() }
    private val updates=Mutex()
    private suspend fun update(e: Long, action: suspend () -> List<ChapterDownload>) = updates.withLock {
        val jobs=action(); check(e)
        mutable.value=state.value.copy(loaded=true,jobs=jobs)
    }
    private fun command(action: suspend (Long) -> Unit) {
        if(closed || !active() || state.value.controlling) return
        val e=epoch; mutable.value=state.value.copy(controlling=true,error=null)
        control=scope.launch {
            try { action(e) } catch(c: CancellationException) { throw c }
            catch(_: Exception) { if(allowed(e)) { pauseAll(); mutable.value=state.value.copy(error="队列操作结果待确认，请刷新后重试。") } }
            finally { if(allowed(e)) { mutable.value=state.value.copy(controlling=false); launchWorker() } }
        }
    }
    fun start() {
        if(state.value.loaded) return
        val previousWorker=worker; val previousControl=control
        command { e ->
            // Cancellation can still be unwinding a native/file operation. Do not reuse its directory yet.
            previousWorker?.join(); previousControl?.join(); check(e)
            update(e) { store.recover { allowed(e) } }
        }
    }
    fun enqueue(source: String, manga: String, chapters: List<Pair<String,String>>) {
        if(!state.value.loaded) return
        command { e -> update(e) { store.enqueue(source,manga,chapters) { allowed(e) } } }
    }
    fun resume(id: String) {
        if(!state.value.loaded) return
        command { e -> update(e) { store.change(id,{allowed(e)}) { j ->
            if(j.status in listOf(DownloadStatus.PAUSED,DownloadStatus.FAILED)) j.copy(status=DownloadStatus.QUEUED,error=null) else j
        } } }
    }
    fun stop(id: String, discard: Boolean = false) {
        if(!state.value.loaded) return
        command { e ->
            worker?.cancelAndJoin(); worker=null; check(e)
            // A stopped active chapter becomes resumable even if a different queued item was removed.
            var jobs=store.list { allowed(e) }
            jobs.filter { it.status==DownloadStatus.RUNNING }.forEach { running ->
                jobs=store.change(running.id,{allowed(e)}) { it.copy(status=if(it.id==id) DownloadStatus.PAUSED else DownloadStatus.QUEUED) }
            }
            val job=jobs.first { it.id==id }
            if(discard) withContext(Dispatchers.IO) { files.clear(job) }
            check(e)
            update(e) { store.change(id,{allowed(e)}) {
                when { discard && it.status in listOf(DownloadStatus.COMPLETE,DownloadStatus.CANCELLED) -> null
                    discard -> it.copy(status=DownloadStatus.CANCELLED,pages=emptyList(),error=null)
                    it.status==DownloadStatus.COMPLETE || it.status==DownloadStatus.CANCELLED -> it
                    else -> it.copy(status=DownloadStatus.PAUSED,error=null) }
            } }
        }
    }
    fun batch(ids: Set<String>, action: DownloadBatchAction) {
        if (!state.value.loaded || ids.isEmpty()) return
        command { e ->
            worker?.cancelAndJoin(); worker = null; check(e)
            val jobs = store.list { allowed(e) }
            for (job in jobs) {
                check(e)
                val selected = job.id in ids
                if (selected && action == DownloadBatchAction.REMOVE) withContext(Dispatchers.IO) { files.clear(job) }
                update(e) { store.change(job.id, { allowed(e) }) { current ->
                    when {
                        selected && action == DownloadBatchAction.REMOVE -> null
                        selected && action == DownloadBatchAction.PAUSE && current.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) -> current.copy(status = DownloadStatus.PAUSED, error = null)
                        selected && action == DownloadBatchAction.RESUME && current.status in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) -> current.copy(status = DownloadStatus.QUEUED, error = null)
                        current.status == DownloadStatus.RUNNING -> current.copy(status = DownloadStatus.QUEUED)
                        else -> current
                    }
                } }
            }
        }
    }
    private fun launchWorker() {
        if(worker?.isActive==true || !state.value.loaded || !active() || closed) return
        val e=epoch
        worker=scope.launch {
            while(allowed(e)) {
                networkBlock.filter { it == null }.first(); check(e)
                val job=state.value.jobs.firstOrNull { it.status==DownloadStatus.QUEUED } ?: break
                try { download(job,e) }
                catch (_: NetworkWait) {
                    if (!allowed(e)) break
                    try { update(e) { store.change(job.id, { allowed(e) }) { it.copy(status = DownloadStatus.QUEUED, error = null) } } }
                    catch (c: CancellationException) { throw c }
                    catch (_: Exception) { mutable.value = state.value.copy(loaded = false, error = "请重新读取队列核对状态"); break }
                }
                catch(c: CancellationException) {
                    if(!allowed(e) || !currentCoroutineContext().isActive) throw c
                    // A provider invalidation is resumable, not a permanently running queue item.
                    try { update(e) { store.change(job.id,{allowed(e)}) { it.copy(status=DownloadStatus.PAUSED,error="图源会话已变化，请验证账号后继续") } } }
                    catch(c: CancellationException) { throw c }
                    catch(_: Exception) { mutable.value=state.value.copy(loaded=false,error="请重新读取队列核对状态"); break }
                }
                catch(error: Exception) {
                    if(!allowed(e)) break
                    try { update(e) { store.change(job.id,{allowed(e)}) { it.copy(status=DownloadStatus.FAILED,error=downloadFailure(error)) } } }
                    catch(c: CancellationException) { throw c }
                    catch(_: Exception) { mutable.value=state.value.copy(loaded=false,error="下载结果尚未确认，请重新读取队列"); break }
                }
            }
        }
    }
    private suspend fun download(initial: ChapterDownload, e: Long) {
        var job=initial
        update(e) { store.change(job.id,{allowed(e)}) { it.copy(status=DownloadStatus.RUNNING,error=null) } }; check(e)
        job=state.value.jobs.first { it.id==job.id }
        withContext(Dispatchers.IO) { files.cleanTemporary(job) }; check(e)
        val pages=networkCall { network.pages(job) }; check(e)
        require(pages.isNotEmpty() && pages.size<=5000 && pages.map { it.id }.distinct().size==pages.size)
        require(pages.withIndex().all { (index,p) -> p.manga.sourceId==job.source && p.manga.mangaId==job.manga && p.chapterId==job.chapter && p.ordinal.toInt()==index+1 && p.manga.world==com.flux.manga.core.SourceWorld.SFW })
        if(job.pages.isNotEmpty() && job.pages.map { it.id }!=pages.map { it.id }) throw IOException("章节页序已变化，请取消此任务后重新选择下载")
        if(job.pages.isEmpty()) {
            update(e) { store.change(job.id,{allowed(e)}) { it.copy(pages=pages.map { p -> DownloadPage(p.id) }) } }; check(e)
            job=state.value.jobs.first { it.id==job.id }
        }
        for((index,page) in pages.withIndex()) {
            check(e)
            if(withContext(Dispatchers.IO) { files.valid(job,index) }) continue
            val downloaded=withContext(Dispatchers.IO) {
                val directory=files.directory(job); if(!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建下载目录")
                networkCall { network.image(job,page,directory) }.use { owned ->
                    check(e); val copyJob=currentCoroutineContext()[Job]!!
                    files.save(job,index,owned.file) { allowed(e) && copyJob.isActive }
                }
            }
            check(e)
            update(e) { store.change(job.id,{allowed(e)}) { it.copy(pages=it.pages.toMutableList().apply { this[index]=downloaded }) } }; check(e)
            job=state.value.jobs.first { it.id==job.id }
            yield()
        }
        update(e) { store.change(job.id,{allowed(e)}) { it.copy(status=DownloadStatus.COMPLETE,error=null) } }
    }
    fun pauseAll() {
        epoch++; worker?.cancel(); control?.cancel()
        mutable.value=state.value.copy(loaded=false,controlling=false,jobs=state.value.jobs.map {
            if(it.status in listOf(DownloadStatus.QUEUED,DownloadStatus.RUNNING)) it.copy(status=DownloadStatus.PAUSED) else it
        })
    }
    fun close() { pauseAll(); closed=true; mutable.value=DownloadQueueState() }
}
internal fun downloadFailure(error: Exception): String = com.flux.manga.data.sourceNetworkFailure(error) ?: when(error) {
    is IOException -> error.message ?: "下载文件保存失败，可重试"
    is com.flux.manga.core.SourceException.AuthenticationRequired -> "账号已失效，请在图源管理重新验证后重试"
    is com.flux.manga.core.SourceException.Timeout -> "请求超时，已完成页面保留，可重试"
    is com.flux.manga.core.SourceException.Transport -> "网络不可用，已完成页面保留，可重试"
    else -> "下载或章节校验失败，已完成页面保留，可重试"
}

internal enum class DownloadBatchAction { PAUSE, RESUME, REMOVE }
