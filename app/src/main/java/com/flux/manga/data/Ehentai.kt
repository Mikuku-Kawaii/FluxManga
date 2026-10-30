package com.flux.manga.data

import android.content.Context
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

internal const val EH_SOURCE = "ehentai-public"
internal fun isEhSource(source: String) = source == EH_SOURCE || source.matches(Regex("ehentai-user-[1-9][0-9]*"))
internal fun isExSource(source: String) = source.matches(Regex("exhentai-user-[1-9][0-9]*"))
internal enum class GallerySite(val label: String, val host: String, val prefix: String) {
    EH("E-Hentai", "e-hentai.org", "ehentai"), EX("ExHentai", "exhentai.org", "exhentai");
    val base get() = "https://$host/"
    val emptySource get() = if (this == EH) EH_SOURCE else "exhentai-unconfigured"
    fun source(account: EhAccount?) = account?.let { "$prefix-user-${it.uid}" } ?: emptySource
    fun accepts(source: String) = if (this == EH) isEhSource(source) else isExSource(source)
    companion object { fun forSource(source: String) = if (isExSource(source)) EX else EH }
}
internal fun ehId(id: String) = id.matches(Regex("[1-9][0-9]*-[0-9a-f]{10}"))
internal data class EhAccount(val uid: String, val hash: String, val name: String, val igneous: String = "") {
    val source get() = "ehentai-user-$uid"
    val cookie get() = "ipb_member_id=$uid; ipb_pass_hash=$hash; nw=1"
}
internal data class EhSettings(val account: EhAccount? = null, val original: Boolean = false)
internal data class EhBook(val id: String, val title: String, val cover: String = "", val subtitle: String = "",
    val uploader: String = "", val category: String = "", val pages: Int = 0, val tags: List<String> = emptyList())
internal data class EhFolder(val id: String, val title: String)
internal data class EhBatch(val books: List<EhBook>, val next: String?, val previous: String?, val folders: List<EhFolder>)
internal data class EhPage(val ordinal: Int, val url: String)
internal data class EhRemoteFavorite(val category: Int?, val note: String, val folders: List<EhFolder>)
internal data class EhComment(val id: String, val author: String, val posted: String, val body: String, val score: String, val edited: String,
    val editable: Boolean = false, val voteUp: Boolean = false, val voteDown: Boolean = false, val vote: Int = 0)
internal data class EhComments(val title: String, val entries: List<EhComment>, val more: Boolean, val canPost: Boolean = false)
internal data class EhCommentVote(val id: String, val score: Int, val vote: Int)
internal fun ehFailure(error: Exception, site: GallerySite = GallerySite.EH) = com.flux.manga.data.sourceNetworkFailure(error) ?: when (error) {
    is IOException -> error.message ?: "${site.label} 请求失败"
    is SourceException.AuthenticationRequired -> "${site.label} 会话已过期或当前权限不足，请检查该站 Cookie"
    is SourceException.RateLimited -> "服务请求限流，请稍后重试"
    is SourceException.NotFound -> "画廊或图片已不可用"
    is SourceException.Timeout -> "${site.label} 请求超时，请重试"
    else -> "${site.label} 加载失败，请重试。"
}

