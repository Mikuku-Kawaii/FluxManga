package com.flux.manga.data

import android.content.Context
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import java.net.HttpCookie
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal const val JM_SOURCE = "jmcomic-public"
internal fun isJmSource(id: String) = id == JM_SOURCE || id.matches(Regex("jmcomic-user-[0-9]+"))
internal fun jmId(id: String) = id.matches(Regex("[1-9][0-9]{0,14}"))
// Public domain configuration reviewed 2026-10-25; retain the old host for saved accounts.
internal val JM_APIS = listOf("www.cdnhjk.net", "www.cdnutc.cc", "www.cdngwc.net", "www.cdngwc.club", "www.cdnutc.me", "www.cdngwc.cc")
internal const val JM_APP_VERSION = "2.1.12"
internal val JM_IMAGES = listOf("cdn-msp.jmapiproxy1.cc", "cdn-msp.jmapiproxy2.cc", "cdn-msp2.jmapiproxy2.cc",
    "cdn-msp3.jmapiproxy2.cc", "cdn-msp.jmapinodeudzn.net", "cdn-msp3.jmapinodeudzn.net")
internal data class JmAccount(val uid: String, val username: String, val cookie: String) {
    val source get() = "jmcomic-user-$uid"
}
internal data class JmSettings(val api: String = JM_APIS.first(), val image: String = JM_IMAGES.first(), val account: JmAccount? = null)
internal data class JmBook(val id: String, val title: String, val author: String = "", val description: String = "",
    val tags: List<String> = emptyList(), val chapters: List<JmChapter> = emptyList())
internal data class JmChapter(val id: String, val title: String)
internal data class JmFolder(val id: String, val title: String)
internal data class JmPage(val books: List<JmBook>, val next: Int?, val folders: List<JmFolder> = emptyList())
internal data class JmPages(val chapter: String, val filenames: List<String>, val scramble: Long)
internal fun jmFailure(error: Exception): String = when (error) {
    is SourceException.AuthenticationRequired -> "登录已失效或访问受限，请检查账号与所选线路"
    is SourceException.Timeout -> "请求超时，请重试或在图源管理更换线路"
    is SourceException.Transport -> "JMComic 线路连接失败，请检查网络或在图源管理更换 API 线路"
    is SourceException.HttpStatus -> "JMComic 线路返回 HTTP ${error.status}，请稍后重试或更换线路"
    is SourceException.NotFound -> "作品或章节不存在"
    is IOException -> error.message ?: "JMComic 请求失败，请重试"
    else -> "JMComic 请求或解析失败，请重试；现有记录保留"
}

internal class JmStore(context: Context, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity)
    suspend fun settings(): JmSettings {
        check(active())
        val bytes = data.session(PrototypeWorld.NSFW, "jm-settings-v1", ::active) ?: return JmSettings()
        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val api = json.getString("api"); val image = json.getString("image")
            require(api in JM_APIS && image in JM_IMAGES)
            val account = json.optJSONObject("account")?.let {
                JmAccount(it.getString("uid"), it.getString("username"), it.getString("cookie")).also { a -> require(jmId(a.uid)) }
            }
            JmSettings(api, image, account)
        } finally { bytes.fill(0) }
    }
    suspend fun save(settings: JmSettings) {
        require(settings.api in JM_APIS && settings.image in JM_IMAGES)
        val json = JSONObject().put("api", settings.api).put("image", settings.image)
        settings.account?.let { a -> json.put("account", JSONObject().put("uid", a.uid).put("username", a.username).put("cookie", a.cookie)) }
        val bytes = json.toString().toByteArray()
        try { data.putSession(PrototypeWorld.NSFW, "jm-settings-v1", bytes, ::active) } finally { bytes.fill(0) }
    }
    suspend fun remember(source: String, book: JmBook, favorite: Boolean? = null) = data.worldData(PrototypeWorld.NSFW, ::active) { dao ->
        require(isJmSource(source) && jmId(book.id))
        val old = dao.onlineBook(source, book.id)
        dao.putOnlineBook(OnlineBook(source, book.id, book.title, favorite ?: old?.favorite ?: false, System.currentTimeMillis()))
    }
    suspend fun importPage(source: String, books: List<JmBook>) = data.worldData(PrototypeWorld.NSFW, ::active) { dao ->
        require(isJmSource(source))
        var added = 0
        books.forEach { book ->
            if (dao.onlineBook(source, book.id) == null) {
                dao.putOnlineBook(OnlineBook(source, book.id, book.title, true, System.currentTimeMillis())); added++
            }
        }
        added
    }
}

