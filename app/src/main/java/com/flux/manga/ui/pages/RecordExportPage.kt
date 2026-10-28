package com.flux.manga.ui.pages

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal enum class RecordExportPhase { IDLE, PICKING, READY, WRITING, CANCELLING, COMPLETE }
internal data class RecordExportState(val counts: RecordExportCounts? = null, val loading: Boolean = false,
    val phase: RecordExportPhase = RecordExportPhase.IDLE, val written: Long = 0, val message: String? = null)

internal class RecordExportModel(app: Application, private val session: WorldSession,
    private val store: WorldDataStore = WorldDataStore(app),
    private val resolver: ContentResolver = app.contentResolver) : AndroidViewModel(app) {
    private val identity = session.identity.value
    val world = identity.world
    val state = MutableStateFlow(RecordExportState())
    private var selected: Uri? = null
    private var job: Job? = null
    @Volatile private var closed = false
    fun active() = !closed && session.accepts(identity) && session.state.value.phase == SwitchPhase.Stable
    private fun foreground() = active() && session.state.value.foreground
    fun refresh(clearMessage: Boolean = true) {
        if (!foreground() || state.value.loading || state.value.phase != RecordExportPhase.IDLE) return
        state.value = state.value.copy(loading = true, message = if (clearMessage) null else state.value.message)
        job = viewModelScope.launch {
            try {
                val counts = store.recordExportData(world, ::foreground) { it.exportCounts() }
                ensureActive()
                if (foreground()) state.value = state.value.copy(counts = counts, loading = false)
            } catch (cancelled: CancellationException) { state.value = state.value.copy(loading = false); throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(loading = false, message = "记录读取失败，原数据保持。请刷新重试。") }
        }
    }
    fun preparePicker(): Boolean {
        if (!foreground() || state.value.loading || state.value.counts == null ||
            state.value.phase !in setOf(RecordExportPhase.IDLE, RecordExportPhase.COMPLETE)) return false
        selected = null
        state.value = state.value.copy(phase = RecordExportPhase.PICKING, written = 0, message = null)
        return true
    }
    fun filename() = "FluxManga-records-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json"
    fun pickerFailed() { if (!closed) state.value = state.value.copy(phase = RecordExportPhase.IDLE, message = "无法打开保存位置，请稍后重试。") }
    fun pickerUnavailable() { if (!closed) state.value = state.value.copy(message = "当前无法选择文件，请先结束尚未返回的文件选择，再重试。") }
    fun receive(uri: Uri?) {
        if (!active() || state.value.phase != RecordExportPhase.PICKING) return
        selected = uri?.takeIf { it.scheme == "content" }
        state.value = state.value.copy(phase = if (selected == null) RecordExportPhase.IDLE else RecordExportPhase.READY,
            message = when {
                uri == null -> "已取消选择，未导出记录。"
                selected == null -> "此保存位置不受支持，未写入记录，请重新选择。"
                else -> "已选择新文件，点击开始导出后写入当前空间记录。"
            })
    }
    fun abandonSelection() {
        selected = null
        state.value = state.value.copy(phase = RecordExportPhase.IDLE, message = "未写入记录；系统可能已创建空文件，可自行移除。")
    }
    fun start() {
        val uri = selected ?: return
        if (!foreground() || state.value.phase != RecordExportPhase.READY) return
        selected = null // One use only. Unknown outcomes always require a new destination.
        state.value = state.value.copy(phase = RecordExportPhase.WRITING, written = 0, message = null)
        job = viewModelScope.launch {
            try {
                val counts = exportRecordDocument(store, resolver, uri, world, ::foreground) { count ->
                    if (foreground()) state.update { it.copy(written = count) }
                }
                ensureActive()
                if (foreground()) state.value = state.value.copy(phase = RecordExportPhase.COMPLETE,
                    written = counts.values.sum(), message = "导出完成。此文件只包含记录，不能替代原文件或完整备份。")
            } catch (_: CancellationException) {
                if (!closed) state.value = state.value.copy(phase = RecordExportPhase.IDLE,
                    message = "导出已停止。目标可能为空或不完整，请检查后自行移除；重试会重新选择新文件。")
            } catch (_: Exception) {
                if (!closed) state.value = state.value.copy(phase = RecordExportPhase.IDLE,
                    message = "导出失败，原数据未删除。目标可能不完整，请检查可用空间与文件权限后重新选择新文件。")
            }
        }
    }
    fun cancel() {
        if (state.value.phase == RecordExportPhase.WRITING) {
            state.value = state.value.copy(phase = RecordExportPhase.CANCELLING)
            job?.cancel()
        }
    }
    override fun onCleared() { closed = true; selected = null; job?.cancel() }
}

