package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ChapterReadingState(val records: Map<String, ChapterReading> = emptyMap(),
    val loaded: Boolean = false, val busy: Boolean = false, val error: String? = null)

internal class ChapterReadingModel(private val repository: ChapterReadings) : ViewModel() {
    private val mutable = MutableStateFlow(ChapterReadingState())
    val state = mutable.asStateFlow()
    fun refresh() = request(null, false)
    fun mark(chapter: String, completed: Boolean) {
        if (state.value.loaded) request(chapter, completed)
    }
    private fun request(chapter: String?, completed: Boolean) {
        if (state.value.busy || !repository.active()) return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val rows = if (chapter == null) repository.load() else repository.mark(chapter, completed)
                ensureActive()
                if (repository.active()) mutable.value = ChapterReadingState(rows, loaded = true)
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) {
                if (repository.active()) mutable.value = state.value.copy(busy = false, loaded = false,
                    error = if (chapter == null) "章节记录读取失败，原记录保留" else "标记结果待核对，请重新读取后再操作")
            }
        }
    }
}

@Composable
internal fun rememberChapterReadings(session: WorldSession, source: String, manga: String): ChapterReadingModel {
    val app = LocalContext.current.applicationContext as Application
    val identity by session.identity.collectAsStateWithLifecycle()
    val key = listOf(identity.processSession, identity.world.name, identity.generation.toString(), source, manga).joinToString(":") { "${it.length}:$it" }
    val model: ChapterReadingModel = viewModel(key = "chapter-reading:$key", factory = viewModelFactory {
        initializer { ChapterReadingModel(ChapterReadings(WorldDataStore(app), session, source, manga)) }
    })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    return model
}

@Composable
internal fun ChapterReadingStatus(state: ChapterReadingState, refresh: () -> Unit) {
    if (state.busy) Text("正在核对章节记录…", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    state.error?.let {
        RequestFeedback(it, busy = state.busy, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = feedbackAttempt(refresh), enabled = !state.busy) { Text("重新读取章节记录") }
    }
}

@Composable
internal fun ChapterReadingAction(title: String, record: ChapterReading?, enabled: Boolean,
    showProgress: Boolean = true, mark: (Boolean) -> Unit) {
    var showActions by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showProgress) chapterReadingLabel(record)?.let { Text(it, Modifier.weight(1f, fill = false),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        ReferenceActionsButton("$title，阅读标记", { showActions = true })
    }
    if (showActions) ReferenceDialog(onDismissRequest = { showActions = false }, title = { Text(title) },
        text = { Column {
            Text(chapterReadingLabel(record) ?: "暂无阅读记录")
            TextButton(onClick = { showActions = false; mark(record?.completed != true) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (record?.completed == true) "取消已读标记" else "标记已读")
            }
        } }, confirmButton = { TextButton(onClick = { showActions = false }) { Text("关闭") } })
}

@Composable
internal fun RecordedDetailCatalog(session: WorldSession, source: String, manga: String,
    entries: List<DetailCatalogEntry>, lastId: String?, open: (String) -> Unit,
    busy: Boolean = false, hasMore: Boolean = false, more: (() -> Unit)? = null,
    appendError: String? = null, enabled: Boolean = true, totalCount: Long? = null) {
    val showSingle = session.identity.value.world == com.flux.manga.ui.navigation.PrototypeWorld.SFW
    if ((entries.isEmpty() || (entries.size == 1 && !showSingle)) && !hasMore && !busy && appendError == null) return
    val model = rememberChapterReadings(session, source, manga)
    val state by model.state.collectAsStateWithLifecycle()
    var selectedChapter by rememberSaveable(source, manga) { mutableStateOf<String?>(null) }
    DetailCatalog(entries, lastId, open, busy = busy, hasMore = hasMore, more = more,
        appendError = appendError, enabled = enabled, totalCount = totalCount, showSingle = showSingle,
        completed = state.records.filterValues { it.completed }.keys,
        readingLabels = state.records.mapNotNull { (id, record) -> record.ordinal?.let { id to "第 $it 页" } }.toMap(),
        recordStatus = { ChapterReadingStatus(state, model::refresh) },
        chapterActions = { selectedChapter = it.id })
    entries.firstOrNull { it.id == selectedChapter }?.let { entry ->
        val record = state.records[entry.id]
        ReferenceDialog(onDismissRequest = { selectedChapter = null }, title = { Text(entry.title) },
            text = { Column {
                if (entry.description.isNotBlank()) Text(entry.description)
                Text(chapterReadingLabel(record) ?: "暂无阅读记录")
                TextButton(onClick = { selectedChapter = null; model.mark(entry.id, record?.completed != true) },
                    enabled = state.loaded && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (record?.completed == true) "取消已读标记" else "标记已读")
                }
            } }, confirmButton = { TextButton({ selectedChapter = null }) { Text("关闭") } })
    }
}
