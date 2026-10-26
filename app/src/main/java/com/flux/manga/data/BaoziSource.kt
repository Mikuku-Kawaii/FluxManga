package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object BaoziEndpoint {
    const val SOURCE_ID = "baozimh-cn-v1"
    const val BASE_URL = "https://cn.baozimh.com/"
    const val PUBLIC_URL = "https://cn.bzmgcn.com/"
}
internal data class BaoziChapter(val manga: MangaKey, val id: String, val title: String)
internal data class BaoziBook(val card: MangaCard, val author: String, val description: String,
    val chapters: List<BaoziChapter>? = null, val chapterError: String? = null, val tags: List<String>? = null,
    val metadata: SourceWorkMetadata = SourceWorkMetadata(emptyList(), null, null, null, false))

/** Cover location errors stay local to the cover, never discard a valid search result. */
internal sealed interface BaoziCover {
    data class Url(val value: String) : BaoziCover
    data object Missing : BaoziCover
    data object Invalid : BaoziCover
}
internal fun baoziCoverLocation(raw: String?): BaoziCover {
    if (raw.isNullOrBlank()) return BaoziCover.Missing
    return try {
        val url = URI(BaoziEndpoint.BASE_URL).resolve(raw.trim())
        val host = url.host.orEmpty()
        val allowed = host == "static-tw.bzmgcn.com" || host.matches(Regex("[a-z0-9-]+\\.(baozimh|baozicdn)\\.com"))
        if (raw.length > 8192 || url.scheme != "https" || !allowed || url.port !in listOf(-1, 443) ||
            url.rawUserInfo != null || url.rawFragment != null || !url.rawPath.startsWith("/cover/") ||
            url.rawPath.contains("%") || url.rawPath.split('/').any { it == ".." || it == "." }) BaoziCover.Invalid
        else if (url.path.endsWith("/404.jpg")) BaoziCover.Missing else BaoziCover.Url(url.toASCIIString())
    } catch (_: Exception) { BaoziCover.Invalid }
}

/** Selectors/path semantics adapted from Keiyoushi Baozi.kt, ff521c17 (Apache-2.0).
 * Modified 2026-10-21: project identities, strict response boundaries and injected transport.
 * See assets/licenses/baozi-NOTICE.txt. No source-host, network API or DOM retained. */
