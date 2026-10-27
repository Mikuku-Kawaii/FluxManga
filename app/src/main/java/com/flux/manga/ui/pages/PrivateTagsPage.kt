package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.TagTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

internal class PrivateTagsModel(app: Application, private val session: WorldSession) : ViewModel() {
    private val identity = session.identity.value
    private val catalog = PrivateCatalog(app, session)
    val selection = SourceSelection(app, identity.world)
    private val entries = mutableMapOf<String, List<PrivateCatalogTag>>()
    val states = MutableStateFlow<Map<String, SourceTagsState>>(emptyMap())
    private fun active() = session.accepts(identity) && session.state.value.foreground
    private val enabledSources = listOf(JM_SOURCE, PrivateCatalog.PICA).filter(selection::enabled)
    private val snapshots = enabledSources.associateWith { CatalogPageCache(app, session, "tags:$it") }
    val directories = enabledSources.associateWith { source ->
        SourceTagsController(viewModelScope, ::active,
            failureMessage = if (source == JM_SOURCE) ::jmFailure else ::picaFailure,
            restore = { query -> snapshots.getValue(source).read(query)?.let { json ->
                val rows = json.getJSONArray("entries")
                entries[source] = List(rows.length()) { index -> rows.getJSONObject(index).let {
                    PrivateCatalogTag(it.getString("name"), it.getString("query"), it.getBoolean("category"))
                } }
                tagsState(json, query)
            } }, save = { state ->
                val rows = JSONArray()
                entries[source].orEmpty().forEach { rows.put(JSONObject().put("name", it.name).put("query", it.query).put("category", it.category)) }
                snapshots.getValue(source).write(tagsJson(state).put("entries", rows), state.query)
            }) { _, _ ->
            val tags = withContext(Dispatchers.IO) { catalog.tags(source) }
            currentCoroutineContext().ensureActive()
            if (!active()) throw CancellationException()
            entries[source] = tags
            SourceTagList(tags.map { it.name }.distinct(), SourceContinuation.End)
        }
    }
    init {
        directories.forEach { (source, directory) -> viewModelScope.launch {
            directory.state.collect { result ->
                if (session.accepts(identity)) states.update { it + (source to result) }
            }
        } }
    }
    fun start() = directories.values.forEach { it.start() }
    fun refresh() = directories.values.forEach { it.refresh() }
    fun leave() = directories.values.forEach { it.leave() }
    fun targets(name: String) = directories.keys.flatMap { source ->
        entries[source].orEmpty().filter { it.name == name }.map { TagTarget(source, it.query, it.category) }
    }.distinct()
    fun categories() = entries.filterKeys { states.value[it]?.let { it.busy || it.error == null } == true }
        .values.flatten().filter { it.category }.map { it.name }.distinct()
}

