package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class FavoritesImportState(
    val loaded: Boolean = false, val running: Boolean = false, val stopping: Boolean = false,
    val task: PrivateFavoritesTask? = null, val message: String? = null, val error: Boolean = false,
)

internal class FavoritesImportModel(app: Application, private val session: WorldSession, private val source: String) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val importer = PrivateFavoritesImport(app, session, source)
    val state = MutableStateFlow(FavoritesImportState())
    private var job: Job? = null
    private fun active() = session.accepts(identity) && session.state.value.foreground
    private fun failure(error: Exception) = when {
        isJmSource(source) -> jmFailure(error)
        isPicaSource(source) -> picaFailure(error)
        else -> ehFailure(error, GallerySite.forSource(source))
    }
    fun load() {
        if (!active() || job?.isCompleted == false) return
        job = viewModelScope.launch {
            try { val task = importer.task(); if (active()) state.update { it.copy(loaded = true, task = task) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (active()) state.update { it.copy(message = failure(error), error = true) } }
        }
    }
    fun start(rescan: Boolean = false) {
        if (!active() || job?.isCompleted == false) return
        state.update { it.copy(running = true, stopping = false, message = null, error = false) }
        job = viewModelScope.launch {
            try {
                importer.run(CoroutineScope(currentCoroutineContext()), rescan) { task ->
                    if (active()) state.update { it.copy(loaded = true, task = task) }
                }
                if (active()) state.update { it.copy(message = "已读至收藏末页，本轮导入完成") }
            } catch (cancelled: CancellationException) {
                if (session.accepts(identity)) state.update { it.copy(message = "已停止；续办前会核对已保存进度", error = false) }
                throw cancelled
            } catch (error: Exception) {
                if (active()) state.update { it.copy(message = failure(error), error = true) }
            } finally { state.update { it.copy(running = false, stopping = false) } }
        }
    }
    fun stop() {
        if (state.value.running) state.update { it.copy(stopping = true) }
        job?.cancel()
    }
    override fun onCleared() { stop() }
}

@Composable
internal fun rememberPrivateFavoritesImport(session: WorldSession, source: String?): FavoritesImportModel? {
    if (source == null) return null
    val app = LocalContext.current.applicationContext as Application
    val identity by session.identity.collectAsStateWithLifecycle()
    val model: FavoritesImportModel = viewModel(key = "favorites-all:${identity.generation}:$source",
        factory = viewModelFactory { initializer { FavoritesImportModel(app, session, source) } })
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.load() }
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    return model
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivateFavoritesImportPanel(model: FavoritesImportModel, previewBusy: Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    AccountPanel("全部收藏") {
        Text("导入全部云收藏，忽略当前筛选。离页或进入后台会暂停，可返回继续。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val task = state.task
        task?.let {
            Text("已保存 ${it.pages} 页 · 新增 ${it.added} 本 · 已有 ${it.existing} 条")
            Text(if (it.done) "已读至末页" else "尚未完成，可从已保存位置继续", style = MaterialTheme.typography.bodySmall)
        }
        if (state.running) {
            RequestLoading()
            TextButton(model::stop, enabled = !state.stopping) { Text(if (state.stopping) "正在停止…" else "取消 · 保留进度") }
        } else {
            if (state.loaded) FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReferenceButton(feedbackAttempt({ model.start(rescan = task?.done == true) }), enabled = !previewBusy) {
                    Text(if (task != null && !task.done) "继续导入全部收藏" else if (task?.done == true) "重新导入全部收藏" else "一键导入全部收藏")
                }
                if (task != null && !task.done) TextButton(feedbackAttempt({ model.start(rescan = true) }), enabled = !previewBusy) { Text("从头重扫 · 保留已导入书目") }
            } else TextButton(feedbackAttempt(model::load)) { Text("读取导入进度") }
        }
        state.message?.let { RequestFeedback(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, failure = state.error, busy = state.running) }
        Text("新书目加入本机收藏，已有记录保留。列表有变化时可重新扫描。",
            style = MaterialTheme.typography.bodySmall)
    }
}
