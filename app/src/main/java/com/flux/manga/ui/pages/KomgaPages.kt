package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.core.SourceContinuation
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld

@Composable
private fun KomgaNotice(text: String) {
    RequestFeedback(text)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KomgaSettingsPage(session: WorldSession, back: () -> Unit, browse: (String) -> Unit, imports: () -> Unit = {}) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if (identity.world != PrototypeWorld.SFW) {
        TextButton(onClick = back) { Text("图源设置仅在表世界可用 · 返回") }; return
    }
    val app = LocalContext.current.applicationContext as Application
    val model: KomgaSettingsModel = viewModel(factory = viewModelFactory { initializer { KomgaSettingsModel(app, session) } })
    val navigation = rememberSourceSettingsNavigation(back)
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(model) { model.load(); onDispose { model.leave() } }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("komga-settings"), state = navigation.scroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading(navigation.title("Komga"), "", navigation::back, backModifier = Modifier.testTag("komga-back")) }
        sourceSettingsOverview(navigation,
            account = if (!model.loaded) "配置待读取" else if (model.sourceId != null) "已保存服务器账号" else "尚未连接服务器",
            imports = imports, importSummary = "授权书库、系列集合与阅读列表",
            connectionSummary = model.savedAddress ?: "配置自己的 Komga 服务",
            accountAction = { navigation.open(SourceSettingsSection.CONNECTION) },
            importEnabled = model.loaded && !model.busy && model.sourceId != null)
        if (navigation.section == SourceSettingsSection.CONNECTION) item {
            AccountPanel("服务器与账号") {
                OutlinedTextField(value = model.address, onValueChange = model::address,
                    label = { Text("HTTPS 服务地址") }, placeholder = { Text("https://example.com/komga/") },
                    singleLine = true, shape = MaterialTheme.shapes.small, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("komga-address"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                WebAccountEntry(model.address,
                    "网页登录成功后自动验证并保存账号；注册是否开放由服务器管理员决定。", !model.busy,
                    connect = { page, _, _ -> model.connectWebSession(page.cookie(model.address, "KOMGA-SESSION")) },
                    autoConnectKey = { page, _, _ -> page.cookie(model.address, "KOMGA-SESSION").takeIf(String::isNotBlank) })
                SettingsDisclosure("连接服务器账号") {
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(model.auth == com.flux.manga.data.KomgaAuth.API_KEY, { model.auth(com.flux.manga.data.KomgaAuth.API_KEY) }, label = { Text("API Key") }, enabled = !model.busy)
                        FilterChip(model.auth == com.flux.manga.data.KomgaAuth.BASIC, { model.auth(com.flux.manga.data.KomgaAuth.BASIC) }, label = { Text("邮箱 / 密码") }, enabled = !model.busy)
                    }
                    if (model.auth == com.flux.manga.data.KomgaAuth.BASIC) OutlinedTextField(model.username, model::username,
                        label = { Text("账号邮箱") }, singleLine = true, enabled = !model.busy, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    if (model.auth == com.flux.manga.data.KomgaAuth.SESSION) Text("已连接网页账号；可重新网页登录更新会话")
                    else {
                    OutlinedTextField(value = model.apiKey, onValueChange = model::apiKey,
                        label = { Text(if (model.auth == com.flux.manga.data.KomgaAuth.API_KEY) "API Key" else "密码") }, singleLine = true, shape = MaterialTheme.shapes.small, enabled = !model.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("连接信息不变时，可留空沿用已保存凭据") },
                        modifier = Modifier.fillMaxWidth().testTag("komga-key"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); model.save() }))
                    ReferenceButton(onClick = { keyboard?.hide(); model.save() },
                        enabled = !model.busy && model.loaded, modifier = Modifier.heightIn(min = 48.dp).testTag("komga-save")) {
                        Text("验证并保存")
                    }
                    }
                }
                if (model.busy) {
                    RequestLoading()
                    if (!model.saving) TextButton(onClick = model::cancel) { Text("取消请求") }
                }
                if (!model.loaded && !model.busy) TextButton(onClick = feedbackAttempt(model::load)) { Text("重新读取配置") }
                model.message?.let { KomgaNotice(it) }
            }
        }
        if (navigation.section == SourceSettingsSection.CONNECTION) item("library-import") { SourceLibraryImportCard("选择授权书库、系列集合或阅读列表导入；已有进度保留。",
            model.loaded && model.sourceId != null, model.busy, "import-komga-library", imports) }
        if (navigation.section == SourceSettingsSection.OVERVIEW) model.sourceId?.let { sourceId ->
            item {
                Text(model.savedAddress.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReferenceButton(onClick = { keyboard?.hide(); browse(sourceId) }, enabled = !model.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("komga-browse")) { Text("浏览作品") }
                }
            }
        }
        if (navigation.section == SourceSettingsSection.CONNECTION) item { AccountMoreActions("账号管理") { close ->
            if (model.sourceId != null) TextButton(onClick = { close(); model.logout() }, enabled = !model.busy && model.loaded) { Text("退出账号 · 保留书目与进度") }
            Text("可连接一个 Komga 服务。验证成功后，登录信息加密保存在本机。")
            Text("API Key 或邮箱密码连接信息不变时，凭据可以留空沿用；网页会话失效后需重新登录。")
            Text("支持在线阅读和章节下载，进度保存在本机；服务端进度同步尚未开放。")
        } }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun KomgaContentPage(session: WorldSession, sourceId: String, mangaId: String? = null,
    back: () -> Unit, open: (String) -> Unit = {}, read: (String?) -> Unit = {},
    history: SearchHistoryModel? = null, sources: () -> Unit = {}, openTag: (String, String) -> Unit = { _, _ -> }, download: () -> Unit = {}, searchAuthor: ((String) -> Unit)? = null) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if (identity.world != PrototypeWorld.SFW) {
        TextButton(onClick = back) { Text("在线浏览仅在表世界可用 · 返回") }; return
    }
    val app = LocalContext.current.applicationContext as Application
    val model: KomgaContentModel = viewModel(key = "komga-content:$sourceId:$mangaId", factory = viewModelFactory {
        initializer { KomgaContentModel(app, session, sourceId, mangaId, createSavedStateHandle(), manualSearch = history != null) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    val submit: () -> Unit = feedbackAttempt {
        history?.let { model.query(it.query); it.submit(sourceId, it.query) }
        model.search()
    }
    val query = history?.query ?: model.query
    val changeQuery: (String) -> Unit = { history?.query(it); model.query(it) }
    val feed = DiscoveryFeedState(cards = state.cards, next = state.next, loaded = state.loaded, busy = state.busy,
        error = state.error, appendError = state.appendError, notice = state.notice)
    var previousQuery by rememberSaveable { mutableStateOf(model.submitted) }
    LaunchedEffect(model.submitted) {
        if (mangaId == null && previousQuery != model.submitted) scroll.scrollToItem(0)
        previousQuery = model.submitted
    }
    DisposableEffect(model) { model.start(); onDispose { model.leave() } }
    BookResultsLayout(enabled = mangaId == null) { columns ->
        ReferenceRefreshColumn(refreshing = state.busy, refresh = { model.refresh() }, refreshEnabled = true, modifier = Modifier.fillMaxSize().testTag(if (mangaId == null) "komga-catalog" else "komga-detail"),
            state = scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "heading") {
                if (mangaId != null) DetailHeading(back, backModifier = Modifier.testTag("komga-back"))
                else SourceBrowseHeading("Komga", back,
                    "浏览全部作品", { changeQuery(""); model.search() }, sources,
                    "搜索服务器作品，留空可浏览全部。修改关键词后请重新搜索，阅读进度保存在本机。",
                    backTag = "komga-back")
            }
            if (mangaId == null) {
                item(key = "search") {
                    SourceBrowseQuery(query, changeQuery, !state.busy || query.trim() != model.submitted, submit, "komga-query")
                }
                item(key = "result-summary") {
                    Text(if (history != null && !state.loaded && !state.busy && state.error == null && state.notice == null) "输入关键词或浏览全部作品"
                        else if (model.isBrowsingAll) "全部作品" else "搜索：${model.submitted}", style = MaterialTheme.typography.titleMedium)
                    Text("已显示 ${state.cards.size} 部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (mangaId == null && history != null) item(key = "history") {
                SearchHistoryPanel(history, sourceId, { changeQuery(it); keyboard?.hide(); submit() }, sources)
            }
            if (mangaId != null && !readContentLayout(state.detail != null, state.busy, state.error ?: state.notice.takeIf { state.detail == null }, model::refresh, detail = true, cancel = model::cancel))
                return@ReferenceRefreshColumn
            if (mangaId != null || state.busy || state.error != null || state.notice != null || (state.loaded && state.cards.isEmpty())) item(key = "status") {
                if (mangaId == null) {
                    SourceBrowseStatus(feed, model::refresh, model::cancel,
                        if (model.isBrowsingAll) "服务中暂无作品" else "没有匹配作品，可修改搜索词")
                } else {
                    if (state.busy) {
                        Text(if (state.loaded) "正在更新…" else "正在加载…")
                        TextButton(onClick = model::cancel) { Text("取消请求") }
                    }
                    state.notice?.let {
                        KomgaNotice(it)
                        if (!state.busy) TextButton(onClick = feedbackAttempt(model::refresh)) { Text("重新加载") }
                    }
                    state.error?.let {
                        KomgaNotice(it)
                        TextButton(onClick = feedbackAttempt(model::refresh), enabled = !state.busy, modifier = Modifier.testTag("komga-retry")) { Text("重试") }
                    }
                }
            }
            if (mangaId == null) {
                bookGridItems(if (state.error != null && !state.busy) emptyList() else state.cards, columns, { "book:${it.key.sourceId}:${it.key.mangaId}" }) { card ->
                    BookResultCard(card.title, { keyboard?.hide(); open(card.key.mangaId) },
                        titleModifier = Modifier.testTag("komga-book-${card.key.mangaId}"), cover = {
                            OnlineCover(model.library, card.key.sourceId, card.key.mangaId, fillWidth = true)
                        })
                }
            } else {
                state.detail?.let { detail ->
                    item(key = "hero") {
                        DetailHero(detail.manga.title, { OnlineCover(model.library, sourceId, detail.manga.key.mangaId, fillWidth = true) }) {
                            WorkMetadata(detail.metadata, "$sourceId:$mangaId", searchAuthor)
                            KomgaDetailTags(detail.manga.key.sourceId, detail.manga.key.mangaId, detail.tags, compact = true, open = openTag)
                        }
                    }
                    item(key = "actions") {
                        DetailActions(state.favorite, state.savingFavorite, toggleFavorite = model::favorite,
                            download = download, read = { read(if (state.hasProgress) null else state.chapters.firstOrNull()?.id) },
                            canRead = state.hasProgress || state.chapters.isNotEmpty(), hasProgress = state.hasProgress,
                            description = detail.description, descriptionIdentity = "$sourceId:$mangaId",
                            readingPosition = readingPosition(state.progress, chapterEntries(state.chapters)))
                    }
                    if (state.chapters.size != 1 || state.next is SourceContinuation.Next || state.busy || state.appendError != null) item(key = "directory") {
                        RecordedDetailCatalog(session, sourceId, mangaId, chapterEntries(state.chapters), state.progress?.chapterId, { read(it) }, busy = state.busy,
                            hasMore = state.next is SourceContinuation.Next, more = model::more, appendError = state.appendError, totalCount = state.chapterTotal)
                        if (state.loaded && state.chapters.isEmpty() && state.error == null) Text("暂无章节")
                    }
                }
            }
            if (mangaId == null) item(key = "pagination") {
                key(model, model.submitted) { SourceBrowsePagination(feed, model::more, "komga-more", scroll) }
            }
        }

    }
}
