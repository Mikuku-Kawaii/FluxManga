package com.flux.manga.ui.pages

import android.app.Application
import android.Manifest
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
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
import kotlinx.coroutines.flow.*

internal data class DownloadChaptersState(val items: List<SourceChapter> = emptyList(), val loaded: Boolean=false,
    val next: SourceContinuation=SourceContinuation.Next(SourcePageRequest.Start), val busy: Boolean=false, val error: String?=null)
internal class DownloadChaptersModel(app: Application, session: WorldSession, source: String, manga: String) : ViewModel() {
    private val repository=OnlineReaderRepository(app,session,source,manga,false)
    private val catalogs=OfflineCatalogStore(WorldDataStore(app).accountImportDatabase())
    private val sourceId=source; private val mangaId=manga
    private val mutable=MutableStateFlow(DownloadChaptersState()); val state=mutable.asStateFlow()
    private var work: Job?=null; private var revision=0L
    fun load(all: Boolean = false) {
        if(state.value.busy || state.value.next !is SourceContinuation.Next) return
        val ticket=++revision; mutable.value=state.value.copy(busy=true,error=null)
        work=viewModelScope.launch {
            try {
                val seen=mutableSetOf<SourcePageRequest>()
                do {
                    val next=state.value.next as? SourceContinuation.Next ?: break
                    require(seen.add(next.request)) { "目录分页未前进" }
                    val page=repository.chapters(next.request); ensureActive(); repository.active()
                    if(ticket!=revision) return@launch
                    val entries=(state.value.items+page.items).distinctBy { it.id }; require(entries.size<=10000)
                    require(page.continuation is SourceContinuation.End || entries.size>state.value.items.size) { "目录分页没有新增章节" }
                    catalogs.save(OfflineCatalog(sourceId,mangaId,page.continuation is SourceContinuation.End,entries)) { repository.isActive() }
                    mutable.value=state.value.copy(items=entries,next=page.continuation,loaded=true)
                } while(all && state.value.next is SourceContinuation.Next)
            } catch(c: CancellationException) { throw c }
            catch(e: Exception) { if(ticket==revision) mutable.value=state.value.copy(error=downloadFailure(e)) }
            finally { if(ticket==revision) mutable.value=state.value.copy(busy=false) }
        }
    }
    fun leave() { revision++; work?.cancel(); mutable.value=state.value.copy(busy=false) }
}
@Composable
private fun downloadOwner(session: WorldSession): DownloadController {
    val context=LocalContext.current
    val identity by session.identity.collectAsStateWithLifecycle()
    val owner=remember(session,identity.generation) { session.downloads(context.applicationContext) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(owner,lifecycle) { if(lifecycle==Lifecycle.State.RESUMED) owner.start() }
    return owner
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadChaptersPage(session: WorldSession, source: String, manga: String, back: () -> Unit, queue: () -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if(identity.world!=PrototypeWorld.SFW) { SettingsHeading("下载尚未开放","请返回表世界",back); return }
    val app=LocalContext.current.applicationContext as Application
    val model: DownloadChaptersModel=viewModel(key="download-chapters:$source:$manga",factory=viewModelFactory {
        initializer { DownloadChaptersModel(app,session,source,manga) }
    })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(model,lifecycle) { if(lifecycle==Lifecycle.State.RESUMED && !model.state.value.loaded) model.load(); onDispose { model.leave() } }
    val chapters by model.state.collectAsStateWithLifecycle()
    val owner=downloadOwner(session); val state by owner.state.collectAsStateWithLifecycle()
    var selection by rememberSaveable(source,manga) { mutableStateOf(emptyList<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var query by rememberSaveable(source, manga) { mutableStateOf("") }
    var showTools by rememberSaveable(source, manga) { mutableStateOf(false) }
    val visibleChapters = remember(chapters.items, query) { chapters.items.filter { downloadTitleMatches(it.title, query) } }
    val jobs = remember(state.jobs, source, manga) { state.jobs.filter { it.source == source && it.manga == manga }.associateBy { it.chapter } }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("download-chapters"),contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("选择下载章节","",back, actions = {
            ReferenceActionsButton("目录操作与下载说明", { showTools = true })
        }) }
        item { TransferPanel(title = "下载选择") {
            DownloadTitleFilter(query, { query = it }, visibleChapters.size, chapters.items.size, !state.controlling)
            DownloadSelectionSummary(selection, visibleChapters.map { it.id })
            if (selection.size == 200) Text("已达单次 200 章上限；可先取消部分选择，再添加其他章节。")
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={selection=addDownloadSelection(selection, visibleChapters.map { it.id })},
                    enabled=!state.controlling && visibleChapters.isNotEmpty() && selection.size<200) { Text("选择当前结果") }
                if (selection.isNotEmpty()) TextButton(onClick={selection=emptyList()}, enabled=!state.controlling) { Text("清除选择") }
                TextButton(onClick=queue) { Text("查看下载队列") }
            }
            ReferenceButton(onClick={confirm=true}, modifier = Modifier.fillMaxWidth(),
                enabled=state.loaded && !state.controlling && selection.isNotEmpty()) { Text("加入下载队列 ${selection.size} 章") }
            state.error?.let { RequestFeedback(it,color=MaterialTheme.colorScheme.error); TextButton(onClick=feedbackAttempt(owner::start)) { Text("重新读取队列") } }
            if(state.controlling) Text("正在保存队列…",Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            val count=state.jobs.count { it.source==source && it.manga==manga && it.status!=DownloadStatus.CANCELLED }
            if (count > 0) Text("已有 $count 章下载任务", style = MaterialTheme.typography.bodySmall)
        } }
        if (chapters.items.isNotEmpty() && visibleChapters.isEmpty()) item { Text("已加载章节中没有匹配结果，可清除筛选或继续加载目录。") }
        items(visibleChapters,key={it.id}) { chapter ->
            DownloadChapterChoice(chapter.title, chapter.id in selection,
                !state.controlling && (chapter.id in selection || selection.size < 200), jobs[chapter.id]?.status?.downloadLabel()) { checked ->
                selection = if (checked) addDownloadSelection(selection, listOf(chapter.id)) else selection - chapter.id
            }
        }
        item {
            if(chapters.busy) { RequestLoading(); TextButton(onClick=model::leave) { Text("停止目录请求") } }
            chapters.error?.let { RequestFeedback(it,color=MaterialTheme.colorScheme.error) }
            if(chapters.loaded && chapters.items.isEmpty() && chapters.next is SourceContinuation.End) Text("暂无可下载章节")
            if(chapters.next is SourceContinuation.Next) TextButton(onClick=feedbackAttempt({model.load()}),enabled=!chapters.busy && chapters.items.size<10000) { Text(if (chapters.error != null) "重试加载章节" else "加载更多章节") }
        }
    }
    if (showTools) ReferenceSheet(onDismissRequest = { showTools = false }, title = { Text("目录操作") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selection = chapters.items.take(200).map { it.id }; showTools = false }, enabled = !state.controlling) { Text("选择已加载章节（最多 200）") }
            Text(if (chapters.next is SourceContinuation.End) "完整目录已缓存" else "目录尚未完整缓存")
            if (chapters.next is SourceContinuation.Next) TextButton(onClick = feedbackAttempt({ model.load(true); showTools = false }), enabled = !chapters.busy) { Text("缓存完整章节目录") }
            Text("单次最多选择 200 章，相同章节不会重复添加。下载保存应用副本，保留收藏、进度和原文件。")
            Text("后台下载需在队列开启；否则离开前台暂停。切换空间始终暂停。只有下载完成的章节可离线阅读。")
        } }, confirmButton = { TextButton(onClick = { showTools = false }) { Text("关闭") } })
    if(confirm) ReferenceDialog(onDismissRequest={confirm=false},title={Text("下载选中的章节？")},text={Text("将保存 ${selection.size} 章的图片副本，可能使用移动网络。已有下载去重；原书目和阅读位置保持。", Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={ReferenceDialogConfirm("加入下载队列", onClick={confirm=false; owner.enqueue(source,manga,chapters.items.filter { it.id in selection }.map { it.id to it.title })},
            enabled = state.loaded && !state.controlling && selection.isNotEmpty())},
        dismissButton={TextButton(onClick={confirm=false}) { Text("返回") }})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadQueuePage(session: WorldSession, back: () -> Unit, catalog: (String,String) -> Unit, read: (String,String,String) -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if(identity.world!=PrototypeWorld.SFW) { DownloadsUnavailablePage(back); return }
    val owner=downloadOwner(session); val state by owner.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<ChapterDownload?>(null) }
    var selected by rememberSaveable(identity.generation) { mutableStateOf(emptyList<String>()) }
    var filter by rememberSaveable(identity.generation) { mutableStateOf(DownloadQueueFilter.ALL) }
    var query by rememberSaveable(identity.generation) { mutableStateOf("") }
    var selecting by rememberSaveable(identity.generation) { mutableStateOf(false) }
    var showFilters by rememberSaveable(identity.generation) { mutableStateOf(false) }
    var showPolicy by rememberSaveable(identity.generation) { mutableStateOf(false) }
    var actionJobId by rememberSaveable(identity.generation) { mutableStateOf<String?>(null) }
    val visibleJobs = remember(state.jobs, filter, query) { filterDownloadJobs(state.jobs, filter, query) }
    LaunchedEffect(state.jobs, state.loaded) {
        if (state.loaded) selected = selected.filter { id -> state.jobs.any { it.id == id } }
    }
    var clearSelected by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val network=remember(session,identity.generation) { session.downloadNetwork(context.applicationContext) }
    val policy by network.policy.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("download-queue"),contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("下载队列", "", back, actions = {
            IconButton(onClick = { showPolicy = true }, modifier = Modifier.semantics { contentDescription = "下载网络设置" }) {
                ReferenceIcon(ReferenceSymbol.OPTIONS)
            }
        }) }
        item {
            Text(policy.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.networkMessage?.let { Text(it,Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
        }
        item {
            ReferenceActionRow(text = { Text("任务 · ${visibleJobs.size}", it, style = MaterialTheme.typography.titleLarge) }, actions = {
                TextButton(onClick = { showFilters = true }) { Text("筛选") }
                TextButton(onClick = { selecting = !selecting; if (!selecting) selected = emptyList() }, enabled = !state.controlling) { Text(if (selecting) "完成管理" else "批量管理") }
            })
            if (query.isNotBlank() || filter != DownloadQueueFilter.ALL) ReferenceActionRow(text = { modifier ->
                Text(listOfNotNull(filter.takeIf { it != DownloadQueueFilter.ALL }?.label, query.takeIf { it.isNotBlank() }).joinToString(" · "),
                    modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }, actions = {
                TextButton(onClick = { query = ""; filter = DownloadQueueFilter.ALL }, enabled = !state.controlling) { Text("清除") }
            })
        }
        if (selecting) item { TransferPanel {
            DownloadSelectionSummary(selected, visibleJobs.map { it.id })
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={selected=state.jobs.map { it.id }}, enabled=!state.controlling) { Text("全选整个队列") }
                TextButton(onClick={selected=addDownloadSelection(selected, visibleJobs.map { it.id })},
                    enabled=!state.controlling && visibleJobs.isNotEmpty()) { Text("追加筛选结果") }
                TextButton(onClick={selected=emptyList()}, enabled=!state.controlling) { Text("清除选择") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={owner.batch(selected.toSet(),DownloadBatchAction.PAUSE)},enabled=state.loaded && !state.controlling && selected.isNotEmpty()) { Text("暂停选中") }
                TextButton(onClick={owner.batch(selected.toSet(),DownloadBatchAction.RESUME)},enabled=state.loaded && !state.controlling && selected.isNotEmpty()) { Text("继续 / 重试选中") }
            }
            TextButton(onClick={clearSelected=true},shape = MaterialTheme.shapes.small,enabled=state.loaded && !state.controlling && selected.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移除选中下载及记录") }
        } }
        item {
            if(!state.loaded && !state.controlling) TextButton(onClick=feedbackAttempt(owner::start)) { Text("读取 / 恢复队列") }
            if(state.controlling) { RequestLoading(); Text("正在处理并核对已保存状态…",Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
            state.error?.let { RequestFeedback(it,color=MaterialTheme.colorScheme.error) }
            if(state.loaded && state.jobs.isEmpty()) ReferenceEmptyState("暂无下载任务，请在作品详情选择章节")
        }
        if (state.loaded && state.jobs.isNotEmpty() && visibleJobs.isEmpty()) item {
            Text("当前筛选没有下载任务；已有任务与选择仍保留。")
            TextButton(onClick={filter=DownloadQueueFilter.ALL;query=""}, enabled=!state.controlling) { Text("显示全部任务") }
        }
        items(visibleJobs,key={it.id}) { job -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).then(if (selecting) Modifier.toggleable(job.id in selected,enabled=!state.controlling,role=Role.Checkbox,
                onValueChange={selected=if(it) addDownloadSelection(selected, listOf(job.id)) else selected-job.id}) else Modifier),verticalAlignment=Alignment.CenterVertically) {
                if (selecting) Checkbox(job.id in selected,null,enabled=!state.controlling)
                Text(job.title,Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (!selecting) ReferenceActionsButton("${job.title}，下载操作", { actionJobId = job.id })
            }
            DownloadTaskProgress(job)
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(job.status in listOf(DownloadStatus.PAUSED,DownloadStatus.FAILED)) TextButton(onClick={owner.resume(job.id)},enabled=state.loaded && !state.controlling) { Text("继续 / 重试") }
                if(job.status in listOf(DownloadStatus.QUEUED,DownloadStatus.RUNNING)) TextButton(onClick={owner.stop(job.id)},enabled=state.loaded && !state.controlling) { Text("暂停") }
                if(job.status==DownloadStatus.COMPLETE) TextButton(onClick={read(job.source,job.manga,job.chapter)},enabled=!state.controlling) {
                    ReferenceIcon(ReferenceSymbol.PLAY); Spacer(Modifier.width(6.dp)); Text("离线阅读")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        } }
    }
    if (showFilters) ReferenceSheet(onDismissRequest = { showFilters = false }, title = { Text("筛选下载任务") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DownloadFilterChips(filter, state.jobs, !state.controlling) { filter = it }
            DownloadTitleFilter(query, { query = it }, visibleJobs.size, state.jobs.size, !state.controlling, "筛选任务标题")
        } }, confirmButton = { TextButton({ showFilters = false }) { Text("完成") } })
    if (showPolicy) ReferenceSheet(onDismissRequest = { showPolicy = false }, title = { Text("下载设置") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadBackgroundPanel(session)
            Text("网络条件", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadNetworkPolicy.entries.forEach { value ->
                    SettingsChoiceRow(value.label, policy == value, { network.set(value) })
                }
            }
            Text("条件恢复只继续本轮排队任务；手动暂停、失败与重开队列不会自动续办。计费网络可能产生流量费用。")
            Text("已保存 ${state.jobs.sumOf { it.bytes } / 1024} KiB。取消或删除只清理下载副本，保留原文件与阅读进度。")
        } }, confirmButton = { TextButton(onClick = { showPolicy = false }) { Text("关闭") } })
    state.jobs.firstOrNull { it.id == actionJobId }?.let { job ->
        ReferenceSheet(onDismissRequest = { actionJobId = null }, title = { Text(job.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(job.status.downloadLabel())
                TextButton(onClick = { actionJobId = null; catalog(job.source, job.manga) }) { Text("离线目录") }
                TextButton(onClick = { actionJobId = null; confirm = job }, enabled = state.loaded && !state.controlling,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(when (job.status) { DownloadStatus.COMPLETE -> "删除此章节下载"; DownloadStatus.CANCELLED -> "移除此任务记录"; else -> "取消并清理此任务" })
                }
            } }, confirmButton = { TextButton(onClick = { actionJobId = null }) { Text("关闭") } })
    }
    if(clearSelected) ReferenceDialog(onDismissRequest={clearSelected=false},title={Text("移除选中下载？")},
        text={Text("清理所选 ${selected.size} 章的下载副本及队列记录；保留书目、收藏、目录、进度和原文件。中断时已完成的清理不会回滚，请重新读取核对。", Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={ReferenceDialogConfirm("移除所选下载", onClick={clearSelected=false;owner.batch(selected.toSet(),DownloadBatchAction.REMOVE);selected=emptyList()}, destructive = true)},
        dismissButton={TextButton(onClick={clearSelected=false}) { Text("返回") }})
    confirm?.let { job -> ReferenceDialog(onDismissRequest={confirm=null},title={Text(when(job.status) { DownloadStatus.COMPLETE -> "删除下载副本？"; DownloadStatus.CANCELLED -> "移除已取消记录？"; else -> "取消下载？" })},
        text={Text("仅清理此章节的应用下载副本，保留书目、收藏、阅读进度和用户原文件。", Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={ReferenceDialogConfirm(when (job.status) {
            DownloadStatus.COMPLETE -> "删除下载副本"; DownloadStatus.CANCELLED -> "移除任务记录"; else -> "取消并清理"
        }, onClick={confirm=null; owner.stop(job.id,true)}, destructive = true)},dismissButton={TextButton(onClick={confirm=null}) { Text("返回") }}) }
}

@Composable
private fun DownloadBackgroundPanel(session: WorldSession) {
    val context = LocalContext.current
    val identity by session.identity.collectAsStateWithLifecycle()
    val background = remember(session, identity.generation) { session.downloadBackground(context.applicationContext) }
    val state by background.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var permitted by remember { mutableStateOf(ChapterDownloadService.notificationsAllowed(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = ChapterDownloadService.notificationsAllowed(context)
    }
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) permitted = ChapterDownloadService.notificationsAllowed(context)
    }
    TransferPanel {
        Text(if (state.running) "本轮后台下载已开启" else if (state.starting) "正在连接后台服务…" else "后台下载未开启",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.message?.let { RequestFeedback(it) }
        if (state.running || state.starting) TextButton(onClick = { background.stop() }) { Text("暂停全部并关闭后台下载") }
        TransferDisclosure("后台下载与通知设置") {
            Text("先允许通知并开始或继续任务，再开启本轮后台下载。结束、切空间或系统停止后需重新开启；不会自动续办。切换空间始终暂停。")
            if (!permitted) {
                Text("下载通知未允许或已关闭；前台队列仍可使用。")
                if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许进度通知") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("打开系统通知设置") }
            }
            if (!state.running && !state.starting) TextButton(onClick = { background.enable() }, enabled = permitted) { Text("开启本轮后台下载") }
        }
    }
}
