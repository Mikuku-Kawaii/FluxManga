package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

// Not a data class: never include a session cookie in generated toString().
internal class PublicWebAccount(val id: String, val name: String, val cookie: String)

internal class PublicWebAccountStore(private val data: WorldDataStore, private val session: WorldSession, val site: PublicWebSite) {
    private val identity = session.identity.value
    private val lock = Mutex()
    private var loaded = false
    private var saved: PublicWebAccount? = null
    private val epoch = AtomicLong()
    val revision get() = epoch.get()
    private fun active() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    private fun check() { if (!active()) throw CancellationException() }
    suspend fun read(): PublicWebAccount? = lock.withLock {
        check()
        if (!loaded) {
            val bytes = data.session(identity.world, "public-account:${site.sourceId}", ::active)
            try {
                saved = bytes?.let { JSONObject(it.toString(Charsets.UTF_8)).let { json ->
                    PublicWebAccount(json.getString("id"), json.getString("name"), validPublicCookie(json.getString("cookie")))
                } }
                check(); loaded = true
            } finally { bytes?.fill(0) }
        }
        saved
    }
    suspend fun write(account: PublicWebAccount?, allowed: () -> Boolean) = lock.withLock {
        check()
        val bytes = account?.let { JSONObject().put("id", it.id).put("name", it.name)
            .put("cookie", validPublicCookie(it.cookie)).toString().toByteArray() }
        try {
            data.putSession(identity.world, "public-account:${site.sourceId}", bytes, { active() && allowed() })
            check(); saved = account; loaded = true; epoch.incrementAndGet()
        } finally { bytes?.fill(0) }
    }
}

internal fun validPublicCookie(value: String): String {
    if (value.isBlank() || value.length > 16384 || value.any { it.code !in 32..126 })
        throw IOException("网页会话无效，请重新登录")
    return value
}

/** Website protocols verified from public forms/user-info responses; no upstream login code copied. */
internal class PublicWebAccountClient(private val session: WorldSession, private val site: PublicWebSite) {
    private val identity = session.identity.value
    private val context = identity.sourceContext(site.sourceId)
    private fun active() { if (!session.accepts(identity) || !session.state.value.foreground) throw CancellationException() }
    private suspend fun <T> request(block: suspend (SourceHttpSession) -> T): T = withContext(Dispatchers.IO) {
        active(); ensureActive()
        val native = SourceHttpSession.publicCatalog(context)
        try { block(native).also { ensureActive(); active() } }
        finally { native.invalidate(); native.destroy() }
    }
    suspend fun signIn(username: String, password: String): PublicWebAccount = request { native ->
        require(site == PublicWebSite.MANHUAGUI)
        val reply = native.publicCatalogSignIn(context, username.trim(), password)
        val result = JSONObject(reply.body.toString(Charsets.UTF_8))
        if (result.optInt("status") != 1) throw IOException("漫画柜登录未成功，请检查账号和密码，或稍后重试")
        val cookie = reply.cookies.map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("my=") && it.substringAfter('=').isNotBlank() }
            ?: throw IOException("漫画柜没有返回账号会话，请重新登录")
        verify(native, validPublicCookie(cookie))
    }
    suspend fun verify(cookie: String): PublicWebAccount = request { verify(it, validPublicCookie(cookie)) }
    private suspend fun verify(native: SourceHttpSession, cookie: String): PublicWebAccount {
        val path = when (site) {
            PublicWebSite.GUAZI -> "user.php"
            PublicWebSite.KUAIKAN -> "v2/pweb/user_info/get"
            PublicWebSite.MANHUAGUI -> "tools/submit_ajax.ashx?action=user_check_login"
        }
        val reply = native.publicCatalogAccountRead(context, site.origin + path, cookie)
        if (reply.context != context) throw CancellationException()
        val text = reply.body.toString(Charsets.UTF_8)
        val id: String
        val name: String
        when (site) {
            PublicWebSite.MANHUAGUI -> {
                val json = JSONObject(text)
                if (json.optInt("status") != 1) throw IOException("漫画柜尚未登录或登录已失效")
                name = json.optString("username").trim()
                id = name
            }
            PublicWebSite.KUAIKAN -> {
                val json = JSONObject(text)
                val user = json.optJSONObject("data")?.optJSONObject("user_info")
                if (json.optInt("code") != 200 || user == null || user.optInt("authenticate") <= 0)
                    throw IOException("快看尚未登录或登录已失效，请完成网页验证")
                id = user.optString("user_id")
                if (!id.matches(Regex("[1-9][0-9]*"))) throw IOException("快看账号身份无法确认")
                name = user.optString("nickname").takeIf { it.isNotBlank() && it != "null" } ?: "快看用户 $id"
            }
            PublicWebSite.GUAZI -> {
                val doc = Jsoup.parse(text, site.origin)
                // Require authenticated controls AND the server-rendered profile. A Cookie alone is not proof.
                val logout = doc.selectFirst("a[href='/logout.php'], form[action='/logout.php'], a[href='logout.php']")
                name = doc.selectFirst(".identity h2, .mobile-mine-identity h2")?.text().orEmpty().trim()
                if (logout == null || name.isBlank() || name == "未登录" || doc.selectFirst("#guest-profile-title") != null)
                    throw IOException("瓜子账号尚未登录，或用户中心格式无法确认，请在网页打开个人中心后重试")
                id = name
            }
        }
        if (id.isBlank() || name.isBlank()) throw IOException("无法确认账号身份，请重新登录")
        return PublicWebAccount(id, name, cookie)
    }
}
