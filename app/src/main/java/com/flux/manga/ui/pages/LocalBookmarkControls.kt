package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class BookmarkDraft(val bookmark: LocalBookmark, val creating: Boolean)
internal data class LocalBookmarkState(val rows: List<LocalBookmark> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null, val draft: BookmarkDraft? = null)

internal class LocalBookmarkModel(private val repository: LocalBookmarks, private val saved: SavedStateHandle) : ViewModel() {
    private fun restoredDraft(): BookmarkDraft? {
        val id = saved.get<String>("bookmark-id") ?: return null
        return BookmarkDraft(LocalBookmark(id, "cbz", saved.get<String>("bookmark-manga") ?: return null,
            saved.get<String>("bookmark-page") ?: return null, saved.get<Int>("bookmark-ordinal") ?: return null,
            saved.get<Float>("bookmark-fraction") ?: 0f, saved.get<String>("bookmark-mode") ?: ReadingMode.LTR.name,
            saved.get<String>("bookmark-note").orEmpty(), 0, 0), saved.get<Boolean>("bookmark-new") ?: false)
    }
    private val mutable = MutableStateFlow(LocalBookmarkState(draft = restoredDraft()))
    val state = mutable.asStateFlow()
    private fun draft(value: BookmarkDraft?) {
        saved["bookmark-id"] = value?.bookmark?.id
        saved["bookmark-manga"] = value?.bookmark?.mangaId
        saved["bookmark-page"] = value?.bookmark?.pageId
        saved["bookmark-ordinal"] = value?.bookmark?.ordinal
        saved["bookmark-fraction"] = value?.bookmark?.fraction
        saved["bookmark-mode"] = value?.bookmark?.mode
        saved["bookmark-note"] = value?.bookmark?.note
        saved["bookmark-new"] = value?.creating
        mutable.value = state.value.copy(draft = value)
    }
    fun add(page: PageResource, position: ReaderPosition) {
        if (state.value.busy || !state.value.loaded || state.value.draft != null || !repository.active()) return
        draft(BookmarkDraft(LocalBookmark(UUID.randomUUID().toString(), "cbz", page.manga.mangaId,
            page.id, page.ordinal, position.fraction.coerceIn(0f, .9999f), position.mode.name, "", 0, 0), true))
    }
    fun edit(value: LocalBookmark) {
        if (!state.value.busy && state.value.loaded && repository.active()) draft(BookmarkDraft(value, false))
    }
    fun note(value: String) {
        if (!state.value.busy) state.value.draft?.let { draft(it.copy(bookmark = it.bookmark.copy(note = value.take(BOOKMARK_NOTE_LIMIT)))) }
    }
    fun cancelDraft() { if (!state.value.busy) draft(null) }
    fun refresh() = request(false) { repository.load() }
    fun save() {
        val current = state.value.draft ?: return
        if (state.value.loaded) request(true, clearDraft = true) { repository.save(current.bookmark, current.creating) }
    }
    fun delete(id: String) { if (state.value.loaded) request(true) { repository.delete(id) } }
    private fun request(writing: Boolean, clearDraft: Boolean = false, action: suspend () -> List<LocalBookmark>) {
        if (state.value.busy || !repository.active()) return
        mutable.value = state.value.copy(busy = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val rows = action()
                ensureActive()
                if (repository.active()) {
                    if (clearDraft) draft(null)
                    mutable.value = state.value.copy(rows = rows, loaded = true, busy = false,
                        notice = if (writing) if (clearDraft) "书签已保存" else "书签已删除" else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (repository.active()) mutable.value = state.value.copy(busy = false, loaded = false,
                    error = if (writing) "保存结果待确认，请刷新后重试。草稿保留。${failure.message.orEmpty()}"
                        else "书签读取失败，请重试。${failure.message.orEmpty()}")
            }
        }
    }
}

@Composable
internal fun rememberLocalBookmarks(session: WorldSession, manga: String, visible: Boolean): LocalBookmarkModel {
    val app = LocalContext.current.applicationContext
    val identity by session.identity.collectAsStateWithLifecycle()
    val key = "local-bookmarks:${identity.processSession}:${identity.world}:${identity.generation}:$manga"
    val model: LocalBookmarkModel = viewModel(key = key, factory = viewModelFactory {
        initializer { LocalBookmarkModel(LocalBookmarks(WorldDataStore(app), session, manga), createSavedStateHandle()) }
    })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle, visible) { if (visible && lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    return model
}

private fun bookmarkPosition(value: LocalBookmark): String = "第 ${value.ordinal} 页" +
    (if (value.fraction > 0f) " · 页内 ${(value.fraction * 100).toInt()}%" else "") +
    " · ${decodeReadingMode(value.mode)?.label ?: "阅读方式不可用"}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalBookmarkDialog(model: LocalBookmarkModel, canOpen: Boolean, dismiss: () -> Unit,
    open: (LocalBookmark) -> Unit, add: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val close = { keyboard?.hide(); dismiss() }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = state.draft
    ReferenceSheet(onDismissRequest = close, title = { Text(if (draft == null) "书签与备注" else if (draft.creating) "添加书签" else "编辑备注") },
        confirmButton = {
            if (draft != null) ReferenceDialogConfirm("保存", onClick = { keyboard?.hide(); model.save() }, enabled = state.loaded && !state.busy,
                modifier = Modifier.testTag("bookmark-save"))
            else TextButton(onClick = close) { Text("关闭") }
        }, dismissButton = if (draft != null) ({ TextButton(onClick = { keyboard?.hide(); model.cancelDraft() }, enabled = !state.busy) { Text("取消编辑") } }) else null,
        text = {
            if (draft != null) Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(bookmarkPosition(draft.bookmark))
                Text("只保存书签和备注，不改变阅读进度。关闭窗口可保留草稿。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(draft.bookmark.note, model::note, label = { Text("备注（可不填）") },
                    enabled = !state.busy, minLines = 3, maxLines = 7, shape = MaterialTheme.shapes.small,
                    supportingText = { Text("${draft.bookmark.note.length} / $BOOKMARK_NOTE_LIMIT") },
                    modifier = Modifier.fillMaxWidth().testTag("bookmark-note"))
                BookmarkFeedback(state, model::refresh)
            } else ReferenceRefreshColumn(refreshing = state.busy, refresh = { model.refresh() }, refreshEnabled = true, modifier = Modifier.heightIn(max = 440.dp).testTag("bookmark-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("actions") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (add != null) TextButton(onClick = add, enabled = state.loaded && !state.busy,
                            modifier = Modifier.testTag("bookmark-add")) { Text("添加当前位置") }
                    }
                    Text("按页码排列 · ${state.rows.size} 个", style = MaterialTheme.typography.bodySmall)
                    BookmarkFeedback(state, model::refresh)
                    if (state.loaded && state.rows.isEmpty() && !state.busy) Text("还没有书签，可在阅读工具中添加。")
                    if (!canOpen) Text("当前无法跳转阅读，仍可管理备注。", style = MaterialTheme.typography.bodySmall)
                } }
                items(state.rows, key = { it.id }) { bookmark ->
                    var expanded by rememberSaveable(bookmark.id) { mutableStateOf(false) }
                    var overflow by remember(bookmark.note) { mutableStateOf(false) }
                    Column(Modifier.referenceContentResize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { close(); open(bookmark) }, enabled = canOpen && state.loaded && !state.busy,
                            contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(bookmarkPosition(bookmark), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            SettingsChevron()
                        }
                        if (bookmark.note.isNotEmpty()) {
                            Text(bookmark.note, maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
                            if (expanded || overflow) TextButton(onClick = { expanded = !expanded },
                                modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
                                Text(if (expanded) "收起备注" else "展开备注")
                                Spacer(Modifier.width(4.dp))
                                ReferenceDisclosureChevron(expanded)
                            }
                        }
                        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { model.edit(bookmark) }, enabled = state.loaded && !state.busy) { Text("编辑备注") }
                            TextButton(onClick = { deleteId = bookmark.id }, enabled = state.loaded && !state.busy) { Text("删除书签") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        })
    deleteId?.let { id ->
        ReferenceDialog(onDismissRequest = { deleteId = null }, title = { Text("删除此书签？") },
            text = { Text("只删除这条书签及备注，保留原文件和阅读进度。") },
            confirmButton = { ReferenceDialogConfirm("删除书签", onClick = { deleteId = null; model.delete(id) }, enabled = state.loaded && !state.busy, destructive = true) },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
    }
}

@Composable
private fun BookmarkFeedback(state: LocalBookmarkState, refresh: () -> Unit) {
    if (state.busy) RequestLoading()
    state.error?.let {
        RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = feedbackAttempt(refresh), enabled = !state.busy) { Text("重新读取书签") }
    }
    state.notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
}
