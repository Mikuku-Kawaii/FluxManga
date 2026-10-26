package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class OnlineShelfState(val items: List<OnlineShelfItem> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val saving: Set<String> = emptySet())
internal class OnlineShelfModel(app: Application, session: WorldSession) : AndroidViewModel(app) {
    val library = OnlineLibrary(app, session)
    private val mutable = MutableStateFlow(OnlineShelfState())
    val state = mutable.asStateFlow()
    fun refresh() {
        if (state.value.loading) return
        mutable.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try { mutable.value = state.value.copy(items = library.shelf(), loading = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(loading = false, error = "在线书库读取失败，请重试") }
        }
    }
    fun favorite(item: OnlineShelfItem) {
        if (item.key in state.value.saving) return
        mutable.value = state.value.copy(saving = state.value.saving + item.key)
        viewModelScope.launch {
            try {
                library.favorite(item.book, !item.book.favorite)
                val refreshed = library.shelf()
                mutable.value = state.value.copy(items = refreshed, error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(error = "收藏状态保存失败，请刷新后重试") }
            finally { mutable.value = state.value.copy(saving = state.value.saving - item.key) }
        }
    }
}

@Composable
internal fun onlineShelfModel(session: WorldSession): OnlineShelfModel {
    val app = LocalContext.current.applicationContext as Application
    val model: OnlineShelfModel = viewModel(factory = viewModelFactory { initializer { OnlineShelfModel(app, session) } })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    return model
}

@Composable
internal fun OnlineCover(library: OnlineLibrary, sourceId: String, mangaId: String, open: (() -> Unit)? = null, fillWidth: Boolean = false,
    coverRatio: Float = .7f) {
    val visibility by library.visibility.collectAsState()
    val coverEpoch = if (library.privateWorld) visibility.visibilityEpoch else 0L
    val revision by library.coverCache.revision.collectAsState()
    var bitmap by remember(library, sourceId, mangaId, coverEpoch, revision) { mutableStateOf(library.cachedCover(sourceId, mangaId)) }
    var missing by remember(library, sourceId, mangaId) { mutableStateOf(false) }
    var error by remember(library, sourceId, mangaId) { mutableStateOf<String?>(null) }
    var attempt by remember(library, sourceId, mangaId) { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && (!library.privateWorld || visibility.foreground)
    LaunchedEffect(library, sourceId, mangaId, active, if (library.privateWorld) visibility.visibilityEpoch else 0L, attempt, revision) {
        if (!active) {
            if (library.privateWorld) bitmap = null
            return@LaunchedEffect
        }
        if (bitmap != null) return@LaunchedEffect
        error = null; missing = false
        if (active) try { bitmap = library.cover(sourceId, mangaId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: com.flux.manga.core.SourceException.NotFound) { missing = true }
        catch (_: com.flux.manga.core.SourceException.Timeout) { error = "加载超时" }
        catch (_: Exception) { error = "加载失败" }
    }
    CoverArtwork(bitmap?.takeIf { !library.privateWorld || active }, active && bitmap == null && !missing && error == null,
        if (!active) "封面" else if (missing) "暂无封面" else error ?: "正在加载封面",
        Modifier.then(if (fillWidth) Modifier else Modifier.widthIn(max = 112.dp)).fillMaxWidth().aspectRatio(coverRatio)
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClickLabel = "查看漫画详情", onClick = open) else Modifier),
        retry = if (active && error != null) ({ attempt++ }) else null)
}

@Composable
private fun OnlineShelfBook(item: OnlineShelfItem, library: OnlineLibrary,
    open: (String, String) -> Unit, read: (String, String) -> Unit, settings: () -> Unit,
    saving: Boolean, favorite: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var information by remember { mutableStateOf(false) }
    if (information) ReferenceDialog(onDismissRequest = { information = false }, title = { Text(item.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { BookInformationSummary(item.information, expanded = true) } },
        confirmButton = { TextButton({ information = false }) { Text("关闭") } })
    ReferenceBookSummary(cover = {
        key(item.information?.coverReady) { OnlineCover(library, item.book.sourceId, item.book.mangaId) }
    }, onClick = if (item.available) ({ open(item.book.sourceId, item.book.mangaId) }) else null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    ReferenceActionsButton("《${item.title}》作品操作", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (item.book.favorite) "取消收藏" else "收藏") },
                            enabled = !saving, onClick = { menu = false; favorite() })
                        if (item.available) DropdownMenuItem(text = { Text("查看详情") },
                            onClick = { menu = false; open(item.book.sourceId, item.book.mangaId) })
                        DropdownMenuItem(text = { Text("已保存作品资料") }, onClick = { menu = false; information = true })
                        DropdownMenuItem(text = { Text("图源设置") }, onClick = { menu = false; settings() })
                    }
                }
            }
            BookInformationSummary(item.information)
            if (saving) Text("正在保存收藏…", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else if (item.book.favorite) Text("已收藏", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            item.progress?.let { progress ->
                Text(listOfNotNull("上次读到第 ${progress.ordinal} 页",
                    ReadingMode.entries.firstOrNull { it.name == progress.mode }?.label).joinToString(" · "))
            }
            if (!item.available) {
                Text("此来源配置已失效；书目和进度保留")
                TextButton(onClick = settings) { Text("图源设置") }
            } else {
                if (item.progress != null) ReferenceButton(onClick = { read(item.book.sourceId, item.book.mangaId) }) { Text("继续阅读") }
            }
    }
}

