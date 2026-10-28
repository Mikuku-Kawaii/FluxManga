package com.flux.manga.ui.pages

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

internal data class GalleryTagsState(val dictionary: GalleryTagDictionary? = null, val preview: GalleryTagDictionary? = null,
    val enabled: Boolean = true, val ready: Boolean = false, val busy: Boolean = false, val picking: Boolean = false,
    val selected: Boolean = false, val query: String = "", val namespace: String? = null,
    val matches: List<GalleryTag> = emptyList(), val more: Boolean = false, val searching: Boolean = false, val message: String? = null)

internal class GalleryTagsModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val store = GalleryTagStore(app, session)
    private var selected: Uri? = null
    private var work: Job? = null
    private var search: Job? = null
    private var limit = 50
    private var closed = false
    val state = MutableStateFlow(GalleryTagsState())
    fun active() = !closed && store.active()
    private fun foreground() = active() && session.state.value.foreground
    private fun run(action: suspend () -> Unit) {
        if (!foreground() || state.value.busy) return
        state.update { it.copy(busy = true, message = null) }
        work = viewModelScope.launch {
            try { action(); ensureActive() }
            catch (_: CancellationException) { if (active()) state.update { it.copy(message = "已停止；重新载入可确认当前词库。") } }
            catch (error: Exception) {
                if (active()) state.update { it.copy(message = if (error is IllegalArgumentException) error.message else "词库操作失败，请重新载入或重新选择文件。原书库与文件保持。") }
            } finally { if (active()) state.update { it.copy(busy = false) } }
        }
    }
    fun start() { if (!state.value.ready) refresh() }
    fun refresh() = run {
        val dictionary = store.load(); val enabled = store.enabled()
        if (!foreground()) throw CancellationException()
        state.update { it.copy(dictionary = dictionary, enabled = enabled, ready = true) }; find()
    }
    fun preparePicker(): Boolean {
        if (!foreground() || state.value.busy || state.value.picking) return false
        selected = null; state.update { it.copy(picking = true, selected = false, preview = null, message = null) }; return true
    }
    fun receive(uri: Uri?) {
        if (!active() || !state.value.picking) return
        selected = uri?.takeIf { it.scheme == "content" }
        state.update { it.copy(picking = false, selected = selected != null, message = if (selected == null) "已取消选择，词库保持。" else "已选择文件，读取预览后再确认替换。") }
    }
    fun pickerFailed() { if (active()) state.update { it.copy(picking = false, message = "无法打开文件选择器，请重试。") } }
    fun pickerUnavailable() { if (active()) state.update { it.copy(message = "请先结束尚未返回的文件选择。") } }
    fun preview() {
        val uri = selected ?: return
        run {
            val dictionary = store.preview(uri)
            if (!foreground()) throw CancellationException()
            selected = null; state.update { it.copy(preview = dictionary, selected = false) }
        }
    }
    fun discard() { selected = null; state.update { it.copy(preview = null, selected = false, message = "未替换词库。") } }
    fun install() {
        val dictionary = state.value.preview ?: return
        run {
            store.save(dictionary)
            if (!foreground()) throw CancellationException()
            state.update { it.copy(dictionary = dictionary, preview = null, ready = true, message = "词库已替换，书目和原标签保持。") }; find()
        }
    }
    fun remove() = run {
        store.remove(); search?.cancel(); selected = null
        state.update { it.copy(dictionary = null, preview = null, selected = false, matches = emptyList(), more = false,
            searching = false, ready = true, message = "已移除应用词库副本，用户原文件保持。") }
    }
    fun enabled(value: Boolean) = run { store.enabled(value); state.update { it.copy(enabled = value) } }
    fun query(value: String) { state.update { it.copy(query = value.take(200)) }; limit = 50; find() }
    fun namespace(value: String?) { state.update { it.copy(namespace = value) }; limit = 50; find() }
    fun more() { limit += 50; find() }
    private fun find() {
        search?.cancel()
        val snapshot = state.value; val dictionary = snapshot.dictionary ?: return
        if (!foreground()) return
        state.update { it.copy(searching = true, matches = emptyList(), more = false) }
        val count = limit
        search = viewModelScope.launch {
            val result = dictionary.search(snapshot.query, snapshot.namespace, count)
            if (foreground()) state.update { it.copy(matches = result.entries, more = result.more, searching = false) }
        }
    }
    fun stop() {
        work?.cancel(); search?.cancel()
        state.update { it.copy(dictionary = null, preview = null, matches = emptyList(), ready = false, more = false, searching = false) }
    }
    override fun onCleared() { closed = true; stop(); selected = null }
}

