package com.flux.manga.data

import android.content.Context
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun picaId(value: String) = value.matches(Regex("[0-9a-f]{24}"))
internal fun isPicaSource(source: String) = source.startsWith("picacg-user-") && picaId(source.removePrefix("picacg-user-"))
internal fun isPrivateComicSource(source: String) = isJmSource(source) || isPicaSource(source) || isEhSource(source) || isExSource(source)
internal data class PicaAccount(val uid: String, val name: String, val token: String) { val source get() = "picacg-user-$uid" }
internal data class PicaBook(val id: String, val title: String, val cover: String, val author: String = "", val artist: String = "",
    val description: String = "", val tags: List<String> = emptyList(), val categories: List<String> = emptyList(), val finished: Boolean = false,
    val updatedAt: String = "")
internal data class PicaChapter(val order: Int, val title: String, val updatedAt: String = "")
internal data class PicaImage(val id: String, val ordinal: Int, val url: String)
internal data class PicaPage<T>(val items: List<T>, val page: Int, val next: Int?)
internal fun picaFailure(error: Exception) = when (error) {
    is SourceException.AuthenticationRequired -> "登录已失效或访问受限，请在图源管理重新登录"
    is SourceException.Timeout -> "PicACG 请求超时，请重试"
    is SourceException.RateLimited -> "PicACG 请求过于频繁，请稍后重试"
    is SourceException.NotFound -> "作品或章节不存在"
    is IOException -> error.message ?: "PicACG 请求失败"
    else -> "PicACG 请求或解析失败，可重试；已有记录保留"
}

internal class PicaStore(context: Context, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity)
    suspend fun account(): PicaAccount? {
        val bytes = data.session(identity.world, "picacg-account-v1", ::active) ?: return null
        return try { JSONObject(bytes.toString(Charsets.UTF_8)).let {
            PicaAccount(it.getString("uid"), it.getString("name"), it.getString("token")).also { account -> require(picaId(account.uid) && account.token.isNotBlank()) }
        } } finally { bytes.fill(0) }
    }
    suspend fun save(account: PicaAccount?) {
        val bytes = account?.let { JSONObject().put("uid", it.uid).put("name", it.name).put("token", it.token).toString().toByteArray() }
        try { data.putSession(identity.world, "picacg-account-v1", bytes, ::active) } finally { bytes?.fill(0) }
    }
    suspend fun remember(source: String, book: PicaBook, favorite: Boolean? = null) = data.worldData(identity.world, ::active) { dao ->
        require(isPicaSource(source))
        val old = dao.onlineBook(source, book.id)
        dao.putOnlineBook(OnlineBook(source, book.id, book.title, favorite ?: old?.favorite ?: false, System.currentTimeMillis()))
    }
    suspend fun importPage(source: String, books: List<PicaBook>) = data.worldData(identity.world, ::active) { dao ->
        require(isPicaSource(source))
        var added = 0
        books.forEach { book -> if (dao.onlineBook(source, book.id) == null) {
            dao.putOnlineBook(OnlineBook(source, book.id, book.title, true, System.currentTimeMillis())); added++
        } }
        added
    }
}