internal fun LazyListScope.onlineHomeItems(state: OnlineShelfState, model: OnlineShelfModel,
    all: () -> Unit, open: (String, String) -> Unit, read: (String, String) -> Unit, settings: () -> Unit) {
    item(key = "online-header") {
        Text("在线书库", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        TextButton(onClick = all) { Text("全部在线书目 / 收藏 / 阅读记录") }
        if (state.loading) RequestLoading()
        state.error?.let { RequestFeedback(it); TextButton(onClick = feedbackAttempt(model::refresh)) { Text("重试在线书库") } }
        if (state.items.isEmpty() && !state.loading && state.error == null)
            TextButton(onClick = settings) { Text("配置图源，开始在线阅读") }
    }
    val continuing = state.items.filter { it.progress != null }.take(3)
    if (continuing.isNotEmpty()) item { Text("在线继续阅读", style = MaterialTheme.typography.titleMedium) }
    items(continuing, key = { "online-continue:${it.key}" }) {
        OnlineShelfBook(it, model.library, open, read, settings, it.key in state.saving) { model.favorite(it) }
    }
    val favorites = state.items.filter { it.book.favorite }.take(3)
    if (favorites.isNotEmpty()) item { Text("在线收藏", style = MaterialTheme.typography.titleMedium) }
    items(favorites, key = { "online-favorite:${it.key}" }) {
        OnlineShelfBook(it, model.library, open, read, settings, it.key in state.saving) { model.favorite(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnlineLibraryPage(session: WorldSession, back: () -> Unit, open: (String, String) -> Unit,
    read: (String, String) -> Unit, settings: () -> Unit) {
    val model = onlineShelfModel(session)
    val state by model.state.collectAsStateWithLifecycle()
    val informationModel = rememberBookInformation(session)
    val informationState by informationModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(informationState.running, informationState.revision) {
        if (!informationState.running && informationState.revision > 0) model.refresh()
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val shown = state.items.filter { (filter != 1 || it.book.favorite) && (filter != 2 || it.progress != null) &&
        matchesOnlineInformation(it.title, it.information, query, LocalSearchField.ALL) }
    if (help) ReferenceDialog(onDismissRequest = { help = false }, title = { Text("在线书库说明") },
        text = { Text("这里保存已浏览或导入的在线书目。查询和筛选使用本机记录，封面与在线正文需要相应来源可用。取消收藏保留书目和阅读进度；来源失效时也不会清除记录。",
            Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { help = false }) { Text("关闭") } })
    ReferenceRefreshColumn(refreshing = state.loading, refresh = { model.refresh() }, refreshEnabled = true, modifier = Modifier.fillMaxSize().testTag("online-library"), state = rememberLazyListState(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") {
            SettingsHeading("在线书库", "", { if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else back() }, actions = {
                Box {
                    ReferenceActionsButton("在线书库操作", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("图源设置") }, onClick = { menu = false; settings() })
                        DropdownMenuItem(text = { Text("书库说明") }, onClick = { menu = false; help = true })
                    }
                }
            })
        }
        item("information") { BookInformationPanel(informationModel) }
        item("query") { LibraryQueryField(query, { query = it }, "online-library-query") }
        item("filters") {
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "收藏", "阅读记录").forEachIndexed { index, title ->
                    FilterChip(filter == index, { filter = index }, label = { Text(title) })
                }
            }
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, verticalArrangement = Arrangement.Center) {
                Text(if (state.loading && state.items.isEmpty()) "读取中…" else "${shown.size} 部${if (state.error != null) " · 待确认" else ""}", Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (query.isNotEmpty() || filter != 0) TextButton(onClick = { query = ""; filter = 0 }) { Text("清除筛选") }
            }
        }
        if (state.loading || state.error != null) item("status") {
            if (state.loading) {
                Text(if (state.items.isEmpty()) "正在读取书目…" else "正在刷新，保留已显示书目",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.error?.let {
                RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = feedbackAttempt(model::refresh), enabled = !state.loading) { Text("重试读取书库") }
            }
        }
        if (state.loading && state.items.isEmpty()) item("read-layout") { ReadLayoutPlaceholder() }
        if (!state.loading && state.error == null && shown.isEmpty()) item("empty") {
            Text(if (state.items.isEmpty()) "还没有在线书目" else "没有符合当前查询与筛选的书目")
            if (state.items.isEmpty()) TextButton(onClick = settings) { Text("前往图源管理") }
            else TextButton(onClick = { query = ""; filter = 0 }) { Text("查看全部书目") }
        }
        items(shown, key = { it.key }) {
            OnlineShelfBook(it, model.library, open, read, settings, it.key in state.saving) { model.favorite(it) }
        }
    }
}
