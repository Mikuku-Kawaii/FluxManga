package com.flux.manga.ui.pages

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import com.flux.manga.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

private const val PASSWORD_BRIDGE = "fluxPasswordCandidate"

// Only the main-frame login form. Registration/reset forms and hidden fields are excluded.
private val PASSWORD_FORM = """
const visible = e => e && !e.disabled && !e.readOnly && e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden';
const passwords = Array.from(document.querySelectorAll('input[type=password]')).filter(visible);
if (passwords.length !== 1 || passwords[0].autocomplete === 'new-password') return null;
const p = passwords[0], root = p.form || document;
const inputs = Array.from(root.querySelectorAll('input')).filter(e => visible(e) && ['text','email','tel'].includes(e.type));
const u = inputs.find(e => e.autocomplete === 'username') || inputs.find(e => /user|email|login|account/i.test(e.name + ' ' + e.id)) || inputs.filter(e => e.compareDocumentPosition(p) & Node.DOCUMENT_POSITION_FOLLOWING).pop();
if (!u) return null;
return {u,p};
""".trimIndent()

private val PASSWORD_CAPTURE = """
(() => {
 if (window !== top || window.__fluxPasswordObserver) return;
 window.__fluxPasswordObserver = true;
 const form = () => { $PASSWORD_FORM };
 const capture = e => {
  if (!e.isTrusted) return;
  const f = form(); if (!f) return;
  const b = e.target.closest && e.target.closest('button,input[type=submit],[role=button]');
  const submitted = e.type === 'submit' || (e.type === 'keydown' && e.key === 'Enter') ||
    (e.type === 'click' && b && (b.type === 'submit' || /登录|登入|log\s*in|sign\s*in/i.test(b.textContent || b.value || '')));
  if (!submitted && !(['input','change'].includes(e.type) && (e.target === f.u || e.target === f.p))) return;
  window.$PASSWORD_BRIDGE.postMessage(JSON.stringify({username:f.u.value,password:f.p.value,submitted:!!submitted}));
 };
 ['input','change','submit','click','keydown'].forEach(type => document.addEventListener(type,capture,true));
})();
""".trimIndent()

