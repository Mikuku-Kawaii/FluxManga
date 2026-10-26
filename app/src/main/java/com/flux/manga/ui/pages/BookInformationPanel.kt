package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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

internal data class BookInformationState(val running: Boolean = false, val stopping: Boolean = false,
    val processed: Int = 0, val total: Int = 0, val failed: Int = 0, val title: String = "", val message: String? = null,
    val revision: Int = 0)
internal class BookInformationModel(app: Application, private val session: WorldSession, private val source: String?) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val data = WorldDataStore(app)
    private val loader = OnlineBookInformationLoader(app, session)
    val state = MutableStateFlow(BookInformationState())
    private var job: Job? = null
    private fun active() = session.accepts(identity) && session.state.value.foreground
    fun start(force: Boolean = false) {
        if (!active() || job?.isCompleted == false) return
        state.value = BookInformationState(running = true, revision = state.value.revision)
        job = viewModelScope.launch {
            try {
                val books = data.worldData(identity.world, ::active) { dao -> dao.onlineBooks().filter { source == null || it.sourceId == source } }
                state.update { it.copy(total = books.size) }
                for (book in books) {
                    ensureActive(); if (!active()) throw CancellationException()
                    state.update { it.copy(title = book.title) }
                    try {
                        val result = loader.load(book.sourceId, book.mangaId, force)
                        if (result.coverError != null || result.metadataError != null) state.update { it.copy(failed = it.failed + 1) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { state.update { it.copy(failed = it.failed + 1) } }
                    state.update { it.copy(processed = it.processed + 1, revision = it.revision + 1) }
                }
                state.update { it.copy(message = if (it.failed == 0) "作品资料已补齐；源未提供的字段保持为空" else "${it.failed} 本资料或封面未补齐，可检查图源账号后重试") }
            } catch (cancelled: CancellationException) {
                state.update { it.copy(message = "已停止，已保存资料保留；可继续补齐") }; throw cancelled
            } catch (_: Exception) { state.update { it.copy(message = "无法读取待补齐书目，请重试") } }
            finally { state.update { it.copy(running = false, stopping = false, title = "", revision = it.revision + 1) } }
        }
    }
    fun stop() { if (state.value.running) state.update { it.copy(stopping = true) }; job?.cancel() }
    override fun onCleared() { stop() }
}

/** Bind work to the page, never to a lazy item which disappears when scrolled away. */
@Composable
internal fun rememberBookInformation(session: WorldSession, source: String? = null): BookInformationModel {
    val app = LocalContext.current.applicationContext as Application
    val identity by session.identity.collectAsStateWithLifecycle()
    val model: BookInformationModel = viewModel(key = "book-info:${identity.generation}:${source ?: "all"}",
        factory = viewModelFactory { initializer { BookInformationModel(app, session, source) } })
    val owner = LocalLifecycleOwner.current
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    return model
}

@Composable
internal fun BookInformationPanel(model: BookInformationModel, enabled: Boolean = true) {
    val state by model.state.collectAsStateWithLifecycle()
    AccountPanel("导入书目的资料") {
        Text("补齐封面、作者、简介和源标签。已保存资料可直接复用；不改收藏、阅读位置或自定义分组。",
            style = MaterialTheme.typography.bodySmall)
        if (state.running) {
            Text("已处理 ${state.processed} / ${state.total} 本 · 未补齐 ${state.failed} 本")
            if (state.title.isNotBlank()) Text(state.title)
            RequestLoading()
            TextButton(model::stop, enabled = !state.stopping) { Text(if (state.stopping) "正在停止…" else "取消 · 保留已补齐资料") }
        } else {
            ReferenceButton(feedbackAttempt({ model.start() }), enabled = enabled) { Text("补齐资料 / 继续或重试") }
            TextButton(feedbackAttempt({ model.start(force = true) }), enabled = enabled) { Text("重新获取已导入作品资料") }
        }
        state.message?.let { RequestFeedback(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookInformationSummary(info: OnlineBookInformation?, expanded: Boolean = false) {
    if (info == null) return
    if (!expanded) {
        val summary = info.author.takeIf { it.isNotBlank() } ?: info.status.takeIf { it.isNotBlank() } ?: return
        Text(summary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (info.author.isNotBlank()) Text(info.author, style = MaterialTheme.typography.bodySmall)
    if (info.status.isNotBlank()) Text(info.status, style = MaterialTheme.typography.bodySmall)
    if (info.tags.isNotEmpty()) FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { info.tags.forEach { tag ->
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(end = 4.dp)) { Text(tag, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
    } }
    if (info.updated.isNotBlank()) Text("图源更新：${info.updated}")
    if (info.summary.isNotBlank()) Text(info.summary)
    if (info.partial) Text("图源返回了部分资料", style = MaterialTheme.typography.bodySmall)
}