/** MIT protocol adaptation from pinned JMComic-Crawler-Python; no Python runtime. */
internal class JmClient(private val session: WorldSession, private val settings: JmSettings,
    val source: String, owner: CoroutineScope) : Closeable {
    private val identity = session.identity.value
    private val context = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    private val lock = Mutex()
    private val sessionLock = Mutex()
    private var anonymousCookies = emptyMap<String, String>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val api = SourceHttpSession.jm(context, "https://${settings.api}/", SourceHttpConfig(30_000u, 2u, 150u, 0u, 4u * 1024u * 1024u))
    private val images = SourceHttpSession.jm(context, "https://${settings.image}/", SourceHttpConfig(30_000u, 2u, 150u, 0u, 32u * 1024u * 1024u))
    private val lifetime = Job(requireNotNull(owner.coroutineContext[Job]))
    init {
        require(identity.world == PrototypeWorld.NSFW && isJmSource(source))
        lifetime.invokeOnCompletion {
            if (closed.compareAndSet(false, true)) {
                api.invalidate(); images.invalidate(); api.destroy(); images.destroy()
            }
        }
        CoroutineScope(owner.coroutineContext + lifetime).launch {
            combine(session.identity, session.state) { _, _ -> session.accepts(identity) }
                .collect { if (!it) close() }
        }
    }
    private fun active() { if (closed.get() || !session.accepts(identity) || !session.state.value.foreground) throw CancellationException("Expired JM session") }
    private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun query(values: Map<String, String>) = values.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    private fun responseCookies(): Map<String, String> = buildMap {
        api.jmCookies().forEach { header -> HttpCookie.parse(header).forEach {
            if (!it.hasExpired() && (it.domain == null || settings.api == it.domain.trimStart('.') || settings.api.endsWith(".${it.domain.trimStart('.')}"))) put(it.name, it.value)
        } }
    }
    private suspend fun request(path: String, params: Map<String, String> = emptyMap(), form: Map<String, String>? = null, template: Boolean = false): Pair<JSONObject?, String?> {
        sessionLock.withLock {
            active()
            if (settings.account?.takeIf { it.source == source }?.cookie.isNullOrBlank() && anonymousCookies.isEmpty()) {
                requestRaw("setting")
                val cookies = responseCookies()
                if (cookies.isEmpty()) throw IOException("当前 JMComic 线路未返回会话，请重试或更换 API 线路")
                active(); currentCoroutineContext().ensureActive()
                anonymousCookies = cookies
            }
        }
        return requestRaw(path, params, form, template)
    }
    private suspend fun requestRaw(path: String, params: Map<String, String> = emptyMap(), form: Map<String, String>? = null, template: Boolean = false): Pair<JSONObject?, String?> = withContext(Dispatchers.IO) {
        active(); currentCoroutineContext().ensureActive()
        val ts = (System.currentTimeMillis() / 1000).toString()
        val cookie = settings.account?.takeIf { it.source == source }?.cookie
            ?: anonymousCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val result = api.jmRequest(context, path + if (params.isEmpty()) "" else "?${query(params)}",
            md5(ts + if (template) "18comicAPPContent" else "185Hcomic3PAPP7R"), "$ts,$JM_APP_VERSION", cookie, form?.let(::query))
        val key = md5(ts + "185Hcomic3PAPP7R").toByteArray()
        try {
            active(); currentCoroutineContext().ensureActive()
            if (template) return@withContext null to result.body.toString(Charsets.UTF_8)
            val envelope = JSONObject(result.body.toString(Charsets.UTF_8))
            val code = envelope.optInt("code")
            if (code != 200) {
                if (path == "login") {
                    val message = envelope.optString("errorMsg", envelope.optString("message"))
                    val reason = when {
                        listOf("帳號不存在", "账号不存在", "用戶不存在", "用户不存在").any(message::contains) -> "账号不存在，请核对登录用户名"
                        listOf("密碼", "密码").any(message::contains) -> "账号或密码未通过验证，请核对后重试"
                        listOf("驗證碼", "验证码").any(message::contains) -> "网站要求验证码，请使用网页登录"
                        else -> "登录请求被拒绝，请核对账号或稍后重试"
                    }
                    throw IOException("JMComic $reason（$code）")
                }
                throw IOException("JMComic 未接受请求（$code），请核对账号或稍后重试")
            }
            val encrypted = Base64.getDecoder().decode(envelope.getString("data"))
            val clear = try { Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(encrypted) } }
                finally { encrypted.fill(0) }
            try {
                val json = clear.toString(Charsets.UTF_8)
                // The homepage recommendation endpoint returns an array, unlike other JM operations.
                (if (path == "promote") JSONObject().put("groups", JSONArray(json)) else JSONObject(json)) to null
            } finally { clear.fill(0) }
        } finally { result.body.fill(0); key.fill(0) }
    }
    suspend fun login(username: String, password: String): JmAccount = lock.withLock {
        val json = requireNotNull(request("login", form = mapOf("username" to username, "password" to password)).first)
        val uid = json.getString("uid"); require(jmId(uid))
        val cookies = anonymousCookies.toMutableMap().apply { putAll(responseCookies()) }
        cookies["AVS"] = json.getString("s")
        require(cookies.values.all { it.isNotBlank() && it.none { c -> c == ';' || c == '\r' || c == '\n' } })
        JmAccount(uid, json.optString("username", username), cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }
    /** Read the authenticated edit profile, not a public user page or the browser's title. */
    suspend fun verifyWebAccount(): JmAccount {
        val account = settings.account?.takeIf { it.source == source }
            ?: throw IOException("请先在网页完成登录")
        require(jmId(account.uid))
        val profile = requireNotNull(request("useredit/${account.uid}").first)
        val username = profile.optString("username").trim()
        if (username.isEmpty() || username != account.username) {
            throw IOException("网页账号与账号资料不一致，原账号保持不变")
        }
        // A profile response alone does not establish permission to read the collection.
        favorites("0", 1)
        active(); currentCoroutineContext().ensureActive()
        return account.copy(username = username)
    }
    suspend fun detail(id: String): JmBook {
        require(jmId(id))
        val data = requireNotNull(request("album", mapOf("id" to id)).first)
        val list = data.optJSONArray("series") ?: JSONArray()
        val chapters = (0 until list.length()).map { list.getJSONObject(it) }.sortedBy { it.optInt("sort", 1) }
            .map { JmChapter(it.getString("id").also { value -> require(jmId(value)) }, it.optString("name", "章节")) }
            .ifEmpty { listOf(JmChapter(id, data.getString("name"))) }
        return JmBook(id, data.getString("name"), strings(data.optJSONArray("author")).joinToString(" / "),
            data.optString("description").takeUnless { data.isNull("description") }.orEmpty(), strings(data.optJSONArray("tags")), chapters)
    }
    suspend fun search(text: String, page: Int): JmPage {
        if (jmId(text.trim())) return JmPage(listOf(detail(text.trim())), null)
        val data = requireNotNull(request("search", mapOf("search_query" to text, "page" to page.toString(), "main_tag" to "0", "o" to "mr", "t" to "a")).first)
        data.optString("redirect_aid").takeIf(::jmId)?.let { return JmPage(listOf(detail(it)), null) }
        return parsePage(data, "content", page, 80)
    }
    suspend fun recommendations(): JmPage {
        val groups = requireNotNull(request("promote").first).getJSONArray("groups")
        val books = buildList {
            for (index in 0 until groups.length()) {
                val group = groups.getJSONObject(index)
                // Other homepage blocks can contain novels, libraries or category shortcuts.
                if (group.getString("type") != "promote") continue
                val content = group.getJSONArray("content")
                for (row in 0 until content.length()) add(summary(content.getJSONObject(row)))
            }
        }.distinctBy { it.id }
        return JmPage(books, null)
    }
    suspend fun tags(): List<PrivateCatalogTag> {
        val data = requireNotNull(request("categories").first)
        if (!data.has("categories") && !data.has("blocks")) throw IOException("图源未返回分类目录，请重试")
        val categories = data.optJSONArray("categories") ?: JSONArray()
        val blocks = data.optJSONArray("blocks") ?: JSONArray()
        return buildList {
            for (index in 0 until categories.length()) {
                val row = categories.getJSONObject(index)
                val name = row.getString("name")
                val slug = row.optString("slug")
                val category = row.optString("type") != "search" && slug.isNotBlank()
                add(PrivateCatalogTag(name, if (category) slug else name, category))
            }
            for (index in 0 until blocks.length()) {
                strings(blocks.getJSONObject(index).getJSONArray("content")).forEach {
                    add(PrivateCatalogTag(it, it))
                }
            }
        }.filter { it.name.isNotBlank() && it.query.isNotBlank() }.distinct()
    }
    suspend fun catalog(page: Int, popular: Boolean = false, category: String = "0"): JmPage {
        val data = requireNotNull(request("categories/filter", mapOf(
            "page" to page.toString(), "order" to "", "c" to category, "o" to if (popular) "mv" else "mr",
        )).first)
        return parsePage(data, "content", page, 80)
    }
    suspend fun favorites(folder: String, number: Int): JmPage {
        if (settings.account?.source != source) throw IOException("请先登录 JMComic 账号")
        require(folder == "0" || jmId(folder))
        val data = requireNotNull(request("favorite", mapOf("folder_id" to folder, "page" to number.toString(), "o" to "mr")).first)
        if (!data.has("list") || !data.has("total")) throw IOException("收藏列表未返回，请重新登录")
        val folders = data.optJSONArray("folder_list") ?: JSONArray()
        return parsePage(data, "list", number, 20).copy(folders = listOf(JmFolder("0", "全部收藏")) +
            (0 until folders.length()).map { folders.getJSONObject(it).let { row -> JmFolder(row.getString("FID"), row.getString("name")) } })
    }
    private fun summary(row: JSONObject) = JmBook(row.getString("id").also { require(jmId(it)) },
        row.getString("name"), row.optString("author"))
    private fun parsePage(json: JSONObject, field: String, number: Int, size: Int): JmPage {
        val array = json.getJSONArray(field)
        val books = (0 until array.length()).map { summary(array.getJSONObject(it)) }.distinctBy { it.id }
        val total = json.optString("total").toLongOrNull()
        val more = if (total != null) number.toLong() * size < total else array.length() == size
        if (more && books.isEmpty()) throw IOException("分页未返回作品，请重试当前页")
        return JmPage(books, if (more) number + 1 else null)
    }
    suspend fun pages(id: String): JmPages {
        require(jmId(id))
        val data = requireNotNull(request("chapter", mapOf("id" to id)).first)
        require(data.getString("id") == id)
        val files = strings(data.getJSONArray("images"))
        require(files.isNotEmpty() && files.all { it.matches(Regex("[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp|gif)")) })
        val html = requireNotNull(request("chapter_view_template", mapOf("id" to id, "mode" to "vertical", "page" to "0", "app_img_shunt" to "1", "express" to "off", "v" to System.currentTimeMillis().toString()), template = true).second)
        val scramble = Regex("scramble_id\\s*=\\s*['\"]?([0-9]+)").find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw IOException("章节未返回图片重组参数，请稍后重试")
        return JmPages(id, files, scramble)
    }
    suspend fun image(path: String): ByteArray {
        active()
        val reply = images.jmRequest(context, path, "", "", "", null)
        try { active(); currentCoroutineContext().ensureActive(); require(reply.contentType?.startsWith("image/") == true) }
        catch (failure: Throwable) { reply.body.fill(0); throw failure }
        return reply.body
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        api.invalidate(); images.invalidate(); api.destroy(); images.destroy(); lifetime.cancel()
    }
}
private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
