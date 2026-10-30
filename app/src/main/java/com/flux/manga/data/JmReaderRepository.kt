package com.flux.manga.data

import android.content.Context
import android.graphics.*
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal class JmReaderRepository(context: Context, private val session: WorldSession, private val source: String,
    private val manga: String, cacheSession: String = java.util.UUID.randomUUID().toString()) : PrivateDownloadReader {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    private val settings = JmStore(context, session)
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val setup = Mutex()
    private var client: JmClient? = null
    private val revision = AtomicLong()
    private val chapterData = mutableMapOf<String, JmPages>()
    private var book: JmBook? = null
    override val mangaKey = SourceMangaKey(SourceWorld.NSFW, source, manga)
    private val sourceContext = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    private val owner = SampleKey(PrototypeWorld.NSFW, source, manga)
    init { require(identity.world == PrototypeWorld.NSFW && isJmSource(source) && jmId(manga)) }
    override fun isActive() = session.accepts(identity)
    override fun active() { if (!isActive()) throw CancellationException("Expired JM reader") }
    private suspend fun client(): JmClient = setup.withLock {
        active()
        client ?: settings.settings().let { config ->
            if (source != JM_SOURCE && config.account?.source != source) throw IOException("请在图源管理登录原 JMComic 账号")
            JmClient(session, config, source, ownerScope).also { client = it }
        }
    }
    private suspend fun detail(): JmBook = book ?: client().detail(manga).also { value ->
        active(); settings.remember(source, value); book = value
    }
    private fun epoch() = session.state.value.visibilityEpoch to revision.get()
    private fun request(page: SourcePageResource): ResourceKey {
        active()
        require(page.manga == mangaKey && page.context == sourceContext &&
            synchronized(chapterData) { chapterData[page.chapterId]?.filenames?.getOrNull(page.ordinal.toInt() - 1) } == page.id)
        return ResourceKey(owner, "${page.chapterId}/${page.id}")
    }
    private val pageCache = ReaderEncodedCache(data, cacheSession)
    private val pipeline = PrivateImagePipeline(owner, pageCache, PrivateImageTransport { key ->
        val bytes = client().image("media/photos/${key.resourceId}")
        object : ByteArrayInputStream(bytes) { override fun close() { bytes.fill(0); super.close() } }
    }, ::epoch, { ticket -> isActive() && session.state.value.foreground && epoch() == ticket })
    override suspend fun chapters(request: SourcePageRequest): SourceChapterPage {
        require(request == SourcePageRequest.Start)
        return SourceChapterPage(sourceContext, detail().chapters.mapIndexed { index, chapter ->
            SourceChapter(mangaKey, chapter.id, chapter.title, (index + 1).toUInt(), null)
        }, SourceContinuation.End, detail().chapters.size.toUInt())
    }
    override suspend fun pages(chapterId: String): List<SourcePageResource> {
        client() // Validate the current account before reusing this reader session.
        val saved = pageCache.decode(owner, "pages:$chapterId", ::isActive) { json -> JmPages(chapterId,
            json.getJSONArray("files").let { rows -> List(rows.length()) { rows.getString(it) } }, json.getLong("scramble")) }
        val chapter = saved ?: run {
            require(detail().chapters.any { it.id == chapterId }) { "章节不属于当前作品" }
            ContentRequests.run(source, ContentPriority.READER) { client().pages(chapterId) }.also {
                pageCache.json(owner, "pages:$chapterId", org.json.JSONObject().put("files", org.json.JSONArray(it.filenames))
                    .put("scramble", it.scramble), ::isActive)
            }
        }
        active()
        synchronized(chapterData) { chapterData[chapterId] = chapter }
        return chapter.filenames.mapIndexed { index, name ->
            SourcePageResource(mangaKey, chapterId, name, (index + 1).toUInt(), "jm-private-v1", null, null, null, sourceContext)
        }
    }
    override suspend fun completeOfflineCatalog() = chapters(SourcePageRequest.Start).items
    override suspend fun offlineIds() = emptySet<String>()
    override suspend fun downloadPage(page: SourcePageResource): PrivateDownloadPayload {
        request(page)
        val meta = synchronized(chapterData) { requireNotNull(chapterData[page.chapterId]) }
        val strips = JmImageRules.strips(meta.scramble, page.chapterId.toLong(), page.id.substringBeforeLast('.'))
        return PrivateDownloadPayload(client().image("media/photos/${page.chapterId}/${page.id}"), strips)
    }
    override suspend fun progress() = data.worldData(identity.world, ::isActive) { it.onlineProgress(source, manga) }
    override suspend fun chapterProgress(chapter: String) = data.worldData(identity.world, ::isActive) { it.chapterReading(source, manga, chapter) }
    override suspend fun save(page: SourcePageResource, mode: ReadingMode, fraction: Float, modeExplicit: Boolean) {
        request(page); require(fraction.isFinite() && fraction in 0f..1f)
        data.worldData(identity.world, ::isActive) { dao ->
            saveChapterProgress(dao, OnlineReadingProgress(source, manga, page.chapterId, page.id, page.ordinal.toInt(), fraction,
                mode.name, System.currentTimeMillis(), modeExplicit))
        }
    }
    override suspend fun dimensions(page: SourcePageResource): Pair<Int, Int> = pipeline.decode(request(page), { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 100_000 && bounds.outHeight <= 100_000) { "图片尺寸不受支持" }
        bounds.outWidth to bounds.outHeight
    }, {})
    override suspend fun image(page: SourcePageResource, width: Int, top: Int, bottom: Int?): Bitmap {
        val caller = currentCoroutineContext()
        return pipeline.decode(request(page), { bytes ->
            val ticket = epoch()
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            try {
                val end = bottom ?: decoder.height
                require(top >= 0 && end > top && end <= decoder.height)
                var sample = 1
                while (decoder.width / sample > width.coerceIn(256, 2048) ||
                    (end - top).toLong() / sample * (decoder.width / sample) > 2_097_152 ||
                    (bottom == null && (end - top) / sample > 4096)) sample *= 2
                val meta = synchronized(chapterData) { requireNotNull(chapterData[page.chapterId]) }
                val strips = JmImageRules.strips(meta.scramble, page.chapterId.toLong(), page.id.substringBeforeLast('.'))
                PrivateStripDecoder.decode(decoder, strips, top, end, sample) {
                    caller.ensureActive(); active()
                    if (!session.state.value.foreground || ticket != epoch()) throw CancellationException("Expired JM visibility")
                }
            } finally { decoder.recycle() }
        }, { it.eraseColor(Color.TRANSPARENT); it.recycle() })
    }
    override suspend fun clearImages() { revision.incrementAndGet(); data.clearMemory() }
    override suspend fun releaseImages() { clearImages(); pageCache.close() }
    override fun close() { client?.close(); ownerScope.cancel(); data.clearMemory() }
}