internal val LocalGalleryTagsPicker = staticCompositionLocalOf<(GalleryTagsModel) -> Unit> { { it.pickerFailed() } }

@Composable internal fun galleryTagsModel(session: WorldSession, enabled: Boolean = true): GalleryTagsModel {
    val app = LocalContext.current.applicationContext as Application
    val model = preloadedPages(session).galleryTags()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle, enabled) { if (enabled && lifecycle == Lifecycle.State.RESUMED) model.start() }
    return model
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun GalleryTagsPage(session: WorldSession, site: GallerySite, back: () -> Unit, open: (String) -> Unit) {
    val model = galleryTagsModel(session); val state by model.state.collectAsStateWithLifecycle()
    val pick = LocalGalleryTagsPicker.current; val context = LocalContext.current
    var removing by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    ReferenceRefreshColumn(refreshing = state.busy, refresh = model::refresh, state = scroll,
        modifier = Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("中文标签", site.label, back) }
        item {
            if (state.busy) { RequestLoading(); TextButton(model::stop) { Text("停止") } }
            state.message?.let { RequestFeedback(it) }
            TextButton(feedbackAttempt(model::refresh), enabled = !state.busy) { Text("重新载入") }
        }
        item { SettingsPanel {
            val dictionary = state.dictionary
            Text(dictionary?.let { "已载入 ${it.entries.size} 个标签 · ${it.revision.take(12)}" } ?: "尚未载入词库")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("详情显示中文译名", Modifier.weight(1f))
                Switch(state.enabled, model::enabled, enabled = state.ready && !state.busy)
            }
            Text("EH 与 EX 共用词库；选择中文候选后，使用原标签搜索。账号与搜索结果仍各自独立。")
        } }
        item { SettingsDisclosure("导入、更新与许可") {
            Text("下载 EhTagTranslation 的 db.text.json（v7，最多 16 MiB），选择文件后确认导入。")
            Text("词库来自 EhTagTranslation，许可为 CC BY-NC-SA 3.0 CN。仅导入名称索引。")
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GALLERY_TAG_RELEASES))) }) { Text("获取词库") }
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GALLERY_TAG_LICENSE))) }) { Text("查看数据许可") }
                ReferenceButton({ pick(model) }, enabled = !state.busy && !state.picking) { Text("选择词库文件") }
                if (state.selected) TextButton(model::preview, enabled = !state.busy) { Text("读取预览") }
                if (state.dictionary != null) TextButton({ removing = true }, enabled = !state.busy) { Text("移除词库副本") }
            }
            state.dictionary?.let { Text("当前文件 SHA-256：${it.digest}") }
        } }
        state.preview?.let { preview -> item { ReferencePanel {
            Text("待替换：${preview.entries.size} 个标签", style = MaterialTheme.typography.titleMedium)
            Text("文件版本：${preview.revision}（文件自述）")
            Text("SHA-256：${preview.digest}")
            Text("将替换现有名称索引；书目、账号、进度与用户原文件保持。词库不随书库备份迁移。")
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { ReferenceButton(model::install, enabled = !state.busy) { Text("确认替换") }; TextButton(model::discard, enabled = !state.busy) { Text("取消") } }
        } } }
        if (state.dictionary != null) {
            item { ReferenceSearchField(state.query, model::query, "查找中文或原标签", Modifier.fillMaxWidth()) }
            item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.namespace == null, { model.namespace(null) }, label = { Text("全部") })
                state.dictionary!!.namespaceKeys.forEach { ns ->
                    FilterChip(state.namespace == ns, { model.namespace(ns) }, label = { Text(state.dictionary!!.namespaces[ns] ?: ns) })
                }
            } }
            if (state.searching) item { RequestLoading() }
            else if (state.matches.isEmpty()) item { Text("没有匹配标签，保留输入，可修改关键词。") }
            items(state.matches, key = { it.key }) { entry -> ReferencePanel {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(entry.key, style = MaterialTheme.typography.bodySmall)
                TextButton({ open(entry.query) }) { Text("搜索此标签") }
            } }
            if (state.more) item("more") {
                key(model, state.query, state.namespace) {
                    AutomaticPagination(scroll, "more", state.matches.size, !state.busy && !state.searching && state.message == null, model::more)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (removing) ReferenceDialog(onDismissRequest = { removing = false }, title = { Text("移除词库副本？") },
        text = { Text("详情恢复原标签显示；书目、进度和原文件保持。") },
        confirmButton = { TextButton({ removing = false; model.remove() }) { Text("移除") } },
        dismissButton = { TextButton({ removing = false }) { Text("取消") } })
}
