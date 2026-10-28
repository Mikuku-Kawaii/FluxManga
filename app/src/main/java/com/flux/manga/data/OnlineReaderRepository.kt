package com.flux.manga.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

internal class OnlineReaderRepository(context: Context, private val session: WorldSession,
    private val sourceId: String, private val mangaId: String, private val offlineEnabled: Boolean = true, private val offlineOnly: Boolean = false) : ReaderRepository {
    private val baozi = isBaoziSource(sourceId)
    private val copy = isCopySource(sourceId)
    private val access = if (copy || baozi) null else KomgaAccess(context, session, sourceId)
    private val identity = session.identity.value
    private val app = context.applicationContext
    private val data = WorldDataStore(context)
    private val sourceDirectory = java.security.MessageDigest.getInstance("SHA-256").digest(sourceId.toByteArray()).joinToString("") { "%02x".format(it) }
    private val directory = File(data.cacheDirectory(PrototypeWorld.SFW), "online-reader/$sourceDirectory")
    private val key = SourceMangaKey(SourceWorld.SFW, sourceId, mangaId)
    override val mangaKey get() = key
    private val files = LinkedHashMap<String, SourceImageFile>(4, .75f, true)
    // Encoded files survive paging and chapter changes for this reader entry, not in bitmap RAM.
    private val fileLock = Mutex()
    private val downloadedPaths = mutableMapOf<String,String>()
    private val downloadFiles = DownloadFiles(File(data.directory(PrototypeWorld.SFW), "downloads"))
    companion object {
        private val prepared = mutableSetOf<String>()
        @Synchronized private fun prepare(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建图片缓存")
            // Keep the existing restart cleanup, once per source. Never remove another
            // live reader's files when opening a second entry in this process.
            if (prepared.add(directory.path)) {
                directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("flux-image-[0-9]+-[0-9]+\\.tmp")) }?.forEach { it.delete() }
            }
        }
    }
    override fun isActive() = identity.world == PrototypeWorld.SFW && session.accepts(identity) && (access?.isActive() ?: true)
    override fun active() { if (!isActive()) throw CancellationException("Expired reader session") }
    private suspend fun copyClient(): WorldCopyMangaClient { active(); return copyMangaClient(session, sourceId) }
    override suspend fun pages(chapterId: String): List<SourcePageResource> {
        active()
        if(offlineEnabled) {
            val job=DownloadStore(data.accountImportDatabase()).list { isActive() }.firstOrNull {
                it.source==sourceId && it.manga==mangaId && it.chapter==chapterId && it.status==DownloadStatus.COMPLETE
            }
            if(job!=null) return withContext(Dispatchers.IO) {
                active()
                if(!job.pages.indices.all { downloadFiles.valid(job,it) }) throw IOException("已下载章节缺失或校验失败，请在下载队列删除此副本后重新下载")
                val context=identity.sourceContext(sourceId)
                job.pages.forEachIndexed { index,p -> downloadedPaths["$chapterId:${p.id}"]=downloadFiles.file(job,index).path }
                downloadedResources(job,context).also { ensureActive(); active() }
            }
        }
        if(offlineOnly) throw IOException("此章节尚未下载完成，请返回目录选择已下载章节")
        val result = if (baozi) withContext(Dispatchers.IO) { session.baoziSources.client(app, sourceId).source.pages(mangaId, chapterId) } else if (copy) copyClient().pages(mangaId, chapterId) else requireNotNull(access).client().pages(key, chapterId)
        active()
        if (result.continuation !is SourceContinuation.End || result.items.isEmpty()) throw IOException("章节没有可阅读的图片")
        return result.items
    }
    override suspend fun chapters(request: SourcePageRequest): SourceChapterPage {
        active()
        if(offlineOnly) {
            val store=DownloadStore(data.accountImportDatabase())
            val catalog=OfflineCatalogStore(data.accountImportDatabase()).load(sourceId,mangaId) { isActive() }
            val chapters=offlineChapters(sourceId,mangaId,catalog,store.list { isActive() })
            val number=when(request) { SourcePageRequest.Start -> 1; is SourcePageRequest.Page -> request.number.toInt(); else -> throw IOException("离线目录分页无效") }
            require(number in 1..101)
            val offset=(number-1)*100
            return SourceChapterPage(identity.sourceContext(sourceId),chapters.drop(offset).take(100),
                if(offset+100>=chapters.size) SourceContinuation.End else SourceContinuation.Next(SourcePageRequest.Page((number+1).toUInt())), chapters.size.toUInt())
        }
        return (if (baozi) withContext(Dispatchers.IO) { session.baoziSources.client(app, sourceId).source.chapterPage(mangaId, request) } else if (copy) copyClient().allChapters(mangaId, request) else requireNotNull(access).client().chapters(key, request)).also { active() }
    }
    override suspend fun completeOfflineCatalog(): List<SourceChapter> {
        active()
        val catalog = OfflineCatalogStore(data.accountImportDatabase()).load(sourceId, mangaId) { isActive() }
        return completeReaderCatalog(catalog, sourceId, mangaId)
    }
    override suspend fun offlineIds(): Set<String> = DownloadStore(data.accountImportDatabase()).list { isActive() }
        .filter { it.source==sourceId && it.manga==mangaId && it.status==DownloadStatus.COMPLETE }.map { it.chapter }.toSet()
    override suspend fun progress(): OnlineReadingProgress? = data.localData { active(); it.onlineProgress(sourceId, mangaId) }
    override suspend fun chapterProgress(chapter: String): ChapterReading? = data.localData { active(); it.chapterReading(sourceId, mangaId, chapter) }
    override suspend fun save(page: SourcePageResource, mode: ReadingMode, fraction: Float, modeExplicit: Boolean) {
        require(page.manga == key && fraction.isFinite() && fraction in 0f..1f)
        require(page.context.processSession == identity.processSession && page.context.generation == identity.generation.toULong() && page.context.sourceId == sourceId && page.context.world == SourceWorld.SFW)
        data.localData { dao ->
            active()
            saveChapterProgress(dao, OnlineReadingProgress(sourceId, mangaId, page.chapterId, page.id,
                page.ordinal.toInt(), fraction, mode.name, System.currentTimeMillis(), modeExplicit))
        }
    }
    suspend fun downloadFile(page: SourcePageResource, directory: File): SourceImageFile {
        active(); require(page.manga==key)
        val file=if (baozi) session.baoziSources.client(app, sourceId).pageFile(page, directory.path)
            else if (copy) copyClient().pageFile(page, directory.path)
            else requireNotNull(access).client().pageFile(key, page.chapterId, page.ordinal, directory.path)
        try { currentCoroutineContext().ensureActive(); active(); return file }
        catch(e: Exception) { file.close(); throw e }
    }
    private suspend fun path(page: SourcePageResource): String {
        downloadedPaths["${page.chapterId}:${page.id}"]?.let { if(!File(it).isFile) throw IOException("下载副本已移除"); return it }
        return file(page).path()
    }
    private suspend fun file(page: SourcePageResource): SourceImageFile {
        require(page.manga == key)
        val id = "${page.chapterId}:${page.id}"
        files[id]?.let { return it }
        prepare(directory)
        val owned = downloadFile(page, directory)
        files[id] = owned
        return owned
    }
    override suspend fun dimensions(page: SourcePageResource): Pair<Int, Int> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            active()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path(page), bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("图片格式无法解码")
            currentCoroutineContext().ensureActive(); active()
            bounds.outWidth to bounds.outHeight
        }
    }
    override suspend fun image(page: SourcePageResource, width: Int, top: Int, bottom: Int?): Bitmap {
        var decoded: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                fileLock.withLock {
                    active()
                    val id = "${page.chapterId}:${page.id}"
                    try {
                        val decoder = BitmapRegionDecoder.newInstance(path(page))
                            ?: throw IOException("图片格式无法解码")
                        try {
                            val end = bottom ?: decoder.height
                            if (top < 0 || end <= top || end > decoder.height ||
                                (bottom != null && (page.width?.toInt() != decoder.width || page.height?.toInt() != decoder.height)))
                                throw IOException("图片尺寸已变化，请重新打开章节")
                            var sample = 1
                            while (decoder.width / sample > width.coerceIn(256, 2048) ||
                                (end - top).toLong() / sample * (decoder.width / sample) > 2_097_152 ||
                                (bottom == null && (end - top) / sample > 4096)) sample *= 2
                            val bitmap = decoder.decodeRegion(Rect(0, top, decoder.width, end),
                                BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("图片解码失败")
                            decoded = bitmap
                            currentCoroutineContext().ensureActive(); active()
                            bitmap
                        } finally { decoder.recycle() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        files.remove(id)?.close()
                        throw failure
                    }
                }
            }
        } catch (failure: Exception) { decoded?.recycle(); throw failure }
    }
    override suspend fun clearImages() = Unit
    override suspend fun releaseImages() = withContext(NonCancellable + Dispatchers.IO) {
        fileLock.withLock { files.values.forEach { it.close() }; files.clear() }
    }
}