/** Activity registration stays alive while the system picker covers/removes an entry. */
internal val LocalRecordExportPicker = staticCompositionLocalOf<(RecordExportModel) -> Unit> { { it.pickerFailed() } }

@Composable
internal fun RecordExportPage(session: WorldSession, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model: RecordExportModel = viewModel(factory = viewModelFactory { initializer { RecordExportModel(app, session) } })
    RecordExportContent(model, session, back)
}

@Composable
internal fun RecordExportContent(model: RecordExportModel, session: WorldSession, back: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val pick = LocalRecordExportPicker.current
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val visibility by session.state.collectAsState()
    val enabled = lifecycle == Lifecycle.State.RESUMED && visibility.foreground && model.active()
    // Configuration restoration must not erase the warning about an interrupted output file.
    LaunchedEffect(model) { model.refresh(clearMessage = false) }
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.cancel() }
    }
    val writing = state.phase in setOf(RecordExportPhase.WRITING, RecordExportPhase.CANCELLING)
    val leave: () -> Unit = { model.cancel(); back() }
    BackHandler(writing, leave)
    ReferenceRefreshColumn(refreshing = state.loading, refresh = { model.refresh() }, refreshEnabled = enabled && state.phase == RecordExportPhase.IDLE, modifier = Modifier.fillMaxSize().testTag("record-export"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("导出本地记录", if (model.world == PrototypeWorld.SFW) "仅表世界" else "仅里世界", leave) }
        item { SettingsPanel {
            Text("书目 · 书签与备注 · 阅读进度", style = MaterialTheme.typography.titleMedium)
            Text("导出为明文 JSON，包含书名和备注。请保存到可信位置。")
            SettingsDisclosure("导出范围", "仅当前空间的已保存记录") {
                Text("导出当前空间已保存的书目、书签、备注和阅读进度。")
                Text("仅导出记录，不含漫画与账号信息，不能用于恢复。")
            }
        } }
        state.counts?.let { counts -> item { SettingsGroup("最近读取的记录") {
            RecordCountRow("书目", counts.localBooks + counts.onlineBooks,
                "本地 ${counts.localBooks} / 在线 ${counts.onlineBooks}")
            SettingsDivider()
            RecordCountRow("书签与备注", counts.bookmarks)
            SettingsDivider()
            RecordCountRow("阅读进度", counts.localProgress + counts.onlineProgress)
            SettingsDivider()
            RecordCountRow("逐章记录", counts.chapterReadings)
            Text("导出开始时已保存的记录。", Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        if (state.loading || writing) item { RequestLoading() }
        state.message?.let { message -> item { RequestFeedback(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
        if (writing) item {
            Text("正在导出，已处理 ${state.written} 条。")
            TextButton(onClick = model::cancel, enabled = state.phase == RecordExportPhase.WRITING) {
                Text(if (state.phase == RecordExportPhase.CANCELLING) "正在停止…" else "停止导出")
            }
        } else if (state.phase == RecordExportPhase.READY) item {
            ReferenceButton(onClick = feedbackAttempt(model::start), enabled = enabled && acknowledged, modifier = Modifier.fillMaxWidth()) { Text("开始导出当前空间记录") }
            TextButton(onClick = model::abandonSelection) { Text("放弃此文件") }
        } else if (state.phase == RecordExportPhase.PICKING) item { Text("请选择保存位置。") }
        else item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(acknowledged, role = Role.Checkbox,
                onValueChange = { acknowledged = it }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, null)
                Text("我了解导出文件是明文，仅包含当前空间记录", Modifier.weight(1f))
            }
            ReferenceButton(onClick = { pick(model) }, modifier = Modifier.fillMaxWidth(),
                enabled = enabled && acknowledged && state.counts != null && !state.loading) {
                Text(if (state.phase == RecordExportPhase.COMPLETE) "另存一份" else "选择保存位置")
            }
            if (state.phase == RecordExportPhase.COMPLETE) Text("已导出 ${state.written} 条记录。")
        }
        item { SettingsDisclosure("中断与失败处理", "离开或进入后台会停止写入") {
            Text("导出时请停留本页。中断可能留下不完整文件，请删除后重新导出。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun RecordCountRow(title: String, count: Long, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
    }
}
