package com.flux.manga.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

internal data class BookEditorState(val detail: MangaDetail? = null, val title: String = "", val cover: String? = null,
    val busy: Boolean = false, val loaded: Boolean = false, val error: String? = null, val completed: Boolean = false)

internal class LocalBookEditorModel(private val library: CbzLibrary, private val session: WorldSession,
    private val key: MangaKey, private val saved: SavedStateHandle) : ViewModel() {
    private val identity = session.identity.value
    private val mutable = MutableStateFlow(BookEditorState())
    val state = mutable.asStateFlow()
    private fun active() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    fun load() {
        if (state.value.busy || !active()) return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val detail = library.detail(key) ?: throw IOException("本地书目已不存在")
                ensureActive()
                if (active()) {
                    val restored = saved.get<Boolean>("book-editor-draft") == true
                    mutable.value = state.value.copy(detail = detail, busy = false, loaded = true,
                        title = if (restored) saved.get<String>("book-editor-title").orEmpty() else detail.card.title,
                        cover = if (restored) saved.get<String>("book-editor-cover") else detail.coverPageId)
                    rememberDraft()
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(busy = false, loaded = false,
                error = "读取失败，请重试：${e.message.orEmpty()}") }
        }
    }
    private fun rememberDraft() {
        saved["book-editor-draft"] = true
        saved["book-editor-title"] = state.value.title
        saved["book-editor-cover"] = state.value.cover
    }
    fun title(value: String) { if (!state.value.busy) {
        mutable.value = state.value.copy(title = value.take(200)); rememberDraft()
    } }
    fun cover(page: String?) { if (!state.value.busy) {
        mutable.value = state.value.copy(cover = page); rememberDraft()
    } }
    fun defaults() { if (!state.value.busy) state.value.detail?.let {
        mutable.value = state.value.copy(title = it.defaultTitle, cover = null); rememberDraft()
    } }
    fun cancel() { saved["book-editor-draft"] = false; saved["book-editor-title"] = null; saved["book-editor-cover"] = null }
    fun acknowledge() { mutable.value = state.value.copy(completed = false) }
    fun save() {
        val value = state.value
        if (!active() || !value.loaded || value.busy || value.title.trim().isEmpty() || value.title.any { it == '\n' || it == '\r' }) return
        mutable.value = value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                library.editInformation(key, value.title, value.cover, ::active)
                ensureActive()
                if (active()) {
                    session.pageCovers().remove(key.sourceId, key.mangaId)
                    cancel()
                    mutable.value = state.value.copy(busy = false, completed = true)
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(busy = false, loaded = false,
                error = "保存结果待核对，请先重新读取。草稿已保留：${e.message.orEmpty()}") }
        }
    }
    suspend fun preview(page: PageResource): Bitmap {
        if (!active()) throw CancellationException()
        val image = library.readPage(page, targetWidth = 360, wholePage = true)
        if (active()) return image
        image.recycle()
        throw CancellationException()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalBookEditor(session: WorldSession, manga: String, dismiss: () -> Unit, saved: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val identity by session.identity.collectAsStateWithLifecycle()
    val model: LocalBookEditorModel = viewModel(key = "book-editor:${identity.processSession}:${identity.world}:${identity.generation}:$manga",
        factory = viewModelFactory { initializer {
            LocalBookEditorModel(CbzLibrary(WorldDataStore(app)), session, MangaKey(PrototypeWorld.SFW, "cbz", manga), createSavedStateHandle())
        } })
    val state by model.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(state.completed) { if (state.completed) { model.acknowledge(); saved() } }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var pageQuery by rememberSaveable { mutableStateOf("") }
    val close: () -> Unit = { if (!state.busy) { keyboard?.hide(); model.cancel(); dismiss() } }
    val detail = state.detail
    val selectedPage = detail?.chapter?.pages?.let { pages -> if (state.cover == null) pages.firstOrNull() else pages.firstOrNull { it.id == state.cover } }
    val invalidTitle = state.title.trim().isEmpty() || state.title.any { it == '\n' || it == '\r' }
    ReferenceEditor(onDismissRequest = close, title = { Text("编辑书目信息") },
        confirmButton = { ReferenceDialogConfirm("保存", onClick = { keyboard?.hide(); model.save() },
            enabled = state.loaded && !state.busy && !invalidTitle && selectedPage != null,
            modifier = Modifier.testTag("book-info-save")) },
        dismissButton = { TextButton(onClick = close, enabled = !state.busy) { Text("取消") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.busy) RequestLoading()
                state.error?.let {
                    RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = feedbackAttempt(model::load), enabled = !state.busy) { Text("重新读取书目") }
                }
                if (detail != null) {
                    Text("书名", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(state.title, model::title, label = { Text("显示书名") }, singleLine = true, shape = MaterialTheme.shapes.small,
                        enabled = !state.busy, isError = invalidTitle,
                        supportingText = { Text(if (invalidTitle) "请输入非空且不含换行的书名" else "${state.title.length} / 200") },
                        modifier = Modifier.fillMaxWidth().testTag("book-info-title"))
                    TextButton(onClick = { model.title(detail.defaultTitle) }, enabled = !state.busy) { Text("恢复默认书名") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("封面", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(if (state.cover == null) "默认封面 · 第一页" else selectedPage?.let { "封面 · 第 ${it.ordinal} 页" } ?: "所选封面页已不存在",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (detail.card.available && selectedPage != null) BookEditorPreview(selectedPage, model)
                    else Text(if (detail.card.available) "请选择有效页面或恢复默认封面" else "应用副本缺失，可修改书名或恢复默认；重新导入相同 CBZ 后可预览。")
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { keyboard?.hide(); choosing = true }, enabled = detail.card.available && state.loaded && !state.busy) { Text("选择漫画内页") }
                        TextButton(onClick = { model.cover(null) }, enabled = !state.busy) { Text("恢复默认封面") }
                    }
                    Text("保存后生效，仅修改应用中的书名与封面。原 CBZ、阅读进度和书签保持。", style = MaterialTheme.typography.bodySmall)
                    SettingsDisclosure("默认信息与恢复") {
                        Text("默认书名：${detail.defaultTitle}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::defaults, enabled = !state.busy) { Text("全部恢复默认") }
                    }
                }
            }
        })
    if (choosing && detail != null) ReferenceSheet(onDismissRequest = { keyboard?.hide(); choosing = false },
        title = { Text("选择封面页") }, confirmButton = { TextButton(onClick = { keyboard?.hide(); choosing = false }) { Text("返回编辑") } },
        text = {
            val pages = detail.chapter.pages.filter { pageQuery.isBlank() || it.ordinal.toString().contains(pageQuery.trim()) }
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item("query") {
                    ReferenceSearchField(pageQuery, { pageQuery = it.filter(Char::isDigit).take(7) }, "筛选页码",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (pageQuery.isNotEmpty()) ({ TextButton(onClick = { pageQuery = "" }) { Text("清空") } }) else null)
                    if (pages.isEmpty()) Text("没有匹配页码")
                }
                items(pages, key = { it.id }) { page ->
                    SettingsChoiceRow("第 ${page.ordinal} 页", page.id == selectedPage?.id,
                        { model.cover(page.id); keyboard?.hide(); choosing = false })
                }
            }
        })
}

@Composable
private fun BookEditorPreview(page: PageResource, model: LocalBookEditorModel) {
    var image by remember(page) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    var attempt by remember(page) { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(page, attempt, active) {
        image = null; failed = false
        if (active) try { image = model.preview(page) }
        catch (c: CancellationException) { throw c }
        catch (_: IOException) { failed = true }
    }
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        image?.let { Image(it.asImageBitmap(), "第 ${page.ordinal} 页封面预览", Modifier.width(140.dp).fillMaxHeight(), contentScale = ContentScale.Crop) }
            ?: if (failed) {
                RequestFeedback("第 ${page.ordinal} 页封面预览失败")
                TextButton(onClick = feedbackAttempt { attempt++ }) { Text("重试预览") }
            }
            else if (active) CircularProgressIndicator() else Unit
    }
}
