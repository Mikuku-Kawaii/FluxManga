package com.flux.manga.ui.pages

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.data.JmAccount
import com.flux.manga.data.BaoziBrowserSession
import com.flux.manga.data.BaoziAccountOrigins
import com.flux.manga.data.BaoziSavedAccount
import com.flux.manga.data.BaoziAutoLogin
import com.flux.manga.data.jmId
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import kotlin.coroutines.resume

/** One disposable login at a time; this directory is never used for the reader or external browser. */
private object AccountWebStorage {
    private var initialized = false
    var occupied by mutableStateOf(false)
        private set

    fun acquire(): Boolean {
        if (occupied) return false
        if (!initialized) {
            WebView.setDataDirectorySuffix("source-account-login")
            initialized = true
        }
        occupied = true
        return true
    }

    fun clean(done: () -> Unit) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            done()
        }
    }

    fun release() = clean { occupied = false }
}

internal fun accountWebLoginOccupied(): Boolean = AccountWebStorage.occupied

internal class AccountWebSite(val title: String, val url: String, val hosts: Set<String>) {
    private val port = Uri.parse(url).port.let { if (it == -1) 443 else it }
    fun accepts(value: String?): Boolean {
        val uri = value?.let(Uri::parse) ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in hosts &&
            (if (uri.port == -1) 443 else uri.port) == port && uri.userInfo == null
    }
}

// Published by jmcomicgo.org on 2026-10-25; API/CDN addresses are not login websites.
internal val JmWebLoginUrls = listOf("18comic.vip", "18comic.ink", "jmcomic-zzz.one", "jmcomic-zzz.org",
    "comic18j-guta.quest", "comic18j-moco.site", "comic18j-ada.work").map { "https://$it/login" }
internal val JmWebSite = AccountWebSite("JMComic 网页账号", JmWebLoginUrls.first(),
    JmWebLoginUrls.flatMap { val host = requireNotNull(Uri.parse(it).host); listOf(host, "www.$host") }.toSet())

// Official cn.baozimh.com redirect, observed 2026-10-26. Open the website's
// browser verification normally; do not emulate its proof or reuse other origins' cookies.
internal const val BaoziWebOrigin = "https://cn.bzmgcn.com/"
internal val BaoziWebSite = AccountWebSite("包子漫画网页账号",
    "${BaoziWebOrigin}__gatekeeper_challenge/start?return=%2Fuser%2Fsignin",
    BaoziAccountOrigins.map { requireNotNull(Uri.parse(it).host) }.toSet())

