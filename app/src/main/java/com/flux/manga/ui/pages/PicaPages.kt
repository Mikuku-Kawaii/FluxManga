package com.flux.manga.ui.pages

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
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

internal data class PicaUiState(val ready: Boolean = false, val busy: Boolean = false, val account: PicaAccount? = null,
    val source: String? = null, val query: String = "", val page: Int = 0, val next: Int? = null,
    val books: List<PicaBook> = emptyList(), val categories: List<String> = emptyList(), val history: List<String> = emptyList(),
    val book: PicaBook? = null, val chapters: List<PicaChapter> = emptyList(), val favorite: Boolean = false,
    val progress: OnlineReadingProgress? = null, val message: String? = null, val error: Boolean = false, val detailError: String? = null) {
    val resume: String? get() = progress?.chapterId
}

internal class PicaModel(app: Application, private val session: WorldSession, private val selectedSource: String?) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val store = PicaStore(app, session)
    private val data = WorldDataStore(app)
    val state = MutableStateFlow(PicaUiState())
    private var client: PicaClient? = null
    private val lock = Mutex()
    private var job: Job? = null
    private var retry: (() -> Unit)? = null
    private fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity)
    private fun foreground() = active() && session.state.value.foreground
    init { refresh() }
    private suspend fun client(): PicaClient = lock.withLock {
        val account = store.account() ?: throw java.io.IOException("请先在图源管理登录 PicACG")
        if (selectedSource != null && selectedSource != account.source) throw java.io.IOException("请登录此作品所属的 PicACG 账号")
        if (!foreground()) throw CancellationException()
        client ?: PicaClient(session, account, viewModelScope).also { client = it }
    }
    private fun work(again: () -> Unit, preserve: Boolean = false, readingDetail: Boolean = false, action: suspend () -> Unit) {
        if (!foreground() || state.value.busy) return
        if (!preserve) retry = again
        state.update { it.copy(busy = true, message = if (preserve) it.message else null, error = preserve && it.error, detailError = if (readingDetail) null else it.detailError) }
        job = viewModelScope.launch {
            try { action(); ensureActive() }
            catch (_: CancellationException) { retry = again; if (active()) state.update { it.copy(message = "操作已停止，可重试", error = true, detailError = if (readingDetail) "操作已停止，可重试" else it.detailError) } }
            catch (error: Exception) { retry = again; if (active()) state.update { it.copy(message = picaFailure(error), error = true, detailError = if (readingDetail) picaFailure(error) else it.detailError) } }
            finally { if (active()) state.update { it.copy(busy = false) } }
        }
    }
    fun refresh(): Unit = work(::refresh, preserve = state.value.ready) {
        val account = store.account(); val source = selectedSource ?: account?.source
        if (account != state.value.account || source != state.value.source) {
            client?.close(); client = null; retry = null
            state.update { it.copy(books = emptyList(), categories = emptyList(), book = null, chapters = emptyList(), page = 0, next = null, progress = null, message = null, error = false, detailError = null) }
        }
        val history = if (source == null) emptyList() else data.worldData(identity.world, ::active) { it.history("online:$source") }
        val reading = state.value.book?.let { book -> data.worldData(identity.world, ::active) { dao ->
            val sourceId = requireNotNull(source)
            (dao.onlineBook(sourceId, book.id)?.favorite == true) to dao.onlineProgress(sourceId, book.id)
        } }
        state.update { it.copy(ready = true, account = account, source = source, history = history,
            favorite = reading?.first ?: it.favorite, progress = if (reading != null) reading.second else it.progress) }
    }
    fun stop() { job?.cancel(); client?.close(); client = null }
    fun retry() { retry?.invoke() }
    fun query(value: String) { state.update { it.copy(query = value.take(200), books = emptyList(), page = 0, next = null) } }
    fun browse(kind: String, value: String, page: Int = 1): Unit = work({ browse(kind, value, page) }) {
        val api = client()
        if (kind == "categories") {
            val rows = api.categories(); state.update { it.copy(categories = rows, message = if (rows.isEmpty()) "暂无分类" else null) }; return@work
        }
        val result = when (kind) {
            "search" -> { require(value.isNotBlank()); api.search(value.trim(), page) }
            "favorites" -> api.favorites(page)
            "category" -> api.catalog(value, page)
            "latest" -> api.catalog("", page)
            "rank" -> api.rank(value)
            else -> throw java.io.IOException("列表类型不受支持")
        }
        if (!active()) throw CancellationException()
        if (kind == "search") data.remember(identity.world, "online:${api.source}", value.trim(), ::active)
        val history = data.worldData(identity.world, ::active) { it.history("online:${api.source}") }
        state.update { it.copy(books = result.items.distinctBy { row -> row.id }, page = result.page, next = result.next, history = history,
            message = if (result.items.isEmpty()) "当前列表没有作品" else null) }
    }
    fun clearHistory(): Unit = work(::clearHistory) {
        data.clearHistory(identity.world, "online:${requireNotNull(state.value.source)}", ::active)
        state.update { it.copy(history = emptyList()) }
    }
    fun importPage() = importSelected(null)
    fun importSelected(ids: Set<String>?): Unit = work({ importSelected(ids) }) {
        val rows = state.value.books.filter { ids == null || it.id in ids }; val source = requireNotNull(state.value.source)
        val added = store.importPage(source, rows)
        state.update { it.copy(message = "新增 $added 本，保留已有 ${rows.size - added} 本；可继续读取其他页") }
    }
    fun detail(id: String): Unit = work({ detail(id) }, readingDetail = true) {
        val api = client()
        val cache = DetailPageCache(getApplication<Application>(), session, api.source, id)
        cache.read()?.let { json ->
            val reading = data.worldData(identity.world, ::active) { dao -> (dao.onlineBook(api.source, id)?.favorite == true) to dao.onlineProgress(api.source, id) }
            if (active()) state.update { it.copy(book = picaDetail(json), favorite = reading.first, progress = reading.second) }
        }
        val book = api.detail(id)
        store.remember(api.source, book)
        val reading = data.worldData(identity.world, ::active) { dao ->
            dao.putVisit(BrowseVisit("online", api.source, id, book.title, System.currentTimeMillis()))
            (dao.onlineBook(api.source, id)?.favorite == true) to dao.onlineProgress(api.source, id)
        }
        state.update { it.copy(book = book, favorite = reading.first, progress = reading.second) }
        cache.write(detailJson(book))
        cache.information(detailInformation(book))
    }
    fun chapters(id: String): Unit = work({ chapters(id) }) {
        val api = client()
        val cache = DetailPageCache(getApplication<Application>(), session, api.source, id)
        cache.read("chapters")?.let { json -> if (active()) state.update { it.copy(chapters = picaChapters(json)) } }
        val rows = api.allChapters(id)
        if (active()) state.update { it.copy(chapters = rows, message = if (rows.isEmpty()) "暂无章节" else null) }
        cache.write(picaChaptersJson(rows), "chapters")
    }
    fun favorite() {
        val book = state.value.book ?: return; val value = !state.value.favorite
        work(::favorite) { store.remember(requireNotNull(state.value.source), book, value)
            state.update { it.copy(favorite = value, message = "本机收藏已更新，未修改网站收藏") } }
    }
    fun login(email: String, password: String) = work({ state.update { it.copy(message = "请重新输入密码登录") } }) {
        PicaClient(session, null, viewModelScope).use { api ->
            val account = api.login(email, password); store.save(account)
            client?.close(); client = null
            state.update { it.copy(account = account, source = account.source, message = "已登录，可读取收藏和浏览作品") }
        }
    }
    fun connectToken(token: String) = work({ state.update { it.copy(message = "请重新输入会话 Token") } }) {
        PicaClient(session, null, viewModelScope).use { api ->
            val account = api.verifyToken(token); store.save(account)
            client?.close(); client = null
            state.update { PicaUiState(ready = true, busy = true, account = account, source = account.source,
                message = "会话账号已验证，可读取收藏和浏览作品") }
        }
    }
    fun logout(): Unit = work(::logout) {
        store.save(null); client?.close(); client = null
        state.update { PicaUiState(ready = true, busy = true, message = "已退出；原有书库和进度保留") }
    }
    suspend fun cover(book: PicaBook) = picaCover(data, session, client(), book.id, book)
    override fun onCleared() { stop(); data.clearMemory() }
}

