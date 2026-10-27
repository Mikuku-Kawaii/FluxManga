package com.flux.manga.ui.pages

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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

internal data class EhUiState(val ready: Boolean = false, val busy: Boolean = false, val settings: EhSettings = EhSettings(),
    val source: String = EH_SOURCE, val query: String = "", val folder: String = "all", val folders: List<EhFolder> = emptyList(),
    val batch: EhBatch? = null, val history: List<String> = emptyList(), val book: EhBook? = null, val favorite: Boolean = false,
    val progress: OnlineReadingProgress? = null, val message: String? = null, val error: Boolean = false, val detailError: String? = null, val ehOverlap: Int = 0) {
    val resume: Boolean get() = progress != null
}

internal class EhModel(app: Application, private val session: WorldSession, private val selectedSource: String?, private val site: GallerySite = GallerySite.EH) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val store = EhStore(app, session, site)
    private val data = WorldDataStore(app)
    val state = MutableStateFlow(EhUiState(source = selectedSource ?: site.emptySource))
    private var client: EhClient? = null
    private var passwordLogin: EhPasswordLogin? = null
    private var loginVerification: EhClient? = null
    private val lock = Mutex()
    private var job: Job? = null
    private var retry: (() -> Unit)? = null
    private fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity)
    private fun foreground() = active() && session.state.value.foreground
    init { refresh() }
    private suspend fun client(): EhClient = lock.withLock {
        val settings = store.settings(); val source = selectedSource ?: site.source(settings.account)
        if (source != EH_SOURCE && (settings.account == null || source != site.source(settings.account))) throw java.io.IOException("请独立登录此画廊所属的 ${site.label} 账号")
        if (!foreground()) throw CancellationException()
        client ?: EhClient(session, if (source == EH_SOURCE) null else settings.account, viewModelScope, site).also { client = it }
    }
    private fun work(again: () -> Unit, preserve: Boolean = false, readingDetail: Boolean = false, action: suspend () -> Unit) {
        if (!foreground() || state.value.busy) return
        if (!preserve) retry = again
        state.update { it.copy(busy = true, message = if (preserve) it.message else null, error = preserve && it.error, detailError = if (readingDetail) null else it.detailError) }
        job = viewModelScope.launch {
            try { action(); ensureActive() }
            catch (_: CancellationException) { retry = again; if (active()) state.update { it.copy(message = "请求已停止，可重试", error = true, detailError = if (readingDetail) "请求已停止，可重试" else it.detailError) } }
            catch (error: Exception) { retry = again; if (active()) state.update { it.copy(message = ehFailure(error, site), error = true, detailError = if (readingDetail) ehFailure(error, site) else it.detailError) } }
            finally { if (active()) state.update { it.copy(busy = false) } }
        }
    }
    fun refresh(): Unit = work(::refresh, preserve = state.value.ready) {
        val settings = store.settings(); val source = selectedSource ?: site.source(settings.account)
        if (settings.account != state.value.settings.account || source != state.value.source) {
            client?.close(); client = null; retry = null
            state.update { it.copy(batch = null, folders = emptyList(), folder = "all", book = null, progress = null, favorite = false, message = null, error = false, detailError = null, ehOverlap = 0) }
        }
        val history = data.worldData(identity.world, ::active) { it.history("online:$source") }
        val reading = state.value.book?.let { book -> data.worldData(identity.world, ::active) { dao ->
            (dao.onlineBook(source, book.id)?.favorite == true) to dao.onlineProgress(source, book.id)
        } }
        state.update { it.copy(ready = true, settings = settings, source = source, history = history,
            favorite = reading?.first ?: it.favorite, progress = if (reading != null) reading.second else it.progress) }
    }
    fun stop() {
        job?.cancel(); client?.close(); client = null
        passwordLogin?.close(); passwordLogin = null
        loginVerification?.close(); loginVerification = null
    }
    fun retry() { retry?.invoke() }
    fun query(value: String) { retry = null; state.update { it.copy(query = value.take(300), batch = null, message = null, error = false, ehOverlap = 0) } }
    fun browse(favorites: Boolean, cursor: String? = null): Unit = work({ browse(favorites, cursor) }) {
        val api = client(); val query = state.value.query.trim()
        val result = api.browse(favorites, query, state.value.folder, cursor)
        if (!active()) throw CancellationException()
        if (query.isNotBlank()) data.remember(identity.world, "online:${api.source}", query, ::active)
        val history = data.worldData(identity.world, ::active) { it.history("online:${api.source}") }
        val overlap = if (site == GallerySite.EX && favorites) data.worldData(identity.world, ::active) { dao ->
            val ehIds = dao.onlineBooks().filter { isEhSource(it.sourceId) }.map { it.mangaId }.toSet()
            result.books.count { it.id in ehIds }
        } else 0
        state.update { it.copy(batch = result, folders = result.folders, history = history, ehOverlap = overlap, message = if (result.books.isEmpty()) "当前列表没有画廊" else null) }
    }
    fun folder(value: String) { if (!state.value.busy) { state.update { it.copy(folder = value, batch = null) }; browse(true) } }
    fun clearHistory(): Unit = work(::clearHistory) {
        data.clearHistory(identity.world, "online:${state.value.source}", ::active); state.update { it.copy(history = emptyList()) }
    }
    fun importPage(): Unit = work(::importPage) {
        val books = state.value.batch?.books.orEmpty(); val added = store.importPage(state.value.source, books)
        state.update { it.copy(message = "新增 $added 本，保留已有 ${books.size - added} 本；可继续下一批") }
    }
    fun detail(id: String): Unit = work({ detail(id) }, readingDetail = true) {
        val api = client()
        val cache = DetailPageCache(getApplication<Application>(), session, api.source, id)
        cache.read()?.let { json ->
            val reading = data.worldData(identity.world, ::active) { dao -> (dao.onlineBook(api.source, id)?.favorite == true) to dao.onlineProgress(api.source, id) }
            if (active()) state.update { it.copy(book = ehDetail(json), favorite = reading.first, progress = reading.second) }
        }
        val book = api.detail(id); store.remember(api.source, book)
        val reading = data.worldData(identity.world, ::active) { dao ->
            dao.putVisit(BrowseVisit("online", api.source, id, book.title, System.currentTimeMillis()))
            (dao.onlineBook(api.source, id)?.favorite == true) to dao.onlineProgress(api.source, id)
        }
        state.update { it.copy(book = book, favorite = reading.first, progress = reading.second) }
        cache.write(detailJson(book))
        cache.information(detailInformation(book))
    }
    fun favorite() {
        val book = state.value.book ?: return; val next = !state.value.favorite
        work(::favorite) { store.remember(state.value.source, book, next); state.update { it.copy(favorite = next, message = "本机收藏已更新，未修改网站收藏") } }
    }
    fun login(uid: String, hash: String, igneous: String = "") = work({ state.update { it.copy(message = "请重新输入 Cookie 验证", error = false) } }) {
        EhClient(session, EhAccount(uid.trim(), hash.trim(), "", igneous.trim()), viewModelScope, site).use { api ->
            val account = api.verifyAccount(); val next = store.settings().copy(account = account); store.save(next)
            client?.close(); client = null
            state.update { EhUiState(ready = true, busy = true, settings = next, source = site.source(account), message = "${site.label} 账号与本站收藏访问已确认") }
        }
    }
    fun loginPassword(username: String, password: String, igneous: String) = work({
        state.update { it.copy(message = "请重新输入密码后登录；未自动重发。", error = false) }
    }) {
        val candidate = EhPasswordLogin(session, site, viewModelScope).use { login ->
            passwordLogin = login
            try { login.signIn(username, password, igneous) } finally { passwordLogin = null }
        }
        if (!foreground()) throw CancellationException()
        state.update { it.copy(message = "论坛登录已返回，正在确认 ${site.label} 身份与本站权限…") }
        EhClient(session, candidate, viewModelScope, site).use { api ->
            loginVerification = api
            try {
                val account = api.verifyAccount()
                if (!foreground()) throw CancellationException()
                val next = store.settings().copy(account = account)
                if (!foreground()) throw CancellationException()
                store.save(next)
                if (!foreground()) throw CancellationException()
                client?.close(); client = null
                state.update { EhUiState(ready = true, busy = true, settings = next, source = site.source(account),
                    message = "${site.label} 账号与本站权限已确认并加密保存") }
            } finally { loginVerification = null }
        }
    }
    suspend fun connectWebAccount(candidate: EhAccount) {
        if (!foreground()) throw CancellationException()
        check(!state.value.busy)
        state.update { it.copy(busy = true, message = null, error = false) }
        try {
            val account = EhClient(session, candidate, viewModelScope, site).use { it.verifyAccount() }
            currentCoroutineContext().ensureActive()
            if (!foreground()) throw CancellationException()
            val next = store.settings().copy(account = account)
            store.save(next)
            client?.close(); client = null
            if (foreground()) state.update { EhUiState(ready = true, busy = true, settings = next,
                source = site.source(account), message = "网页账号与 ${site.label} 本站收藏访问已确认") }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { throw java.io.IOException(ehFailure(failure, site))
        } finally { if (active()) state.update { it.copy(busy = false) } }
    }
    fun verifyAccess(): Unit = work(::verifyAccess) {
        val settings = store.settings()
        val account = settings.account ?: throw java.io.IOException("请先填写本站 Cookie")
        EhClient(session, account, viewModelScope, site).use { api ->
            val verified = api.verifyAccount(); val next = settings.copy(account = verified); store.save(next)
            state.update { it.copy(settings = next, message = "本次 ${site.label} 身份与本站收藏访问已确认") }
        }
    }
    fun logout(): Unit = work(::logout) {
        val next = store.settings().copy(account = null); store.save(next); client?.close(); client = null
        state.update { EhUiState(ready = true, busy = true, settings = next, source = site.emptySource, message = "已退出 ${site.label}；原账号书库与进度保留") }
    }
    fun original(value: Boolean): Unit = work({ original(value) }) {
        val next = store.settings().copy(original = value); store.save(next); state.update { it.copy(settings = next, message = "下次打开阅读器生效；阅读位置保留") }
    }
    suspend fun cover(book: EhBook) = ehCover(data, session, client(), book.id, book)
    override fun onCleared() { stop(); data.clearMemory() }
}

