package com.flux.manga.data

import com.flux.manga.core.SourceContext
import com.flux.manga.core.SourceHttpSession
import com.flux.manga.core.SourceWorld
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.jsoup.Jsoup
import java.io.Closeable
import java.io.IOException
import java.net.HttpCookie
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed EhViewer sign-in contract; see ehentai-NOTICE.txt / exhentai-NOTICE.txt. */
internal class EhPasswordLogin(private val session: WorldSession, site: GallerySite, owner: CoroutineScope) : Closeable {
    private val identity = session.identity.value
    private val source = "${site.prefix}-login"
    private val context = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    private val native = run {
        require(identity.world == PrototypeWorld.NSFW)
        SourceHttpSession.galleryLogin(context)
    }
    private val closed = AtomicBoolean()
    private val lifetime = Job(requireNotNull(owner.coroutineContext[Job]))
    init {
        lifetime.invokeOnCompletion { release() }
        CoroutineScope(owner.coroutineContext + lifetime).launch {
            combine(session.identity, session.state) { _, state -> session.accepts(identity) && state.foreground }
                .collect { if (!it) close() }
        }
    }
    private fun active() {
        if (closed.get() || !session.accepts(identity) || !session.state.value.foreground) throw CancellationException()
    }
    private fun release() { if (closed.compareAndSet(false, true)) { native.invalidate(); native.destroy() } }
    override fun close() { release(); lifetime.cancel() }

    suspend fun signIn(username: String, password: String, igneous: String): EhAccount = withContext(Dispatchers.IO) {
        active()
        val reply = native.gallerySignIn(context, username.trim(), password)
        try {
            currentCoroutineContext().ensureActive(); active()
            val document = Jsoup.parse(reply.body.toString(Charsets.UTF_8))
            val success = document.select("p").firstOrNull { it.text().startsWith("You are now logged in as: ") }
            if (success == null) {
                val error = document.select("h4").firstOrNull { it.text() == "The error returned was:" }
                    ?.nextElementSibling()?.takeIf { it.tagName() == "p" }?.text()
                    ?: document.selectFirst("span.postcolor")?.text()
                throw IOException(error?.takeIf(String::isNotBlank)?.take(1000)
                    ?: "论坛未确认登录，可能需要浏览器验证；请使用浏览器与 Cookie 入口。")
            }
            val cookies = mutableMapOf<String, String>()
            reply.cookies.forEach { header ->
                HttpCookie.parse(header).forEach { cookie ->
                    if (cookie.name in setOf("ipb_member_id", "ipb_pass_hash") &&
                        (cookie.domain == null || cookie.domain.trimStart('.').lowercase() in setOf("e-hentai.org", "forums.e-hentai.org")) &&
                        (cookie.path == null || cookie.path == "/")) {
                        if (cookie.hasExpired()) cookies.remove(cookie.name) else cookies[cookie.name] = cookie.value
                    }
                }
            }
            val uid = cookies["ipb_member_id"] ?: throw IOException("登录响应缺少账号 Cookie，请使用手动 Cookie 入口")
            val hash = cookies["ipb_pass_hash"] ?: throw IOException("登录响应缺少身份 Cookie，请使用手动 Cookie 入口")
            if (!uid.matches(Regex("[1-9][0-9]*")) || !hash.matches(Regex("[a-fA-F0-9]{32}"))) throw IOException("论坛返回的身份 Cookie 格式无法识别")
            EhAccount(uid, hash, success.text().substringAfter("You are now logged in as: ").trim(), igneous.trim())
        } finally { reply.body.fill(0) }
    }
}
