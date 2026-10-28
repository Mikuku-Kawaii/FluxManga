package com.flux.manga.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Only Baozi uses this persistent profile. Other sources keep their disposable default profile. */
internal object BaoziBrowserProfile {
    private const val NAME = "flux-baozi-account-v1"
    private var attached: WebView? = null
    private var initialized = false
    fun initialize() {
        if (!initialized) { WebView.setDataDirectorySuffix("source-account-login"); initialized = true }
    }
    fun supported(): Boolean { initialize(); return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
    private fun profile() = ProfileStore.getInstance().getOrCreateProfile(NAME)
    fun attach(view: WebView): Boolean {
        if (!supported()) return false
        WebViewCompat.setProfile(view, NAME)
        attached = view
        return true
    }
    fun detach(view: WebView) { if (attached === view) attached = null }
    fun cookies(view: WebView): CookieManager =
        if (attached === view) profile().cookieManager else CookieManager.getInstance()
    private fun preferences(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Returns true only when the encrypted legacy cookie snapshot needs seeding. */
    suspend fun prepare(context: Context, saved: BaoziSavedAccount?): Boolean {
        if (!supported()) return true
        val owner = saved?.takeIf { it.token != null }?.sourceId.orEmpty()
        val prefs = preferences(context)
        if (prefs.getString("owner", null) != owner) {
            clear(context, stopBrowser = false)
            prefs.edit().putString("owner", owner).apply()
            return true
        }
        return !profile().cookieManager.hasCookies()
    }

    suspend fun accepted(context: Context, source: String) {
        if (!supported()) return
        val prefs = preferences(context)
        val previous = prefs.getString("owner", null)
        // An explicit account change starts clean next time, seeded from its verified encrypted record.
        if (!previous.isNullOrBlank() && previous != source) clear(context, stopBrowser = false)
        prefs.edit().putString("owner", source).apply()
        val cookies = profile().cookieManager
        withContext(Dispatchers.IO) { cookies.flush() }
    }

    suspend fun clear(context: Context, stopBrowser: Boolean = true) = withContext(Dispatchers.Main.immediate) {
        preferences(context).edit().remove("owner").apply()
        if (!supported()) return@withContext
        attached?.apply { if (stopBrowser) { stopLoading(); loadUrl("about:blank") }; clearCache(true) }
        val current = profile()
        current.webStorage.deleteAllData()
        val cookies = current.cookieManager
        suspendCancellableCoroutine<Unit> { continuation ->
            cookies.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        withContext(Dispatchers.IO) { cookies.flush() }
    }
}