internal class EhStore(context: Context, private val session: WorldSession, private val site: GallerySite = GallerySite.EH) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity)
    suspend fun settings(): EhSettings {
        val bytes = data.session(identity.world, "${site.prefix}-settings-v1", ::active) ?: return EhSettings()
        return try { val json = JSONObject(bytes.toString(Charsets.UTF_8)); val a = json.optJSONObject("account")
            EhSettings(a?.let { EhAccount(it.getString("uid"), it.getString("hash"), it.getString("name"), if (site == GallerySite.EX) it.getString("igneous") else "").also { account -> ehCredential(account, site) } }, json.optBoolean("original"))
        } finally { bytes.fill(0) }
    }
    suspend fun save(value: EhSettings) {
        val json = JSONObject().put("original", value.original)
        value.account?.let {
            ehCredential(it, site)
            val account = JSONObject().put("uid", it.uid).put("hash", it.hash).put("name", it.name)
            if (site == GallerySite.EX) account.put("igneous", it.igneous)
            json.put("account", account)
        }
        val bytes = json.toString().toByteArray()
        try { data.putSession(identity.world, "${site.prefix}-settings-v1", bytes, ::active) } finally { bytes.fill(0) }
    }
    suspend fun remember(source: String, book: EhBook, favorite: Boolean? = null) = data.worldData(identity.world, ::active) { dao ->
        require(site.accepts(source) && ehId(book.id))
        val old = dao.onlineBook(source, book.id)
        dao.putOnlineBook(OnlineBook(source, book.id, book.title, favorite ?: old?.favorite ?: false, System.currentTimeMillis()))
    }
    suspend fun importPage(source: String, books: List<EhBook>) = data.worldData(identity.world, ::active) { dao ->
        require(site.accepts(source) && source != EH_SOURCE)
        var added = 0
        books.forEach { book -> if (dao.onlineBook(source, book.id) == null) {
            dao.putOnlineBook(OnlineBook(source, book.id, book.title, true, System.currentTimeMillis())); added++
        } }; added
    }
}
private fun ehCredential(account: EhAccount, site: GallerySite) {
    require(account.uid.matches(Regex("[1-9][0-9]*")) && account.hash.matches(Regex("[0-9a-fA-F]{32}"))) { "Cookie 格式无效" }
    if (site == GallerySite.EX) require(account.igneous.length in 1..4096 && account.igneous.all { it.code in 0x21..0x7e && it !in "\";,\\" }) { "请填写有效的 EX igneous Cookie" }
}