@Composable private fun ehModel(session: WorldSession, source: String? = null, site: GallerySite = GallerySite.EH): EhModel {
    val app = LocalContext.current.applicationContext as Application
    val model: EhModel = viewModel(key = "gallery-${site.prefix}", factory = viewModelFactory { initializer { EhModel(app, session, source, site) } })
    val owner = LocalLifecycleOwner.current; val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.refresh() }
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }; return model
}
@Composable private fun EhFeedback(model: EhModel, state: EhUiState) {
    if (state.busy) { RequestLoading(); TextButton(model::stop) { Text("取消请求") } }
    state.message?.let { RequestFeedback(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, failure = state.error, busy = state.busy) }
    if (state.error && !state.busy) TextButton(feedbackAttempt(model::retry)) { Text("重试") }
}
@Composable internal fun EhSettingsPage(session: WorldSession, back: () -> Unit, browse: (Boolean) -> Unit, site: GallerySite = GallerySite.EH, tags: () -> Unit = {}) {
    val model = ehModel(session, site = site); val state by model.state.collectAsStateWithLifecycle(); val context = LocalContext.current
    var uid by remember { mutableStateOf("") }; var hash by remember { mutableStateOf("") }
    var igneous by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var passwordMode by remember { mutableStateOf(false) }
    var webLogin by remember(site) { mutableStateOf(false) }
    val webSite = remember(site) { AccountWebSite("${site.label} 网页账号",
        "https://forums.e-hentai.org/index.php?act=Login&CODE=00",
        setOf("forums.e-hentai.org", "e-hentai.org", site.host)) }
    val navigation = rememberSourceSettingsNavigation(back)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (!lifecycle.isAtLeast(Lifecycle.State.STARTED)) { uid = ""; hash = ""; igneous = ""; username = ""; password = "" } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = navigation.scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading(navigation.title(site.label), "", navigation::back) }
        sourceSettingsOverview(navigation,
            account = if (!state.ready) "账号状态待读取" else state.settings.account?.let { "已保存本站会话 · ${it.name}" }
                ?: if (site == GallerySite.EX) "未登录 · 需要独立确认 EX 权限" else "未登录 · 可匿名浏览",
            imports = { browse(true) }, importSummary = "本站全部收藏或按分类导入", connectionSummary = "图片质量与站点权限",
            importEnabled = state.ready && !state.busy && state.settings.account != null)
        item { EhFeedback(model, state) }
        if (navigation.section == SourceSettingsSection.ACCOUNT) item { SettingsPanel {
            AccountSummary(when {
                !state.ready -> if (state.busy) "正在读取账号…" else "账号状态待核对"
                state.settings.account != null -> "已保存 ${site.label} 登录会话"
                site == GallerySite.EX -> "未登录 · 需确认 EX 访问权限"
                else -> "未登录 · 可匿名浏览"
            }, "${site.label} 收藏分类与画廊", state.settings.account?.name.orEmpty())
            ReferenceButton({ webLogin = true }, enabled = state.ready && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("应用内网页登录 / 注册")
            }
            Text("在论坛登录后打开 ${site.label} 本站，再连接账号与收藏。", style = MaterialTheme.typography.bodySmall)
            if (site == GallerySite.EX) TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.base))) }) { Text("登录后打开 EX 确认权限") }
            SettingsDisclosure("连接网页会话 / 备用账号登录") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(passwordMode, { passwordMode = true; uid = ""; hash = "" }, enabled = !state.busy,
                        modifier = Modifier.weight(1f), label = { Text("账号密码") })
                    FilterChip(!passwordMode, { passwordMode = false; password = "" }, enabled = !state.busy,
                        modifier = Modifier.weight(1f), label = { Text("手动 Cookie") })
                }
                if (passwordMode) {
                    OutlinedTextField(username, { username = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("论坛用户名") }, enabled = !state.busy, singleLine = true)
                    OutlinedTextField(password, { password = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text("密码") }, enabled = !state.busy,
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Text("密码仅用于本次登录，不保存。失败后原账号与书库保留。", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(uid, { uid = it }, Modifier.fillMaxWidth(), label = { Text("ipb_member_id") }, enabled = !state.busy, singleLine = true)
                    OutlinedTextField(hash, { hash = it }, Modifier.fillMaxWidth(), label = { Text("ipb_pass_hash") }, enabled = !state.busy, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                if (site == GallerySite.EX) OutlinedTextField(igneous, { igneous = it }, Modifier.fillMaxWidth(), label = { Text("EX 域 igneous") }, enabled = !state.busy, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (passwordMode) ReferenceButton(feedbackAttempt({ model.loginPassword(username, password, igneous); password = ""; igneous = "" }),
                    enabled = state.ready && !state.busy && username.isNotBlank() && password.isNotEmpty() && (site == GallerySite.EH || igneous.isNotBlank())) { Text("登录并确认本站权限") }
                else
                ReferenceButton(feedbackAttempt({ model.login(uid, hash, igneous); hash = ""; uid = ""; igneous = "" }), enabled = state.ready && !state.busy && uid.isNotBlank() && hash.isNotBlank() && (site == GallerySite.EH || igneous.isNotBlank())) { Text("验证本站权限并保存") }
            }
            if (state.settings.account != null) TextButton(feedbackAttempt(model::verifyAccess), enabled = !state.busy) { Text("重新确认本站访问权限") }
            if (state.settings.account != null) TextButton(model::logout, enabled = !state.busy) { Text("退出账号 · 保留书库") }
        } }
        if (navigation.section == SourceSettingsSection.ACCOUNT) item("library-import") { SourceLibraryImportCard("${site.label} 全部收藏或按分类导入；各站账号和书库独立。",
            state.ready && state.settings.account != null, state.busy,
            if (site == GallerySite.EX) "import-ex-library" else "import-eh-library", { browse(true) }) }
        if (navigation.section == SourceSettingsSection.OVERVIEW) item { SettingsGroup("浏览") {
            SettingsRow("中文标签词库", "", "gallery-settings-tags", SettingsSymbol.GROUPS, tags)
            SettingsDivider()
            SettingsRow("浏览与搜索画廊", "", "gallery-settings-browse", SettingsSymbol.SOURCES, { browse(false) },
                enabled = site == GallerySite.EH || state.settings.account != null)
        } }
        if (navigation.section == SourceSettingsSection.CONNECTION) item { SettingsPanel {
            SettingsSwitchRow("使用原图", "需要站点权限，可能消耗原图配额；不可用时不会自动改图。",
                state.settings.original, model::original, enabled = state.ready && !state.busy)
        } }
        if (navigation.section == SourceSettingsSection.CONNECTION && site == GallerySite.EX) item {
            Text("EX 权限独立验证；EH 登录成功不代表 EX 可用。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (webLogin) AccountWebLogin(webSite, close = { webLogin = false }, destination = site.base,
        autoConnectKey = { page, _, _ ->
            val names = if (site == GallerySite.EX) listOf("ipb_member_id", "ipb_pass_hash", "igneous") else listOf("ipb_member_id", "ipb_pass_hash")
            names.map { page.cookie(site.base, it) }.takeIf { values -> values.all(String::isNotBlank) }?.joinToString(";")
        },
        connect = { page, _, _ ->
            // Read only the target site's cookie jar. Never copy EH cookies into EX.
            val member = page.cookie(site.base, "ipb_member_id")
            val pass = page.cookie(site.base, "ipb_pass_hash")
            val ex = if (site == GallerySite.EX) page.cookie(site.base, "igneous") else ""
            if (member.isBlank() || pass.isBlank() || (site == GallerySite.EX && ex.isBlank())) {
                throw java.io.IOException("尚未取得 ${site.label} 本站会话，请登录后打开本站；EX 不沿用 EH 登录结果")
            }
            model.connectWebAccount(EhAccount(member, pass, "", ex))
        }, afterConnected = { browse(true) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EhBrowsePage(session: WorldSession, back: () -> Unit, settings: () -> Unit, open: (String, String) -> Unit,
    favorites: Boolean = false, initial: String = "", source: String? = null, heading: String? = null, bottom: Dp = 22.dp,
    favoritesPage: (() -> Unit)? = null, visits: (() -> Unit)? = null, site: GallerySite = GallerySite.EH, tags: (() -> Unit)? = null, library: (() -> Unit)? = null) {
    val model = ehModel(session, source, site); val state by model.state.collectAsStateWithLifecycle()
    val signedIn = state.settings.account != null
    val canBrowse = signedIn || (site == GallerySite.EH && !favorites)
    var initialized by remember(model, state.source, state.settings.account) { mutableStateOf(false) }
    LaunchedEffect(state.ready, state.busy, state.source, state.settings.account) { if (state.ready && !state.busy && canBrowse && !initialized) { initialized = true
        if (state.batch == null) { if (state.query.isBlank() && initial.isNotBlank()) model.query(initial); model.browse(favorites) }
    } }
    val favoritesImport = rememberPrivateFavoritesImport(session, if (favorites && signedIn && state.source == site.source(state.settings.account)) state.source else null)
    val informationModel = if (favoritesImport != null) rememberBookInformation(session, state.source) else null
    BookResultsLayout(enabled = !favorites) { columns ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { if (heading == null) SettingsHeading(if (favorites) "${if (site == GallerySite.EX) "ExHentai" else "E-Hentai"} 书库导入" else "搜索画廊", "", back) else DiscoveryHeading("${site.prefix}-root-settings", back, heading) }
            item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { TextButton(settings) { Text("图源管理与账号") }; favoritesPage?.let { TextButton(it) { Text("云收藏") } }; visits?.let { TextButton(it) { Text("最近浏览") } } } }
            tags?.let { item { TextButton(it) { Text("中文标签查找") } } }
            item { EhFeedback(model, state) }
            if (state.ready && !canBrowse && site == GallerySite.EX) item { Text("请先在图源管理独立配置 ExHentai，并确认本站访问权限。") }
            item { ReferenceSubmitSearchField(state.query, model::query, "关键词 / namespace:tag", { model.browse(favorites) },
                inputEnabled = !state.busy, submitEnabled = state.ready && canBrowse,
                actionLabel = if (state.query.isBlank()) "载入列表" else "搜索") }
            if (state.batch == null && state.history.isNotEmpty()) item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { state.history.forEach { text ->
                TextButton({ model.query(text); model.browse(favorites) }, enabled = canBrowse && !state.busy) { Text(text) }
            } }; TextButton(model::clearHistory, enabled = !state.busy) { Text("清空当前来源搜索历史") } }
            informationModel?.let { info -> item { BookInformationPanel(info, enabled = !state.busy) } }
            favoritesImport?.let { model -> item { PrivateFavoritesImportPanel(model, state.busy) } }
            if (favorites) item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(state.folder == "all", { model.folder("all") }, enabled = canBrowse && !state.busy, label = { Text("全部分类") })
                state.folders.forEach { folder -> FilterChip(state.folder == folder.id, { model.folder(folder.id) }, enabled = canBrowse && !state.busy, label = { Text(folder.title) }) }
            }
                if (site == GallerySite.EX && state.batch != null) Text("本批 ${state.ehOverlap} 本在当前空间已有 EH 同画廊 ID 记录；仍按 EX 独立导入，不合并收藏或进度。")
                AccountLibraryImportPanel("按收藏分类分批导入，关键词可缩小列表范围；本次只保存当前批作品。",
                    state.batch?.books?.size ?: 0, batch = true,
                    ready = state.ready, signedIn = signedIn, busy = state.busy,
                    login = settings, importPage = model::importPage, library = library)
            }
            bookGridItems(state.batch?.books.orEmpty(), columns, { it.id }) { book ->
                BookResultCard(book.title, grid = !favorites, metadata = book.category, cover = {
                    PrivateSourceCover(session, state.source, book.id, book.title) { model.cover(book) }
                }, open = { open(state.source, book.id) })
            }
            state.batch?.let { batch -> item { Text("本批 ${batch.books.size} 本")
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { batch.previous?.let { TextButton({ model.browse(favorites, it) }, enabled = !state.busy) { Text("上一批") } }
                    batch.next?.let { ReferenceButton({ model.browse(favorites, it) }, enabled = !state.busy) { Text("下一批") } }
                    TextButton({ model.browse(favorites) }, enabled = !state.busy) { Text("刷新首批") }
                }
            } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EhDetailPage(session: WorldSession, source: String, id: String, back: () -> Unit, settings: () -> Unit,
    read: () -> Unit, tag: (String) -> Unit, site: GallerySite = GallerySite.EH, tags: (() -> Unit)? = null, interactions: ((Boolean) -> Unit)? = null, download: () -> Unit = {}, searchAuthor: ((String) -> Unit)? = null) {
    val model = ehModel(session, source, site); val state by model.state.collectAsStateWithLifecycle()
    val translations = galleryTagsModel(session); val translated by translations.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ready, state.settings.account) { if (state.ready && state.book == null) model.detail(id) }
    ReferenceRefreshColumn(modifier = Modifier.fillMaxSize(), refreshing = state.busy, refresh = { model.detail(id) },
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DetailHeading(back) }
        if (!readContentLayout(state.book != null, state.busy, state.detailError ?: state.message.takeIf { state.error && state.book == null }, { model.detail(id) }, detail = true, cancel = model::stop)) {
            item("offline-entry") { TextButton(download) { Text("加密下载与离线章节") } }
            item("source-settings") { TextButton(settings) { Text("图源与账号设置") } }
            return@ReferenceRefreshColumn
        }
        item { EhFeedback(model, state) }
        state.book?.let { book -> item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailHero(book.title, { PrivateSourceCover(session, source, book.id, book.title) { model.cover(book) } }, titleLines = 5) {
                Text("${book.category} · ${book.pages} 页", style = MaterialTheme.typography.bodyMedium)
                val artists = book.tags.filter { it.startsWith("artist:") }.map { it.substringAfter(':').trim() }.filter(String::isNotBlank)
                if (artists.isNotEmpty()) Text(authorNames(artists.map { it to it }, searchAuthor, "作者："), style = MaterialTheme.typography.bodyMedium)
                if (book.uploader.isNotBlank()) Text("上传者：${book.uploader}", style = MaterialTheme.typography.bodySmall)
                if (book.subtitle.isNotBlank()) Text(book.subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            DetailActions(state.favorite, state.busy, toggleFavorite = model::favorite, download = download,
                read = read, canRead = !state.busy, hasProgress = state.resume,
                readingPosition = readingPosition(state.progress, emptyList()), belowActions = {
                    WorkTags(book.tags.map { text ->
                        (if (translated.enabled) translated.dictionary?.label(text) ?: text else text) to text
                    }) { text, _ ->
                        tag(text.substringBefore(':') + ":\"" + text.substringAfter(':').replace("\"", "") + "\"")
                    }
                    translated.message?.let { Text("中文标签暂不可用；原标签仍可搜索。", style = MaterialTheme.typography.bodySmall) }
                })
            tags?.let { TextButton(it) { Text("中文标签与词库管理") } }
            interactions?.let { open -> FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton({ open(false) }) { Text("查看评论") }
                TextButton({ open(true) }) { Text("管理网站收藏") }
            } }
        } } }
        item("source-settings") { TextButton(settings) { Text("图源与账号设置") } }
    }
}
