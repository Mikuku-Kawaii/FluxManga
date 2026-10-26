package com.flux.manga.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class OnlineBookInformation(
    val title: String, val author: String = "", val summary: String = "", val tags: List<String> = emptyList(),
    val status: String = "", val updated: String = "", val partial: Boolean = false, val coverReady: Boolean = false, val detailsReady: Boolean = true,
)
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
                List(tags.length()) { tags.getString(it) }, j.getString("status"), j.getString("updated"), j.getBoolean("partial"), j.getBoolean("cover"), j.optBoolean("detailsReady", true))
        } finally { bytes.fill(0) }
    }
    suspend fun read(source: String, id: String) = db.transaction(::active) { it.readInfo(source, id) }
    suspend fun readAll(books: List<OnlineBook>) = db.transaction(::active) { rows ->
        books.mapNotNull { book -> rows.readInfo(book.sourceId, book.mangaId)?.let { (book.sourceId to book.mangaId) to it } }.toMap()
    }
    suspend fun save(source: String, id: String, info: OnlineBookInformation) = db.transaction(::active) { rows ->
        // Do not resurrect a book removed while its detail request was in flight.
        if (rows.book(source, id) == null) return@transaction
        if (rows.readInfo(source, id) == info) return@transaction
        val j = JSONObject().put("version", 1).put("title", info.title).put("author", info.author).put("summary", info.summary)
            .put("tags", JSONArray(info.tags.distinct())).put("status", info.status).put("updated", info.updated)
            .put("partial", info.partial).put("cover", info.coverReady).put("detailsReady", info.detailsReady)
        val bytes = j.toString().toByteArray()
        try { rows.write(informationSlot(source, id), bytes) } finally { bytes.fill(0) }
    }
    suspend fun cover(source: String, id: String): Bitmap? {
        val bytes = data.resource(informationCover(identity.world, source, id), ::active) ?: return null
        var bitmap: Bitmap? = null
        return try {
            withContext(Dispatchers.Default) { bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
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
    companion object { private val slots = Semaphore(2) }
    private fun active() = session.accepts(identity) && session.state.value.foreground
    private fun SourceDetail.info() = OnlineBookInformation(manga.title, metadata.authors.joinToString(" / ") { it.name },
        description.orEmpty(), tags.orEmpty(), metadata.status.orEmpty(), metadata.updatedAt.orEmpty(), metadata.partial)
    suspend fun load(source: String, id: String, force: Boolean = false): BookInformationResult = slots.withPermit { coroutineScope {
        if (!active()) throw CancellationException()
        var info = store.read(source, id)
        var metadataError: String? = null
        if (info == null || !info.detailsReady || force) {
            info = try { if (identity.world == PrototypeWorld.SFW) when {
                isCopySource(source) -> copyMangaClient(app, session, source).detail(id).info()
                isBaoziSource(source) -> session.baoziSources.client(app, source).source.detail(id).let {
                    OnlineBookInformation(it.card.title, it.author, it.description, it.tags.orEmpty(),
                        it.metadata.status.orEmpty(), it.metadata.updatedAt.orEmpty(), it.metadata.partial)
                }
                else -> KomgaAccess(app, session, source).client().detail(SourceMangaKey(SourceWorld.SFW, source, id)).info()
            } else when {
                isJmSource(source) -> {
                    val settings = JmStore(app, session).settings()
                    if (source != JM_SOURCE && source != settings.account?.source) throw IOException("请登录作品所属的 JMComic 账号")
                    JmClient(session, if (source == JM_SOURCE) settings.copy(account = null) else settings, source, this).use { client ->
                        client.detail(id).let { OnlineBookInformation(it.title, it.author, it.description, it.tags) }
                    }
                }
                isPicaSource(source) -> {
                    val account = PicaStore(app, session).account()?.takeIf { it.source == source } ?: throw IOException("请登录作品所属的 PicACG 账号")
                    PicaClient(session, account, this).use { client -> client.detail(id).let {
                        OnlineBookInformation(it.title, listOf(it.author, it.artist).filter(String::isNotBlank).distinct().joinToString(" / "),
                            it.description, (it.tags + it.categories).distinct(), if (it.finished) "已完结" else "连载中")
                    } }
                }
                else -> {
                    val site = GallerySite.forSource(source); val settings = EhStore(app, session, site).settings()
                    if (source != EH_SOURCE && (settings.account == null || source != site.source(settings.account))) throw IOException("请登录作品所属的 ${site.label} 账号")
                    EhClient(session, if (source == EH_SOURCE) null else settings.account, this, site).use { client -> client.detail(id).let {
                        OnlineBookInformation(it.title, summary = listOf(it.subtitle, it.uploader.takeIf(String::isNotBlank)?.let { name -> "上传者：$name" }.orEmpty()).filter(String::isNotBlank).joinToString("\n"),
                            tags = (listOf(it.category).filter(String::isNotBlank) + it.tags).distinct(), status = "${it.pages} 页")
                    } }
                }
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                metadataError = "作品资料未补齐，可重试；已保存资料保留"
                info ?: OnlineBookInformation(data.worldData(identity.world, ::active) { it.onlineBook(source, id)?.title.orEmpty() }, detailsReady = false)
            }
            ensureActive(); store.save(source, id, requireNotNull(info))
        }
        var current = requireNotNull(info)
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
        if (current != info) store.save(source, id, current)
        ensureActive(); if (!active()) throw CancellationException()
        BookInformationResult(current, coverError, metadataError)
    } }
}