/** Apache-2.0 selectors adapted from the fixed files listed in ehentai-NOTICE.txt and exhentai-NOTICE.txt. */
internal class EhClient(private val session: WorldSession, val account: EhAccount?, owner: CoroutineScope, val site: GallerySite = GallerySite.EH) : Closeable {
    val source = site.source(account)
    private val identity = session.identity.value
    private val context = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    private val native = run {
        require(identity.world == PrototypeWorld.NSFW)
        if (site == GallerySite.EX && account == null) throw IOException("请先独立配置 ExHentai Cookie")
        account?.let { ehCredential(it, site) }
        if (site == GallerySite.EX) SourceHttpSession.ex(context) else SourceHttpSession.eh(context)
    }
    private val closed = AtomicBoolean()
    private val lifetime = Job(requireNotNull(owner.coroutineContext[Job]))
    init {
        lifetime.invokeOnCompletion { release() }
        CoroutineScope(owner.coroutineContext + lifetime).launch {
            combine(session.identity, session.state) { _, _ -> session.accepts(identity) }.collect { if (!it) close() }
        }
    }
    private fun release() { if (closed.compareAndSet(false, true)) { native.invalidate(); native.destroy() } }
    override fun close() { release(); lifetime.cancel() }
    private fun active() { if (closed.get() || !session.accepts(identity) || !session.state.value.foreground) throw CancellationException("Expired EH session") }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private suspend fun document(url: String): Document = withContext(Dispatchers.IO) {
        active(); val reply = native.ehRead(context, url, cookie(url), false)
        replyDocument(url, reply)
    }
    private suspend fun replyDocument(url: String, reply: SourceHttpReply): Document {
        try {
            currentCoroutineContext().ensureActive(); active()
            val body = reply.body.toString(Charsets.UTF_8)
            if (site == GallerySite.EX && URI(url).host == site.host && (body.isBlank() || reply.contentType?.startsWith("image/") == true || body.contains("/img/kokomade.jpg"))) {
                throw IOException("EX 登录失效或权限不足，请检查 igneous。")
            }
            val doc = Jsoup.parse(body, url)
            val text = doc.text()
            if (text.contains("This page requires you to log on", true)) throw IOException("当前列表需要有效账号 Cookie")
            if (text.contains("temporarily banned", true) || text.contains("exceeded your image", true)) throw IOException("服务访问或图片配额受限，请稍后再试")
            if (doc.selectFirst("#challenge-form, #cf-challenge-running") != null || doc.title().contains("Just a moment", true)) throw IOException("服务要求浏览器验证，当前请求未完成")
            return doc
        } finally { reply.body.fill(0) }
    }
    suspend fun verifyAccount(): EhAccount {
        val current = requireNotNull(account)
        val doc = document("https://forums.e-hentai.org/")
        val profile = doc.select("#userlinks a[href]").firstOrNull { link ->
            val uri = runCatching { URI(link.absUrl("href")) }.getOrNull()
            uri?.host == "forums.e-hentai.org" && uri.rawQuery?.split('&')?.contains("showuser=${current.uid}") == true
        } ?: throw IOException("无法确认当前账号身份，请检查 Cookie 或在浏览器重新登录")
        if (site == GallerySite.EX) browse(false, "", "all", null)
        browse(true, "", "all", null) // Each site's own response is required, never EH-as-EX.
        return current.copy(name = profile.text().ifBlank { "账号 ${current.uid}" })
    }
    private fun gallery(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != site.host || uri.userInfo != null) return null
        return Regex("/g/([1-9][0-9]*)/([0-9a-f]{10})/?").matchEntire(uri.path)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
    }
    private fun pageLink(url: String, id: String): EhPage? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != site.host || uri.userInfo != null) return null
        val match = Regex("/s/[0-9a-f]+/([1-9][0-9]*)-([1-9][0-9]*)").matchEntire(uri.path) ?: return null
        require(match.groupValues[1] == id.substringBefore('-')) { "图片页不属于当前画廊" }
        return EhPage(match.groupValues[2].toInt(), url)
    }
    private fun galleryUrl(id: String): String { require(ehId(id)); return "${site.base}g/${id.substringBefore('-')}/${id.substringAfter('-')}/" }
    private fun cursor(raw: String, favorites: Boolean): String {
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host == site.host && uri.userInfo == null && uri.fragment == null &&
            uri.port in listOf(-1, 443) && uri.path == (if (favorites) "/favorites.php" else "/")) { "列表游标不属于当前来源" }
        return raw
    }
    suspend fun browse(favorites: Boolean, query: String, folder: String, next: String?): EhBatch {
        if (favorites && account == null) throw IOException("云收藏需要登录")
        require(folder == "all" || folder in (0..9).map(Int::toString))
        val url = next?.let { cursor(it, favorites) } ?: if (favorites) "${site.base}favorites.php?favcat=$folder&f_search=${encode(query)}" else "${site.base}?f_search=${encode(query)}"
        val doc = document(url)
        val containers = doc.select(".fp")
        val folders = if (favorites) {
            if (containers.size != 11) throw IOException("未能读取账号收藏分类，请检查登录状态")
            containers.take(10).mapIndexed { index, element -> EhFolder(index.toString(), element.children().getOrNull(2)?.text()?.takeIf(String::isNotBlank)
                ?: throw IOException("收藏分类结构已变化")) }
        } else emptyList()
        val table = doc.selectFirst(".itg")
        if (table == null && !doc.text().contains("No hits found", true)) throw IOException("列表未返回可识别结果，请重试或检查服务访问状态")
        val rows = table?.select("a[href]")?.mapNotNull { link ->
            val id = gallery(link.absUrl("href")) ?: return@mapNotNull null
            val title = link.selectFirst(".glink")?.text() ?: link.ownText()
            title.takeIf(String::isNotBlank)?.let { EhBook(id, it) }
        }?.distinctBy { it.id }.orEmpty()
        if (rows.isEmpty() && table?.select("a[href*=/g/]")?.isNotEmpty() == true) throw IOException("作品标题解析失败，未导入任何记录")
        fun link(selector: String) = doc.selectFirst(selector)?.takeIf { it.hasAttr("href") && it.attr("href").isNotBlank() }?.absUrl("href")?.let { cursor(it, favorites) }
        return EhBatch(rows, link("#unext") ?: link(".ptt tr:first-child td:last-child a[href]"),
            link("#uprev") ?: link(".ptt tr:first-child td:first-child a[href]"), folders)
    }
    private fun parseDetail(id: String, doc: Document): EhBook {
        val title = doc.selectFirst("#gn")?.text()?.takeIf(String::isNotBlank) ?: throw IOException("画廊不可访问或详情结构已变化")
        val length = doc.select("#gdd tr").firstOrNull { it.selectFirst("td")?.text()?.startsWith("Length") == true }?.select("td")?.lastOrNull()?.text()
        val pages = length?.substringBefore(' ')?.replace(",", "")?.toIntOrNull() ?: throw IOException("无法读取画廊页数")
        require(pages in 1..10000) { "画廊页数超过当前阅读器范围" }
        val style = doc.selectFirst("#gd1 [style]")?.attr("style").orEmpty()
        val cover = Regex("url\\(['\"]?([^)'\"]+)['\"]?\\)").find(style)?.groupValues?.get(1).orEmpty()
        val tags = doc.select("#taglist tr").flatMap { row ->
            val cells = row.select("td"); val namespace = cells.firstOrNull()?.text()?.trim()?.removeSuffix(":").orEmpty()
            cells.getOrNull(1)?.select("a")?.map { "$namespace:${it.text().trim()}" }.orEmpty()
        }.distinct()
        val resolvedCover = cover.takeIf(String::isNotBlank)?.let { URI(doc.baseUri()).resolve(it).toString() }.orEmpty()
        return EhBook(id, title, resolvedCover, doc.selectFirst("#gj")?.text().orEmpty(), doc.selectFirst("#gdn")?.text().orEmpty(),
            doc.selectFirst("#gdc")?.text().orEmpty(), pages, tags)
    }
    suspend fun detail(id: String) = parseDetail(id, document(galleryUrl(id)))
    private fun favoriteUrl(id: String): String {
        require(ehId(id))
        return "${site.base}gallerypopups.php?gid=${id.substringBefore('-')}&t=${id.substringAfter('-')}&act=addfav"
    }
    suspend fun remoteFavorite(id: String): EhRemoteFavorite = withContext(Dispatchers.IO) {
        if (account == null) throw IOException("网站收藏需要登录此来源所属账号")
        val detail = document(galleryUrl(id))
        val status = detail.selectFirst("#gdf")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: throw IOException("无法确认当前网站收藏状态，未修改任何收藏")
        val popup = document(favoriteUrl(id))
        val noteField = popup.selectFirst("textarea[name=favnote], input[name=favnote]")
            ?: throw IOException("未读取到网站原备注，已停止以免覆盖")
        val note = if (noteField.tagName() == "textarea") noteField.wholeText() else noteField.attr("value")
        val choices = popup.select("input[name=favcat], select[name=favcat] option")
        val categories = choices.map { it.`val`() }.filter { it in (0..9).map(Int::toString) }.toSet()
        if (categories.size != 10) throw IOException("网站收藏表单已变化，未修改收藏")
        val selected = choices.firstOrNull { it.hasAttr("checked") || it.hasAttr("selected") }?.`val`()
        val category = if (status.equals("Add to Favorites", true)) null else {
            selected?.toIntOrNull()?.takeIf { it in 0..9 } ?: throw IOException("无法确认原收藏分类，未修改收藏")
        }
        val folders = browse(true, "", "all", null).folders
        EhRemoteFavorite(category, note, folders)
    }
    suspend fun writeRemoteFavorite(id: String, category: Int?, note: String) = withContext(Dispatchers.IO) {
        require(ehId(id) && account != null && (category == null || category in 0..9) && note.length <= 250)
        active()
        val reply = native.ehFavorite(context, id.substringBefore('-').toULong(), id.substringAfter('-'), category ?: -1, note, cookie(favoriteUrl(id)))
        // POST acknowledgement is not proof. The caller must read the site's state again.
        reply.body.fill(0)
        currentCoroutineContext().ensureActive(); active()
    }
    suspend fun comments(id: String, all: Boolean): EhComments = withContext(Dispatchers.IO) {
        parseComments(document(galleryUrl(id) + if (all) "?hc=1" else ""))
    }
    private fun parseComments(doc: Document): EhComments {
        val title = doc.selectFirst("#gn")?.text()?.takeIf(String::isNotBlank) ?: throw IOException("画廊不可访问")
        val area = doc.selectFirst("#cdiv") ?: throw IOException("未返回可识别评论区，请重试")
        val entries = area.select(".c1").mapIndexed { index, element ->
            val header = element.selectFirst(".c3") ?: throw IOException("评论信息结构已变化")
            val body = element.selectFirst(".c6") ?: throw IOException("评论正文结构已变化")
            val plain = body.clone().apply { select("script, style").remove(); select("br").append("\n") }.wholeText().trim()
            val id = element.previousElementSibling()?.attr("name")?.takeIf { it.matches(Regex("c[1-9][0-9]*")) }
            val actions = element.selectFirst(".c4")?.children().orEmpty()
            val up = actions.firstOrNull { it.text() == "Vote+" }; val down = actions.firstOrNull { it.text() == "Vote-" }
            val signed = account != null && id != null
            EhComment(id ?: "row-$index",
                header.children().firstOrNull()?.text().orEmpty(), header.ownText(),
                plain.ifBlank { "此评论没有文字内容" }, element.selectFirst(".c5")?.text().orEmpty(), element.selectFirst(".c8")?.text().orEmpty(),
                signed && actions.any { it.text() == "Edit" }, signed && up != null, signed && down != null,
                if (up?.attr("style")?.isNotBlank() == true) 1 else if (down?.attr("style")?.isNotBlank() == true) -1 else 0)
        }
        val more = area.selectFirst("#chd")?.text()?.contains("click to show all", true) == true
        return EhComments(title, entries, more, account != null && doc.selectFirst("textarea[name=commenttext_new]") != null)
    }
    private suspend fun commentApi(id: String, comment: String, method: String, vote: Int = 0): JSONObject = withContext(Dispatchers.IO) {
        val current = account ?: throw IOException("评论操作需要登录原账号")
        require(comment.matches(Regex("c[1-9][0-9]*")))
        val doc = document(galleryUrl(id))
        val script = doc.select("script").joinToString("\n") { it.data() }
        fun constant(name: String, quoted: Boolean) = Regex(if (quoted) "var\\s+$name\\s*=\\s*\"([a-f0-9]+)\"" else "var\\s+$name\\s*=\\s*(-?[0-9]+)").find(script)?.groupValues?.get(1)
        if (constant("gid", false) != id.substringBefore('-') || constant("token", true) != id.substringAfter('-') || constant("apiuid", false) != current.uid)
            throw IOException("无法确认评论操作权限，请检查当前账号")
        val apiKey = constant("apikey", true) ?: throw IOException("页面没有提供评论操作凭据")
        val url = "${site.base}api.php"
        val reply = native.ehCommentApi(context, id.substringBefore('-').toULong(), id.substringAfter('-'), current.uid.toULong(),
            apiKey, comment.removePrefix("c").toULong(), method, vote, cookie(url))
        try {
            currentCoroutineContext().ensureActive(); active()
            val json = JSONObject(reply.body.toString(Charsets.UTF_8))
            if (json.has("error")) throw IOException(json.getString("error"))
            if (json.getLong("comment_id").toString() != comment.removePrefix("c")) throw IOException("评论回执不属于当前记录")
            json
        } finally { reply.body.fill(0) }
    }
    suspend fun editableComment(id: String, comment: String): String {
        val json = commentApi(id, comment, "geteditcomment")
        return Jsoup.parseBodyFragment(json.getString("editable_comment")).selectFirst("textarea[name=commenttext_edit]")?.wholeText()
            ?: throw IOException("未返回原始评论文本，不能以阅读视图覆盖")
    }
    suspend fun postComment(id: String, comment: String?, text: String): EhComments = withContext(Dispatchers.IO) {
        require(account != null && text.isNotBlank() && text.length <= 10_000)
        require(comment == null || comment.matches(Regex("c[1-9][0-9]*")))
        active()
        val url = galleryUrl(id) + "?hc=1"
        val reply = native.ehComment(context, id.substringBefore('-').toULong(), id.substringAfter('-'), comment?.removePrefix("c")?.toULong(), text, cookie(url))
        val doc = replyDocument(url, reply)
        doc.selectFirst("#chd + p")?.text()?.takeIf(String::isNotBlank)?.let { throw IOException(it) }
        parseComments(doc)
    }
    suspend fun voteComment(id: String, comment: String, direction: Int): EhCommentVote {
        require(direction == 1 || direction == -1)
        val json = commentApi(id, comment, "votecomment", direction)
        val vote = json.getInt("comment_vote"); require(vote in -1..1)
        return EhCommentVote(comment, json.getInt("comment_score"), vote)
    }
    suspend fun pages(id: String): List<EhPage> {
        val root = galleryUrl(id); var doc = document(root); val count = parseDetail(id, doc).pages
        val result = sortedMapOf<Int, EhPage>(); var index = 0
        while (true) {
            currentCoroutineContext().ensureActive(); active()
            val refs = doc.select("#gdt a[href]").mapNotNull { pageLink(it.absUrl("href"), id) }.distinctBy { it.ordinal }
            if (refs.isEmpty()) throw IOException("画廊未返回图片页目录")
            val oldSize = result.size
            refs.forEach { page -> require(page.ordinal <= count); result[page.ordinal] = page }
            if (result.size == count) break
            if (result.size == oldSize) throw IOException("画廊分页没有前进，请重新加载")
            index++
            val next = doc.select(".ptt a[href]").firstOrNull { anchor ->
                val url = URI(anchor.absUrl("href"))
                gallery(url.toString()) == id && url.rawQuery?.split('&')?.contains("p=$index") == true
            }?.absUrl("href") ?: throw IOException("画廊目录尚未完整，未返回下一页")
            doc = document(next)
        }
        require(result.keys.toList() == (1..count).toList()) { "图片页序不连续" }
        return result.values.toList()
    }
    suspend fun pageImage(page: EhPage, original: Boolean): ByteArray {
        val doc = document(page.url)
        val displayed = doc.selectFirst("img#img")?.absUrl("src")?.takeIf(String::isNotBlank)
            ?: throw IOException("正文图片不可用，请检查配额或重新载入")
        if (displayed.contains("509.gif") || displayed.contains("509.png")) throw IOException("图片配额已用尽，请稍后再试")
        if (displayed.contains("/img/kokomade.jpg") || displayed.contains("sadpanda")) throw IOException("${site.label} 拒绝当前图片访问，请检查会话与权限")
        val originalLink = doc.select("a[href]").firstOrNull { runCatching { val uri = URI(it.absUrl("href")); uri.host == site.host && uri.path == "/fullimg.php" }.getOrDefault(false) }?.absUrl("href")
        if (original && originalLink == null) throw IOException("此页未提供可访问原图；可在图源设置使用站点图片")
        return image(if (original) requireNotNull(originalLink) else displayed)
    }
    suspend fun image(url: String): ByteArray {
        active(); val reply = native.ehRead(context, url, cookie(url), true)
        try { currentCoroutineContext().ensureActive(); active()
            if (reply.contentType?.startsWith("image/") != true) throw IOException("图片返回了非图像内容，可能需要权限或配额")
        } catch (error: Throwable) { reply.body.fill(0); throw error }
        return reply.body
    }
    private fun cookie(url: String): String {
        val host = URI(url).host
        if (host != site.host && host != "forums.e-hentai.org") return ""
        val identity = account?.cookie ?: "nw=1"
        return if (site == GallerySite.EX && host == site.host) "$identity; igneous=${requireNotNull(account).igneous}" else identity
    }
}
