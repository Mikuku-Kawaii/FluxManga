package com.flux.manga.ui.pages

import android.content.Context
import android.net.Uri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.flux.manga.data.SourceBypassNetwork
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The existing AccountWebStorage lock guarantees one login WebView/profile at a
 * time. Clear the global WebView override before handing that lock to another source. */
internal class BrowserNetworkRoute(private val context: Context, scope: String, site: AccountWebSite) {
    private val tunnel = SourceBypassNetwork.browser(scope, site.hosts)
    val bypass get() = tunnel != null
    @Volatile var ready = tunnel == null
        private set
    private var overridden = false
    suspend fun install() {
        val route = tunnel ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            throw java.io.IOException("当前系统 WebView 不支持图源直连，请更新 Android System WebView")
        }
        overridden = true
        suspendCancellableCoroutine<Unit> { continuation ->
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule(route.proxyAddress).build(), context.mainExecutor) {
                if (continuation.isActive) { ready = true; continuation.resume(Unit) }
            }
        }
    }
    fun requested(url: Uri) {
        if (url.scheme == "https") tunnel?.allowBrowserHost(url.host)
    }
    fun close(done: () -> Unit) {
        ready = false
        tunnel?.close()
        if (overridden) {
            overridden = false
            ProxyController.getInstance().clearProxyOverride(context.mainExecutor, done)
        } else done()
    }
}