/** A reference to the current page, not an authentication result. Never put it in saved state. */
internal class AccountWebPage(private val view: WebView, private val site: AccountWebSite,
    val baoziAutoLogin: BaoziAutoLogin? = null, private val current: () -> Boolean) {
    private fun checkPage() {
        if (!current()) throw CancellationException()
        if (!site.accepts(view.url)) throw IOException("请先返回本站登录页面")
    }

    fun cookie(url: String, name: String): String {
        checkPage()
        require(site.accepts(url))
        return CookieManager.getInstance().getCookie(url).orEmpty().split(';').firstNotNullOfOrNull { entry ->
            val key = entry.substringBefore('=').trim()
            if (key == name) entry.substringAfter('=', "").takeIf { value ->
                value.isNotBlank() && value.length <= 16384 && value.none { it == '\r' || it == '\n' }
            } else null
        }.orEmpty()
    }

    fun baoziOrigin(): String {
        checkPage()
        require(site == BaoziWebSite)
        return "https://${Uri.parse(view.url).host}/".also { require(it in BaoziAccountOrigins) }
    }

    fun baoziSession(): Pair<String, BaoziBrowserSession> {
        val origin = baoziOrigin()
        val token = cookie(origin, "TSID")
        if (token.isBlank()) throw IOException("请先完成官网验证与登录，再连接书架")
        val cookies = CookieManager.getInstance().getCookie(origin).orEmpty().split(';')
            .map(String::trim).filter { part ->
                val name = part.substringBefore('=')
                name == "TSID" || name.matches(Regex("__Host-gk_[A-Za-z0-9_]+"))
            }.joinToString("; ")
        return token to BaoziBrowserSession(cookies, view.settings.userAgentString, origin).also { it.validate(token) }
    }

    /** Existing /api/bui/signin protocol, executed by the verified same-origin browser.
     * Password is a transient JS argument, never localStorage/sessionStorage or a bridge. */
    suspend fun signInBaozi(login: BaoziAutoLogin) {
        val origin = baoziOrigin()
        if (login.origin != origin) throw IOException("官网地址已变化，请在当前官网重新登录")
        if (login.username.isBlank() || login.password.toByteArray().size !in 1..4096)
            throw IOException("请填写官网邮箱和有效密码")
        suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
        }
        val script = """
            (() => {
              if (location.origin !== ${JSONObject.quote(origin.removeSuffix("/"))}) return;
              const controller = new AbortController();
              const state = {done:false, abort:()=>controller.abort()};
              window.__fluxBaoziSignIn = state;
              const timer = setTimeout(()=>controller.abort(), 15000);
              const body = new FormData();
              body.append('username', ${JSONObject.quote(login.username)});
              body.append('password', ${JSONObject.quote(login.password)});
              fetch('/api/bui/signin', {method:'POST', body, credentials:'same-origin',
                redirect:'error', signal:controller.signal})
                .then(async response => {
                  state.status = response.status;
                  if (response.ok) {
                    const json = await response.json();
                    state.token = typeof json.data === 'string' ? json.data : '';
                  }
                }).catch(()=>{state.status = 0;})
                .finally(()=>{clearTimeout(timer); state.done = true;});
            })()
        """.trimIndent()
        try {
            evaluate(script)
            val result = withTimeout(20_000) {
                var result: JSONObject? = null
                while (result == null) {
                    checkPage()
                    val raw = evaluate("JSON.stringify(window.__fluxBaoziSignIn?.done ? {status:window.__fluxBaoziSignIn.status, token:window.__fluxBaoziSignIn.token || ''} : null)")
                    val decoded = JSONTokener(raw).nextValue() as? String
                    if (decoded != null && decoded != "null") result = JSONObject(decoded) else delay(250)
                }
                requireNotNull(result)
            }
            checkPage()
            val token = result.optString("token")
            if (token.isEmpty()) throw IOException(when (result.optInt("status")) {
                403 -> "网站验证已过期，请重新完成网站验证"
                0 -> "自动登录连接未完成，请检查网络后重试"
                429 -> "登录过于频繁，请稍后重试"
                else -> "自动登录未成功，请核对密码或使用官网登录"
            })
            BaoziBrowserSession("TSID=$token", view.settings.userAgentString, origin).validate(token)
            suspendCancellableCoroutine<Unit> { continuation ->
                CookieManager.getInstance().setCookie(origin, "TSID=$token; Path=/; Secure; HttpOnly; SameSite=Lax") { accepted ->
                    if (continuation.isActive) {
                        if (accepted) continuation.resume(Unit)
                        else continuation.resumeWith(Result.failure(IOException("浏览器未保存登录会话")))
                    }
                }
            }
            checkPage()
        } finally {
            if (current() && site.accepts(view.url))
                view.evaluateJavascript("window.__fluxBaoziSignIn?.abort(); delete window.__fluxBaoziSignIn;", null)
        }
    }

    /** Reuse the email already entered on the official form; never read password inputs.
     * The identifier survives the website's same-origin login navigation only, and is
     * cleared with the disposable WebView storage. It is not authentication evidence. */
    suspend fun baoziLoginEmail(): String? {
        baoziOrigin()
        val result = suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript(BAOZI_LOGIN_EMAIL) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
        checkPage()
        return (JSONTokener(result).nextValue() as? String)?.trim()?.takeIf {
            it.length in 3..256 && '@' in it && it.none(Char::isWhitespace)
        }
    }

    suspend fun baoziVerificationPassed(): Boolean {
        checkPage()
        require(site == BaoziWebSite)
        val result = suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript("document.getElementById('status')?.textContent === '验证通过'") { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
        checkPage()
        return result == "true"
    }

    suspend fun baoziNeedsLogin(): Boolean {
        baoziOrigin()
        if (Uri.parse(view.url).path?.trimEnd('/') == "/user/signin") return true
        val result = suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript("!!document.querySelector('input[type=password]')") { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
        checkPage()
        return result == "true"
    }

    suspend fun copyToken(): String {
        checkPage()
        require(Uri.parse(view.url).host == "mangacopy.com")
        // The official mobile login stores only its session in localStorage.user;
        // the desktop login uses a cookie. Never inspect login forms or password fields.
        val cookie = cookie("https://mangacopy.com/", "token")
        if (cookie.isNotBlank()) return cookie
        val result = suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript("""
                (() => { try {
                    const user = JSON.parse(localStorage.getItem('user') || 'null');
                    return user && typeof user.token === 'string' ? user.token : '';
                } catch (_) { return ''; } })()
            """.trimIndent()) { value -> if (continuation.isActive) continuation.resume(value) }
        }
        checkPage()
        return (JSONTokener(result).nextValue() as? String).orEmpty()
    }

    suspend fun jmAccount(uidFallback: String, usernameFallback: String): JmAccount {
        checkPage()
        val snapshot = suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript(JM_ACCOUNT_SNAPSHOT) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
        checkPage()
        val decoded = JSONTokener(snapshot).nextValue() as? String ?: throw IOException("网页尚未提供账号资料")
        val fields = JSONObject(decoded)
        val uid = uidFallback.trim().ifBlank { fields.optString("uid") }
        val username = usernameFallback.trim().ifBlank { fields.optString("username") }
        if (!jmId(uid) || username.isBlank()) throw IOException("未能读取账号 UID / 用户名。请打开网页个人资料，或在下方补充这两项后连接。")
        val avs = cookie(requireNotNull(view.url), "AVS")
        if (avs.isBlank()) throw IOException("尚未取得 JM 登录会话，请在网页完成登录后重试")
        // Only JM's account session crosses to the pinned JM API. No challenge/tracking cookies.
        return JmAccount(uid, username, "AVS=$avs")
    }
}

