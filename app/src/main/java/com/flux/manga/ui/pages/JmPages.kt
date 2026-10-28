package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class JmUiState(val ready: Boolean = false, val busy: Boolean = false, val settings: JmSettings = JmSettings(),
    val source: String = JM_SOURCE, val query: String = "", val page: Int = 0, val next: Int? = null,
    val folder: String = "0", val folders: List<JmFolder> = listOf(JmFolder("0", "全部收藏")),
    val books: List<JmBook> = emptyList(), val detail: JmBook? = null, val favorite: Boolean = false,
    val progress: OnlineReadingProgress? = null, val history: List<String> = emptyList(), val message: String? = null, val error: Boolean = false, val detailError: String? = null) {
    val resume: String? get() = progress?.chapterId
}

internal class JmModel(app: Application, private val session: WorldSession, private val selectedSource: String? = null) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val store = JmStore(app, session)
    private val data = WorldDataStore(app)
    val state = MutableStateFlow(JmUiState())
    private var client: JmClient? = null
    private val clientLock = Mutex()
    private var job: Job? = null
    private var retry: (() -> Unit)? = null
    fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity)
    private fun foreground() = active() && session.state.value.foreground
    init { refreshSettings() }
    private suspend fun client(): JmClient = clientLock.withLock {
        if (!active()) throw CancellationException()
        val config = store.settings()
        if (!foreground()) throw CancellationException()
        val source = selectedSource ?: config.account?.source ?: JM_SOURCE
        if (source != JM_SOURCE && source != config.account?.source) throw java.io.IOException("请登录此作品所属的 JMComic 账号")
        client ?: JmClient(session, config, source, viewModelScope).also { client = it }
    }
    private fun work(again: () -> Unit, preserveFeedback: Boolean = false, readingDetail: Boolean = false, action: suspend () -> Unit) {
        if (!foreground() || state.value.busy) return
        if (!preserveFeedback) retry = again
        state.update { it.copy(busy = true, message = if (preserveFeedback) it.message else null, error = preserveFeedback && it.error, detailError = if (readingDetail) null else it.detailError) }
        job = viewModelScope.launch {
            try { action(); ensureActive() }
            catch (_: CancellationException) { retry = again; if (active()) state.update { it.copy(message = "操作已停止，可重试", error = true, detailError = if (readingDetail) "操作已停止，可重试" else it.detailError) } }
            catch (error: Exception) { retry = again; if (active()) state.update { it.copy(message = jmFailure(error), error = true, detailError = if (readingDetail) jmFailure(error) else it.detailError) } }
            finally { if (active()) state.update { it.copy(busy = false) } }
        }
    }
    fun refreshSettings(): Unit = work(::refreshSettings, preserveFeedback = state.value.ready) {
        val settings = store.settings(); val source = selectedSource ?: settings.account?.source ?: JM_SOURCE
        if (settings != state.value.settings || source != state.value.source) {
            client?.close(); client = null
            retry = null
            state.update { it.copy(books = emptyList(), folders = emptyList(), folder = "0", page = 0, next = null, detail = null, progress = null, message = null, error = false, detailError = null) }
        }
        val history = data.worldData(identity.world, ::active) { it.history("online:$source") }
        val reading = state.value.detail?.let { book -> data.worldData(identity.world, ::active) { dao ->
            (dao.onlineBook(source, book.id)?.favorite == true) to dao.onlineProgress(source, book.id)
        } }
        state.update { it.copy(ready = true, settings = settings, source = source, history = history,
            favorite = reading?.first ?: it.favorite, progress = if (reading != null) reading.second else it.progress) }
    }
    fun retry() { retry?.invoke() }
    fun stop() { job?.cancel(); client?.close(); client = null }
    fun query(value: String) { state.update { it.copy(query = value.take(200), books = emptyList(), page = 0, next = null) } }
    fun search(page: Int = 1): Unit = work({ search(page) }) {
        val text = state.value.query.trim()
        require(text.isNotBlank()) { "请输入关键词或 JM 编号" }
        val current = client(); val result = current.search(text, page)
        if (!active()) throw CancellationException()
        data.remember(identity.world, "online:${current.source}", text, ::active)
        val history = data.worldData(identity.world, ::active) { it.history("online:${current.source}") }
        state.update { it.copy(books = result.books, page = page, next = result.next, history = history, message = if (result.books.isEmpty()) "没有匹配作品" else null) }
    }
    fun favorites(folder: String = state.value.folder, page: Int = 1): Unit = work({ favorites(folder, page) }) {
        val result = client().favorites(folder, page)
        if (!active()) throw CancellationException()
        state.update { it.copy(books = result.books, folders = result.folders, folder = folder, page = page, next = result.next,
            message = if (result.books.isEmpty()) "此收藏夹为空" else null) }
    }
    fun importPage() = importSelected(null)
    fun importSelected(ids: Set<String>?) {
        val rows = state.value.books.filter { ids == null || it.id in ids }; val source = state.value.source
        work({ importSelected(ids) }) {
            val added = store.importPage(source, rows)
            state.update { it.copy(message = "新增 $added 本，保留已有 ${rows.size - added} 本；可继续读取其他页。") }
        }
    }
    fun clearHistory(): Unit = work(::clearHistory) {
        data.clearHistory(identity.world, "online:${state.value.source}", ::active)
        state.update { it.copy(history = emptyList()) }
    }
    fun detail(id: String): Unit = work({ detail(id) }, readingDetail = true) {
        val current = client()
        val cache = DetailPageCache(getApplication<Application>(), session, current.source, id)
        cache.read()?.let { json ->
            val reading = data.worldData(identity.world, ::active) { dao -> (dao.onlineBook(current.source, id)?.favorite == true) to dao.onlineProgress(current.source, id) }
            if (active()) state.update { it.copy(detail = jmDetail(json), favorite = reading.first, progress = reading.second) }
        }
        val book = current.detail(id)
        store.remember(current.source, book)
        val (favorite, progress) = data.worldData(identity.world, ::active) { dao ->
            dao.putVisit(BrowseVisit("online", current.source, id, book.title, System.currentTimeMillis()))
            val favorite = dao.onlineBook(current.source, id)?.favorite == true
            val progress = dao.onlineProgress(current.source, id)
            favorite to progress
        }
        if (active()) state.update { it.copy(detail = book, favorite = favorite, progress = progress) }
        cache.write(detailJson(book))
        cache.information(detailInformation(book))
    }
    fun favorite() {
        val book = state.value.detail ?: return
        val value = !state.value.favorite
        work(::favorite) { store.remember(state.value.source, book, value); state.update { it.copy(favorite = value, message = "本机收藏已更新；未改动网站收藏") } }
    }
    fun login(username: String, password: String) = work({ state.update { it.copy(message = "请重新输入密码登录") } }) {
        require(username.isNotBlank() && password.isNotEmpty())
        val old = store.settings()
        JmClient(session, old.copy(account = null), JM_SOURCE, viewModelScope).use { login ->
            val account = login.login(username.trim(), password)
            val next = old.copy(account = account); store.save(next)
            client?.close(); client = null
            state.update { it.copy(settings = next, source = account.source, books = emptyList(), detail = null, page = 0, next = null,
                message = "已登录，密码未保存；可读取收藏文件夹") }
        }
    }
    suspend fun connectWebAccount(candidate: JmAccount) {
        if (!foreground()) throw CancellationException()
        check(!state.value.busy)
        state.update { it.copy(busy = true, message = null, error = false) }
        try {
            val old = store.settings()
            val next = JmClient(session, old.copy(account = candidate), candidate.source, viewModelScope).use { api ->
                old.copy(account = api.verifyWebAccount())
            }
            currentCoroutineContext().ensureActive()
            if (!foreground()) throw CancellationException()
            store.save(next)
            client?.close(); client = null; retry = null
            if (foreground()) state.update { JmUiState(ready = true, busy = true, settings = next, source = candidate.source,
                message = "网页账号与收藏访问已确认；可分页导入或一键导入全部收藏") }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { throw java.io.IOException(jmFailure(failure))
        } finally { if (active()) state.update { it.copy(busy = false) } }
    }
    fun configure(api: String, image: String): Unit = work({ configure(api, image) }) {
        val old = store.settings()
        val next = old.copy(api = api, image = image, account = if (old.api == api) old.account else null)
        store.save(next); client?.close(); client = null
        state.update { it.copy(settings = next, source = next.account?.source ?: JM_SOURCE, books = emptyList(), page = 0, next = null,
            message = if (old.api != api) "线路已保存，请重新登录；旧书目和进度保留" else "线路已保存") }
    }
    fun logout(): Unit = work(::logout) {
        val next = store.settings().copy(account = null)
        store.save(next); client?.close(); client = null
        state.update { it.copy(settings = next, source = JM_SOURCE, books = emptyList(), page = 0, next = null, message = "已退出，书库和进度保留") }
    }
    suspend fun cover(id: String): Bitmap = jmCover(data, session, client(), id)
    override fun onCleared() { stop(); data.clearMemory() }
}