internal class BaoziSource(private val context: SourceContext, private val accepts: () -> Boolean,
    private val chapterGet: (suspend (String, String) -> SourceHttpReply)? = null,
    private val metadataOrigin: String = BaoziEndpoint.BASE_URL,
    private val authenticationRequired: () -> Unit = {},
    private val get: suspend (String) -> SourceHttpReply) {
    init { require(isBaoziSource(context.sourceId) && context.world == SourceWorld.SFW && context.authRef == null) }
    private val covers = object : LinkedHashMap<String, BaoziCover>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BaoziCover>?) = size > 256
    }
    private val directories = linkedMapOf<String, List<BaoziChapter>>()
    private data class PageEntry(val resource: SourcePageResource, val url: String)
    private val pageSets = linkedMapOf<Pair<String, String>, List<PageEntry>>()
    fun clear() {
        synchronized(covers) { covers.clear() }
        synchronized(directories) { directories.clear() }
        synchronized(pageSets) { pageSets.clear() }
    }
    private fun rememberCover(id: String, location: BaoziCover) = synchronized(covers) { active(); covers[id] = location }
    suspend fun cover(id: String): String {
        active()
        var location = synchronized(covers) { covers[id] }
        if (location == null) { detail(id); location = synchronized(covers) { covers[id] } }
        currentCoroutineContext().ensureActive(); active()
        return when (location) {
            is BaoziCover.Url -> location.value
            BaoziCover.Missing -> throw SourceException.NotFound()
            else -> throw SourceException.InvalidResponse()
        }
    }
    private fun active() { if (!accepts()) throw CancellationException("Expired Baozi session") }
    private suspend fun document(path: String, chapter: Pair<String, String>? = null) = withContext(Dispatchers.IO) {
        ensureActive(); active()
        val reply = if (chapter == null) get(path) else (chapterGet ?: throw SourceException.Unsupported(SourceOperation.PAGES))(chapter.first, chapter.second)
        ensureActive(); active()
        if (reply.context != context || reply.body.size > 2 * 1024 * 1024 ||
            reply.contentType?.substringBefore(';')?.trim()?.lowercase() !in setOf("text/html", "application/xhtml+xml")) {
            throw SourceException.InvalidResponse()
        }
        val html = reply.body.toString(Charsets.UTF_8)
        if (html.contains("cf-chl-", true) || html.contains("challenge-platform", true) ||
            html.contains("__gatekeeper_challenge", true)) {
            if (chapter == null) authenticationRequired()
            throw SourceException.HttpStatus(403u)
        }
        Jsoup.parse(html, if (chapter == null) metadataOrigin else "https://appcn.baozimh.com/baozimhapp/").also { ensureActive(); active() }
    }
    private fun id(value: String): String {
        if (!value.matches(Regex("[A-Za-z0-9_-]{1,512}"))) throw SourceException.InvalidResponse()
        return value
    }
    private fun key(value: String) = MangaKey(PrototypeWorld.SFW, context.sourceId, id(value))
    private fun linkId(href: String): String {
        val uri = try { URI(metadataOrigin).resolve(href) } catch (_: Exception) { throw SourceException.InvalidResponse() }
        if (uri.scheme != "https" || "https://${uri.host}/" !in BaoziAccountOrigins || uri.rawUserInfo != null ||
            uri.port !in listOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null) throw SourceException.InvalidResponse()
        val path = uri.rawPath.removeSuffix("/")
        if (!path.startsWith("/comic/")) throw SourceException.InvalidResponse()
        return id(path.removePrefix("/comic/"))
    }
    suspend fun search(input: String, page: SourcePageRequest, tag: String? = null, updates: Boolean = false): SourceCardPage = withContext(Dispatchers.IO) {
        val query = input.trim()
        if (query.length > 200) throw SourceException.InvalidRequest()
        val number = when (page) {
            SourcePageRequest.Start -> 1u
            is SourcePageRequest.Page -> page.number
            else -> throw SourceException.InvalidRequest()
        }
        if (number == 0u || number > 10_000u || (query.isNotEmpty() && number != 1u)) throw SourceException.InvalidRequest()
        if (tag != null && (query.isNotEmpty() || tag !in BaoziCategories.tags.values)) throw SourceException.InvalidRequest()
        if (updates && (number != 1u || query.isNotEmpty() || tag != null)) throw SourceException.InvalidRequest()
        if (updates) return@withContext homeSection(setOf("最近更新"))
        val path = if (tag != null) "classify?page=$number&type=$tag" else if (query.isEmpty()) "classify?page=$number" else "search?q=${URLEncoder.encode(query, "UTF-8") }"
        val doc = document(path)
        val wrappers = doc.select("div.comics-card")
        val elements = wrappers.ifEmpty { doc.select("div.pure-g div a.comics-card__poster") }
        if (elements.isEmpty() && doc.select("div.pure-g").isEmpty()) throw SourceException.InvalidResponse()
        val cards = cards(elements)
        return@withContext SourceCardPage(cards, if (query.isEmpty() && cards.size >= 36)
            SourceContinuation.Next(SourcePageRequest.Page(number + 1u)) else SourceContinuation.End)
    }
    /** Official home recommendation sections are a snapshot, not a fabricated paged feed. */
    suspend fun recommendations(page: SourcePageRequest): SourceCardPage = withContext(Dispatchers.IO) {
        if (page != SourcePageRequest.Start) throw SourceException.InvalidRequest()
        homeSection(setOf("推荐国漫", "推荐韩漫", "推荐日漫", "推薦國漫", "推薦韓漫", "推薦日漫"))
    }
    private suspend fun homeSection(titles: Set<String>): SourceCardPage {
        val sections = document("").select(".index-recommend-items").filter {
            it.selectFirst(".catalog-title")?.text()?.trim() in titles
        }
        val elements = sections.flatMap { it.select("div.comics-card") }
        if (elements.isEmpty()) throw SourceException.InvalidResponse()
        return SourceCardPage(cards(elements), SourceContinuation.End)
    }
    private suspend fun cards(elements: List<Element>): List<MangaCard> {
        // The official classification HTML includes a Vue next-page template alongside
        // server-rendered cards. It is not a manga and must not invalidate the whole page.
        val rendered = elements.filterNot { it.selectFirst(".comics-card__title")?.text()?.contains("{{") == true }
        if (elements.isNotEmpty() && rendered.isEmpty()) throw SourceException.InvalidResponse()
        val locations = mutableMapOf<String, BaoziCover>()
        val cards = rendered.map { element ->
            val poster = element.selectFirst(".comics-card__poster") ?: element.takeIf { it.tagName() == "a" && it.hasClass("comics-card__poster") }
                ?: throw SourceException.InvalidResponse()
            val mangaId = if (poster.attr("href").isNotBlank()) linkId(poster.attr("href").trim()) else {
                val slug = Regex("'comic',\\s*'([A-Za-z0-9_-]+)'").find(poster.attr("onclick"))?.groupValues?.get(1)
                    ?: throw SourceException.InvalidResponse()
                id(slug)
            }
            val title = element.selectFirst(".comics-card__title")?.text()?.takeIf { it.isNotBlank() }
                ?: poster.attr("title").takeIf { it.isNotBlank() } ?: throw SourceException.InvalidResponse()
            if (title.contains("{{")) throw SourceException.InvalidResponse()
            val img = element.selectFirst("img, amp-img")
            locations[mangaId] = baoziCoverLocation(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
            MangaCard(key(mangaId), title.trim(), null, true)
        }.distinctBy { it.key }
        currentCoroutineContext().ensureActive(); active()
        locations.forEach { (id, location) -> rememberCover(id, location) }
        return cards
    }
    suspend fun detail(mangaId: String): BaoziBook = withContext(Dispatchers.IO) {
        val key = key(mangaId)
        val doc = document("comic/${key.mangaId}")
        doc.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf { it.isNotBlank() }?.let {
            if (linkId(it) != mangaId) throw SourceException.InvalidResponse()
        }
        val title = doc.selectFirst("h1.comics-detail__title")?.text()?.trim()?.takeIf { it.isNotEmpty() && !it.contains("{{") }
            ?: throw SourceException.InvalidResponse()
        currentCoroutineContext().ensureActive(); active()
        val cover = doc.selectFirst("meta[name=og:image], meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div.pure-g div > amp-img, div.pure-g div > img")?.let {
                it.attr("data-src").takeIf(String::isNotBlank) ?: it.attr("src")
            }
        var chapterError: String? = null
        val chapters = try { chapters(doc, key) } catch (_: SourceException.InvalidResponse) {
            chapterError = "章节目录无法识别，请刷新重试"; null
        }
        currentCoroutineContext().ensureActive(); active()
        rememberCover(mangaId, baoziCoverLocation(cover))
        if (chapters != null) synchronized(directories) {
            active(); directories.remove(mangaId); directories[mangaId] = chapters
            while (directories.size > 2) directories.remove(directories.keys.first())
        }
        return@withContext BaoziBook(MangaCard(key, title, null, true), doc.selectFirst("h2.comics-detail__author")?.text().orEmpty(),
            doc.selectFirst("p.comics-detail__desc")?.text().orEmpty(), chapters, chapterError,
            doc.selectFirst(".tag-list")?.select(".tag")?.map { it.text().trim() }?.filter { it.isNotEmpty() && it !in setOf("连载中", "連載中", "已完结", "已完結") }?.distinct(), baoziWorkMetadata(doc))
    }
    suspend fun chapterPage(mangaId: String, page: SourcePageRequest): SourceChapterPage {
        if (page != SourcePageRequest.Start) throw SourceException.InvalidRequest()
        val book = detail(mangaId)
        if (book.chapterError != null || book.chapters == null) throw SourceException.InvalidResponse()
        val key = SourceMangaKey(context.world, context.sourceId, mangaId)
        return SourceChapterPage(context, book.chapters.mapIndexed { i, c -> SourceChapter(key, c.id, c.title, (i + 1).toUInt(), null) }, SourceContinuation.End, book.chapters.size.toUInt())
    }
    suspend fun pages(mangaId: String, chapterId: String): SourcePages = withContext(Dispatchers.IO) {
        id(mangaId)
        if (!chapterId.matches(Regex("[0-9]+_[0-9]+"))) throw SourceException.InvalidRequest()
        val directory = synchronized(directories) { directories[mangaId] } ?: detail(mangaId).chapters
            ?: throw SourceException.InvalidResponse()
        if (directory.none { it.id == chapterId }) throw SourceException.NotFound()
        val doc = document("", mangaId to chapterId)
        val urls = doc.select(".comic-contain amp-img, .comic-contain img, .comic-article img, .chapter-img amp-img, .chapter-img img, .comic-page amp-img, .comic-page img, [class*=chapter] amp-img, [class*=chapter] img, [class*=comic] amp-img, [class*=comic] img")
            .mapNotNull { image -> image.absUrl("data-src").ifEmpty { image.absUrl("src") }.takeIf { it.isNotBlank() } }
            .filterNot { url -> listOf("/cover/", "logo", "loading.gif", "404.png", "favicon", "/img/", "banner", "recommend").any { url.contains(it, true) } }
            .distinct()
        if (urls.isEmpty() || urls.size > 2000) throw SourceException.InvalidResponse()
        val key = SourceMangaKey(context.world, context.sourceId, mangaId)
        val entries = urls.mapIndexed { index, raw ->
            val url = baoziPageUrl(raw)
            val id = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            PageEntry(SourcePageResource(key, chapterId, id, (index + 1).toUInt(), "baozi://$mangaId/$chapterId/$id", null, null, null, context), url)
        }.distinctBy { it.resource.id }.mapIndexed { index, entry -> entry.copy(resource = entry.resource.copy(ordinal = (index + 1).toUInt())) }
        currentCoroutineContext().ensureActive(); active()
        synchronized(pageSets) {
            active(); val identity = mangaId to chapterId
            pageSets.remove(identity); pageSets[identity] = entries
            while (pageSets.size > 2) pageSets.remove(pageSets.keys.first())
        }
        SourcePages(context, entries.map { it.resource }, SourceContinuation.End)
    }
    fun pageUrl(page: SourcePageResource): String {
        active()
        if (page.context != context || page.manga.world != context.world || page.manga.sourceId != context.sourceId)
            throw SourceException.InvalidRequest()
        val entry = synchronized(pageSets) { pageSets[page.manga.mangaId to page.chapterId]?.firstOrNull { it.resource.id == page.id } }
            ?: throw SourceException.InvalidRequest()
        if (entry.resource.copy(width = page.width, height = page.height) != page) throw SourceException.InvalidRequest()
        return entry.url
    }
    private fun chapters(doc: Document, key: MangaKey): List<BaoziChapter>? {
        val heading = doc.selectFirst(".section-title:containsOwn(章节目录), .section-title:containsOwn(章節目錄)")
        val elements = if (heading == null) doc.select(".comics-chapters")
            else heading.parent()?.select(".comics-chapters")?.reversed() ?: throw SourceException.InvalidResponse()
        if (heading == null && elements.isEmpty()) return null
        if (elements.size > 10_000) throw SourceException.InvalidResponse()
        return elements.map { element ->
            val title = element.text().trim()
            if (title.isEmpty() || title.contains("{{")) throw SourceException.InvalidResponse()
            BaoziChapter(key, chapterId(element, key.mangaId), title)
        }.distinctBy { it.id }
    }
    private fun chapterId(element: Element, mangaId: String): String {
        val href = element.selectFirst("a")?.attr("href")?.trim().orEmpty()
        val parts: List<String>
        if (href.isNotEmpty()) {
            val uri = try { URI(metadataOrigin).resolve(href) } catch (_: Exception) { throw SourceException.InvalidResponse() }
            if (uri.scheme != "https" || "https://${uri.host}/" !in BaoziAccountOrigins || uri.port !in listOf(-1, 443) ||
                uri.rawUserInfo != null || uri.rawFragment != null) throw SourceException.InvalidResponse()
            if (uri.rawPath == "/user/page_direct") {
                val values = uri.rawQuery.orEmpty().split('&').map {
                    val pair = it.split('=', limit = 2)
                    if (pair.size != 2) throw SourceException.InvalidResponse()
                    try { pair[0] to URLDecoder.decode(pair[1], "UTF-8") } catch (_: Exception) { throw SourceException.InvalidResponse() }
                }
                if (values.size != 3 || values.map { it.first }.toSet() != setOf("comic_id", "section_slot", "chapter_slot"))
                    throw SourceException.InvalidResponse()
                val query = values.toMap()
                parts = listOf(query.getValue("comic_id"), query.getValue("section_slot"), query.getValue("chapter_slot"))
            } else {
                if (uri.rawQuery != null) throw SourceException.InvalidResponse()
                val match = Regex("/comic/chapter/([A-Za-z0-9_-]+)/([0-9]+)_([0-9]+)\\.html").matchEntire(uri.rawPath)
                    ?: throw SourceException.InvalidResponse()
                parts = match.groupValues.drop(1)
            }
        } else {
            val onclick = element.selectFirst("[onclick]")?.attr("onclick").orEmpty()
            val match = Regex("""send_app_msg\('call_page',\s*\['chapter',\s*'([^']+)',\s*(\d+),\s*(\d+)\]\)""").find(onclick)
                ?: throw SourceException.InvalidResponse()
            parts = match.groupValues.drop(1)
        }
        if (parts[0] != mangaId || parts.drop(1).any { !it.matches(Regex("[0-9]+")) || it.toUIntOrNull() == null })
            throw SourceException.InvalidResponse()
        return "${parts[1].toUInt()}_${parts[2].toUInt()}"
    }

}

/** Pinned upstream rewrites reader CDN hosts only. Never pass cookies or execute page scripts. */
internal fun baoziPageUrl(raw: String): String {
    val uri = try { URI(raw) } catch (_: Exception) { throw SourceException.InvalidResponse() }
    val host = uri.host.orEmpty()
    if (raw.length > 8192 || uri.scheme != "https" || uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawFragment != null ||
        !host.matches(Regex("[a-z0-9-]+\\.(baozimh\\.com|baozicdn\\.com|bzcdn\\.net)")) || uri.path.isNullOrBlank()) throw SourceException.InvalidResponse()
    return if (host.endsWith(".baozicdn.com")) raw.replace(host, host.removeSuffix(".baozicdn.com") + ".baozimh.com") else raw
}