/** Secrets live only for this disposable browser; never in saved Compose state or routes. */
private class BaoziWebMemory {
    var restored: BaoziSavedAccount? = null
    var candidate: BaoziAutoLogin? = null
    var automaticAttempted = false
    fun clear() { restored = null; candidate = null }
}

private val BAOZI_LOGIN_EMAIL = """
(() => {
  const key = 'fluxmanga.baozi.login-email';
  try {
    if (/^\/user\/(signin|signup)\/?${'$'}/.test(location.pathname)) {
      const remember = () => {
        const fields = document.querySelectorAll('input[type="email"]');
        if (fields.length === 1) {
          const field = fields[0];
          const email = field.value.trim();
          if (email.length <= 256 && /^[^\s@]+@[^\s@]+${'$'}/.test(email)) sessionStorage.setItem(key, email);
          else sessionStorage.removeItem(key);
        }
      };
      if (!document.documentElement.dataset.fluxBaoziEmail) {
        document.documentElement.dataset.fluxBaoziEmail = '1';
        document.addEventListener('input', remember, true);
        document.addEventListener('change', remember, true);
        document.addEventListener('submit', remember, true);
      }
      remember();
    }
    return sessionStorage.getItem(key) || '';
  } catch (_) { return ''; }
})()
"""

/** Read account links / profile metadata only: no passwords, form submission or arbitrary JS bridge. */
private val JM_ACCOUNT_SNAPSHOT = """
(() => {
  const links = Array.from(document.querySelectorAll('a[href]'));
  const path = a => { try { const u = new URL(a.href, location.href); return u.origin === location.origin ? u.pathname : ''; } catch (_) { return ''; } };
  const favorites = links.map(path).map(p => p.match(/^\/user\/([^/]+)\/favorite\/albums\/?$/)).filter(Boolean);
  const names = Array.from(new Set(favorites.map(m => decodeURIComponent(m[1]))));
  const username = names.length === 1 ? names[0] : '';
  const ids = [];
  const add = v => { if (/^[1-9][0-9]{0,14}$/.test(v || '')) ids.push(v); };
  document.querySelectorAll('input[type="hidden"][name="uid"], input[type="hidden"][name="user_id"]').forEach(e => add(e.value));
  links.forEach(a => {
    const p = path(a); const edit = p.match(/^\/useredit\/([1-9][0-9]*)\/?$/); if (edit) add(edit[1]);
    const user = p.match(/^\/user\/([^/]+)\/?$/);
    if (username && user && decodeURIComponent(user[1]) === username) {
      add(a.getAttribute('data-userid'));
      a.querySelectorAll('img').forEach(img => { const m = (img.getAttribute('src') || '').match(/\/media\/users\/([1-9][0-9]*)\./); if (m) add(m[1]); });
    }
  });
  if (username) document.querySelectorAll('header img, nav img, .navbar img').forEach(img => {
    const m = (img.getAttribute('src') || '').match(/\/media\/users\/([1-9][0-9]*)\./); if (m) add(m[1]);
  });
  const unique = Array.from(new Set(ids));
  return JSON.stringify({username, uid: unique.length === 1 ? unique[0] : ''});
})()
"""

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AccountWebLogin(site: AccountWebSite, close: () -> Unit,
    connectLabel: String = "连接账号与收藏", connect: (suspend (AccountWebPage, String, String) -> Unit)? = null,
    afterConnected: () -> Unit = {}, destination: String? = null, jmIdentityFields: Boolean = false,
    loginUrls: List<String> = emptyList(),
    autoConnectKey: (suspend (AccountWebPage, String, String) -> String?)? = null,
    accountNameLabel: String? = null, initialAccountName: String = "",
    restoreBaozi: (suspend () -> BaoziSavedAccount?)? = null, setupBaoziAutoLogin: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestClose by rememberUpdatedState(close)
    val latestConnected by rememberUpdatedState(afterConnected)
    val latestConnect by rememberUpdatedState(connect)
    val latestAutoKey by rememberUpdatedState(autoConnectKey)
    val latestRestoreBaozi by rememberUpdatedState(restoreBaozi)
    val baoziMemory = remember { BaoziWebMemory() }
    var baoziPassword by remember { mutableStateOf("") }
    var showBaoziPassword by remember { mutableStateOf(setupBaoziAutoLogin) }
    var gateAttempted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var pageVersion by remember { mutableIntStateOf(0) }
    var progress by remember { mutableIntStateOf(0) }
    var host by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf<Job?>(null) }
    var uid by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(initialAccountName) }
    var alive by remember { mutableStateOf(true) }
    var loginUrl by remember(site) { mutableStateOf(site.url) }
    var chooseLoginUrl by remember { mutableStateOf(false) }
    var verifyingBrowser by remember { mutableStateOf(false) }
    var attemptedKey by remember { mutableStateOf<String?>(null) }

    fun connectAccount() {
        val action = latestConnect ?: return
        val browser = view ?: return
        if (!ready || busy) return
        val version = pageVersion
        busy = true; error = null
        connection = scope.launch {
            try {
                action(AccountWebPage(browser, site, baoziMemory.candidate) { alive && ready && version == pageVersion }, uid, username)
                ensureActive()
                if (alive) { latestClose(); latestConnected() }
            } catch (_: CancellationException) {
                if (alive) error = "连接已停止，可返回图源管理核对账号状态"
            } catch (failure: Exception) {
                if (alive) error = failure.message?.takeIf { failure is IOException } ?: "账号验证未完成，请确认已登录并重试"
            } finally { if (alive) busy = false }
        }
    }

    fun loginBaozi(login: BaoziAutoLogin) {
        val action = latestConnect ?: return
        val browser = view ?: return
        if (!ready || busy) return
        val version = pageVersion
        busy = true; error = null
        connection = scope.launch {
            try {
                val page = AccountWebPage(browser, site, login) { alive && ready && version == pageVersion }
                page.signInBaozi(login)
                baoziMemory.candidate = login
                action(page, "", login.username)
                ensureActive()
                if (alive) { baoziPassword = ""; latestClose(); latestConnected() }
            } catch (_: TimeoutCancellationException) {
                if (alive) error = "自动登录超时，请检查网络后重试"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (alive) error = if (failure is IOException) failure.message else "自动登录未完成，请重新登录"
            } finally { if (alive) busy = false }
        }
    }

    // Local cookie/profile observation also covers SPA login without a full navigation.
    // A new candidate or route triggers verification once; a failed candidate never loops requests.
    LaunchedEffect(view, ready, pageVersion) {
        val browser = view ?: return@LaunchedEffect
        if (!ready || latestAutoKey == null) return@LaunchedEffect
        val version = pageVersion
        val page = AccountWebPage(browser, site) { alive && ready && version == pageVersion }
        while (isActive && alive && ready) {
            if (verifyingBrowser && site == BaoziWebSite) {
                if (page.baoziVerificationPassed()) {
                    browser.loadUrl("${page.baoziOrigin()}user/my_bookshelf")
                    return@LaunchedEffect
                }
                delay(1000)
                continue
            }
            if (!busy) {
                if (site == BaoziWebSite) {
                    // Name is only the import identity; TSID and shelf validation remain required.
                    username = page.baoziLoginEmail()
                        ?: baoziMemory.restored?.takeIf { it.browserSession?.origin == page.baoziOrigin() }?.username.orEmpty()
                    val login = baoziMemory.restored?.autoLogin
                    if (!showBaoziPassword && !baoziMemory.automaticAttempted && login != null && login.origin == page.baoziOrigin() &&
                        page.baoziNeedsLogin()) {
                        baoziMemory.automaticAttempted = true
                        loginBaozi(login)
                        delay(1000)
                        continue
                    }
                }
                val candidate = try { latestAutoKey?.invoke(page, uid, username) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { null }
                if (!candidate.isNullOrBlank() && !(site == BaoziWebSite && showBaoziPassword)) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest("${browser.url}\n$candidate".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    if (digest != attemptedKey) { attemptedKey = digest; connectAccount() }
                }
            }
            delay(1000)
        }
    }

    DisposableEffect(site, owner) {
        var ownsStorage = false
        var browser: WebView? = null
        var disposed = false
        var preparing: Job? = null
        alive = true
        fun disposeBrowser() {
            if (disposed) return
            disposed = true; alive = false; connection?.cancel(); preparing?.cancel()
            uid = ""; username = ""; baoziPassword = ""; baoziMemory.clear()
            browser?.apply {
                (parent as? android.view.ViewGroup)?.removeView(this)
                stopLoading(); clearHistory(); clearCache(true); removeAllViews(); destroy()
            }
            view = null
            if (ownsStorage) AccountWebStorage.release()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                disposeBrowser()
                latestClose()
            }
        }
        owner.lifecycle.addObserver(observer)
        try {
            ownsStorage = AccountWebStorage.acquire()
            if (!ownsStorage) error = "上一登录窗口仍在清理，请关闭后重新打开"
            else {
                browser = WebView(context).apply {
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        @Suppress("DEPRECATION")
                        saveFormData = false
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(web: WebView, value: Int) { if (alive) progress = value }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            if (site.accepts(request.url.toString())) return false
                            val target = request.url
                            error = "本站跳转到了未接入的地址（${target.scheme}://${target.host.orEmpty()}），已停止；请返回或重试"
                            return true
                        }
                        override fun onPageStarted(web: WebView, url: String?, icon: android.graphics.Bitmap?) {
                            pageVersion++; connection?.cancel(); busy = false
                            baoziMemory.candidate = null
                            verifyingBrowser = site == BaoziWebSite && Uri.parse(url.orEmpty()).path?.startsWith("/__gatekeeper_challenge/") == true
                            if (alive) { ready = false; error = null; host = Uri.parse(url.orEmpty()).host.orEmpty() }
                        }
                        override fun onPageFinished(web: WebView, url: String?) {
                            // Error pages also finish loading: they must not enable session import.
                            if (alive) ready = error == null && site.accepts(url)
                        }
                        override fun onPageCommitVisible(web: WebView, url: String?) {
                            if (alive && site == BaoziWebSite && site.accepts(url)) {
                                web.evaluateJavascript(BAOZI_LOGIN_EMAIL, null)
                            }
                        }
                        override fun onReceivedError(web: WebView, request: WebResourceRequest, failure: WebResourceError) {
                            if (alive && request.isForMainFrame) {
                                ready = false
                                val reason = when (failure.errorCode) {
                                    ERROR_CONNECT -> "无法连接网页登录地址"
                                    ERROR_HOST_LOOKUP -> "无法解析网页登录地址"
                                    ERROR_TIMEOUT -> "网页登录连接超时"
                                    ERROR_FAILED_SSL_HANDSHAKE -> "网页登录安全连接失败"
                                    else -> "网页加载失败"
                                }
                                error = "$reason（${failure.errorCode}）。" +
                                    if (loginUrls.isNotEmpty()) "可切换网页登录线路后重试；这与 API 线路独立。" else "请检查网络后重试。"
                            }
                        }
                        override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (alive && request.isForMainFrame) {
                                ready = false
                                if (site == BaoziWebSite && response.statusCode == 403 && !gateAttempted && !verifyingBrowser &&
                                    site.accepts(request.url.toString())) {
                                    gateAttempted = true
                                    val origin = "https://${request.url.host}/"
                                    web.post { if (alive) web.loadUrl("${origin}__gatekeeper_challenge/start?return=%2Fuser%2Fmy_bookshelf") }
                                    return
                                }
                                error = "网站返回 HTTP ${response.statusCode}；请在网页完成验证或稍后重试"
                            }
                        }
                        override fun onReceivedSslError(web: WebView, handler: SslErrorHandler, failure: SslError) {
                            handler.cancel()
                            if (alive) { ready = false; error = "网页证书验证失败，已停止连接" }
                        }
                        override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                            disposeBrowser(); latestClose()
                            return true
                        }
                    }
                    clearCache(true)
                }
                view = browser
                AccountWebStorage.clean {
                    if (alive) preparing = scope.launch {
                        try {
                            val saved = if (site == BaoziWebSite) latestRestoreBaozi?.invoke() else null
                            ensureActive()
                            if (!alive) return@launch
                            baoziMemory.restored = saved
                            saved?.browserSession?.let { profile ->
                                profile.validate(requireNotNull(saved.token))
                                browser?.settings?.userAgentString = profile.userAgent
                                for (cookie in profile.cookie.split(';').map(String::trim)) {
                                    suspendCancellableCoroutine<Unit> { continuation ->
                                        CookieManager.getInstance().setCookie(profile.origin, "$cookie; Path=/; Secure; HttpOnly") { accepted ->
                                            if (continuation.isActive) {
                                                if (accepted) continuation.resume(Unit)
                                                else continuation.resumeWith(Result.failure(IOException("浏览器会话恢复失败")))
                                            }
                                        }
                                    }
                                }
                                loginUrl = "${profile.origin}user/my_bookshelf"
                            }
                            if (alive && site.accepts(loginUrl)) browser?.loadUrl(loginUrl)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { if (alive) error = "已保存账号读取未完成，请关闭后重试；原账号保留" }
                    }
                }
            }
        } catch (_: Exception) { error = "无法启动系统 WebView，请检查 Android System WebView 是否可用" }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            disposeBrowser()
        }
    }

    fun back() { if (view?.canGoBack() == true && !busy) view?.goBack() else { connection?.cancel(); latestClose() } }
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, dismissOnBackPress = false, decorFitsSystemWindows = false,
        securePolicy = SecureFlagPolicy.SecureOn)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ connection?.cancel(); latestClose() }) { Text("关闭") }
                    TextButton({ back() }, enabled = !busy && view?.canGoBack() == true) { Text("网页返回") }
                    TextButton(feedbackAttempt({
                        ready = false; error = null; baoziMemory.automaticAttempted = false
                        attemptedKey = null; gateAttempted = false; view?.loadUrl(loginUrl)
                    }), enabled = view != null && !busy) { Text("重新打开登录") }
                    if (loginUrls.isNotEmpty()) Box {
                        TextButton({ chooseLoginUrl = true }, enabled = view != null && !busy) { Text("切换网页登录线路") }
                        DropdownMenu(chooseLoginUrl, { chooseLoginUrl = false }) {
                            loginUrls.forEach { target ->
                                DropdownMenuItem(text = { Text(requireNotNull(Uri.parse(target).host)) }, onClick = {
                                    chooseLoginUrl = false
                                    if (site.accepts(target)) {
                                        connection?.cancel(); pageVersion++; ready = false; error = null
                                        loginUrl = target; view?.stopLoading(); view?.loadUrl(target)
                                    }
                                })
                            }
                        }
                    }
                }
                Text(site.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                Text(host.ifBlank { "正在准备登录页面…" }, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                if (busy || (error == null && progress < 100)) RequestLoading()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    view?.let { browser -> AndroidView(factory = { browser }, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The page-level popup belongs to the activity window, behind this secure dialog.
                    // Keep connection/load failures visible inside the window that owns the action.
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium) }
                    if (site == BaoziWebSite && error != null) TextButton(onClick = {
                        connection?.cancel(); attemptedKey = null
                        baoziMemory.automaticAttempted = false
                        val origin = view?.url?.takeIf(site::accepts)?.let { "https://${Uri.parse(it).host}/" }
                        view?.loadUrl(origin?.let { "${it}__gatekeeper_challenge/start?return=%2Fuser%2Fsignin" } ?: BaoziWebSite.url)
                    }, enabled = !busy) { Text("重新完成网站验证") }
                    if (site == BaoziWebSite) {
                        TextButton(onClick = { showBaoziPassword = !showBaoziPassword }, enabled = !busy) { Text("设置自动登录") }
                        if (showBaoziPassword) {
                            Text("复用官网邮箱，密码加密保存在本机；掉线后自动重登，退出账号时删除。",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(baoziPassword, { baoziPassword = it }, Modifier.fillMaxWidth(),
                                label = { Text("自动登录密码") }, enabled = !busy, singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                            TextButton(onClick = {
                                val browser = view ?: return@TextButton
                                val page = AccountWebPage(browser, site) { alive && ready }
                                loginBaozi(BaoziAutoLogin(username, baoziPassword, page.baoziOrigin()))
                            }, enabled = ready && !busy && username.isNotBlank() && baoziPassword.isNotEmpty()) { Text("登录并记住") }
                            if (username.isBlank()) Text("请先在上方官网填写登录邮箱。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (jmIdentityFields) SettingsDisclosure("补充 / 更正账号信息") {
                        Text("可先打开网页个人资料。仍无法读取时，填写资料中的数字 UID 和登录用户名；软件会向 JM 核对，失败不会替换原账号。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(uid, { uid = it }, Modifier.fillMaxWidth(), label = { Text("JM UID") }, enabled = !busy, singleLine = true)
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("登录用户名") }, enabled = !busy, singleLine = true)
                    }
                    accountNameLabel?.let { label ->
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text(label) },
                            enabled = !busy, singleLine = true)
                    }
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        destination?.let { target -> TextButton(feedbackAttempt({ error = null; view?.loadUrl(target) }), enabled = !busy && ready) { Text("打开本站") } }
                        if (connect != null) ReferenceButton(feedbackAttempt(::connectAccount), enabled = ready && !busy && !verifyingBrowser) {
                            Text(if (busy) "正在确认账号…" else connectLabel)
                        }
                        if (busy) TextButton({ connection?.cancel() }) { Text("取消连接") }
                    }
                    Text(if (connect == null) "请使用网页上的登录 / 注册入口；注册由网站完成，返回不代表已连接软件账号。"
                        else if (autoConnectKey != null) "网页登录成功后自动验证并保存；未接回时可手动重试连接。"
                        else "网页登录后点击连接。只有账号与本站权限确认成功才会替换软件中的登录。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("网页会话临时保存于应用登录目录，关闭或进入后台时清理。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
