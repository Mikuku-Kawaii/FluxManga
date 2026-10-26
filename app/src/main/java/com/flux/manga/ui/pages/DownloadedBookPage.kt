package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.SourceChapter
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DownloadedBookState(val title: String = "离线作品", val loading: Boolean = false,
    val completeCatalog: Boolean = false, val chapters: List<SourceChapter> = emptyList(),
    val jobs: List<ChapterDownload> = emptyList(), val error: String? = null)
internal class DownloadedBookModel(app: Application, private val session: WorldSession,
    private val source: String, private val manga: String) : ViewModel() {
    private val identity = session.identity.value
    private val data = WorldDataStore(app)
    private val mutable = MutableStateFlow(DownloadedBookState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private fun valid() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    fun refresh() {
        if (!valid()) return
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = state.value.copy(loading = true, error = null)
            try {
                val jobs = DownloadStore(data.accountImportDatabase()).list { valid() }.filter { it.source == source && it.manga == manga }
                val catalog = OfflineCatalogStore(data.accountImportDatabase()).load(source, manga) { valid() }
                val title = data.localData { it.onlineBook(source, manga)?.title } ?: "离线作品"
                ensureActive(); if (!valid()) return@launch
                mutable.value = DownloadedBookState(title, false, catalog?.complete == true,
                    offlineChapters(source, manga, catalog, jobs), jobs)
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if(valid()) mutable.value = state.value.copy(loading = false, error = "离线目录读取失败，原记录保留，可重试") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadedBookPage(session: WorldSession, source: String, manga: String, back: () -> Unit,
    read: (String) -> Unit, download: () -> Unit, queue: () -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if (identity.world != PrototypeWorld.SFW) { SettingsHeading("离线内容尚未开放", "请返回表世界", back); return }
    val app = LocalContext.current.applicationContext as Application
    val model: DownloadedBookModel = viewModel(factory = viewModelFactory { initializer { DownloadedBookModel(app,session,source,manga) } })
    val state by model.state.collectAsStateWithLifecycle()
    val readings = rememberChapterReadings(session, source, manga)
    val readingState by readings.state.collectAsStateWithLifecycle()
    val controller = remember(session,identity.generation) { session.downloads(app) }
    val queueState by controller.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if(lifecycle == Lifecycle.State.RESUMED) { controller.start(); model.refresh() } }
    LaunchedEffect(queueState.jobs,queueState.controlling) { if(!queueState.controlling) model.refresh() }
    var onlyDownloaded by rememberSaveable(source, manga) { mutableStateOf(false) }
    var query by rememberSaveable(source, manga) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var showActions by rememberSaveable(source, manga) { mutableStateOf(false) }
    val jobs = state.jobs.associateBy { it.chapter }
    val visibleChapters = remember(state.chapters, jobs, onlyDownloaded, query) {
        state.chapters.filter { (!onlyDownloaded || jobs[it.id]?.status == DownloadStatus.COMPLETE) && downloadTitleMatches(it.title, query) }
    }
    ReferenceRefreshColumn(refreshing = state.loading, refresh = { model.refresh(); readings.refresh() }, refreshEnabled = !queueState.controlling && !readingState.busy, modifier = Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("离线目录", "", back, actions = { ReferenceActionsButton("离线作品管理", { showActions = true }) }) }
        item {
            Text(state.title, style = MaterialTheme.typography.titleLarge)
            Text("已下载 ${state.jobs.count { it.status == DownloadStatus.COMPLETE }} 章" + if (state.completeCatalog) " · 目录完整" else " · 目录不完整",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            DownloadTitleFilter(query, { query = it }, visibleChapters.size, state.chapters.size)
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(onlyDownloaded, { onlyDownloaded = !onlyDownloaded }, label = { Text("只看已下载") })
                TextButton(onClick=download) { Text("下载章节") }
            }
        }
        item {
            ChapterReadingStatus(readingState, readings::refresh)
            if(state.loading) RequestLoading()
            state.error?.let { RequestFeedback(it); TextButton(onClick=feedbackAttempt(model::refresh)) { Text("重试") } }
            if(!state.loading && state.chapters.isEmpty()) Text("暂无离线目录或已下载章节")
            queueState.error?.let { RequestFeedback(it); TextButton(onClick=feedbackAttempt(controller::start)) { Text("重新核对下载队列") } }
        }
        if (!state.loading && state.chapters.isNotEmpty() && visibleChapters.isEmpty()) item {
            Text("当前筛选没有章节；可清除标题筛选或显示全部目录。")
            TextButton(onClick={query="";onlyDownloaded=false}) { Text("显示全部目录") }
        }
        if (state.loading && state.chapters.isEmpty()) item("read-layout") { ReadTagPlaceholder(3) }
        if (!state.loading && state.error != null) {
            item("read-failure") { ReadFailure(state.error!!, model::refresh) }
            return@ReferenceRefreshColumn
        }
        items(visibleChapters,key={it.id}) { chapter ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val complete = jobs[chapter.id]?.status == DownloadStatus.COMPLETE
                ReferenceActionRow(text = { modifier ->
                    Column(modifier.clickable(enabled = complete, role = Role.Button,
                        onClickLabel = "离线阅读", onClick = { read(chapter.id) }).heightIn(min = 64.dp).padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chapter.title, style = MaterialTheme.typography.titleMedium)
                        Text(when(jobs[chapter.id]?.status) { DownloadStatus.COMPLETE -> "已下载 · 离线阅读"; null, DownloadStatus.CANCELLED -> "未下载"; else -> "尚未完成 · 可在队列查看" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, actions = {
                    ChapterReadingAction(chapter.title, readingState.records[chapter.id], readingState.loaded && !readingState.busy) { readings.mark(chapter.id, it) }
                })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (showActions) ReferenceDialog(onDismissRequest = { showActions = false }, title = { Text("离线作品管理") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("已保存 ${state.jobs.sumOf { it.bytes } / 1024} KiB", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsRow("更新目录 / 下载章节", "", "offline-book-download", null, { showActions = false; download() })
            SettingsRow("下载队列", "", "offline-book-queue", null, { showActions = false; queue() })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { showActions = false; confirm = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = queueState.loaded && !queueState.controlling && state.jobs.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清理本作全部下载副本") }
            SettingsDisclosure("离线阅读说明") {
                Text("离线阅读会核验文件，不转为在线读取。未下载章节需先下载；不完整目录可在下载页继续加载。")
            }
        } }, confirmButton = { TextButton(onClick = { showActions = false }) { Text("关闭") } })
    if(confirm) ReferenceDialog(onDismissRequest={confirm=false},title={Text("清理本作全部下载副本？")},
        text={Text("仅清理此来源作品的 ${state.jobs.size} 项下载及队列记录，保留目录、书目、收藏、进度和原文件；其他来源同名作品不受影响。", Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={ReferenceDialogConfirm("清理下载副本", onClick={confirm=false;controller.batch(state.jobs.map { it.id }.toSet(),DownloadBatchAction.REMOVE)},
            enabled = queueState.loaded && !queueState.controlling && state.jobs.isNotEmpty(), destructive = true)},
        dismissButton={TextButton(onClick={confirm=false}) { Text("返回") }})
}
