package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.MangaCard
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.tabTitle
import com.flux.manga.traceMeasure

@Composable
fun LocalHomePage(tab: MainTab, session: WorldSession, openLibrary: () -> Unit, open: (String) -> Unit,
                  read: (String) -> Unit, samples: () -> Unit, bottomClearance: Dp,
                  onlineLibrary: () -> Unit, onlineOpen: (String, String) -> Unit, onlineRead: (String, String) -> Unit, sources: () -> Unit,
                  reportScroll: (LazyListState) -> Unit) {
    val context = LocalContext.current.applicationContext
    val model: LocalLibraryViewModel = viewModel(factory = viewModelFactory {
        initializer { LocalLibraryViewModel(context, session, null, createSavedStateHandle()) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val onlineModel = onlineShelfModel(session)
    val online by onlineModel.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    val scroll = rememberLazyListState()
    val loadingScroll = rememberLazyListState()
    val visibleScroll = if (state.loading && state.cards.isEmpty()) loadingScroll else scroll
    SideEffect { reportScroll(visibleScroll) }
    val recent = state.cards.take(8)
    val continuing = state.cards.filter { it.progress != null }.sortedByDescending { it.progress!!.updatedAt }.take(8)
    ReferenceRefreshColumn(refreshing = state.loading || online.loading, refresh = { model.refresh(); onlineModel.refresh() }, refreshEnabled = true, modifier = Modifier.fillMaxSize().traceMeasure("LocalList").testTag("list-${tab.name}"), state = visibleScroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("表世界 · ${tabTitle(tab)}", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("root-title").semantics { heading() })
            Text(if (tab == MainTab.HOME) "从本地最近导入开始阅读；在线推荐尚未接入。" else "你的本地漫画 · 原文件始终保留")
            TextButton(onClick = openLibrary, modifier = Modifier.testTag("open-local-library")) { Text("本地 CBZ · 导入与页序") }
            TextButton(onClick = samples, modifier = Modifier.testTag("sample-mode")) { Text("固定样本验证") }
            state.message?.let { RequestFeedback(it, Modifier.testTag("home-error").semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.loading) CircularProgressIndicator(Modifier.testTag("home-loading"))
        } }
        if (state.loading && state.cards.isEmpty()) item("read-layout") { ReadLayoutPlaceholder() }
        if (!state.loading || state.cards.isNotEmpty()) {
            if (state.cards.isEmpty() && state.message == null) item { Text("还没有本地漫画，选择 CBZ 导入后会显示封面与阅读记录。", Modifier.testTag("home-empty")) }
            if (tab == MainTab.LIBRARY || tab == MainTab.HOME) {
                item { Text("继续阅读", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
                if (continuing.isEmpty()) item { Text("开始阅读一本漫画后，在这里接着看。", Modifier.testTag("home-no-progress")) }
                items(continuing, key = { "continue-${it.key.mangaId}" }) { card ->
                    HomeBook(card, model, state.revision, "continue", open, read)
                }
            }
            item { Text("最近导入", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            items(recent, key = { "recent-${it.key.mangaId}" }) { card ->
                HomeBook(card, model, state.revision, "recent", open, read)
            }
            item { TextButton(onClick = openLibrary, modifier = Modifier.testTag("home-all")) { Text("查看全部 / 搜索 / 收藏") } }
        }
        onlineHomeItems(online, onlineModel, onlineLibrary, onlineOpen, onlineRead, sources)
    }
}

@Composable
internal fun HomeBook(card: MangaCard, model: LocalLibraryViewModel, revision: Int, section: String,
                     open: (String) -> Unit, read: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("home-$section-${card.key.mangaId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LocalCover(card, model, revision)
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            Text("${card.pageCount?.let { "$it 页" } ?: "页数待获取"}${if (card.favorite) " · 已收藏" else ""}")
            TextButton(onClick = { open(card.key.mangaId) }, modifier = Modifier.heightIn(min = 48.dp).testTag("home-detail-$section-${card.key.mangaId}")
                .semantics { contentDescription = "查看《${card.title}》详情" }) {
                Text("查看详情", Modifier.clearAndSetSemantics { })
            }
            TextButton(onClick = { read(card.key.mangaId) }, enabled = card.available,
                modifier = Modifier.heightIn(min = 48.dp).testTag("home-read-$section-${card.key.mangaId}").semantics {
                    contentDescription = if (!card.available) "《${card.title}》副本缺失，请重新导入"
                        else card.progress?.let { "继续阅读《${card.title}》，第 ${it.ordinal} 页" } ?: "开始阅读《${card.title}》"
                }) {
                // Parent supplies the full book/action label; avoid announcing visible text twice.
                Text(if (!card.available) "副本缺失，请重新导入" else card.progress?.let { "继续阅读 · 第 ${it.ordinal} 页" } ?: "开始阅读",
                    Modifier.clearAndSetSemantics { })
            }
        }
    }
}