@Composable private fun jmModel(session: WorldSession, source: String? = null): JmModel {
    val app = LocalContext.current.applicationContext as Application
    val model: JmModel = viewModel(factory = viewModelFactory { initializer { JmModel(app, session, source) } })
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refreshSettings() }
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    return model
}
@Composable private fun JmFeedback(model: JmModel, state: JmUiState) {
    if (state.busy) { RequestLoading(); TextButton(model::stop) { Text("取消请求") } }
    state.message?.let { RequestFeedback(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, failure = state.error, busy = state.busy) }
    if (state.error && !state.busy) TextButton(feedbackAttempt(model::retry)) { Text("重试") }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun JmBrowsePage(session: WorldSession, back: () -> Unit, settings: () -> Unit,
    open: (String, String) -> Unit, favorites: Boolean = false, initialQuery: String = "", bottom: androidx.compose.ui.unit.Dp = 22.dp,
    heading: String? = null, visits: (() -> Unit)? = null, source: String? = null, library: (() -> Unit)? = null) {
    val model = jmModel(session, source)
    val state by model.state.collectAsStateWithLifecycle()
    val signedIn = state.settings.account != null
    var initialized by remember(model, state.source, state.settings) { mutableStateOf(false) }
    LaunchedEffect(state.ready, state.busy, state.source, state.settings) { if (state.ready && !state.busy && (!favorites || signedIn) && !initialized) {
        initialized = true
        if (state.page == 0) {
            if (favorites) model.favorites() else if (initialQuery.isNotBlank() && state.query.isBlank()) { model.query(initialQuery); model.search() }
        }
    } }
    val favoritesImport = rememberPrivateFavoritesImport(session, if (favorites && signedIn && state.source == state.settings.account?.source) state.source else null)
    val informationModel = if (favoritesImport != null) rememberBookInformation(session, state.source) else null
    if (favorites) {
        PrivateCollectionImportPage(session, state.source, "JMComic", signedIn,
            state.books.map { ImportBook(it.id, it.id, it.title, it.author) }, state.busy, state.message, state.error,
            favoritesImport, informationModel, back, settings, { open(state.source, it) }, library,
            refresh = { model.favorites(page = state.page.coerceAtLeast(1)) }, stop = model::stop,
            importSelected = { model.importSelected(it) },
            scope = { enabled -> ImportScopePicker(state.folder, state.folders.map { it.id to it.title }, enabled) { model.favorites(it) } }, pagination = { enabled -> FlowRow {
                if (state.page > 1) TextButton({ model.favorites(page = state.page - 1) }, enabled = enabled) { Text("上一页") }
                state.next?.let { next -> TextButton({ model.favorites(page = next) }, enabled = enabled) { Text("下一页") } }
            } }, cover = { book -> PrivateSourceCover(session, state.source, book.manga, book.title) { model.cover(book.manga) } })
        return
    }
    BookResultsLayout(enabled = !favorites) { columns ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (heading != null) DiscoveryHeading("jm-root-settings", back, heading)
                else SettingsHeading(if (favorites) "JMComic 书库导入" else "搜索漫画", "", back)
            }
            item { TextButton(settings) { Text("图源管理与账号") } }
            if (visits != null && heading == "首页") item { TextButton(visits) { Text("最近观看") } }
            if (!favorites) item {
                if (heading == "标签搜索") Text("输入标签作为关键词搜索；也可从作品详情点击标签继续浏览。")
                ReferenceSubmitSearchField(state.query, model::query, "关键词或 JM 编号", { model.search() },
                    inputEnabled = !state.busy, submitEnabled = state.ready && state.query.isNotBlank())
            }
            if (!favorites && state.page == 0 && state.history.isNotEmpty()) item {
                Text("搜索历史", style = MaterialTheme.typography.titleSmall)
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.history.forEach { query -> TextButton(feedbackAttempt({ model.query(query); model.search() }), enabled = !state.busy) { Text(query) } }
                }
                TextButton(model::clearHistory, enabled = !state.busy) { Text("清空当前账号搜索历史") }
            }
            item { JmFeedback(model, state) }
            bookGridItems(state.books, columns, { it.id }) { book ->
                BookResultCard(book.title, grid = !favorites, metadata = book.author, cover = {
                    PrivateSourceCover(session, state.source, book.id, book.title) { model.cover(book.id) }
                }, open = { open(state.source, book.id) })
            }

            if (state.page > 0) item {
                Text("第 ${state.page} 页 · 本页 ${state.books.size} 本")
                if (state.page > 1) TextButton(feedbackAttempt({ if (favorites) model.favorites(page = state.page - 1) else model.search(state.page - 1) }), enabled = !state.busy) { Text("上一页") }
                state.next?.let { next -> ReferenceButton(feedbackAttempt({ if (favorites) model.favorites(page = next) else model.search(next) }), enabled = !state.busy) { Text("下一页") } }
            }
        }
    }
}
@Composable internal fun JmSettingsPage(session: WorldSession, back: () -> Unit, browse: () -> Unit, favorites: () -> Unit) {
    val model = jmModel(session); val state by model.state.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var api by remember(state.settings.api) { mutableStateOf(state.settings.api) }
    var webLogin by remember { mutableStateOf(false) }
    var image by remember(state.settings.image) { mutableStateOf(state.settings.image) }
    val navigation = rememberSourceSettingsNavigation(back)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (!lifecycle.isAtLeast(Lifecycle.State.STARTED)) password = "" }
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = navigation.scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading(navigation.title("JMComic"), "", navigation::back) }
        sourceSettingsOverview(navigation,
            account = if (!state.ready) "账号状态待读取" else state.settings.account?.let { "已保存会话 · ${it.username}" } ?: "未登录 · 可匿名阅读",
            imports = favorites, importSummary = "全部收藏或按文件夹导入", connectionSummary = "API 与图片线路",
            importEnabled = state.ready && !state.busy && state.settings.account != null)
        item { JmFeedback(model, state) }
        if (navigation.section == SourceSettingsSection.CONNECTION) item { SettingsPanel {
            Text("API 线路 · 与网页地址不同")
            JM_APIS.forEach { host -> SettingsChoiceRow(if (host == "www.cdngwc.cc") "$host（旧线路）" else host,
                api == host, { api = host }, enabled = !state.busy) }
            Text("图片线路")
            JM_IMAGES.forEach { host -> SettingsChoiceRow(host, image == host, { image = host }, enabled = !state.busy) }
            ReferenceButton({ model.configure(api, image) }, enabled = state.ready && !state.busy) { Text("保存线路") }
        } }
        if (navigation.section == SourceSettingsSection.ACCOUNT) item { SettingsPanel {
            AccountSummary(when {
                !state.ready -> if (state.busy) "正在读取账号…" else "账号状态待核对"
                state.settings.account != null -> "已保存登录会话"
                else -> "未登录 · 可匿名搜索和阅读"
            }, "收藏文件夹与收藏作品", state.settings.account?.username.orEmpty())
            ReferenceButton({ webLogin = true }, enabled = state.ready && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("应用内网页登录 / 注册")
            }
            Text("登录后连接账号与收藏；确认成功后进入原收藏导入页。", style = MaterialTheme.typography.bodySmall)
            SettingsDisclosure("API 账号连接") {
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("账号") }, enabled = !state.busy)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), enabled = !state.busy)
                ReferenceButton(feedbackAttempt({ model.login(username, password); password = "" }), enabled = state.ready && !state.busy && username.isNotBlank() && password.isNotEmpty()) { Text("登录") }
            }
            if (state.settings.account != null) TextButton(model::logout, enabled = !state.busy) { Text("退出账号 · 保留书库") }
            Text("登录信息加密保存，切换 API 线路后需重新登录。", style = MaterialTheme.typography.bodySmall)
        } }
        if (navigation.section == SourceSettingsSection.ACCOUNT) item("library-import") { SourceLibraryImportCard("全部收藏或按文件夹导入；已有书目与阅读进度保留。",
            state.ready && state.settings.account != null, state.busy, "import-jm-library", favorites) }
        if (navigation.section == SourceSettingsSection.OVERVIEW) item { SettingsGroup {
            SettingsRow("搜索与编号直达", "", "jm-settings-browse", SettingsSymbol.SOURCES, browse, enabled = !state.busy)
        } }
    }
    if (webLogin) AccountWebLogin(JmWebSite, close = { webLogin = false }, jmIdentityFields = true, loginUrls = JmWebLoginUrls,
        autoConnectKey = { page, uid, name -> page.jmAccount(uid, name).let { "${it.uid}:${it.username}:${it.cookie}" } },
        connect = { page, uid, name -> model.connectWebAccount(page.jmAccount(uid, name)) }, afterConnected = favorites)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun JmDetailPage(session: WorldSession, source: String, id: String, back: () -> Unit,
    read: (String) -> Unit, tag: (String) -> Unit, download: () -> Unit = {}, searchAuthor: ((String) -> Unit)? = null) {
    val model = jmModel(session, source); val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ready, state.source, state.settings) { if (state.ready && state.detail == null) model.detail(id) }
    ReferenceRefreshColumn(modifier = Modifier.fillMaxSize(), refreshing = state.busy, refresh = { model.detail(id) },
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DetailHeading(back) }
        if (!readContentLayout(state.detail != null, state.busy, state.detailError ?: state.message.takeIf { state.error && state.detail == null }, { model.detail(id) }, detail = true, cancel = model::stop)) {
            item("offline-entry") { TextButton(download) { Text("加密下载与离线章节") } }
            return@ReferenceRefreshColumn
        }
        item { JmFeedback(model, state) }
        state.detail?.let { book ->
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailHero(book.title, { PrivateSourceCover(session, source, book.id, book.title) { model.cover(id) } }, titleLines = 5) {
                    if (book.author.isNotBlank()) Text(authorNames(book.author.split(" / ").map { it to it }, searchAuthor), style = MaterialTheme.typography.bodyMedium)
                    if (book.chapters.size > 1) Text("${book.chapters.size} 章", style = MaterialTheme.typography.bodySmall)
                }
                DetailActions(state.favorite, state.busy, toggleFavorite = model::favorite, download = download,
                    read = { (state.resume ?: book.chapters.firstOrNull()?.id)?.let(read) },
                    canRead = (state.resume != null || book.chapters.isNotEmpty()) && !state.busy, hasProgress = state.resume != null,
                    description = book.description, descriptionIdentity = "$source:${book.id}",
                    readingPosition = readingPosition(state.progress, book.chapters.mapIndexed { index, chapter -> DetailCatalogEntry(chapter.id, chapterTitle(chapter.title, index + 1)) }),
                    belowActions = { WorkTags(book.tags.map { it to it }, maxRows = 2) { value, _ -> tag(value) } })
            } }
            if (book.chapters.size > 1) item { RecordedDetailCatalog(session, source, id, book.chapters.mapIndexed { index, chapter -> DetailCatalogEntry(chapter.id, chapterTitle(chapter.title, index + 1)) }, state.resume, read,
                enabled = !state.busy) }

        }
    }
}
