package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.runtime.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal data class FeaturedBookInfo(val info: OnlineBookInformation, val chapters: Long?)
private fun OnlineBookInformation.featured() = FeaturedBookInfo(this,
    chapterGroups.values.flatten().distinct().size.toLong().takeIf { chaptersKnown && it > 0 })

/** Visible recommendation metadata, using existing source clients and world-isolated cache. */
internal class HomeFeaturedMetadata(private val app: Application, private val session: WorldSession) {
    private val identity = session.identity.value
    val books = mutableStateMapOf<MangaKey, FeaturedBookInfo>()
    val errors = mutableStateMapOf<MangaKey, String>()
    private val loaded = mutableSetOf<MangaKey>()
    var revision by mutableIntStateOf(0); private set
    fun refresh() { loaded.clear(); revision++ }
    fun retry(key: MangaKey) { loaded.remove(key); revision++ }
    private fun active() = session.accepts(identity) && session.state.value.foreground
    suspend fun load(card: MangaCard) {
        val key = card.key
        if (!active() || key in loaded) return
        val cache = CatalogPageCache(app, session, "home-featured:${key.sourceId.length}:${key.sourceId}${key.mangaId}", includeBlacklist = false, source = key.sourceId)
        errors.remove(key)
        try {
            if (books[key] == null) {
                val cached = try { cache.read() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (cached != null) {
                    val tags = cached.getJSONArray("tags")
                    books[key] = FeaturedBookInfo(OnlineBookInformation(cached.getString("title"), cached.getString("author"),
                        cached.getString("summary"), List(tags.length()) { tags.getString(it) }, cached.getString("status")),
                        if (cached.isNull("chapters")) null else cached.getLong("chapters"))
                    if (revision == 0) { loaded += key; return }
                }
            }
            if (books[key] == null) {
                OnlineBookInformationStore(WorldDataStore(app), session).read(key.sourceId, key.mangaId)?.let { info ->
                    if (active()) books[key] = info.featured()
                }
            }
            val value = if (isCopySource(key.sourceId)) {
                // The detail response already carries group totals; do not download every chapter to count them.
                val book = ContentRequests.run(key.sourceId, ContentPriority.DETAIL) { copyMangaClient(session, key.sourceId).book(key.mangaId) }
                val groups = book.groups
                val count = groups?.takeIf { it.isNotEmpty() && it.all { group -> group.chapterCount != null } }
                    ?.sumOf { it.chapterCount!!.toLong() }
                FeaturedBookInfo(detailInformation(book.detail), count)
            } else {
                // Covers render independently; display source details before a paginated directory finishes.
                val result = OnlineBookInformationLoader(app, session).load(key.sourceId, key.mangaId,
                    refreshMetadata = revision > 0, loadCover = false, loadChapters = false, onMetadata = { info ->
                        if (active()) books[key] = info.featured()
                    })
                if (result.metadataError != null) throw java.io.IOException(result.metadataError)
                result.info.featured()
            }
            currentCoroutineContext().ensureActive()
            if (!active()) return
            books[key] = value
            loaded += key
            val info = value.info
            cache.write(JSONObject().put("title", info.title).put("author", info.author).put("summary", info.summary)
                .put("tags", JSONArray(info.tags)).put("status", info.status).put("chapters", value.chapters ?: JSONObject.NULL))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (active()) { loaded += key; errors[key] = "资料加载失败" } }
    }
}