@Composable private fun picaModel(session: WorldSession, source: String? = null): PicaModel {
    val app = LocalContext.current.applicationContext as Application
    val model: PicaModel = viewModel(factory = viewModelFactory { initializer { PicaModel(app, session, source) } })
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    return model
}
@Composable private fun PicaFeedback(model: PicaModel, state: PicaUiState) {
    if (state.busy) { RequestLoading(); TextButton(model::stop) { Text("取消请求") } }
    state.message?.let { RequestFeedback(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, failure = state.error, busy = state.busy) }
    if (state.error && !state.busy) TextButton(feedbackAttempt(model::retry)) { Text("重试") }
}
@Composable internal fun PicaSettingsPage(session: WorldSession, back: () -> Unit, browse: (String, String) -> Unit) {
    val model = picaModel(session); val state by model.state.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    val navigation = rememberSourceSettingsNavigation(back)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (!lifecycle.isAtLeast(Lifecycle.State.STARTED)) password = "" }
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = navigation.scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading(navigation.title("PicACG"), "", navigation::back) }
        sourceSettingsOverview(navigation,
            account = if (!state.ready) "账号状态待读取" else state.account?.let { "已保存会话 · ${it.name}" } ?: "未登录",
            imports = { browse("favorites", "") }, importSummary = "全部收藏或逐页选择导入",
            importEnabled = state.ready && !state.busy && state.account != null)
        item { PicaFeedback(model, state) }
        if (navigation.section == SourceSettingsSection.ACCOUNT) item { SettingsPanel {
            AccountSummary(when {
                !state.ready -> if (state.busy) "正在读取账号…" else "账号状态待核对"
                state.account != null -> "已保存登录会话"
                else -> "未登录 · 请先连接账号"
            }, "云收藏", state.account?.name.orEmpty())
            SettingsDisclosure("API 账号连接") {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("邮箱 / 账号") }, enabled = !state.busy, singleLine = true)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, enabled = !state.busy,
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                ReferenceButton(feedbackAttempt({ model.login(email, password); password = "" }), enabled = state.ready && !state.busy && email.isNotBlank() && password.isNotBlank()) { Text("登录") }
            }
            if (state.account != null) TextButton(model::logout, enabled = !state.busy) { Text("退出账号 · 保留书库") }
        } }
        if (navigation.section == SourceSettingsSection.ACCOUNT) item("library-import") { SourceLibraryImportCard("全部云收藏或逐页选择导入；已有书目与阅读进度保留。",
            state.ready && state.account != null, state.busy, "import-pica-library", { browse("favorites", "") }) }
        if (navigation.section == SourceSettingsSection.OVERVIEW) item { SettingsGroup("浏览") {
            SettingsRow("搜索作品", "", "pica-settings-search", SettingsSymbol.SOURCES,
                feedbackAttempt({ browse("search", "") }), enabled = state.account != null && !state.busy)
            SettingsDivider()
            SettingsRow("分类目录", "", "pica-settings-categories", SettingsSymbol.GROUPS,
                { browse("categories", "") }, enabled = state.account != null && !state.busy)
            SettingsDivider()
            SettingsRow("热门榜单", "", "pica-settings-rank", SettingsSymbol.READING,
                { browse("rank", "H24") }, enabled = state.account != null && !state.busy)
        } }

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PicaBrowsePage(session: WorldSession, back: () -> Unit, settings: () -> Unit, open: (String, String) -> Unit,
    list: (String, String, String?) -> Unit, kind: String = "search", initial: String = "", source: String? = null,
    heading: String? = null, bottom: Dp = 22.dp, visits: (() -> Unit)? = null, library: (() -> Unit)? = null) {
    val model = picaModel(session, source); val state by model.state.collectAsStateWithLifecycle()
    var initialized by remember(model, state.account, state.source) { mutableStateOf(false) }
    LaunchedEffect(state.ready, state.busy, state.account, state.source) {
        if (state.ready && !state.busy && state.account != null && !initialized) {
            initialized = true
            if (state.page == 0 && state.categories.isEmpty()) {
                if (kind != "search") model.browse(kind, initial)
                else if (initial.isNotBlank() && state.query.isBlank()) { model.query(initial); model.browse(kind, initial) }
            }
        }
    }
    val label = when (kind) { "favorites" -> "PicACG 书库导入"; "categories" -> "分类目录"; "category" -> initial; "rank" -> "热门榜单"; "latest" -> "最新作品"; else -> "搜索漫画" }
    val favoritesImport = rememberPrivateFavoritesImport(session, if (kind == "favorites" && state.account != null && state.source == state.account?.source) state.source else null)
    val informationModel = if (favoritesImport != null) rememberBookInformation(session, state.source) else null
    if (kind == "favorites") {
        PrivateCollectionImportPage(session, state.source, "PicACG", state.account != null,
            state.books.map { ImportBook(it.id, it.id, it.title, it.author) }, state.busy, state.message, state.error,
            favoritesImport, informationModel, back, settings, { id -> state.source?.let { open(it, id) } }, library,
            refresh = { model.browse("favorites", initial, state.page.coerceAtLeast(1)) }, stop = model::stop,
            importSelected = { model.importSelected(it) }, pagination = { enabled -> FlowRow {
                if (state.page > 1) TextButton({ model.browse("favorites", initial, state.page - 1) }, enabled = enabled) { Text("上一页") }
                state.next?.let { next -> TextButton({ model.browse("favorites", initial, next) }, enabled = enabled) { Text("下一页") } }
            } }, cover = { entry -> state.source?.let { source ->
                val book = state.books.first { it.id == entry.key }
                PrivateSourceCover(session, source, book.id, book.title) { model.cover(book) }
            } })
        return
    }
    BookResultsLayout(enabled = kind != "favorites") { columns ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { if (heading != null) DiscoveryHeading("pica-root-settings", back, heading) else SettingsHeading(label, "", back) }
            item { TextButton(settings) { Text(if (state.account == null) "登录 PicACG" else "图源管理与账号") } }
            item { PicaFeedback(model, state) }
            if (kind == "search") item {
                ReferenceSubmitSearchField(state.query, model::query, "作品、作者或标签关键词", { model.browse("search", state.query) },
                    inputEnabled = !state.busy, submitEnabled = state.ready && state.account != null && state.query.isNotBlank())
                if (state.page == 0) FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { state.history.forEach { text -> TextButton(feedbackAttempt({ model.query(text); model.browse("search", text) }), enabled = !state.busy) { Text(text) } } }
                if (state.page == 0 && state.history.isNotEmpty()) TextButton(model::clearHistory, enabled = !state.busy) { Text("清空当前账号搜索历史") }
            }
            if (heading != null) item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton({ list("latest", "", state.source) }) { Text("最新作品") }
                TextButton({ list("categories", "", state.source) }) { Text("分类") }
                TextButton({ list("rank", "H24", state.source) }) { Text("榜单") }
                TextButton({ list("favorites", "", state.source) }) { Text("云收藏") }
                visits?.let { TextButton(it) { Text("最近浏览") } }
            } }
            if (kind == "rank") item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { listOf("H24" to "24 小时", "D7" to "7 天", "D30" to "30 天").forEach { (value, text) ->
                FilterChip(initial == value, { list("rank", value, state.source) }, label = { Text(text) })
            } } }
            if (kind != "search") item {
                TextButton({ model.browse(kind, initial, state.page.coerceAtLeast(1)) }, enabled = state.ready && state.account != null && !state.busy) { Text("刷新当前列表") }
            }
            items(state.categories, key = { "category:$it" }) { category -> TextButton({ list("category", category, state.source) }) { Text(category) } }
            bookGridItems(state.books, columns, { it.id }) { book ->
                BookResultCard(book.title, grid = kind != "favorites", metadata = book.author, cover = {
                    PrivateSourceCover(session, requireNotNull(state.source), book.id, book.title) { model.cover(book) }
                }, open = { open(requireNotNull(state.source), book.id) })
            }
            if (state.page > 0) item {
                Text("第 ${state.page} 页 · 本页 ${state.books.size} 本")
                val query = if (kind == "search") state.query else initial
                if (state.page > 1) TextButton({ model.browse(kind, query, state.page - 1) }, enabled = !state.busy) { Text("上一页") }
                state.next?.let { next -> ReferenceButton({ model.browse(kind, query, next) }, enabled = !state.busy) { Text("下一页") } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PicaDetailPage(session: WorldSession, source: String, id: String, back: () -> Unit,
    read: (String) -> Unit, list: (String, String) -> Unit, download: () -> Unit = {}, searchAuthor: ((String) -> Unit)? = null) {
    val model = picaModel(session, source); val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ready, state.account) { if (state.ready && state.book == null) model.detail(id) }
    var requestedChapters by remember(state.account?.uid) { mutableStateOf(false) }
    LaunchedEffect(state.book, state.busy) {
        if (state.book != null && !state.busy && !requestedChapters && state.chapters.isEmpty()) { requestedChapters = true; model.chapters(id) }
    }
    ReferenceRefreshColumn(modifier = Modifier.fillMaxSize(), refreshing = state.busy, refresh = { model.detail(id) },
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DetailHeading(back) }
        if (!readContentLayout(state.book != null, state.busy, state.detailError ?: state.message.takeIf { state.error && state.book == null }, { model.detail(id) }, detail = true, cancel = model::stop)) {
            item("offline-entry") { TextButton(download) { Text("加密下载与离线章节") } }
            return@ReferenceRefreshColumn
        }
        item { PicaFeedback(model, state) }
        state.book?.let { book -> item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailHero(book.title, { PrivateSourceCover(session, source, book.id, book.title) { model.cover(book) } }, titleLines = 5) {
                if (book.author.isNotBlank() || book.artist.isNotBlank()) Text(authorNames(listOf(book.author, book.artist).map { it to it }, searchAuthor), style = MaterialTheme.typography.bodyMedium)
                Text(if (book.finished) "已完结" else "连载中", style = MaterialTheme.typography.bodySmall)
            }
            val target = state.resume ?: state.chapters.firstOrNull()?.order?.toString()
            DetailActions(state.favorite, state.busy, toggleFavorite = model::favorite, download = download,
                read = { target?.let(read) }, canRead = target != null && !state.busy, hasProgress = state.resume != null,
                description = book.description, descriptionIdentity = "$source:${book.id}",
                readingPosition = readingPosition(state.progress, state.chapters.map { DetailCatalogEntry(it.order.toString(), chapterTitle(it.title, it.order)) }),
                belowActions = {
                    WorkTags(book.categories.map { it to "category:$it" } + book.tags.map { it to "search:$it" }, maxRows = 2) { value, _ ->
                        list(value.substringBefore(':'), value.substringAfter(':'))
                    }
                })
        } } }
        if (state.chapters.size != 1) {
            item { TextButton({ model.chapters(id) }, enabled = state.book != null && !state.busy) { Text("刷新章节目录") } }
            item { RecordedDetailCatalog(session, source, id, state.chapters.map { DetailCatalogEntry(it.order.toString(), chapterTitle(it.title, it.order)) }, state.resume, read,
                busy = state.busy, enabled = !state.busy) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PrivateDiscoveryPage(session: WorldSession, tags: Boolean, bottom: Dp, settings: () -> Unit,
    jmSettings: () -> Unit, picaSettings: () -> Unit, open: (String, String) -> Unit,
    picaList: (String, String, String?) -> Unit, visits: () -> Unit, ehSettings: () -> Unit = {}, ehFavorites: () -> Unit = {}, exSettings: () -> Unit = {}, exFavorites: () -> Unit = {}, galleryTags: (Boolean) -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf("jm") }
    Column(Modifier.fillMaxSize()) {
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected == "jm", { selected = "jm" }, label = { Text("JMComic") })
            FilterChip(selected == "pica", { selected = "pica" }, label = { Text("PicACG") })
            FilterChip(selected == "eh", { selected = "eh" }, label = { Text("E-Hentai") })
            FilterChip(selected == "ex", { selected = "ex" }, label = { Text("ExHentai") })
        }
        Box(Modifier.weight(1f)) {
            if (selected == "jm") JmBrowsePage(session, settings, jmSettings, open, bottom = bottom, heading = if (tags) "标签搜索" else "首页", visits = visits)
            else if (selected == "pica") PicaBrowsePage(session, settings, picaSettings, open, picaList, kind = if (tags) "categories" else "search", heading = if (tags) "分类" else "首页", bottom = bottom, visits = visits)
            else if (selected == "ex") EhBrowsePage(session, settings, exSettings, open, heading = if (tags) "标签搜索" else "首页", bottom = bottom, favoritesPage = exFavorites, visits = visits, site = GallerySite.EX, tags = { galleryTags(true) })
            else EhBrowsePage(session, settings, ehSettings, open, heading = if (tags) "标签搜索" else "首页", bottom = bottom, favoritesPage = ehFavorites, visits = visits, tags = { galleryTags(false) })
        }
    }
}