internal class BrowserPasswordManager(private val site: AccountWebSite, private val store: BrowserPasswordStore) {
    private var alive = true
    private var book = BrowserPasswordBook()
    private var candidate: BrowserPassword? = null
    private var submitted = false
    var pending by mutableStateOf<BrowserPassword?>(null); private set
    var managing by mutableStateOf(false); private set
    var origin by mutableStateOf<String?>(null); private set
    var entries by mutableStateOf<List<BrowserPassword>>(emptyList()); private set
    var excluded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var updating by mutableStateOf(false); private set
    // Feature detection may load WebView. Defer it until AccountWebStorage has
    // configured the process data directory and the browser has been created.
    var captureAvailable by mutableStateOf(false); private set
    private fun publish(value: String) {
        origin = value
        entries = book.entries.filter { it.origin == value }
        excluded = value in book.excluded
    }
    fun attach(web: WebView) {
        captureAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!captureAvailable) return
        WebViewCompat.addWebMessageListener(web, PASSWORD_BRIDGE, site.passwordOrigins) { _, message, source, mainFrame, _ ->
            val origin = passwordOrigin(source.toString())
            if (alive && mainFrame && origin in site.passwordOrigins && message.type == WebMessageCompat.TYPE_STRING &&
                message.data?.length?.let { it in 1..16_384 } == true) {
                try {
                    val json = JSONObject(requireNotNull(message.data))
                    val username = json.getString("username").trim()
                    val password = json.getString("password")
                    if (username.toByteArray().size in 1..256 && password.toByteArray().size in 1..4096) {
                        val previous = candidate
                        submitted = json.optBoolean("submitted") || (submitted && previous != null && previous.origin == origin && previous.username == username && previous.password == password)
                        candidate = BrowserPassword(requireNotNull(origin), username, password)
                    } else { candidate = null; submitted = false }
                } catch (_: Exception) { candidate = null; submitted = false }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(web, PASSWORD_CAPTURE, site.passwordOrigins)
    }
    fun pageReady(web: WebView) {
        if (alive && site.accepts(web.url) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            web.evaluateJavascript(PASSWORD_CAPTURE, null)
    }
    suspend fun offer(): Boolean {
        val value = candidate?.takeIf { submitted } ?: return false
        try { book = store.read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (!alive) return false
            pending = value; message = "账号已连接，但密码记录读取失败。可重试保存或暂不保存。"
            return true
        }
        if (!alive || value.origin in book.excluded) return false
        val previous = book.entries.find { it.origin == value.origin && it.username == value.username }
        if (previous?.password == value.password) return false
        updating = previous != null; pending = value; message = null
        return true
    }
    suspend fun open(web: WebView?) {
        val browser = web ?: return
        val target = browser.url?.takeIf(site::accepts)?.let(::passwordOrigin) ?: return
        book = store.read()
        if (!alive || passwordOrigin(browser.url) != target) return
        publish(target); message = null; managing = true
    }
    fun dismiss() { managing = false; message = null }
    suspend fun decide(save: Boolean, never: Boolean) {
        val value = pending ?: return
        if (save || never) book = store.read()
        if (save) book = BrowserPasswordBook(book.entries.filterNot { it.origin == value.origin && it.username == value.username } + value, book.excluded - value.origin)
        else if (never) book = BrowserPasswordBook(book.entries, book.excluded + value.origin)
        if (save || never) store.write(book)
        pending = null; candidate = null; submitted = false
    }
    suspend fun remove(value: BrowserPassword) {
        val next = BrowserPasswordBook(book.entries.filterNot { it.origin == value.origin && it.username == value.username }, book.excluded)
        store.write(next); book = next; origin?.let(::publish)
    }
    suspend fun allowAsking() {
        val next = BrowserPasswordBook(book.entries, book.excluded - requireNotNull(origin))
        store.write(next); book = next; origin?.let(::publish)
    }
    suspend fun fill(web: WebView?, value: BrowserPassword) {
        if (web == null || !site.accepts(web.url) || passwordOrigin(web.url) != value.origin) throw java.io.IOException("请返回保存密码时的登录网站")
        val script = """
        (() => {
         if (window !== top || location.origin !== ${JSONObject.quote(value.origin)}) return false;
         const f = (() => { $PASSWORD_FORM })(); if (!f) return false;
         const set = (e,v) => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v); e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); };
         set(f.u,${JSONObject.quote(value.username)}); set(f.p,${JSONObject.quote(value.password)});
         return true;
        })()
        """.trimIndent()
        val filled = suspendCancellableCoroutine<Boolean> { continuation ->
            web.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it == "true") }
        }
        if (!filled) throw java.io.IOException("未找到账号密码输入框，请先打开网页登录表单")
        managing = false
    }
    suspend fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message = if (error is java.io.IOException) error.message else "密码操作未完成，请重试" }
        finally { busy = false }
    }
    fun clear() { alive = false; candidate = null; pending = null; entries = emptyList(); book = BrowserPasswordBook() }
}

@Composable internal fun BrowserPasswordDialogs(manager: BrowserPasswordManager, web: WebView?, finished: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pending = manager.pending
    if (pending != null) {
        fun decide(save: Boolean, never: Boolean = false) { scope.launch { manager.act { manager.decide(save, never); finished() } } }
        AlertDialog(onDismissRequest = { if (!manager.busy) decide(false) },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(if (manager.updating) "更新密码？" else "保存密码？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pending.origin); Text(pending.username)
                Text("加密保存在当前空间，下次可选择账号填入。")
                manager.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton({ decide(true) }, enabled = !manager.busy) { Text(if (manager.updating) "更新" else "保存") } },
            dismissButton = { Row {
                TextButton({ decide(false) }, enabled = !manager.busy) { Text("暂不") }
                TextButton({ decide(false, true) }, enabled = !manager.busy) { Text("此网站不再询问") }
            } })
    } else if (manager.managing) AlertDialog(onDismissRequest = { if (!manager.busy) manager.dismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), title = { Text("已保存的密码") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(manager.origin.orEmpty())
            if (!manager.captureAvailable) Text("当前 WebView 不支持登录表单识别，请更新 Android System WebView；已有密码仍可填入。")
            if (manager.entries.isEmpty()) Text("当前网站没有保存的账号。登录并连接成功后可保存。")
            manager.entries.forEach { entry ->
                Text(entry.username)
                Row {
                    TextButton({ scope.launch { manager.act { manager.fill(web, entry) } } }, enabled = !manager.busy) { Text("填入") }
                    TextButton({ scope.launch { manager.act { manager.remove(entry) } } }, enabled = !manager.busy) { Text("删除") }
                }
            }
            if (manager.excluded) TextButton({ scope.launch { manager.act { manager.allowAsking() } } }, enabled = !manager.busy) { Text("恢复保存密码提示") }
            manager.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(manager::dismiss, enabled = !manager.busy) { Text("完成") } })
}
