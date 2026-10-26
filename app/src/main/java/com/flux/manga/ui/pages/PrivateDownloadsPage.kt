package com.flux.manga.ui.pages

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class PrivateDownloadsState(val jobs: List<ChapterDownload> = emptyList(), val chapters: List<SourceChapter> = emptyList(),
    val ready: Boolean = false, val busy: Boolean = false, val message: String? = null, val metered: Boolean = false)
internal class PrivateDownloadsModel(private val app: Application, private val session: WorldSession,
    private val source: String?, private val manga: String?) : ViewModel() {
    private val identity = session.identity.value
    private val store = PrivateDownloadStore(app)
    private var work: Job? = null
    private var reader: PrivateDownloadReader? = null
    val state = MutableStateFlow(PrivateDownloadsState())
    private fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity) && session.state.value.foreground
    private suspend fun publish() { val jobs = store.jobs.list(::active); currentCoroutineContext().ensureActive(); state.update { it.copy(jobs = jobs, ready = true) } }
    private fun run(action: suspend () -> Unit) {
        if (!active() || state.value.busy) return
        state.update { it.copy(busy = true, message = null) }
        work = viewModelScope.launch {
            try { action() }
            catch (_: CancellationException) { if (session.accepts(identity)) state.update { old -> old.copy(message = "已暂停，完成页面保留；返回后可继续。",
                jobs = old.jobs.map { if (it.status in listOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED)) it.copy(status = DownloadStatus.PAUSED) else it }) } }
            catch (error: Exception) { if (active()) state.update { it.copy(message = downloadFailure(error)) } }
            finally { reader?.close(); reader = null; state.update { it.copy(busy = false) } }
        }
    }
    fun reload() {
        val previous = work
        viewModelScope.launch {
            previous?.join()
            run {
                store.jobs.recover(::active)
                if (source != null && manga != null) {
                    val catalog = store.catalog(source, manga, ::active)
                    state.update { it.copy(chapters = catalog) }
                }
                publish()
            }
        }
    }
    fun stop() { work?.cancel(); reader?.close() }
    fun metered(value: Boolean) { if (!state.value.busy) state.update { it.copy(metered = value) } }
    fun catalog() = run {
        val sourceId = requireNotNull(source); val mangaId = requireNotNull(manga)
        val api = privateDownloadReader(app, session, sourceId, mangaId); reader = api
        val chapters = api.completeOfflineCatalog(); currentCoroutineContext().ensureActive()
        store.saveCatalog(sourceId, mangaId, chapters, ::active)
        state.update { it.copy(chapters = chapters) }
    }
    fun enqueue(ids: Set<String>) = run {
        require(ids.isNotEmpty() && ids.size <= 200 && ids.all { id -> state.value.chapters.any { it.id == id } })
        store.jobs.recover(::active)
        store.jobs.enqueue(requireNotNull(source), requireNotNull(manga), state.value.chapters.filter { it.id in ids }.map { it.id to it.title }, ::active)
        publish(); download()
    }
    fun resume(id: String) = run {
        store.jobs.recover(::active)
        store.jobs.change(id, ::active) { if (it.status in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)) it.copy(status = DownloadStatus.QUEUED, error = null) else it }
        publish(); download()
    }
    fun remove(job: ChapterDownload) = run {
        val current = store.jobs.list(::active).first { it.id == job.id }
        store.clear(current, ::active)
        store.jobs.change(job.id, ::active) { null }; publish()
    }
    private fun network() {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val message = downloadNetworkBlock(if (state.value.metered) DownloadNetworkPolicy.ANY else DownloadNetworkPolicy.UNMETERED,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
        if (message != null) throw java.io.IOException("$message；请在网络可用后继续")
    }
    private suspend fun download() {
        while (active()) {
            var job = store.jobs.list(::active).firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
            try {
                network()
                val api = privateDownloadReader(app, session, job.source, job.manga); reader = api
                store.jobs.change(job.id, ::active) { it.copy(status = DownloadStatus.RUNNING, error = null) }; publish()
                val pages = api.pages(job.chapter); currentCoroutineContext().ensureActive()
                require(pages.isNotEmpty() && pages.size <= 5000 && pages.map { it.id }.distinct().size == pages.size)
                require(pages.withIndex().all { (index, page) -> page.manga == SourceMangaKey(SourceWorld.NSFW, job.source, job.manga) && page.chapterId == job.chapter && page.ordinal.toInt() == index + 1 })
                if (job.pages.isNotEmpty() && job.pages.map { it.id } != pages.map { it.id }) throw java.io.IOException("章节页序已变化，请移除任务副本后重新下载")
                if (job.pages.isEmpty()) {
                    store.jobs.change(job.id, ::active) { it.copy(pages = pages.map { page -> DownloadPage(page.id) }) }
                    job = store.jobs.list(::active).first { it.id == job.id }
                }
                for ((index, page) in pages.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (store.valid(job, index, ::active)) continue
                    network()
                    val saved = withContext(Dispatchers.IO) {
                        api.downloadPage(page).use { image -> store.save(job, index, image, ::active) }
                    }
                    store.jobs.change(job.id, ::active) { it.copy(pages = it.pages.toMutableList().apply { this[index] = saved }) }
                    job = store.jobs.list(::active).first { it.id == job.id }; publish()
                }
                store.jobs.change(job.id, ::active) { it.copy(status = DownloadStatus.COMPLETE, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!active()) throw CancellationException()
                store.jobs.change(job.id, ::active) { it.copy(status = DownloadStatus.FAILED, error = downloadFailure(error)) }
            } finally { reader?.close(); reader = null }
            publish()
        }
    }
    override fun onCleared() { stop() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PrivateDownloadsPage(session: WorldSession, source: String? = null, manga: String? = null,
    back: () -> Unit, read: (String, String, String) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model: PrivateDownloadsModel = viewModel(key = "private-downloads:$source:$manga", factory = viewModelFactory {
        initializer { PrivateDownloadsModel(app, session, source, manga) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.reload()
        onDispose { model.stop() }
    }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ChapterDownload?>(null) }
    LaunchedEffect(lifecycle) { if (lifecycle != Lifecycle.State.RESUMED) { confirm = false; remove = null } }
    val visibleJobs = state.jobs.filter { source == null || (it.source == source && it.manga == manga) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("加密下载", "", back, actions = {
            ReferenceActionsButton("下载设置", { showSettings = true })
        }) }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("离页或锁屏暂停", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) { RequestLoading(); TextButton(model::stop) { Text("暂停") } }
            state.message?.let { RequestFeedback(it) }
        } }
        if (source != null) {
            item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(model::catalog, enabled = !state.busy) { Text("联网读取完整目录") }
                TextButton({ confirm = true }, enabled = state.ready && !state.busy && selected.isNotEmpty()) { Text("下载已选 ${selected.size} 章") }
                TextButton({ selected = emptySet() }, enabled = !state.busy) { Text("清空选择") }
            } }
            items(state.chapters, key = { "chapter:${it.id}" }) { chapter ->
                DownloadChapterChoice(chapter.title, chapter.id in selected, !state.busy, null) { checked ->
                    selected = if (checked && selected.size < 200) selected + chapter.id else selected - chapter.id
                }
            }
        }
        item { Text("下载任务 · ${visibleJobs.size}", style = MaterialTheme.typography.titleLarge) }
        if (state.ready && visibleJobs.isEmpty()) item { ReferenceEmptyState("暂无任务，请从作品详情选择下载章节。") }
        items(visibleJobs, key = { it.id }) { job -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(job.title, style = MaterialTheme.typography.titleMedium)
            DownloadTaskProgress(job)
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (job.status == DownloadStatus.COMPLETE) TextButton({ read(job.source, job.manga, job.chapter) }, enabled = !state.busy) { Text("离线阅读") }
                if (job.status in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)) TextButton({ model.resume(job.id) }, enabled = !state.busy) { Text("继续 / 重试") }
                TextButton({ remove = job }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text("移除任务与下载副本") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        } }
    }
    if (showSettings) ReferenceSheet(onDismissRequest = { showSettings = false }, title = { Text("下载设置") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSwitchRow("允许计费网络", "默认仅非计费网络", state.metered, model::metered, enabled = !state.busy)
            Text("仅在本页前台下载，离页或锁屏暂停；返回后显式继续。已完成章节可离线阅读。")
            Text("EH / EX 固定下载站点图，在线原图设置保持。", style = MaterialTheme.typography.bodySmall)
            TextButton(feedbackAttempt(model::reload), enabled = !state.busy) { Text("重新读取队列") }
        } }, confirmButton = { TextButton({ showSettings = false }) { Text("关闭") } })
    if (confirm) ReferenceDialog(onDismissRequest = { confirm = false }, title = { Text("下载所选章节？") },
        text = { Text("保存 ${selected.size} 章的应用内加密副本，单次最多 200 章。副本不包含在书库备份中；原书目、进度和文件保持。", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm("确认下载", onClick = { confirm = false; model.enqueue(selected); selected = emptySet() }) },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
    remove?.let { job -> ReferenceDialog(onDismissRequest = { remove = null }, title = { Text("移除任务与下载副本？") },
        text = { Text("仅移除此任务及已保存的加密页面。保留书目、阅读进度、普通缓存与原文件。", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm("确认移除", onClick = { remove = null; model.remove(job) }, destructive = true) },
        dismissButton = { TextButton({ remove = null }) { Text("取消") } }) }
}
