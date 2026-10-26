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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class PicaReaderRepository(context: Context, private val session: WorldSession, private val source: String,
    private val manga: String) : PrivateDownloadReader {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    private val store = PicaStore(context, session)
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val setup = Mutex()
    private var client: PicaClient? = null
    private var directory: List<PicaChapter>? = null
    private val images = ConcurrentHashMap<String, PicaImage>()
    private val revision = AtomicLong()
    override val mangaKey = SourceMangaKey(SourceWorld.NSFW, source, manga)
    private val contextKey = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    private val imageOwner = SampleKey(identity.world, source, manga)
    init { require(identity.world == PrototypeWorld.NSFW && isPicaSource(source) && picaId(manga)) }
    override fun isActive() = session.accepts(identity)
    override fun active() { if (!isActive()) throw CancellationException("Expired PicACG reader") }
    private suspend fun client(): PicaClient = setup.withLock {
        active()
        client ?: store.account().let { account ->
            if (account?.source != source) throw IOException("请在图源管理登录原 PicACG 账号")
            PicaClient(session, account, owner).also { client = it }
        }
    }
    private suspend fun directory(): List<PicaChapter> = directory ?: client().allChapters(manga).also { rows ->
        val book = client().detail(manga); store.remember(source, book); active(); directory = rows
    }
    private fun epoch() = session.state.value.visibilityEpoch to revision.get()
    private fun request(page: SourcePageResource): ResourceKey {
        active()
        require(page.manga == mangaKey && page.context == contextKey && images["${page.chapterId}/${page.id}"]?.ordinal == page.ordinal.toInt())
        return ResourceKey(imageOwner, "${page.chapterId}/${page.id}")
    }
    private val pageCache = ReaderEncodedCache(data)
    private val pipeline = PrivateImagePipeline(imageOwner, pageCache, PrivateImageTransport { key ->
        privateImageInput(client().image(requireNotNull(images[key.resourceId]).url))
    }, ::epoch, { ticket -> isActive() && session.state.value.foreground && ticket == epoch() })
    override suspend fun chapters(request: SourcePageRequest): SourceChapterPage {
        val number = when (request) { SourcePageRequest.Start -> 1; is SourcePageRequest.Page -> request.number.toInt(); else -> throw IOException("章节分页无效") }
        require(number > 0)
        val all = directory(); val start = Math.multiplyExact(number - 1, 100)
        return SourceChapterPage(contextKey, all.drop(start).take(100).map { SourceChapter(mangaKey, it.order.toString(), it.title, it.order.toUInt(), null) },
            if (start + 100 < all.size) SourceContinuation.Next(SourcePageRequest.Page((number + 1).toUInt())) else SourceContinuation.End, all.size.toUInt())
    }
    override suspend fun pages(chapterId: String): List<SourcePageResource> {
        val order = chapterId.toIntOrNull() ?: throw IOException("章节标识无效")
        require(directory().any { it.order == order }) { "章节不属于当前作品" }
        val list = client().pages(manga, order); active()
        return list.map { image ->
            images["$chapterId/${image.id}"] = image
            SourcePageResource(mangaKey, chapterId, image.id, image.ordinal.toUInt(), "picacg-private-v1", null, null, null, contextKey)
        }
    }
    override suspend fun completeOfflineCatalog() = directory().map { SourceChapter(mangaKey, it.order.toString(), it.title, it.order.toUInt(), null) }
    override suspend fun offlineIds() = emptySet<String>()
    override suspend fun downloadPage(page: SourcePageResource): PrivateDownloadPayload {
        val key = request(page)
        return PrivateDownloadPayload(client().image(requireNotNull(images[key.resourceId]).url))
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
        require(options.outWidth in 1..100000 && options.outHeight in 1..100000) { "图片尺寸不受支持" }
        options.outWidth to options.outHeight
    }, {})
    override suspend fun image(page: SourcePageResource, width: Int, top: Int, bottom: Int?): Bitmap = pipeline.decode(request(page), { bytes ->
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        try {
            val end = bottom ?: decoder.height
            require(top >= 0 && end > top && end <= decoder.height)
            var sample = 1
            while (decoder.width / sample > width.coerceIn(256, 2048) || (end - top).toLong() / sample * (decoder.width / sample) > 2_097_152 ||
                (bottom == null && (end - top) / sample > 4096)) sample *= 2
            decoder.decodeRegion(Rect(0, top, decoder.width, end), BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("图片解码失败")
        } finally { decoder.recycle() }
    }, { it.recycle() })
    override suspend fun clearImages() { revision.incrementAndGet(); data.clearMemory() }
    override suspend fun releaseImages() { clearImages(); pageCache.close() }
    override fun close() { client?.close(); owner.cancel(); images.clear(); data.clearMemory() }
}
