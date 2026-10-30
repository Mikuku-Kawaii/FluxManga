package com.flux.manga.data

import android.content.Context
import android.graphics.*
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal suspend fun ehCover(data: WorldDataStore, session: WorldSession, client: EhClient, id: String, book: EhBook? = null): Bitmap {
    val identity = session.identity.value
    require(ehId(id) && (book == null || book.id == id))
    val key = ResourceKey(SampleKey(identity.world, client.source, id), "cover-v1")
    return PrivateImagePipeline(key.manga, privateCache(data), PrivateImageTransport {
        val url = (book ?: client.detail(id)).cover
        if (url.isBlank()) throw IOException("画廊未提供封面")
        privateImageInput(client.image(url))
    }, { session.state.value.visibilityEpoch }, { epoch -> session.accepts(identity) && session.state.value.foreground && epoch == session.state.value.visibilityEpoch })
        .decode(key, { bytes ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0)
            var sample = 1
            while (options.outWidth / sample > 640 || options.outHeight / sample > 960) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("封面读取失败")
        }, { it.recycle() })
}

internal class EhReaderRepository(context: Context, private val session: WorldSession, private val source: String,
    private val manga: String, cacheSession: String = java.util.UUID.randomUUID().toString()) : PrivateDownloadReader {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    private val site = GallerySite.forSource(source)
    private val store = EhStore(context, session, site)
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val setup = Mutex()
    private var client: EhClient? = null
    private var original = false
    private var book: EhBook? = null
    private var refs = emptyMap<String, EhPage>()
    private val revision = AtomicLong()
    override val mangaKey = SourceMangaKey(SourceWorld.NSFW, source, manga)
    private val contextKey = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    private val imageOwner = SampleKey(identity.world, source, manga)
    init { require(identity.world == PrototypeWorld.NSFW && site.accepts(source) && ehId(manga)) }
    override fun isActive() = session.accepts(identity)
    override fun active() { if (!isActive()) throw CancellationException("Expired EH reader") }
    private suspend fun client(): EhClient = setup.withLock {
        active()
        client ?: store.settings().let { settings ->
            if (source != EH_SOURCE && site.source(settings.account) != source) throw IOException("请在图源管理登录原 ${site.label} 账号")
            original = settings.original
            EhClient(session, if (source == EH_SOURCE) null else settings.account, owner, site).also { client = it }
        }
    }
    private suspend fun detail(): EhBook = book ?: client().detail(manga).also { store.remember(source, it); active(); book = it }
    private fun epoch() = session.state.value.visibilityEpoch to revision.get()
    private fun request(page: SourcePageResource): ResourceKey {
        active(); require(page.manga == mangaKey && page.context == contextKey && page.chapterId == "gallery" && refs[page.id]?.ordinal == page.ordinal.toInt())
        return ResourceKey(imageOwner, "gallery/${if (original) "original" else "display"}/${page.id}")
    }
    private val pageCache = ReaderEncodedCache(data, cacheSession)
    private val pipeline = PrivateImagePipeline(imageOwner, pageCache, PrivateImageTransport { key ->
        privateImageInput(client().pageImage(requireNotNull(refs[key.resourceId.substringAfterLast('/')]), original))
    }, ::epoch, { ticket -> isActive() && session.state.value.foreground && ticket == epoch() })
    override suspend fun chapters(request: SourcePageRequest): SourceChapterPage {
        require(request == SourcePageRequest.Start)
        return SourceChapterPage(contextKey, listOf(SourceChapter(mangaKey, "gallery", detail().title, 1u, null)), SourceContinuation.End, 1u)
    }
    override suspend fun pages(chapterId: String): List<SourcePageResource> {
        require(chapterId == "gallery"); client()
        val saved = pageCache.decode(imageOwner, "pages:$chapterId", ::isActive) { json ->
            val rows = json.getJSONArray("images")
            List(rows.length()) { rows.getJSONObject(it).let { row -> EhPage(row.getInt("ordinal"), row.getString("url")) } }
        }
        val pages = saved ?: run { detail(); ContentRequests.run(source, ContentPriority.READER) { client().pages(manga) }.also { rows ->
            pageCache.json(imageOwner, "pages:$chapterId", org.json.JSONObject().put("images", org.json.JSONArray().also { a ->
                rows.forEach { a.put(org.json.JSONObject().put("ordinal", it.ordinal).put("url", it.url)) }
            }), ::isActive)
        } }
        active(); refs = pages.associateBy { it.ordinal.toString() }
        return pages.map { SourcePageResource(mangaKey, chapterId, it.ordinal.toString(), it.ordinal.toUInt(), "eh-private-v1", null, null, null, contextKey) }
    }
    override suspend fun completeOfflineCatalog() = chapters(SourcePageRequest.Start).items
    override suspend fun offlineIds() = emptySet<String>()
    override suspend fun downloadPage(page: SourcePageResource): PrivateDownloadPayload {
        request(page)
        // Downloads have a stable display-image policy, independent of the online original switch.
        return PrivateDownloadPayload(client().pageImage(requireNotNull(refs[page.id]), false))
    }
    override suspend fun progress() = data.worldData(identity.world, ::isActive) { it.onlineProgress(source, manga) }
    override suspend fun chapterProgress(chapter: String) = data.worldData(identity.world, ::isActive) { it.chapterReading(source, manga, chapter) }
    override suspend fun save(page: SourcePageResource, mode: ReadingMode, fraction: Float, modeExplicit: Boolean) {
        request(page); require(fraction.isFinite() && fraction in 0f..1f)
        data.worldData(identity.world, ::isActive) { dao -> saveChapterProgress(dao, OnlineReadingProgress(source, manga, page.chapterId,
            page.id, page.ordinal.toInt(), fraction, mode.name, System.currentTimeMillis(), modeExplicit)) }
    }
    override suspend fun dimensions(page: SourcePageResource): Pair<Int, Int> = pipeline.decode(request(page), { bytes ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..100000 && options.outHeight in 1..100000)
        options.outWidth to options.outHeight
    }, {})
    override suspend fun image(page: SourcePageResource, width: Int, top: Int, bottom: Int?): Bitmap = pipeline.decode(request(page), { bytes ->
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        try {
            val end = bottom ?: decoder.height; require(top >= 0 && end > top && end <= decoder.height)
            var sample = 1
            while (decoder.width / sample > width.coerceIn(256, 2048) || (end - top).toLong() / sample * (decoder.width / sample) > 2_097_152 ||
                (bottom == null && (end - top) / sample > 4096)) sample *= 2
            decoder.decodeRegion(Rect(0, top, decoder.width, end), BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("图片解码失败")
        } finally { decoder.recycle() }
    }, { it.recycle() })
    override suspend fun clearImages() { revision.incrementAndGet(); data.clearMemory() }
    override suspend fun releaseImages() { clearImages(); pageCache.close() }
    override fun close() { client?.close(); owner.cancel(); refs = emptyMap(); data.clearMemory() }
}
