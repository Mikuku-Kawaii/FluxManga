package com.flux.manga.data

import android.content.Context
import android.graphics.*
import com.flux.manga.BuildConfig
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Only generated pixels. Never registered as an online source and never accepts a URL/auth ref. */
internal class SyntheticPrivateReaderRepository(
    context: Context, private val session: WorldSession,
    namespace: String = NAMESPACE,
) : ReaderRepository {
    companion object {
        const val SOURCE = "m4-synthetic-private"
        const val MANGA = "generated-panels-v1"
        const val NAMESPACE = "m4-private-reader-synthetic-v1"
        // Instrumentation may isolate a real UI run without replacing existing sample progress.
        // Process-local only: never restored from an Intent, preferences or saved navigation.
        internal var admissionNamespace: String? = null
            set(value) {
                check(BuildConfig.DEBUG)
                require(value == null || value.startsWith("m4-private-reader-"))
                field = value
            }
        private val CHAPTERS = listOf("chapter-1", "chapter-2", "chapter-3", "chapter-4")
    }
    private val identity = session.identity.value
    init {
        require(BuildConfig.DEBUG && identity.world == PrototypeWorld.NSFW)
        require(namespace.startsWith("m4-private-reader-"))
    }
    private val data = WorldDataStore(context, namespace)
    override val mangaKey = SourceMangaKey(SourceWorld.NSFW, SOURCE, MANGA)
    private val owner = SampleKey(PrototypeWorld.NSFW, SOURCE, MANGA)
    private val sourceContext = SourceContext(SourceWorld.NSFW, SOURCE, identity.processSession, identity.generation.toULong(), null)
    private val revision = AtomicLong()
    private fun epoch() = session.state.value.visibilityEpoch to revision.get()
    override fun isActive() = session.accepts(identity)
    override fun active() { if (!isActive()) throw CancellationException("Expired synthetic reader session") }
    private fun request(page: SourcePageResource): ResourceKey {
        active()
        require(page.context == sourceContext && page.manga == mangaKey)
        require(page == chapterPages(page.chapterId).getOrNull(page.ordinal.toInt() - 1))
        return ResourceKey(owner, "${page.chapterId}/${page.id}")
    }
    private val pipeline = PrivateImagePipeline(owner, object : PrivateEncodedCache {
        override suspend fun read(key: ResourceKey, allowed: () -> Boolean) = data.resource(key, allowed)
        override suspend fun write(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean) = data.putResource(key, bytes, allowed)
        override fun clearMemory() = data.clearMemory()
    }, PrivateImageTransport { key ->
        // Fixed catalog lookup, not a URL parser or network fallback.
        val page = CHAPTERS.flatMap(::chapterPages)
            .single { "${it.chapterId}/${it.id}" == key.resourceId }
        val bitmap = Bitmap.createBitmap(page.width!!.toInt(), page.height!!.toInt(), Bitmap.Config.ARGB_8888)
        val output = object : ByteArrayOutputStream() { override fun close() { buf.fill(0); super.close() } }
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { textSize = 40f; isAntiAlias = true }
            for (top in 0 until bitmap.height step 240) {
                currentCoroutineContext().ensureActive()
                paint.color = if ((top / 240 + page.ordinal.toInt()) % 2 == 0) Color.rgb(31, 76, 102) else Color.rgb(145, 66, 24)
                canvas.drawRect(0f, top.toFloat(), bitmap.width.toFloat(), (top + 240).toFloat(), paint)
                paint.color = Color.WHITE
                canvas.drawText("SYNTHETIC ${page.chapterId.last()} / ${page.ordinal} / ${top / 240 + 1}", 16f, top + 80f, paint)
            }
            if (page.chapterId == "chapter-4") {
                // Incompressible generated RGB tests the >4 MiB path; no imported content.
                val row = IntArray(bitmap.width)
                val pixels = SyntheticNoisePixels()
                try {
                    for (y in 0 until bitmap.height) {
                        currentCoroutineContext().ensureActive()
                        for (x in row.indices) row[x] = pixels.next()
                        bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
                    }
                } finally { row.fill(0) }
            }
            // Only the new chapter is scrambled. Existing page bytes/cache keys stay compatible.
            val encoded = if (page.chapterId == "chapter-3") scramble(bitmap, stripCount(page)) else bitmap
            try {
                if (!encoded.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("合成图片生成失败")
            } finally { if (encoded !== bitmap) { encoded.eraseColor(Color.TRANSPARENT); encoded.recycle() } }
            val bytes = output.toByteArray()
            object : ByteArrayInputStream(bytes) { override fun close() { bytes.fill(0); super.close() } }
        } finally { output.close(); bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
    }, ::epoch, { ticket -> isActive() && session.state.value.foreground && ticket == epoch() })

    private fun chapterPages(chapter: String): List<SourcePageResource> {
        require(chapter in CHAPTERS)
        if (chapter == "chapter-4") return listOf(SourcePageResource(mangaKey, chapter, "noise-v1", 1u,
            "synthetic-large-v1", null, 1536u, 1536u, sourceContext))
        return (1..3).map { ordinal -> SourcePageResource(mangaKey, chapter, "page-$ordinal", ordinal.toUInt(),
            if (chapter == "chapter-3") "synthetic-strips-v1" else "synthetic-private-v1", null, 480u,
            if (chapter == "chapter-3") listOf(723u, 724u, 6003u)[ordinal - 1]
            else if (ordinal == 3) 6000u else 720u, sourceContext) }
    }
    private fun stripCount(page: SourcePageResource) = if (page.chapterId == "chapter-3")
        JmImageRules.strips(220980, 421926, page.ordinal.toString().padStart(5, '0')) else 0

    /** Inverse layout is only for producing a synthetic scrambled transport payload. */
    private suspend fun scramble(original: Bitmap, strips: Int): Bitmap {
        val encoded = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(encoded)
            for (part in imageStripPlan(original.height.toUInt(), strips.toUInt(), 0u, original.height.toUInt())) {
                currentCoroutineContext().ensureActive()
                canvas.drawBitmap(original, Rect(0, part.targetTop.toInt(), original.width,
                    (part.targetTop + part.height).toInt()), Rect(0, part.sourceTop.toInt(), original.width,
                    (part.sourceTop + part.height).toInt()), null)
            }
            return encoded
        } catch (error: Throwable) { encoded.eraseColor(Color.TRANSPARENT); encoded.recycle(); throw error }
    }
    override suspend fun pages(chapterId: String) = chapterPages(chapterId).also { active() }
    override suspend fun chapters(request: SourcePageRequest): SourceChapterPage {
        active(); require(request == SourcePageRequest.Start)
        return SourceChapterPage(sourceContext, CHAPTERS.mapIndexed { i, id ->
            SourceChapter(mangaKey, id, when (id) {
                "chapter-3" -> "合成章节 3 · 切片重组"
                "chapter-4" -> "合成章节 4 · 大页读取"
                else -> "合成章节 ${i + 1}"
            }, (i + 1).toUInt(), null)
        }, SourceContinuation.End, CHAPTERS.size.toUInt())
    }
    override suspend fun completeOfflineCatalog() = chapters(SourcePageRequest.Start).items
    override suspend fun offlineIds() = emptySet<String>()
    override suspend fun progress() = data.worldData(PrototypeWorld.NSFW, ::isActive) { active(); it.onlineProgress(SOURCE, MANGA) }
    override suspend fun chapterProgress(chapter: String) = data.worldData(PrototypeWorld.NSFW, ::isActive) { active(); it.chapterReading(SOURCE, MANGA, chapter) }
    override suspend fun save(page: SourcePageResource, mode: ReadingMode, fraction: Float, modeExplicit: Boolean) {
        request(page); require(fraction.isFinite() && fraction in 0f..1f)
        val coroutine = currentCoroutineContext()
        data.worldData(PrototypeWorld.NSFW, ::isActive) { dao ->
            coroutine.ensureActive(); active()
            saveChapterProgress(dao, OnlineReadingProgress(SOURCE, MANGA, page.chapterId, page.id,
                page.ordinal.toInt(), fraction, mode.name, System.currentTimeMillis(), modeExplicit))
            coroutine.ensureActive(); active()
        }
    }
    override suspend fun dimensions(page: SourcePageResource): Pair<Int, Int> = pipeline.decode(request(page), { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        checkDimensions(page, bounds.outWidth, bounds.outHeight)
        bounds.outWidth to bounds.outHeight
    }, {})
    private fun checkDimensions(page: SourcePageResource, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toUInt() != page.width || height.toUInt() != page.height)
            throw IOException("图片尺寸不符，请返回章节重试")
    }
    override suspend fun image(page: SourcePageResource, width: Int, top: Int, bottom: Int?): Bitmap {
        val coroutine = currentCoroutineContext()
        return pipeline.decode(request(page), { bytes ->
            val ticket = epoch()
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            try {
                checkDimensions(page, decoder.width, decoder.height)
                val end = bottom ?: decoder.height
                if (top < 0 || end <= top || end > decoder.height) throw IOException("图片分段无效")
                var sample = 1
                while (decoder.width / sample > width.coerceIn(256, 2048) ||
                    (end - top).toLong() / sample * (decoder.width / sample) > 2_097_152 ||
                    (bottom == null && (end - top) / sample > 4096)) sample *= 2
                if (stripCount(page) == 0) {
                    decoder.decodeRegion(Rect(0, top, decoder.width, end), BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: throw IOException("图片解码失败")
                } else PrivateStripDecoder.decode(decoder, stripCount(page), top, end, sample) {
                    coroutine.ensureActive(); active()
                    if (!session.state.value.foreground || ticket != epoch()) throw CancellationException("Expired image visibility")
                }
            } finally { decoder.recycle() }
        }, { it.recycle() })
    }
    override suspend fun clearImages() { revision.incrementAndGet(); data.clearMemory() }
}
