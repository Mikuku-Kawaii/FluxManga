package com.flux.manga.ui.pages

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

internal data class StatisticsPageState(val snapshot: ReadingStatisticsSnapshot? = null,
    val loading: Boolean = false, val error: String? = null, val range: Int = 30,
    val filter: ReadingFilter = ReadingFilter(), val works: Boolean = false, val title: String? = null,
    val limit: Int = 50, val lastSelection: String? = null)

internal class ReadingStatisticsModel(app: Application, session: WorldSession,
    private val saved: SavedStateHandle, store: WorldDataStore = WorldDataStore(app)) : AndroidViewModel(app) {
    private val repository = ReadingStatistics(store, session)
    val state = MutableStateFlow(StatisticsPageState(range = saved["range"] ?: 30,
        works = saved["works"] ?: false, title = saved["title"], lastSelection = saved["lastSelection"],
        filter = ReadingFilter(day = saved["day"], kind = saved["kind"], source = saved["source"], manga = saved["manga"])))
    private var job: Job? = null
    fun start() { if (state.value.snapshot == null && !state.value.loading) refresh() }
    fun refresh(more: Boolean = false) {
        job?.cancel()
        val old = state.value
        val filter = old.filter.copy(since = if (old.range == 0) "0000-01-01" else LocalDate.now().minusDays(old.range - 1L).toString())
        val limit = if (more) old.limit + 50 else old.limit
        state.value = old.copy(filter = filter, loading = true, error = null, limit = limit)
        job = viewModelScope.launch {
            try {
                val result = repository.read(filter, limit + 1)
                ensureActive()
                state.value = state.value.copy(snapshot = result, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(loading = false, error = "统计读取失败，已有记录保留，请重试。") }
        }
    }
    private fun select(next: StatisticsPageState) {
        saved["range"] = next.range; saved["works"] = next.works; saved["title"] = next.title
        saved["lastSelection"] = next.lastSelection
        saved["day"] = next.filter.day; saved["kind"] = next.filter.kind
        saved["source"] = next.filter.source; saved["manga"] = next.filter.manga
        state.value = next.copy(snapshot = null, limit = 50)
        refresh()
    }
    fun range(days: Int) = select(state.value.copy(range = days, filter = ReadingFilter(), title = null, lastSelection = null))
    fun tab(works: Boolean) { saved["works"] = works; state.value = state.value.copy(works = works) }
    fun day(day: String) = select(state.value.copy(filter = state.value.filter.copy(day = day), works = true, lastSelection = "day"))
    fun work(work: ReadingWork) = select(state.value.copy(filter = state.value.filter.copy(
        kind = work.kind, source = work.sourceId, manga = work.mangaId), title = work.title, works = false, lastSelection = "work"))
    fun up() {
        val old = state.value
        select(if (old.filter.day != null && (old.filter.kind == null || old.lastSelection == "day"))
            old.copy(filter = old.filter.copy(day = null), works = false, lastSelection = "work")
        else old.copy(filter = old.filter.copy(kind = null, source = null, manga = null), title = null, works = true, lastSelection = "day"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingStatisticsPage(session: WorldSession, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model = preloadedPages(session).statistics()
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    val selected = state.filter.day != null || state.filter.kind != null
    val leave: () -> Unit = { if (selected) model.up() else back() }
    BackHandler(selected, leave)
    val snapshot = state.snapshot
    var menu by remember { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    ReferenceRefreshColumn(refreshing = state.loading, refresh = { model.refresh() }, refreshEnabled = true, modifier = Modifier.fillMaxSize().testTag("reading-statistics"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("阅读统计", "", leave, actions = {
            Box {
                ReferenceActionsButton("统计操作", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("统计说明") }, onClick = { menu = false; help = true })
                }
            }
        }) }
        item {
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "近 7 天", 30 to "近 30 天", 0 to "全部").forEach { (days, text) ->
                    FilterChip(state.range == days, { model.range(days) }, label = { Text(text) })
                }
            }
        }
        if (selected) item { SettingsPanel {
            state.filter.day?.let { Text(it) }
            state.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = model::up) { Text("返回上一级统计") }
        } }
        if (state.loading) item { RequestLoading() }
        state.error?.let { message -> item {
            RequestFeedback(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = feedbackAttempt({ model.refresh() }), enabled = !state.loading) { Text("重试") }
        } }
        snapshot?.let { result ->
            item { SettingsPanel {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("累计阅读", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(readingDuration(result.total.durationMs), style = MaterialTheme.typography.headlineLarge)
                    Text("${result.total.days} 个阅读日 · ${result.total.books} 部作品", style = MaterialTheme.typography.bodyMedium)
                    Text((if (session.identity.value.world == PrototypeWorld.SFW) "表世界" else "里世界") + " · 所选范围的前台正文阅读时长",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!state.works, { model.tab(false) }, label = { Text("每日记录") })
                FilterChip(state.works, { model.tab(true) }, label = { Text("作品统计") })
            } }
            if (result.total.durationMs == 0L) item { Text("这个范围还没有阅读统计。开始阅读后会自动记录，不从旧进度推算时长。") }
            if (state.works) {
                items(result.works.take(state.limit), key = { listOf(it.kind, it.sourceId, it.mangaId).toString() }) { row ->
                    StatisticsRow(row.title, "${readingDuration(row.durationMs)} · ${row.days} 个阅读日",
                        "最近阅读：${row.lastDay}", if (state.filter.kind == null) ({ model.work(row) }) else null)
                }
            } else {
                items(result.days.take(state.limit), key = { it.day }) { row ->
                    StatisticsRow(row.day, "${readingDuration(row.durationMs)} · ${row.books} 部作品",
                        null, if (state.filter.day == null) ({ model.day(row.day) }) else null)
                }
            }
            if ((if (state.works) result.works.size else result.days.size) > state.limit) item {
                TextButton(onClick = feedbackAttempt({ model.refresh(more = true) }), enabled = !state.loading) { Text("加载更多") }
            }
        }
    }
    if (help) ReferenceDialog(onDismissRequest = { help = false }, title = { Text("统计说明") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("关闭") } },
        text = { Text("仅记录正文已显示、窗口有焦点时的前台停留时间；加载、弹窗和后台暂停。每 10 秒及离开时保存，异常退出可能丢失尚未保存的时长。日期按阅读时的本地时区划分。统计按空间独立保存，在线与离线的同一作品合计；同名不同来源分别统计。移除书目或清除阅读进度不删除统计。合成验证样本不计入。",
            Modifier.verticalScroll(rememberScrollState())) })
}

@Composable
private fun StatisticsRow(title: String, summary: String, detail: String?, open: (() -> Unit)?) {
    Column {
        Row(Modifier.fillMaxWidth().then(if (open == null) Modifier else Modifier.clickable(role = Role.Button,
            onClickLabel = "查看明细", onClick = open)).heightIn(min = 72.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (open != null) SettingsChevron()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