@Composable
internal fun PrivateTagsPage(session: WorldSession, bottom: Dp, settings: () -> Unit, sources: () -> Unit,
    search: () -> Unit, reportScroll: (LazyListState) -> Unit, openTag: (String, List<TagTarget>) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model = preloadedPages(session).privateTags()
    val gallerySources = remember(model) { listOf(EH_SOURCE, PrivateCatalog.EX).filter(model.selection::enabled) }
    val dictionary = galleryTagsModel(session, enabled = gallerySources.isNotEmpty())
    val words by dictionary.state.collectAsStateWithLifecycle()
    val states by model.states.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.start() }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by remember(query) { mutableIntStateOf(60) }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    val matches by produceState(GalleryTagMatches(emptyList(), false), words.dictionary, query, limit, gallerySources) {
        value = GalleryTagMatches(emptyList(), false)
        if (gallerySources.isNotEmpty()) value = words.dictionary?.search(query, null, limit) ?: GalleryTagMatches(emptyList(), false)
    }
    val names = remember(states, matches, query) {
        (states.values.filter { it.busy || it.error == null }.flatMap { it.tags }.filter { it.contains(query.trim(), true) } + matches.entries.map { it.name }).distinct()
    }
    val targets: (String) -> List<TagTarget> = { name ->
        (model.targets(name) + matches.entries.filter { it.name == name }.flatMap {
            gallerySources.map { source -> TagTarget(source, it.query) }
        }).distinct()
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val submit = {
        keyboard?.hide(); focus.clearFocus()
        scope.launch { scroll.scrollToItem(0) }
        Unit
    }
    val busy = states.values.any { it.busy } || (gallerySources.isNotEmpty() && words.busy)
    val issues = states.values.count { it.error != null || it.notice != null }
    val refresh = { model.refresh(); if (gallerySources.isNotEmpty()) dictionary.refresh() }
    states.forEach { (source, result) ->
        result.error?.let { RequestFeedback("${discoverySourceName(source)}：$it", busy = result.busy) }
    }
    if (gallerySources.isNotEmpty()) words.message?.let { RequestFeedback(it, busy = words.busy) }
    SideEffect { reportScroll(scroll) }
    if (showStatus) ReferenceSheet(onDismissRequest = { showStatus = false }, title = { Text("标签来源") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            model.directories.forEach { (source, directory) ->
                val state = states[source] ?: SourceTagsState()
                Text(if (source == JM_SOURCE) "JMComic" else "PicACG", style = MaterialTheme.typography.titleMedium)
                RequestFeedback(state.error ?: state.notice ?: when {
                    state.busy -> "正在读取分类与标签…"
                    state.loaded -> "已读取 ${state.tags.size} 个分类与标签"
                    else -> "尚未读取"
                }, failure = state.error != null, busy = state.busy)
                if (state.busy) TextButton(directory::cancel) { Text("取消请求") }
                else TextButton(feedbackAttempt(directory::refresh)) { Text(if (state.error != null) "重试" else "刷新") }
                HorizontalDivider()
            }
            if (gallerySources.isNotEmpty()) {
                RequestFeedback(words.message ?: if (words.dictionary == null) "尚未导入中文标签词库，可在图源管理中配置" else "中文标签词库已载入",
                    failure = words.message != null, busy = words.busy)
                if (words.message != null) TextButton(feedbackAttempt(dictionary::refresh), enabled = !words.busy) { Text("重新读取词库") }
            }
        }
    }, confirmButton = { TextButton({ showStatus = false }) { Text("关闭") } },
        dismissButton = { TextButton({ showStatus = false; sources() }) { Text("图源管理") } })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when {
            LocalDensity.current.fontScale >= 2f -> 1
            LocalDensity.current.fontScale >= 1.5f -> 2
            maxWidth < 360.dp -> 3
            else -> 4
        }
        ReferenceRefreshColumn(refreshing = busy, refresh = refresh, state = scroll,
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { DiscoveryHeading("private-tags-settings", settings, "标签") }
            item("query") { ReferenceSearchField(query, { query = it.take(200) }, "查找标签",
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }), trailingIcon = {
                    IconButton(feedbackAttempt(submit), Modifier.semantics { contentDescription = "查找标签" }) { DiscoverySearchGlyph() }
                }) }
            item("feedback") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(when {
                        busy -> "正在更新标签…"
                        issues > 0 || (gallerySources.isNotEmpty() && words.message != null) -> "部分标签来源需要查看"
                        else -> "分类与标签"
                    }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ showStatus = true }) { Text(if (issues > 0) "查看与重试" else "来源状态") }
                }
            }
            if (names.isEmpty() && busy) item("layout") { ReadTagPlaceholder(columns) }
            if (names.isEmpty() && !busy && issues > 0) item("read-failure") {
                ReadFailure(states.values.mapNotNull { it.error ?: it.notice }.distinct().joinToString("\n"), refresh)
            }
            if (query.isBlank() && model.categories().isNotEmpty()) item("topics") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("按题材探索", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    DiscoveryPreviewGrid(model.categories().take(4), { it }, maxColumns = 2) { name ->
                        TopicCard(name) { openTag(name, targets(name)) }
                    }
                }
            }
            item("all-heading") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (query.isBlank()) "全部标签" else "匹配标签", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    Text("已显示 ${names.take(limit).size} 个", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (names.isEmpty() && !busy) item("empty") {
                ReferenceEmptyState(when {
                    model.directories.isEmpty() && gallerySources.isEmpty() -> "当前没有启用图源，请到图源管理打开开关"
                    query.isNotBlank() -> "没有匹配标签，可按关键词搜索作品"
                    issues > 0 -> "标签暂未载入，可重试对应图源"
                    else -> "暂无标签，可刷新或配置图源"
                }) {
                    TextButton(onClick = {
                        if (model.directories.isEmpty() && gallerySources.isEmpty()) sources()
                        else if (query.isBlank()) showStatus = true else openTag(query.trim(), emptyList())
                    }) {
                        Text(if (model.directories.isEmpty() && gallerySources.isEmpty()) "图源管理" else if (query.isBlank()) "查看来源" else "搜索作品")
                    }
                }
            }
            items(names.take(limit).chunked(columns), key = { "tags:${it.first()}" }) { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { name ->
                        TextButton(onClick = { openTag(name, targets(name)) },
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface)) { Text(name) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (names.size > limit || matches.more) item("more") {
                key(query) { AutomaticPagination(scroll, "more", limit, !busy) { limit += 60 } }
                Spacer(Modifier.height(8.dp))
            }
            item("actions") {
                Column {
                    if (query.isBlank() || names.isNotEmpty() || busy) {
                        TextButton(onClick = feedbackAttempt({ if (query.isBlank()) search() else openTag(query.trim(), emptyList()) })) { Text("搜索作品") }
                    }
                }
            }
        }
    }
}
