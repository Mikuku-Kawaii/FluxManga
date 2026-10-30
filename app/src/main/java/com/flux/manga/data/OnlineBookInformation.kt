package com.flux.manga.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class OnlineBookInformation(
    val title: String, val author: String = "", val summary: String = "", val tags: List<String> = emptyList(),
    val status: String = "", val updated: String = "", val partial: Boolean = false, val coverReady: Boolean = false, val detailsReady: Boolean = true,
    val chapterGroups: Map<String, List<String>> = emptyMap(), val chaptersKnown: Boolean = false,
    val contentUpdated: Boolean = false, val readToEndChapter: String? = null,
)
internal val OnlineBookInformation.isFinished get() = status.trim().uppercase(java.util.Locale.ROOT) in
    setOf("已完结", "完结", "已完結", "完結", "COMPLETED", "ENDED", "FINISHED")
internal val OnlineBookInformation.isReadToEnd get() = isFinished && readToEndChapter != null &&
    chapterGroups.values.none { ids -> readToEndChapter in ids && ids.lastOrNull() != readToEndChapter }

internal data class BookInformationResult(val info: OnlineBookInformation, val coverError: String? = null, val metadataError: String? = null)
internal fun informationSlot(source: String, id: String) = "online-info-v1:${source.length}:$source$id"
internal fun informationCover(world: PrototypeWorld, source: String, id: String) = ResourceKey(SampleKey(world, source, id), "library-cover-v1")

internal class OnlineBookInformationStore(private val data: WorldDataStore, private val session: WorldSession) {
    private val identity = session.identity.value
    private val db = data.accountImportDatabase(identity.world)
    private fun active() = session.accepts(identity) && session.state.value.foreground
    private fun AccountImportRows.readInfo(source: String, id: String): OnlineBookInformation? {
        val bytes = read(informationSlot(source, id)) ?: return null
        return try {
            val j = JSONObject(bytes.toString(Charsets.UTF_8)); require(j.getInt("version") == 1)
            val tags = j.getJSONArray("tags")
            OnlineBookInformation(j.getString("title"), j.getString("author"), j.getString("summary"),
                List(tags.length()) { tags.getString(it) }, j.getString("status"), j.getString("updated"), j.getBoolean("partial"), j.getBoolean("cover"), j.optBoolean("detailsReady", true),
                j.optJSONObject("chapterGroups")?.let { groups -> groups.keys().asSequence().associateWith { group ->
                    val ids = groups.getJSONArray(group); List(ids.length()) { ids.getString(it) }
                } }.orEmpty(), j.optBoolean("chaptersKnown"),
                j.optBoolean("contentUpdated", isCopySource(source) || isBaoziSource(source)),
                j.optString("readToEndChapter").takeIf { it.isNotBlank() })
        } finally { bytes.fill(0) }
    }
    suspend fun read(source: String, id: String) = db.transaction(::active) { it.readInfo(source, id) }
    suspend fun readTags(keys: List<MangaKey>) = db.transaction(::active) { rows ->
        keys.filter { it.world == identity.world }.mapNotNull { key ->
            rows.readInfo(key.sourceId, key.mangaId)?.takeIf { it.detailsReady }?.tags?.let { key to it }
        }.toMap()
    }
    suspend fun readAll(books: List<OnlineBook>) = db.transaction(::active) { rows ->
        books.mapNotNull { book -> rows.readInfo(book.sourceId, book.mangaId)?.let { (book.sourceId to book.mangaId) to it } }.toMap()
    }
    private fun AccountImportRows.writeInfo(source: String, id: String, value: OnlineBookInformation): OnlineBookInformation {
        // Do not resurrect a book removed while its detail request was in flight.
        if (book(source, id) == null) return value
        val old = readInfo(source, id)
        val info = value.copy(chapterGroups = old?.chapterGroups.orEmpty() + value.chapterGroups,
            chaptersKnown = value.chaptersKnown || old?.chaptersKnown == true,
            readToEndChapter = value.readToEndChapter ?: old?.readToEndChapter)
        if (old == info) return info
        val j = JSONObject().put("version", 1).put("title", info.title).put("author", info.author).put("summary", info.summary)
            .put("tags", JSONArray(info.tags.distinct())).put("status", info.status).put("updated", info.updated)
            .put("partial", info.partial).put("cover", info.coverReady).put("detailsReady", info.detailsReady)
            .put("chapterGroups", JSONObject().also { groups -> info.chapterGroups.forEach { (group, ids) -> groups.put(group, JSONArray(ids)) } })
            .put("chaptersKnown", info.chaptersKnown)
            .put("contentUpdated", info.contentUpdated).put("readToEndChapter", info.readToEndChapter.orEmpty())
        val bytes = j.toString().toByteArray()
        try { write(informationSlot(source, id), bytes) } finally { bytes.fill(0) }
        return info
    }
    suspend fun save(source: String, id: String, info: OnlineBookInformation) = db.transaction(::active) { rows ->
        rows.writeInfo(source, id, info)
    }
    // A foreground reader observed the end; allow its pending write to finish on background entry.
    suspend fun markReadToEnd(source: String, id: String, chapter: String) = db.transaction({ session.accepts(identity) }) { rows ->
        val book = rows.book(source, id) ?: return@transaction
        val info = rows.readInfo(source, id) ?: OnlineBookInformation(book.title, detailsReady = false)
        rows.writeInfo(source, id, info.copy(readToEndChapter = chapter))
    }
    suspend fun chapters(source: String, id: String, group: String, ids: List<String>) = db.transaction(::active) { rows ->
        val book = rows.book(source, id) ?: return@transaction
        val info = rows.readInfo(source, id) ?: OnlineBookInformation(book.title, detailsReady = false)
        rows.writeInfo(source, id, info.copy(chapterGroups = info.chapterGroups + (group to ids.distinct()), chaptersKnown = true))
    }
    suspend fun cover(source: String, id: String): Bitmap? {
        val bytes = data.resource(informationCover(identity.world, source, id), ::active)
            ?: if (identity.world == PrototypeWorld.NSFW)
                data.resource(ResourceKey(SampleKey(identity.world, source, id), "cover-v1"), ::active) ?: return null
            else return null
        var bitmap: Bitmap? = null
        return try {
            withContext(Dispatchers.Default) {
                // Source cover-v1 stores the original encoded image; keep its existing preview bound.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 960) sample *= 2
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            }
            currentCoroutineContext().ensureActive(); if (!active()) throw CancellationException()
            bitmap
        } catch (failure: Exception) { bitmap?.recycle(); throw failure }
        finally { bytes.fill(0) }
    }
}

