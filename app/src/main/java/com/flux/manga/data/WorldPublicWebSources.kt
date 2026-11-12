package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** Three lazy clients, owned and invalidated by the existing world session. */
internal class WorldPublicWebSources(private val session: WorldSession) : Closeable {
    private val clients = mutableMapOf<String, PublicWebClient>()
    private val accounts = mutableMapOf<PublicWebSite, PublicWebAccountStore>()
    private lateinit var data: WorldDataStore
    @Synchronized fun configure(context: android.content.Context) {
        if (!::data.isInitialized) data = WorldDataStore(context.applicationContext)
    }
    @Synchronized fun account(site: PublicWebSite): PublicWebAccountStore {
        check(session.identity.value.world == PrototypeWorld.SFW && session.accepts(session.identity.value))
        return accounts.getOrPut(site) { PublicWebAccountStore(data, session, site) }
    }
    @Synchronized fun accountChanged(site: PublicWebSite) { clients.remove(site.sourceId)?.close() }
    @Synchronized fun client(source: String): PublicWebClient {
        val identity = session.identity.value
        if (identity.world != PrototypeWorld.SFW || !session.accepts(identity)) throw CancellationException()
        val site = PublicWebSite.of(source) ?: throw SourceException.InvalidRequest()
        return clients.getOrPut(source) { PublicWebClient(session, site, account(site)) }
    }
    @Synchronized override fun close() { clients.values.forEach { it.close() }; clients.clear(); accounts.clear() }
}

/** Protocol/selectors adapted from Keiyoushi d9420cd1 (Apache-2.0).
 * Modified 2026-11-11: local DTOs, source-scoped sessions, bounded references,
 * literal-only Nuxt, existing reader/download ownership. See public-web-NOTICE.txt. */
