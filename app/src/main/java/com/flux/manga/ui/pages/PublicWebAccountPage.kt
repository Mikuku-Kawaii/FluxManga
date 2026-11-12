package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PublicWebAccountPage(session: WorldSession, site: PublicWebSite, back: () -> Unit, browse: () -> Unit) {
    val identity by session.identity.collectAsState()
    val store = remember(session, identity, site) { session.publicWebSources.account(site) }
    val client = remember(session, identity, site) { PublicWebAccountClient(session, site) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext as android.app.Application
    val owner = LocalLifecycleOwner.current
    var alive by remember(store) { mutableStateOf(true) }
    var account by remember(store) { mutableStateOf<PublicWebAccount?>(null) }
    var loaded by remember(store) { mutableStateOf(false) }
    var verified by remember(store) { mutableStateOf(false) }
    var busy by remember(store) { mutableStateOf(false) }
    var error by remember(store) { mutableStateOf<String?>(null) }
    var notice by remember(store) { mutableStateOf<String?>(null) }
    var username by remember(store) { mutableStateOf("") }
    var password by remember(store) { mutableStateOf("") }
    var browser by remember(store) { mutableStateOf(false) }
    var operation by remember(store) { mutableStateOf<Job?>(null) }
    fun active() = alive && session.accepts(identity) && session.state.value.foreground
    val feedback = LocalRequestFeedback.current

    suspend fun accept(value: PublicWebAccount?) {
        currentCoroutineContext().ensureActive()
        if (!active()) throw CancellationException()
        store.write(value, ::active)
        session.publicWebSources.accountChanged(site)
        if (active()) preloadedPages(context, session).sourceSelectionChanged()
        if (active()) { account = value; verified = value != null; loaded = true; password = "" }
    }
    fun launch(action: suspend () -> Unit) {
        if (busy || !active()) return
        feedback?.beginAttempt(); error = null; notice = null; busy = true
        operation = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (active()) { verified = false; error = publicWebFailure(failure, site.sourceId) } }
            finally { if (alive) busy = false }
        }
    }
    fun refresh() = launch {
        val saved = store.read()
        if (active()) { account = saved; loaded = true }
        if (saved != null) {
            val checked = client.verify(saved.cookie)
            if (checked.id != saved.id) throw java.io.IOException("网页账号已变化，请重新登录连接")
            if (active()) { account = checked; verified = true; notice = "账号会话有效" }
        }
    }
    LaunchedEffect(store) {
        try { account = store.read(); loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (active()) error = publicWebFailure(failure, site.sourceId) }
    }
    DisposableEffect(store, owner) {
        alive = true
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            operation?.cancel(); password = ""; verified = false
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { alive = false; operation?.cancel(); password = ""; owner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = SettingsPagePadding,
        verticalArrangement = SettingsSectionSpacing) {
        item { SettingsHeading(site.label, "账号与图源", back) }
        item("source-network") { SourceNetworkSettings(session, requireNotNull(com.flux.manga.data.SourceKind.of(site.sourceId))) }
        item { SettingsGroup {
            SettingsRow("浏览作品", "目录、搜索与详情", "public-source-browse", SettingsSymbol.SOURCES, browse)
        } }
        item { AccountPanel("账号登录") {
            Text(accountStatus(loaded, verified, account != null, busy), style = MaterialTheme.typography.titleMedium)
            account?.let { Text(it.name, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (site == PublicWebSite.MANHUAGUI) {
                SettingsTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("用户名或邮箱") }, singleLine = true)
                SettingsTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                SettingsButton(onClick = { launch {
                    val value = client.signIn(username, password)
                    accept(value); notice = "漫画柜账号已连接"
                } }, enabled = loaded && !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (account == null) "登录" else "登录并切换账号")
                }
                Text("使用漫画柜官网账号登录，会话加密保存在本机。", style = MaterialTheme.typography.bodySmall)
            } else {
                SettingsButton(onClick = { browser = true; error = null; notice = null }, enabled = loaded && !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("应用内网页登录 / 注册") }
                Text(if (site == PublicWebSite.KUAIKAN) "在官网使用手机号密码或短信验证码登录，完成网站验证后点击连接账号。"
                    else "在官网使用手机号或账号密码登录，完成后点击连接账号。", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SettingsTextButton(::refresh, enabled = !busy) { Text("检查账号状态") }
                if (account != null) SettingsTextButton(onClick = { launch {
                    accept(null); notice = "已退出本机账号，书库和阅读进度保留"
                } }, enabled = !busy) { Text("退出账号") }
            }
        } }
        if (busy || error != null || notice != null) item {
            AccountRequestStatus(busy, "正在连接账号", error, notice, { operation?.cancel() }, ::refresh)
        }
        item { Text(when (site) {
            PublicWebSite.KUAIKAN -> "登录会话供本站请求使用；付费与受限章节仍遵循原站权限。更新栏目展示原站新上架作品。"
            PublicWebSite.MANHUAGUI -> "VPN 线路不兼容时，可开启绕过 VPN 或关闭代理。沿用网站公开目录，阅读不会自动跨章节目录切换。"
            else -> "开启图源开关后参与首页与聚合搜索。"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("本页连接账号，不会自动导入原站收藏或覆盖本机书库。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (browser) {
        val web = remember(site) { AccountWebSite("${site.label}网页账号",
            site.origin + (if (site == PublicWebSite.KUAIKAN) "webs/loginh" else "login.php"),
            setOf(java.net.URI(site.origin).host), desktop = site == PublicWebSite.KUAIKAN) }
        AccountWebLogin(web, close = { browser = false }, session = session, passwordScope = site.sourceId,
            connectLabel = "连接账号", connect = { page, _, _ ->
                val value = client.verify(page.publicCatalogCookie(site.origin))
                accept(value); notice = "${site.label}账号已连接"
            })
    }
}
