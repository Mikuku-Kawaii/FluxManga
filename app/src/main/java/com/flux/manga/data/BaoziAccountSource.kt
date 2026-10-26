package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.net.URI

/** The upstream has no reliable empty-account or complete-library evidence. */
internal enum class BaoziShelfEvidence { CONTENT_PRESENT, EMPTY_UNCONFIRMED }
internal data class BaoziShelfEntry(val card: MangaCard, val cover: BaoziCover)
internal data class BaoziShelfSnapshot(val entries: List<BaoziShelfEntry>, val evidence: BaoziShelfEvidence) {
    // A single server-rendered snapshot must not become SourceContinuation.End / import-complete.
    val completenessVerified: Boolean get() = false
}

/** Account DOM selectors adapted from skepsun/kototoro-parsers a94b6704 (GPL-3.0).
 * See assets/licenses/baozi-account-NOTICE.txt. No credentials, persistence or remote writes here.
 * The account instance stays distinct from anonymous discovery and other accounts. */
internal class BaoziAccountSource(private val context: SourceContext, private val accepts: () -> Boolean,
    private val get: suspend () -> SourceHttpReply) {
    init {
        require(context.world == SourceWorld.SFW && context.sourceId.matches(Regex("baozimh-account-[0-9a-fA-F-]{36}")) &&
            !context.authRef.isNullOrBlank())
    }
    private fun active() { if (!accepts()) throw CancellationException("Expired Baozi account session") }

    suspend fun bookshelf(): BaoziShelfSnapshot = withContext(Dispatchers.IO) {
        ensureActive(); active()
        val reply = get()
        ensureActive(); active()
        if (reply.context != context || reply.body.size > 2 * 1024 * 1024 ||
            reply.contentType?.substringBefore(';')?.trim()?.lowercase() !in setOf("text/html", "application/xhtml+xml"))
            throw SourceException.InvalidResponse()
        val html = reply.body.toString(Charsets.UTF_8)
        if (html.contains("cf-chl-", true) || html.contains("challenge-platform", true) ||
            html.contains("__gatekeeper_challenge", true)) throw SourceException.HttpStatus(403u)
        val doc = Jsoup.parse(html, BaoziEndpoint.BASE_URL)
        // A login wall must never be reported as an empty successful shelf.
        if (doc.select("input[type=password]").isNotEmpty()) throw SourceException.AuthenticationRequired()
        val rows = doc.select("div.bookshelf-items")
        if (rows.size > 1000) throw SourceException.InvalidResponse()
        val entries = rows.map { row ->
            ensureActive(); active()
            val link = row.selectFirst("h4 > a") ?: throw SourceException.InvalidResponse()
            val uri = try { URI(BaoziEndpoint.BASE_URL).resolve(link.attr("href")) }
                catch (_: Exception) { throw SourceException.InvalidResponse() }
            if (uri.scheme != "https" || "https://${uri.host}/" !in BaoziAccountOrigins || uri.port !in listOf(-1, 443) ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                !uri.rawPath.matches(Regex("/comic/[A-Za-z0-9_-]{1,512}/?"))) throw SourceException.InvalidResponse()
            val id = uri.rawPath.removeSuffix("/").removePrefix("/comic/")
            val title = link.text().trim()
            if (title.isEmpty() || title.length > 1024 || title.contains("{{")) throw SourceException.InvalidResponse()
            val image = row.selectFirst("amp-img, img")
            val cover = baoziCoverLocation(image?.attr("data-src")?.takeIf(String::isNotBlank) ?: image?.attr("src"))
            BaoziShelfEntry(MangaCard(MangaKey(PrototypeWorld.SFW, context.sourceId, id), title, null, true), cover)
        }.distinctBy { it.card.key }
        ensureActive(); active()
        BaoziShelfSnapshot(entries, if (entries.isEmpty()) BaoziShelfEvidence.EMPTY_UNCONFIRMED else BaoziShelfEvidence.CONTENT_PRESENT)
    }
}