/** Detail protocols and image transports remain owned by the existing source clients. */
internal class OnlineBookInformationLoader(private val app: Application, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(app)
    private val store = OnlineBookInformationStore(data, session)
    private fun active() = session.accepts(identity) && session.state.value.foreground
    private fun SourceDetail.info() = OnlineBookInformation(manga.title, metadata.authors.joinToString(" / ") { it.name },
        description.orEmpty(), tags.orEmpty(), metadata.status.orEmpty(), metadata.updatedAt.orEmpty(), metadata.partial,
        contentUpdated = metadata.updateKind == SourceUpdateKind.CONTENT)
    private suspend fun chapterIds(load: suspend (SourcePageRequest) -> SourceChapterPage): List<String> {
        val ids = mutableListOf<String>()
        var request: SourcePageRequest = SourcePageRequest.Start
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = load(request)
            ids += page.items.map { it.id }
            request = (page.continuation as? SourceContinuation.Next)?.request ?: break
        }
        return ids.distinct()
    }
    suspend fun load(source: String, id: String, force: Boolean = false, refreshMetadata: Boolean = false,
        loadCover: Boolean = true, loadChapters: Boolean = true, onMetadata: suspend (OnlineBookInformation) -> Unit = {}): BookInformationResult = coroutineScope {
        if (!active()) throw CancellationException()
        var info = store.read(source, id)
        var metadataError: String? = null
        if (info == null || !info.detailsReady || (loadChapters && !info.chaptersKnown) || force || refreshMetadata) {
            info = try { ContentRequests.run(source, ContentPriority.DETAIL) { if (identity.world == PrototypeWorld.SFW) when {
                isCopySource(source) -> copyMangaClient(session, source).let { client ->
                    val details = client.detail(id).info()
                    if (loadChapters) details.copy(chapterGroups = mapOf("default" to chapterIds { client.allChapters(id, it) }), chaptersKnown = true) else details
                }
                isBaoziSource(source) -> session.baoziSources.client(app, source).source.detail(id).let {
                    OnlineBookInformation(it.card.title, it.author, it.description, it.tags.orEmpty(),
                        it.metadata.status.orEmpty(), it.metadata.updatedAt.orEmpty(), it.metadata.partial,
                        chapterGroups = it.chapters?.let { chapters -> mapOf("default" to chapters.map { chapter -> chapter.id }) }.orEmpty(),
                        chaptersKnown = it.chapters != null, contentUpdated = it.metadata.updateKind == SourceUpdateKind.CONTENT)
                }
                else -> KomgaAccess(app, session, source).client().let { client ->
                    val key = SourceMangaKey(SourceWorld.SFW, source, id)
                    val details = client.detail(key).info()
                    if (loadChapters) details.copy(chapterGroups = mapOf("default" to chapterIds { client.chapters(key, it) }), chaptersKnown = true) else details
                }
            } else when {
                isJmSource(source) -> {
                    val settings = JmStore(app, session).settings()
                    if (source != JM_SOURCE && source != settings.account?.source) throw IOException("请登录作品所属的 JMComic 账号")
                    JmClient(session, if (source == JM_SOURCE) settings.copy(account = null) else settings, source, this).use { client ->
                        client.detail(id).let { OnlineBookInformation(it.title, it.author, it.description, it.tags,
                            chapterGroups = mapOf("default" to it.chapters.map { chapter -> chapter.id }), chaptersKnown = true) }
                    }
                }
                isPicaSource(source) -> {
                    val account = PicaStore(app, session).account()?.takeIf { it.source == source } ?: throw IOException("请登录作品所属的 PicACG 账号")
                    PicaClient(session, account, this).use { client -> client.detail(id).let {
                        val details = OnlineBookInformation(it.title, listOf(it.author, it.artist).filter(String::isNotBlank).distinct().joinToString(" / "),
                            it.description, (it.tags + it.categories).distinct(), if (it.finished) "已完结" else "连载中")
                        ensureActive(); if (!active()) throw CancellationException()
                        info = store.save(source, id, details)
                        onMetadata(requireNotNull(info))
                        if (loadChapters) details.copy(chapterGroups = mapOf("default" to client.allChapters(id).map { chapter -> chapter.order.toString() }), chaptersKnown = true) else details
                    } }
                }
                else -> {
                    val site = GallerySite.forSource(source); val settings = EhStore(app, session, site).settings()
                    if (source != EH_SOURCE && (settings.account == null || source != site.source(settings.account))) throw IOException("请登录作品所属的 ${site.label} 账号")
                    EhClient(session, if (source == EH_SOURCE) null else settings.account, this, site).use { client -> client.detail(id).let {
                        OnlineBookInformation(it.title, summary = listOf(it.subtitle, it.uploader.takeIf(String::isNotBlank)?.let { name -> "上传者：$name" }.orEmpty()).filter(String::isNotBlank).joinToString("\n"),
                            tags = (listOf(it.category).filter(String::isNotBlank) + it.tags).distinct(), status = "${it.pages} 页", chaptersKnown = true)
                    } }
                }
            }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                metadataError = "作品资料未补齐，可重试；已保存资料保留"
                info ?: OnlineBookInformation(data.worldData(identity.world, ::active) { it.onlineBook(source, id)?.title.orEmpty() }, detailsReady = false)
            }
            ensureActive(); info = store.save(source, id, requireNotNull(info))
        }
        var current = requireNotNull(info)
        ensureActive(); if (!active()) throw CancellationException()
        onMetadata(current)
        if (!loadCover) return@coroutineScope BookInformationResult(current, metadataError = metadataError)
        var coverError: String? = null
        try {
            val library = OnlineLibrary(app, session)
            if (force) {
                // Forced replacements own this bitmap; ordinary cache reads below only borrow it.
                val bitmap = library.cover(source, id, useSavedCover = false)
                val bytes = try { withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("封面保存失败")
                        output.toByteArray()
                    }
                } } finally { bitmap.recycle() }
                try { data.putResource(informationCover(identity.world, source, id), bytes, ::active) }
                finally { bytes.fill(0) }
                session.pageCovers().remove(source, id)
            } else {
                // Share the visible card's decode/download instead of decoding twice or fetching in parallel.
                library.cover(source, id)
            }
            current = current.copy(coverReady = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { coverError = "封面未补齐，可重试"; current = current.copy(coverReady = false) }
        if (current != info) current = store.save(source, id, current)
        ensureActive(); if (!active()) throw CancellationException()
        BookInformationResult(current, coverError, metadataError)
    }
}
