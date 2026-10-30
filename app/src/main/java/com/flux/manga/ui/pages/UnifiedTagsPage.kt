package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
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
import com.flux.manga.ui.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

internal class UnifiedTagsModel(app: Application, private val session: WorldSession, komgaId: String?) : ViewModel() {
    private val identity = session.identity.value
    private val komga = komgaId?.let { KomgaAccess(app, session, it) }
    private fun active() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    private var copyThemes: List<CopyTheme> = emptyList()
    private val mutable = MutableStateFlow<Map<String, SourceTagsState>>(emptyMap())
    val states = mutable.asStateFlow()
    private val enabledSources = listOfNotNull(BaoziEndpoint.SOURCE_ID, CopyMangaEndpoint.SOURCE_ID, komgaId)
        .filter(SourceSelection(app, identity.world)::enabled)
    private val snapshots = enabledSources
        .associateWith { CatalogPageCache(app, session, "tags:$it", includeBlacklist = false, source = it) }
    val directories = enabledSources.associateWith { source ->
        SourceTagsController(viewModelScope, ::active, failureMessage = if (source == CopyMangaEndpoint.SOURCE_ID) ::copyMangaFailure else ::komgaFailure,
            restore = { query -> snapshots.getValue(source).decode(query) { json ->
                if (source == CopyMangaEndpoint.SOURCE_ID) {
                    val themes = json.getJSONArray("themes")
                    copyThemes = List(themes.length()) { index -> themes.getJSONObject(index).let { CopyTheme(it.getString("name"), it.getString("id")) } }
                }
                tagsState(json, query)
            } }, save = { state ->
                val json = tagsJson(state)
                if (source == CopyMangaEndpoint.SOURCE_ID) {
                    val themes = JSONArray()
                    copyThemes.forEach { themes.put(JSONObject().put("name", it.name).put("id", it.pathWord)) }
                    json.put("themes", themes)
                }
                snapshots.getValue(source).write(json, state.query)
            }) { query, page ->
            when (source) {
                BaoziEndpoint.SOURCE_ID -> SourceTagList(BaoziCategories.tags.keys.filter { it.contains(query, true) }, SourceContinuation.End)
                CopyMangaEndpoint.SOURCE_ID -> {
                    val themes = withContext(Dispatchers.IO) { session.copyMangaSources.client().themes() }
                    currentCoroutineContext().ensureActive()
                    if (!active()) throw CancellationException()
                    copyThemes = themes
                    SourceTagList(themes.map { it.name }.distinct().filter { it.contains(query, true) }, SourceContinuation.End)
                }
                else -> requireNotNull(komga).client().tags(query, page)
            }
        }
    }
    init { directories.forEach { (source, controller) -> viewModelScope.launch {
        controller.state.collect { result -> if (active()) mutable.value = states.value + (source to result) }
    } } }
    fun start() = directories.values.forEach { it.start() }
    fun leave() = directories.values.forEach { it.leave() }
    fun search(query: String) = directories.values.forEach { it.search(query.trim()) }
    fun targets(name: String): List<TagTarget> = directories.keys.flatMap { source ->
        if (states.value[source]?.tags?.contains(name) != true) emptyList()
        else if (source == CopyMangaEndpoint.SOURCE_ID) copyThemes.filter { it.name == name }.map { TagTarget(source, it.pathWord) }
        else listOf(TagTarget(source, name))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedTagsPage(session: WorldSession, configuration: DiscoveryState, bottomClearance: Dp, settings: () -> Unit,
    open: (String, List<TagTarget>) -> Unit, reportScroll: (LazyListState) -> Unit, switchError: String?, retryConfiguration: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val komgaId = DiscoverySource.KOMGA.id(configuration)
    val model = preloadedPages(session).tags(komgaId)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle, configuration.status) {
        if (lifecycle == Lifecycle.State.RESUMED && configuration.status != DiscoveryStatus.LOADING) model.start()
    }
    val states by model.states.collectAsStateWithLifecycle()
    states.forEach { (source, result) ->
        (result.error ?: result.appendError)?.let { RequestFeedback("${discoverySourceName(source)}：$it", busy = result.busy) }
    }
    val names = model.directories.keys.flatMap { source -> states[source]?.let { it.tags }.orEmpty() }.distinct()
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val submit = feedbackAttempt {
        if (configuration.status != DiscoveryStatus.LOADING) {
            keyboard?.hide(); focus.clearFocus()
            model.search(query); scope.launch { scroll.scrollToItem(0) }
        }
        Unit
    }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    if (showStatus) ReferenceSheet(onDismissRequest = { showStatus = false }, title = { Text("标签来源") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (configuration.status == DiscoveryStatus.FAILED) {
                RequestFeedback("部分图源配置读取失败", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = feedbackAttempt(retryConfiguration)) { Text("重试读取配置") }
            }
            model.directories.forEach { (source, directory) ->
                val state = states[source] ?: SourceTagsState()
                Text(discoverySourceName(source), style = MaterialTheme.typography.titleMedium)
                RequestFeedback(state.error ?: state.appendError ?: state.notice ?: when {
                    state.busy -> "正在读取标签…"
                    state.loaded -> "已读取 ${state.tags.size} 个标签"
                    else -> "尚未读取"
                }, failure = state.error != null || state.appendError != null, busy = state.busy)
                if (state.busy) TextButton(directory::cancel) { Text("取消请求") }
                else if (state.error != null || state.appendError != null) TextButton(onClick = feedbackAttempt({
                    if (state.appendError == null) directory.refresh() else directory.more()
                })) { Text("重试标签") }
            }
        } }, confirmButton = { TextButton({ showStatus = false }) { Text("关闭") } })
    SideEffect { reportScroll(scroll) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val tagColumns = when {
        androidx.compose.ui.platform.LocalDensity.current.fontScale >= 2f -> 1
        androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f -> 2
        maxWidth < 360.dp -> 3
        else -> 4
    }
    ReferenceRefreshColumn(refreshing = states.values.any { it.busy }, refresh = { model.directories.values.forEach { it.refresh() }; if (configuration.status == DiscoveryStatus.FAILED) retryConfiguration() }, refreshEnabled = configuration.status != DiscoveryStatus.LOADING, modifier = Modifier.fillMaxSize().testTag("list-TAGS"), state = scroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomClearance), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DiscoveryHeading("tags-more", settings, "标签")
            switchError?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error) }
            ReferenceSearchField(query, { query = it.take(200) }, "查找标签",
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }),
                trailingIcon = { IconButton(onClick = feedbackAttempt(submit), enabled = configuration.status != DiscoveryStatus.LOADING,
                    modifier = Modifier.semantics { contentDescription = "查找标签" }) { DiscoverySearchGlyph() } },
                modifier = Modifier.fillMaxWidth().testTag("source-tag-query"))
          }
        }
        if (configuration.status == DiscoveryStatus.FAILED || states.values.any { it.busy || it.error != null || it.appendError != null || it.notice != null } || names.isEmpty()) item("status") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(when {
                    model.directories.isEmpty() -> "当前没有启用可用图源，请到图源管理打开开关"
                    configuration.status == DiscoveryStatus.LOADING || states.values.any { it.busy } -> "正在读取标签…"
                    configuration.status == DiscoveryStatus.FAILED || states.values.any { it.error != null || it.appendError != null || it.notice != null } -> "部分标签来源需要查看"
                    names.isEmpty() -> "暂无匹配标签"
                    else -> "分类与标签"
                }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ showStatus = true }) { Text("查看来源") }
            }
        }
        if (names.isEmpty() && (configuration.status == DiscoveryStatus.LOADING || states.values.any { it.busy }))
            item("layout") { ReadTagPlaceholder(tagColumns) }
        if (names.isEmpty() && states.values.none { it.busy } && states.values.any { it.error != null })
            item("read-failure") { ReadFailure(states.values.mapNotNull { it.error }.distinct().joinToString("\n")) {
                model.directories.values.forEach { it.refresh() }
            } }
        if (query.isBlank() && names.isNotEmpty()) item("topics") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按题材探索", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            DiscoveryPreviewGrid(featuredTags(names).take(4), { it }, maxColumns = 2) { name -> TopicCard(name) { open(name, model.targets(name)) } }
        } }
        item("all-heading") {
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, verticalArrangement = Arrangement.Center) {
                Text(if (states.values.any { it.query.isNotBlank() }) "匹配标签" else "全部标签", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                Text("已显示 ${names.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(names.chunked(tagColumns), key = { "tags:${it.first()}" }) { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { name -> TextButton(onClick = { open(name, model.targets(name)) }, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface)) { Text(name) } }
                if (row.size < tagColumns) repeat(tagColumns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item("more") {
            states.forEach { (source, result) -> key(model, source, result.query) {
                AutomaticPagination(scroll, "more", result.next, result.loaded && !result.busy &&
                    result.error == null && result.appendError == null && result.notice == null &&
                    result.next is SourceContinuation.Next) { model.directories.getValue(source).more() }
            } }
            if (states.values.any { it.busy }) RequestLoading() else Spacer(Modifier.height(8.dp))
        }
    }
    }
}