/** Pinned Apache-2.0/MIT protocol adaptation; see picacg-NOTICE.txt. */
internal class PicaClient(private val session: WorldSession, private val account: PicaAccount?, owner: CoroutineScope) : Closeable {
    val source = account?.source ?: "picacg-login"
    private val identity = session.identity.value
    private val context = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    private val native = SourceHttpSession.pica(context)
    private val closed = AtomicBoolean()
    private val lifetime = Job(requireNotNull(owner.coroutineContext[Job]))
    init {
        require(identity.world == PrototypeWorld.NSFW)
        lifetime.invokeOnCompletion { release() }
        CoroutineScope(owner.coroutineContext + lifetime).launch {
            combine(session.identity, session.state) { _, _ -> session.accepts(identity) }.collect { if (!it) close() }
        }
    }
    private fun release() { if (closed.compareAndSet(false, true)) { native.invalidate(); native.destroy() } }
    override fun close() { release(); lifetime.cancel() }
    private fun active() { if (closed.get() || !session.accepts(identity) || !session.state.value.foreground) throw CancellationException("Expired PicACG session") }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private suspend fun request(path: String, body: JSONObject? = null, token: String? = account?.token): JSONObject = withContext(Dispatchers.IO) {
        active()
        if (path != "auth/sign-in" && token.isNullOrBlank()) throw IOException("请先登录 PicACG 账号")
        val time = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val method = if (body == null) "GET" else "POST"
        val raw = (path + time + nonce + method + "C69BAF41DA5ABD1FFEDC6D2FEA56B").lowercase(Locale.ROOT)
        val key = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn".toByteArray()
        val signature = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(raw.toByteArray()) }
            .joinToString("") { "%02x".format(it) }
        val reply = native.picaRequest(context, path, token.orEmpty(), time, nonce, signature, body?.toString())
        try {
            currentCoroutineContext().ensureActive(); active()
            val json = JSONObject(reply.body.toString(Charsets.UTF_8))
            if (json.getInt("code") !in 200..299) throw IOException("PicACG 未接受请求，请核对账号或稍后重试")
            json.getJSONObject("data")
        } finally { reply.body.fill(0) }
    }
    suspend fun login(email: String, password: String): PicaAccount {
        val token = request("auth/sign-in", JSONObject().put("email", email.trim()).put("password", password), null).getString("token")
        return verifyToken(token)
    }
    suspend fun verifyToken(token: String): PicaAccount {
        require(token.isNotBlank() && token.length <= 4096 && token.all { it.code in 33..126 })
        val profile = request("users/profile", token = token).getJSONObject("user")
        return PicaAccount(profile.getString("_id").also { require(picaId(it)) }, profile.getString("name"), token)
    }
    private fun image(value: JSONObject): String {
        val server = URI(value.getString("fileServer"))
        val path = value.getString("path")
        require(server.scheme == "https" && server.host != null && server.userInfo == null && server.query == null && server.fragment == null)
        require(path.isNotBlank() && path.split('/').none { it.isEmpty() || it == "." || it == ".." } && path.none { it == '\\' || it == '?' || it == '#' })
        val url = server.toString().trimEnd('/') + "/static/" + path.split('/').joinToString("/") { encode(it) }
        return url
    }
    private fun book(row: JSONObject) = PicaBook(row.getString("_id").also { require(picaId(it)) }, row.getString("title"),
        image(row.getJSONObject("thumb")), row.text("author"), row.text("artist"), row.text("description"),
        row.strings("tags"), row.strings("categories"), row.optBoolean("finished"), row.text("update_at"))
    private fun <T> page(value: JSONObject, number: Int, parse: (JSONObject, Int) -> T): PicaPage<T> {
        val actual = value.getInt("page"); val last = value.getInt("pages")
        require(actual == number && number > 0 && last >= 0)
        val docs = value.getJSONArray("docs")
        if (docs.length() == 0 && actual < last) throw IOException("分页未返回内容，请重试当前页")
        return PicaPage((0 until docs.length()).map { parse(docs.getJSONObject(it), it) }, actual, if (actual < last) actual + 1 else null)
    }
    suspend fun search(query: String, number: Int) = page(request("comics/advanced-search?page=$number",
        JSONObject().put("keyword", query).put("sort", "dd")).getJSONObject("comics"), number) { row, _ -> book(row) }
    suspend fun favorites(number: Int) = page(request("users/favourite?page=$number&s=dd").getJSONObject("comics"), number) { row, _ -> book(row) }
    suspend fun catalog(category: String, number: Int) = page(request("comics?page=$number&s=dd" +
        if (category.isBlank()) "" else "&c=${encode(category)}").getJSONObject("comics"), number) { row, _ -> book(row) }
    suspend fun categories(): List<String> = request("categories").getJSONArray("categories").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("title") }.distinct()
    }
    suspend fun recommendations(): PicaPage<PicaBook> {
        val groups = request("collections").getJSONArray("collections")
        val books = buildList {
            for (index in 0 until groups.length()) {
                val comics = groups.getJSONObject(index).getJSONArray("comics")
                for (row in 0 until comics.length()) add(book(comics.getJSONObject(row)))
            }
        }.distinctBy { it.id }
        return PicaPage(books, 1, null)
    }
    suspend fun rank(period: String): PicaPage<PicaBook> {
        require(period in listOf("H24", "D7", "D30"))
        val array = request("comics/leaderboard?tt=$period&ct=VC").getJSONArray("comics")
        return PicaPage((0 until array.length()).map { book(array.getJSONObject(it)) }, 1, null)
    }
    suspend fun detail(id: String): PicaBook {
        require(picaId(id)); return book(request("comics/$id").getJSONObject("comic")).also { require(it.id == id) }
    }
    suspend fun chapters(id: String, number: Int): PicaPage<PicaChapter> {
        require(picaId(id))
        return page(request("comics/$id/eps?page=$number").getJSONObject("eps"), number) { row, _ ->
            PicaChapter(row.getInt("order").also { require(it > 0) }, row.text("title"), row.text("updated_at"))
        }
    }
    suspend fun allChapters(id: String): List<PicaChapter> {
        val rows = mutableListOf<PicaChapter>()
        var number = 1
        do {
            val page = chapters(id, number); rows += page.items
            require(rows.size <= 10000) { "章节目录超过当前阅读器范围" }
            number = page.next ?: break
        } while (true)
        require(rows.map { it.order }.distinct().size == rows.size) { "章节目录重复，请重新载入" }
        return rows.sortedBy { it.order }
    }
    suspend fun pages(id: String, order: Int): List<PicaImage> {
        require(picaId(id) && order > 0)
        val rows = mutableListOf<PicaImage>()
        var number = 1
        do {
            currentCoroutineContext().ensureActive(); active()
            val data = request("comics/$id/order/$order/pages?page=$number").getJSONObject("pages")
            val limit = data.getInt("limit").also { require(it > 0) }
            val result = page(data, number) { row, index ->
                val ordinal = Math.addExact(Math.multiplyExact(number - 1, limit), index + 1)
                val item = row.getJSONObject("media")
                val name = item.getString("path")
                PicaImage(java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }, ordinal, image(item))
            }
            rows += result.items
            number = result.next ?: break
        } while (true)
        if (rows.isEmpty()) throw IOException("此章节没有正文图片")
        require(rows.map { it.id }.distinct().size == rows.size && rows.map { it.ordinal } == (1..rows.size).toList()) { "正文分页不连续，请重新加载章节" }
        return rows
    }
    suspend fun image(url: String): ByteArray {
        active()
        val reply = native.picaImage(context, url)
        try { currentCoroutineContext().ensureActive(); active(); require(reply.contentType?.startsWith("image/") == true) }
        catch (error: Throwable) { reply.body.fill(0); throw error }
        return reply.body
    }
}
private fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)
private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
