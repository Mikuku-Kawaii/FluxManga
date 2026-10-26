package com.flux.manga.ui.pages

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.R
import com.flux.manga.traceMeasure
import com.flux.manga.data.SampleScenario
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.DetailViewModel
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.MangaDetail
import com.flux.manga.ui.navigation.MangaReader
import com.flux.manga.ui.navigation.tabTitle

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ScenarioControls(prefix: String, model: SamplePageViewModel<*>) {
    val selectedScenario by model.scenario.collectAsStateWithLifecycle()
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SampleScenario.entries.forEach { scenario ->
            TextButton(onClick = { model.selectScenario(scenario) },
                modifier = Modifier.testTag("$prefix-scenario-${scenario.name}")
                    .semantics { selected = selectedScenario == scenario.name }) {
                Text(when (scenario) {
                    SampleScenario.CONTENT -> "正常样本"
                    SampleScenario.EMPTY -> "空样本"
                    SampleScenario.ERROR -> "失败样本"
                })
            }
        }
    }
}

@Composable
private fun <T> PageResult(prefix: String, state: PageState<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        PageState.Loading -> Column(Modifier.padding(16.dp).testTag("$prefix-loading").semantics { liveRegion = LiveRegionMode.Polite }) {
            CircularProgressIndicator()
            Text("正在加载固定样本…")
        }
        PageState.Empty -> Column(Modifier.padding(16.dp).testTag("$prefix-empty")) {
            Text("没有匹配的固定样本")
            TextButton(onClick = feedbackAttempt(retry), modifier = Modifier.testTag("$prefix-refresh")) { Text("重新加载") }
        }
        is PageState.Error -> Column(Modifier.padding(16.dp).testTag("$prefix-error").semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(state.message)
            Button(onClick = retry, modifier = Modifier.testTag("$prefix-retry")) { Text("重试") }
        }
        is PageState.Content -> content(state.value)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SampleRootPage(tab: MainTab, coreStatus: String, session: WorldSession, openDetail: (String) -> Unit, openProbe: () -> Unit, openGlass: () -> Unit, openLocal: () -> Unit,
                   bottomClearance: Dp, reportScroll: (LazyListState) -> Unit) {
    val model: RootPageViewModel = viewModel(factory = viewModelFactory {
        initializer { RootPageViewModel(createSavedStateHandle(), session, includeHistory = tab == MainTab.SEARCH) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val historyError by model.historyError.collectAsStateWithLifecycle()
    val historyBusy by model.historyBusy.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    // A short loading list must not clamp the content's restored item index.
    val loadingScroll = rememberLazyListState()
    val visibleScroll = if (state == PageState.Loading) loadingScroll else scroll
    SideEffect { reportScroll(visibleScroll) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LazyColumn(state = visibleScroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomClearance),
        modifier = Modifier.fillMaxSize().traceMeasure("SampleList").testTag("list-${tab.name}")) {
        item(key = "page-header") {
            Column {
                Text(stringResource(R.string.navigation_title, if (identity.world == com.flux.manga.ui.navigation.PrototypeWorld.SFW) "表世界" else "里世界（固定样本）", tabTitle(tab)), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().testTag("root-title").semantics { heading() })
                Text(stringResource(R.string.navigation_description), style = MaterialTheme.typography.bodySmall)
                if (tab == MainTab.LIBRARY && identity.world == com.flux.manga.ui.navigation.PrototypeWorld.SFW) {
                    TextButton(onClick = openLocal, modifier = Modifier.testTag("open-local-library")) { Text("本地 CBZ · 导入与页序") }
                    Text(coreStatus, style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(onClick = openProbe, modifier = Modifier.testTag("open-probe")) { Text("M0-3 · FFI 验证") }
                        TextButton(onClick = openGlass, modifier = Modifier.testTag("open-glass")) { Text("M0-4 · 亮暗与采样") }
                    }
                }
                if (tab == MainTab.SEARCH) {
                    ReferenceSearchField(value = query, change = model::updateQuery, placeholder = stringResource(R.string.sample_query),
                        modifier = Modifier.fillMaxWidth().testTag("search-query"))
                    FlowRow {
                        TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); model.rememberQuery() },
                            enabled = query.isNotBlank() && !historyBusy, modifier = Modifier.testTag("history-save")) { Text("记入搜索历史") }
                        TextButton(onClick = model::clearHistory, enabled = history.isNotEmpty() && !historyBusy,
                            modifier = Modifier.testTag("history-clear")) { Text("清空当前空间历史") }
                    }
                    if (history.isNotEmpty()) {
                        Text("当前空间最近搜索", modifier = Modifier.semantics { heading() })
                        FlowRow {
                            history.forEachIndexed { index, text ->
                                TextButton(onClick = { model.updateQuery(text) }, modifier = Modifier.testTag("history-$index")) { Text(text) }
                            }
                        }
                    }
                    historyError?.let { message ->
                        RequestFeedback(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onClick = feedbackAttempt(model::retryHistory), enabled = !historyBusy) { Text("重试历史") }
                    }
                }
                ScenarioControls("root", model)
            }
        }
        val content = state as? PageState.Content
        if (content != null) {
                items(content.value, key = { "${it.world}/${it.sourceId}/${tab.name}/${it.id}" }) { sample ->
                    // These always-enabled text rows need one layout, not a full button tree.
                    // Keep the existing button semantics, shape, label and touch target.
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sample-${sample.id}")
                        .clip(ButtonDefaults.textShape).clickable(role = Role.Button, onClick = {
                        keyboard?.hide()
                        focus.clearFocus()
                        openDetail(sample.id)
                    }).padding(ButtonDefaults.TextButtonContentPadding), contentAlignment = Alignment.Center) {
                        Text(sample.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
        } else item(key = "page-state") { PageResult("root", state, model::retry) {} }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SampleDetailPage(route: MangaDetail, session: WorldSession, back: () -> Unit, read: () -> Unit) {
    val model: DetailViewModel = viewModel(factory = viewModelFactory {
        initializer { DetailViewModel(createSavedStateHandle(), route, session) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val marks by model.marks.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = back, modifier = Modifier.testTag("detail-back")) { Text(stringResource(R.string.detail_back)) }
        Text(stringResource(R.string.detail_title, tabTitle(route.tab), route.mangaId),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().testTag("detail-title").semantics { heading() })
        Text(stringResource(R.string.sample_source, route.world.name, route.sourceId))
        ScenarioControls("detail", model)
        PageResult("detail", state, model::retry) { manga ->
            Text(manga.title, modifier = Modifier.testTag("detail-content"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.detail_marks, marks), modifier = Modifier.testTag("detail-marks"))
                TextButton(onClick = model::addMark, modifier = Modifier.testTag("add-mark")) { Text(stringResource(R.string.add_mark)) }
            }
            Text("固定样本简介：本页用于验证页面状态与返回，不包含漫画正文。")
            Button(onClick = read, modifier = Modifier.testTag("open-reader")) { Text("打开阅读器占位页") }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SampleReaderPage(route: MangaReader, session: WorldSession, back: () -> Unit) {
    var privateImages by remember { mutableStateOf(false) }
    val identity by session.identity.collectAsStateWithLifecycle()
    val canOpenPrivateImages = com.flux.manga.BuildConfig.DEBUG &&
        route.world == com.flux.manga.ui.navigation.PrototypeWorld.NSFW && route.world == identity.world
    if (privateImages && canOpenPrivateImages) {
        OnlineReaderPage(session, com.flux.manga.data.SyntheticPrivateReaderRepository.SOURCE,
            com.flux.manga.data.SyntheticPrivateReaderRepository.MANGA, null,
            back = { privateImages = false }, syntheticPrivate = true)
        return
    }
    val model: ReaderViewModel = viewModel(factory = viewModelFactory {
        initializer { ReaderViewModel(createSavedStateHandle(), session, route) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val page by model.page.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = back, modifier = Modifier.testTag("reader-back")) { Text("返回详情") }
        Text("阅读器占位", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().testTag("reader-title").semantics { heading() })
        if (canOpenPrivateImages) TextButton(onClick = { privateImages = true }, modifier = Modifier.testTag("open-private-synthetic-reader")) {
            Text("打开合成图片阅读器")
        }
        ScenarioControls("reader", model)
        PageResult("reader", state, model::retry) { chapter ->
            Text("${chapter.manga.title} · 固定章节 1", modifier = Modifier.testTag("reader-content"))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("占位页 $page / ${chapter.pages}", modifier = Modifier.testTag("reader-page"))
                    Text("这里仅显示编号占位。真实 CBZ 导入、图片解码和阅读进度持久化将在 M2 实现。")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { model.move(-1) }, enabled = page > 1, modifier = Modifier.testTag("reader-previous")) { Text("上一页") }
                Button(onClick = { model.move(1) }, enabled = page < chapter.pages, modifier = Modifier.testTag("reader-next")) { Text("下一页") }
            }
        }
    }
}