internal class PublicWebClient(private val session: WorldSession, private val site: PublicWebSite,
    private val accountStore: PublicWebAccountStore) : PublicBookSource, Closeable {
    private val accountRevision = accountStore.revision
    private val identity = session.identity.value
    private val context = identity.sourceContext(site.sourceId)
    private val http = SourceHttpSession.publicCatalog(context)
    @Volatile private var closed = false
    // Official reader routes share the same signed image path. Session-local only.
    @Volatile private var manhuaguiImageHost = "eu.hamreus.com"
    private val covers = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }
    private val directories = object : LinkedHashMap<String, List<BaoziChapter>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<BaoziChapter>>?) = size > 4
    }
    private data class Image(val resource: SourcePageResource, val url: String)
    private val pageSets = object : LinkedHashMap<Pair<String, String>, List<Image>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, List<Image>>?) = size > 4
    }
    private fun active() { if (closed || !session.accepts(identity) || accountStore.revision != accountRevision) throw CancellationException() }
    private fun key(id: String): MangaKey {
        if (!id.matches(Regex("[1-9][0-9]{0,15}"))) throw SourceException.InvalidRequest()
        return MangaKey(PrototypeWorld.SFW, site.sourceId, id)
    }
    private fun coreKey(id: String): SourceMangaKey { key(id); return SourceMangaKey(SourceWorld.SFW, site.sourceId, id) }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private suspend fun read(url: String): String = withContext(Dispatchers.IO) {
        ensureActive(); active()
        val account = accountStore.read()
        ensureActive(); active()
        // Credentials stay on the exact source website, never the API/CDN host.
        val reply = if (account != null && URI(url).host == URI(site.origin).host)
            http.publicCatalogAccountRead(context, url, account.cookie)
        else http.publicCatalogRead(context, url)
        ensureActive(); active()
        if (reply.context != context) throw CancellationException()
        reply.body.toString(Charsets.UTF_8).also {
            if (it.contains("cf-chl-", true) || it.contains("challenge-platform", true))
                throw IOException("${site.label}需要网站验证，请稍后重试")
        }
    }
    private suspend fun doc(path: String): Document {
        val url = URI(site.origin).resolve(path).toASCIIString()
        return Jsoup.parse(read(url), url)
    }
    private fun image(raw: String): String {
        if (raw.isBlank()) throw SourceException.NotFound()
        val url = URI(site.origin).resolve(raw.trim())
        if (url.scheme != "https" || url.userInfo != null || url.fragment != null) throw SourceException.InvalidResponse()
        return url.toASCIIString()
    }
    private fun cover(id: String, raw: String?) {
        if (raw.isNullOrBlank()) return
        val location = try { image(raw) } catch (_: Exception) { return }
        synchronized(covers) { active(); covers[id] = location }
    }
    private fun linkId(href: String): String {
        val uri = URI(site.origin).resolve(href)
        if (uri.host != URI(site.origin).host) throw SourceException.InvalidResponse()
        val id = if (site == PublicWebSite.GUAZI) {
            if (uri.path != "/comic.php") throw SourceException.InvalidResponse()
            uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") }?.substringAfter('=')
        } else Regex("/comic/([0-9]+)/?").matchEntire(uri.path)?.groupValues?.get(1)
        key(id ?: throw SourceException.InvalidResponse())
        return id
    }
    override suspend fun search(input: String, page: SourcePageRequest, tag: String?, updates: Boolean): SourceCardPage = withContext(Dispatchers.IO) {
        active(); ensureActive()
        val number = when (page) {
            SourcePageRequest.Start -> 1
            is SourcePageRequest.Page -> page.number.toInt().also { if (it !in 1..10000) throw SourceException.InvalidRequest() }
            else -> throw SourceException.InvalidRequest()
        }
        val category = tag?.let { site.tags[it] ?: throw SourceException.InvalidRequest() }
        fun next(hasMore: Boolean) = if (hasMore) SourceContinuation.Next(SourcePageRequest.Page((number + 1).toUInt())) else SourceContinuation.End
        when (site) {
            PublicWebSite.GUAZI -> {
                val document = doc("category.php?sort=${if (updates) "update" else "hits"}&page=$number" +
                    (if (input.isNotBlank()) "&keyword=${encode(input)}" else "") + (category?.let { "&cid=$it" } ?: ""))
                val cards = document.select("article.card").map { item ->
                    val a = item.selectFirst("a.cover-wrap") ?: throw SourceException.InvalidResponse()
                    val id = linkId(a.attr("href"))
                    val title = item.selectFirst("h3 a")?.text()?.takeIf(String::isNotBlank) ?: throw SourceException.InvalidResponse()
                    cover(id, item.selectFirst("img.cover")?.absUrl("src"))
                    MangaCard(key(id), title, null, true, sourceTags = tag?.let(::listOf))
                }
                if (cards.isEmpty() && document.selectFirst("nav.pager, .empty, .empty-state, .no-results") == null &&
                    !document.text().contains("暂无") && !document.text().contains("没有找到")) throw SourceException.InvalidResponse()
                SourceCardPage(cards.distinctBy { it.key }, next(document.select("nav.pager a").any { it.text() == ">" }))
            }
            PublicWebSite.KUAIKAN -> {
                val data: List<JSONObject>
                val more: Boolean
                if (input.isNotBlank()) {
                    val json = JSONObject(read("https://api.kkmh.com/v1/search/topic?q=${encode(input)}&since=${(number - 1) * 10}&size=10"))
                    val result = json.optJSONObject("data") ?: throw SourceException.InvalidResponse()
                    data = result.getJSONArray("hit").objects()
                    more = result.optInt("since", -1) >= 0 && data.isNotEmpty()
                } else {
                    val document = doc("tag/${category ?: "0"}?region=1&pays=0&state=0&sort=${if (updates) 3 else 2}&page=$number")
                    val result = publicNuxt(document).getJSONArray("data").getJSONObject(0)
                    data = result.getJSONArray("dataList").objects()
                    val last = document.selectFirst("ul.pagination li:nth-last-child(2) a")
                    more = last != null && !last.hasClass("active") && data.isNotEmpty()
                }
                SourceCardPage(data.map { item ->
                    val id = item.getString("id"); cover(id, item.optString("vertical_image_url"))
                    MangaCard(key(id), item.getString("title"), null, true,
                        author = item.optString("author_name").takeUnless { it == "null" }.orEmpty()
                            .ifBlank { item.optJSONObject("user")?.optString("nickname").orEmpty() },
                        latestChapter = item.optString("latest_comic_title").takeIf { it.isNotBlank() && it != "null" },
                        sourceTags = item.publicTags("category") ?: item.publicTags("tags") ?: tag?.let(::listOf))
                }.distinctBy { it.key }, next(more))
            }
            PublicWebSite.MANHUAGUI -> {
                val path = if (input.isNotBlank()) "s/${encode(input)}_p$number.html"
                    else "list/${category?.let { "$it/" }.orEmpty()}${if (updates) "update" else "view"}_p$number.html"
                val document = doc(path)
                val selector = if (input.isNotBlank()) "div.book-result > ul > li" else "ul#contList > li"
                val cards = document.select(selector).map { item ->
                    val a = item.selectFirst(if (input.isNotBlank()) "div.book-detail dl > dt > a" else "a.bcover") ?: throw SourceException.InvalidResponse()
                    val id = linkId(a.attr("href"))
                    val img = item.selectFirst("a.bcover img")
                    cover(id, img?.absUrl("data-src")?.takeIf(String::isNotBlank) ?: img?.absUrl("src"))
                    val title = a.attr("title").ifBlank { a.text() }
                    if (title.isBlank()) throw SourceException.InvalidResponse()
                    val date = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").find(item.text())?.value
                    MangaCard(key(id), title, null, true, updatedAt = date, sourceTags = tag?.let(::listOf))
                }
                if (cards.isEmpty() && document.selectFirst("#contList, .book-result") == null) throw SourceException.InvalidResponse()
                SourceCardPage(cards.distinctBy { it.key }, next(document.selectFirst("span.current + a") != null))
            }
        }.also { ensureActive(); active() }
    }
    override suspend fun recommendations(page: SourcePageRequest) = search("", page)

    override suspend fun detail(mangaId: String): BaoziBook = withContext(Dispatchers.IO) {
        val manga = key(mangaId)
        val title: String
        val author: String
        val summary: String
        val tags: List<String>
        val statusText: String
        var updated: String? = null
        var chapterError: String? = null
        val chapters: List<BaoziChapter>?
        when (site) {
            PublicWebSite.GUAZI -> {
                val document = doc("comic.php?id=$mangaId")
                title = document.selectFirst("div.mobile-comic-title")?.text().orEmpty()
                author = document.select("div.cinema-strip > div").firstOrNull { it.selectFirst("span")?.text() == "作者" }?.selectFirst("b")?.text().orEmpty()
                summary = document.selectFirst("p.mobile-comic-desc")?.text().orEmpty()
                tags = document.selectFirst("p.mobile-comic-tags")?.text().orEmpty().split(Regex("[\\s/·,，]+"))
                    .filter(String::isNotBlank).distinct()
                statusText = document.selectFirst("p.mobile-comic-meta")?.text().orEmpty()
                cover(mangaId, document.selectFirst("img.mobile-comic-cover")?.absUrl("src"))
                val container = document.selectFirst("section.mobile-comic-all-chapters")
                chapters = container?.select("div.mobile-chapter-grid a")?.map { a ->
                    val uri = URI(site.origin).resolve(a.attr("href"))
                    if (uri.host != URI(site.origin).host || uri.path != "/chapter.php") throw SourceException.InvalidResponse()
                    val id = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") }?.substringAfter('=') ?: throw SourceException.InvalidResponse()
                    key(id); BaoziChapter(manga, id, a.text())
                }?.distinctBy { it.id }?.reversed()
                if (chapters == null) chapterError = "瓜子漫画章节目录无法识别，请刷新重试"
            }
            PublicWebSite.KUAIKAN -> {
                val data = publicNuxt(doc("web/topic/$mangaId")).getJSONArray("data").getJSONObject(0)
                val topic = data.getJSONObject("topicInfo")
                title = topic.getString("title")
                author = topic.optJSONObject("user")?.optString("nickname").orEmpty()
                summary = topic.optString("description")
                statusText = topic.optString("update_status")
                tags = topic.publicTags("tags").orEmpty()
                cover(mangaId, topic.optString("vertical_image_url"))
                // Current topic pages use comics; retain the older comicList payload.
                // Both are chronological; directory absence must not discard readable metadata.
                val chapterData = data.optJSONArray("comics") ?: data.optJSONArray("comicList")
                chapters = chapterData?.objects()?.map { c ->
                    BaoziChapter(manga, c.getString("id").also { key(it) }, c.getString("title"))
                }?.distinctBy { it.id }
                if (chapters == null) chapterError = "快看漫画章节目录无法识别，请刷新重试"
            }
            PublicWebSite.MANHUAGUI -> {
                val document = doc("comic/$mangaId/")
                title = document.selectFirst("div.book-title > h1")?.text().orEmpty()
                author = document.select("span:contains(漫画作者) > a, span:contains(漫畫作者) > a").joinToString(" / ") { it.text() }
                summary = document.selectFirst("div#intro-all")?.text().orEmpty()
                tags = document.select("span:contains(漫画剧情) > a, span:contains(漫畫劇情) > a").map { it.text() }.distinct()
                statusText = document.selectFirst("li.status > span > span")?.text().orEmpty()
                updated = document.select("li.status span.red").map { it.text() }.firstOrNull { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }
                cover(mangaId, document.selectFirst("p.hcover > img")?.absUrl("src"))
                val sections = document.select("[id^=chapter-list-]")
                chapters = if (sections.isEmpty()) null else sections.flatMap { section ->
                    section.select("ul").flatMap { ul -> ul.select("li > a.status0").reversed() }.map { a ->
                        val path = URI(site.origin).resolve(a.attr("href"))
                        val match = Regex("/comic/$mangaId/([0-9]+)\\.html").matchEntire(path.path) ?: throw SourceException.InvalidResponse()
                        if (path.host != URI(site.origin).host) throw SourceException.InvalidResponse()
                        BaoziChapter(manga, "${section.id().removePrefix("chapter-list-")}~${match.groupValues[1]}", a.attr("title").ifBlank { a.text() })
                    }
                }.distinctBy { it.id }
                if (chapters == null) chapterError = if (document.selectFirst("#__VIEWSTATE, #erroraudit_show") != null)
                    "此作品目录受站点限制，当前公开图源不提供" else "漫画柜章节目录无法识别，请刷新重试"
            }
        }
        if (title.isBlank()) throw SourceException.InvalidResponse()
        val status = when {
            statusText.contains("完结") || statusText.contains("完結") -> "已完结"
            statusText.contains("连载") || statusText.contains("連載") -> "连载中"
            else -> null
        }
        ensureActive(); active()
        if (chapters != null) synchronized(directories) { active(); directories[mangaId] = chapters }
        BaoziBook(MangaCard(manga, title, null, true, author = author, updatedAt = updated, sourceTags = tags), author, summary,
            chapters, chapterError, tags, SourceWorkMetadata(if (author.isBlank()) emptyList() else listOf(SourceAuthor(author, null)),
                status, updated, updated?.let { SourceUpdateKind.CONTENT }, false))
    }
    override suspend fun chapterPage(mangaId: String, page: SourcePageRequest): SourceChapterPage {
        if (page != SourcePageRequest.Start) throw SourceException.InvalidRequest()
        val book = detail(mangaId)
        val chapters = book.chapters ?: throw IOException(book.chapterError ?: "章节目录不可用")
        return SourceChapterPage(context, chapters.mapIndexed { index, c -> SourceChapter(coreKey(mangaId), c.id, c.title, (index + 1).toUInt(), null) }, SourceContinuation.End, chapters.size.toUInt())
    }
    override suspend fun pages(mangaId: String, chapterId: String): SourcePages = withContext(Dispatchers.IO) {
        val directory = synchronized(directories) { directories[mangaId] } ?: detail(mangaId).chapters ?: throw SourceException.NotFound()
        if (directory.none { it.id == chapterId }) throw SourceException.NotFound()
        val urls = when (site) {
            PublicWebSite.GUAZI -> doc("chapter.php?id=$chapterId").select("section.reader-images img").map { image(it.absUrl("src")) }
            PublicWebSite.KUAIKAN -> {
                val info = publicNuxt(doc("webs/comic-next/$chapterId")).getJSONArray("data").getJSONObject(0)
                    .getJSONObject("res").getJSONObject("data").getJSONObject("comic_info")
                val images = info.optJSONArray("comic_images")?.objects().orEmpty()
                if (images.isEmpty()) throw IOException("此快看章节需要付费、登录或已受限，请在官方客户端查看")
                images.map { image(it.optString("url1280").takeIf { u -> u.isNotBlank() && u != "null" } ?: it.getString("url")) }
            }
            PublicWebSite.MANHUAGUI -> {
                val payload = manhuaguiPayload(doc("comic/$mangaId/${chapterId.substringAfter('~')}.html"))
                val files = payload.getJSONArray("files")
                val path = payload.getString("path")
                val sl = payload.getJSONObject("sl")
                List(files.length()) { image("https://i.hamreus.com$path${files.getString(it)}?e=${encode(sl.getString("e"))}&m=${encode(sl.getString("m"))}") }
            }
        }
        if (urls.isEmpty() || urls.size > 2000) throw SourceException.InvalidResponse()
        val entries = urls.mapIndexed { index, url ->
            // Signature/query rotation does not change page identity or saved progress.
            val id = MessageDigest.getInstance("SHA-256").digest("$chapterId:$index:${URI(url).rawPath}".toByteArray()).joinToString("") { "%02x".format(it) }
            Image(SourcePageResource(coreKey(mangaId), chapterId, id, (index + 1).toUInt(),
                "public-web://${site.sourceId}/$mangaId/$chapterId/$id", null, null, null, context), url)
        }
        ensureActive(); active()
        synchronized(pageSets) { active(); pageSets[mangaId to chapterId] = entries }
        SourcePages(context, entries.map { it.resource }, SourceContinuation.End)
    }
    suspend fun pageFile(page: SourcePageResource, directory: String): SourceImageFile {
        active()
        if (page.context != context || page.manga != coreKey(page.manga.mangaId)) throw SourceException.InvalidRequest()
        fun entry() = synchronized(pageSets) { pageSets[page.manga.mangaId to page.chapterId]?.firstOrNull { it.resource.id == page.id } }
        if (entry() == null) pages(page.manga.mangaId, page.chapterId)
        val selected = entry() ?: throw SourceException.NotFound()
        if (selected.resource.copy(width = page.width, height = page.height) != page) throw SourceException.InvalidRequest()
        return try { readerImageFile(selected.url, directory) } catch (error: SourceException) {
            val expired = error is SourceException.NotFound || error is SourceException.AuthenticationRequired ||
                (error is SourceException.HttpStatus && error.status.toInt() in setOf(401, 403, 404))
            if (site == PublicWebSite.GUAZI || !expired) throw error
            // Kuaikan / Manhuagui image signatures expire while a chapter stays open.
            // Refresh once, retaining the same page identity and reading position.
            pages(page.manga.mangaId, page.chapterId)
            val renewed = entry() ?: throw error
            if (renewed.resource.copy(width = page.width, height = page.height) != page || renewed.url == selected.url) throw error
            readerImageFile(renewed.url, directory)
        }
    }
    private suspend fun readerImageFile(url: String, directory: String): SourceImageFile {
        if (site != PublicWebSite.MANHUAGUI) return imageFile(url, directory)
        val hosts = listOf("eu.hamreus.com", "us.hamreus.com", "i.hamreus.com")
        val original = URI(url)
        if (original.host !in hosts) return imageFile(url, directory)
        val routes = (listOf(manhuaguiImageHost) + hosts).distinct()
        for ((index, host) in routes.withIndex()) {
            currentCoroutineContext().ensureActive(); active()
            // Replace only the authority; preserve the signed path/query byte for byte.
            val routed = url.replaceFirst("https://${original.rawAuthority}/", "https://$host/")
            try {
                return imageFile(routed, directory).also { manhuaguiImageHost = host }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive(); active()
                val routeFailure = failure is SourceException.Network || failure is SourceException.Timeout ||
                    failure is SourceException.Transport || failure is SourceException.NotFound ||
                    (failure is SourceException.HttpStatus && failure.status.toInt() in 500..599)
                if (!routeFailure || index == routes.lastIndex) throw failure
            }
        }
        error("No Manhuagui image route")
    }
    suspend fun coverFile(id: String, directory: String): SourceImageFile {
        if (synchronized(covers) { covers[id] } == null) detail(id)
        return imageFile(synchronized(covers) { covers[id] } ?: throw SourceException.NotFound(), directory)
    }
    private suspend fun imageFile(url: String, directory: String): SourceImageFile {
        active(); currentCoroutineContext().ensureActive()
        val file = http.publicCatalogImage(context, url, directory)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch (e: Exception) { file.close(); throw e }
    }
    override fun close() {
        if (closed) return
        closed = true; http.invalidate(); http.destroy()
        synchronized(covers) { covers.clear() }; synchronized(directories) { directories.clear() }; synchronized(pageSets) { pageSets.clear() }
    }
}

/** These fields are literal tag names, not the smaller navigation-category directory. */
private fun JSONObject.publicTags(field: String): List<String>? = optJSONArray(field)?.let { values ->
    (0 until values.length()).mapNotNull { (values.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }.distinct()
}
