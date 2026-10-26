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

internal class PrivateOfflineReader(context: Context, private val session: WorldSession, private val source: String,
    private val manga: String) : ReaderRepository {
    private val identity = session.identity.value
    private val store = PrivateDownloadStore(context)
    private val data = WorldDataStore(context)
    private val revision = AtomicLong()
    private val decodeLock = Mutex()
    private var closed = false
    override val mangaKey = SourceMangaKey(SourceWorld.NSFW, source, manga)
    private val sourceContext = SourceContext(SourceWorld.NSFW, source, identity.processSession, identity.generation.toULong(), source)
    init { require(identity.world == PrototypeWorld.NSFW && isPrivateComicSource(source)) }
    override fun isActive() = !closed && session.accepts(identity)
    override fun active() { if (!isActive()) throw CancellationException() }
    private suspend fun jobs() = store.jobs.list(::isActive).filter { it.source == source && it.manga == manga && it.status == DownloadStatus.COMPLETE }
    private suspend fun job(chapter: String) = jobs().firstOrNull { it.chapter == chapter } ?: throw IOException("此章节未完整下载，请返回下载页")
    override suspend fun pages(chapterId: String): List<SourcePageResource> {
        active(); val job = job(chapterId)
        return job.pages.mapIndexed { index, page -> SourcePageResource(mangaKey, chapterId, page.id, (index + 1).toUInt(),
            "private-offline:${job.id}", null, null, null, sourceContext) }
    }
    override suspend fun completeOfflineCatalog() = store.catalog(source, manga, ::isActive).also {
        if (it.isEmpty()) throw IOException("离线目录缺失，请联网重新读取目录")
    }
    override suspend fun chapters(request: SourcePageRequest): SourceChapterPage {
        require(request == SourcePageRequest.Start)
        val chapters = completeOfflineCatalog()
        return SourceChapterPage(sourceContext, chapters, SourceContinuation.End, chapters.size.toUInt())
    }
    override suspend fun offlineIds() = jobs().map { it.chapter }.toSet()
    override suspend fun progress() = data.worldData(identity.world, ::isActive) { it.onlineProgress(source, manga) }
    override suspend fun chapterProgress(chapter: String) = data.worldData(identity.world, ::isActive) { it.chapterReading(source, manga, chapter) }
    private fun check(page: SourcePageResource) {
        active(); require(page.manga == mangaKey && page.context == sourceContext && page.ordinal > 0u)
    }
    override suspend fun save(page: SourcePageResource, mode: ReadingMode, fraction: Float, modeExplicit: Boolean) {
        check(page); require(fraction.isFinite() && fraction in 0f..1f)
        data.worldData(identity.world, ::isActive) { dao -> saveChapterProgress(dao, OnlineReadingProgress(source, manga,
            page.chapterId, page.id, page.ordinal.toInt(), fraction, mode.name, System.currentTimeMillis(), modeExplicit)) }
    }
    private suspend fun <T> decode(page: SourcePageResource, release: (T) -> Unit, action: (PrivateDownloadPayload, () -> Unit) -> T): T {
        check(page)
        val epoch = session.state.value.visibilityEpoch to revision.get()
        val caller = currentCoroutineContext()
        val allowed = { caller.isActive && isActive() && session.state.value.foreground && epoch == (session.state.value.visibilityEpoch to revision.get()) }
        var owned: T? = null
        fun guard() { if (!allowed()) throw CancellationException() }
        try {
            return withContext(Dispatchers.IO) {
              decodeLock.withLock {
                guard(); val job = job(page.chapterId); val index = page.ordinal.toInt() - 1
                require(job.pages.getOrNull(index)?.id == page.id)
                val payload = store.read(job, index, allowed) ?: throw IOException("离线页缺失，请删除该章节副本后重新下载")
                payload.use {
                    require(it.bytes.size.toLong() == job.pages[index].bytes && it.hash() == job.pages[index].hash) { "离线页内容不一致" }
                    guard(); owned = action(it, ::guard); guard(); requireNotNull(owned)
                }
              }
            }
        } catch (failure: Throwable) { owned?.let(release); throw failure }
    }
    override suspend fun dimensions(page: SourcePageResource): Pair<Int, Int> = decode(page, {}) { image, _ ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        require(options.outWidth in 1..100000 && options.outHeight in 1..100000)
        options.outWidth to options.outHeight
    }
    override suspend fun image(page: SourcePageResource, width: Int, top: Int, bottom: Int?): Bitmap = decode(page, { it.eraseColor(Color.TRANSPARENT); it.recycle() }) { image, guard ->
        val decoder = BitmapRegionDecoder.newInstance(image.bytes, 0, image.bytes.size)
        try {
            val end = bottom ?: decoder.height
            require(top >= 0 && end > top && end <= decoder.height)
            var sample = 1
            while (decoder.width / sample > width.coerceIn(256, 2048) || (end - top).toLong() / sample * (decoder.width / sample) > 2_097_152 ||
                (bottom == null && (end - top) / sample > 4096)) sample *= 2
            PrivateStripDecoder.decode(decoder, image.strips, top, end, sample, guard)
        } finally { decoder.recycle() }
    }
    override suspend fun clearImages() { revision.incrementAndGet(); data.clearMemory() }
    override fun close() { closed = true; revision.incrementAndGet(); data.clearMemory() }
}
