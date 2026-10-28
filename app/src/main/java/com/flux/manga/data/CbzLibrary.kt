package com.flux.manga.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class CbzSource(val displayName: String, val open: () -> InputStream)
data class ImportResult(val key: MangaKey, val duplicate: Boolean, val repaired: Boolean = false)

class CbzLibrary(private val store: WorldDataStore, private val output: (File) -> OutputStream = { FileOutputStream(it) }) {
    companion object {
        private val lock = Mutex()
        // Backup uses the same order as imports/removal: library lock, then world DB lock.
        internal suspend fun <T> portableAccess(action: suspend () -> T): T = lock.withLock { action() }
        // Protected by lock; an import awaiting the user's preview is not an orphan.
        private val activeImports = mutableSetOf<String>()
        private val readerDecodes = Semaphore(2)
        suspend fun source(context: Context, uri: Uri): CbzSource = withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: throw CbzException(ImportFailure.PERMISSION)
            CbzSource(name) { resolver.openInputStream(uri) ?: throw CbzException(ImportFailure.PERMISSION) }
        }
    }
    internal val directory get() = File(store.directory(PrototypeWorld.SFW), "cbz")
    internal val coverDirectory get() = File(store.cacheDirectory(PrototypeWorld.SFW), "cbz-covers")
    private val coverPolicy get() = CoverCachePolicyStore(File(store.directory(PrototypeWorld.SFW), "cover-cache-policy"))
    private fun coverCache() = OwnedCacheFiles(coverDirectory, Regex("[0-9a-f-]{36}\\.png(\\.new|\\.bak)?"))
    internal suspend fun coverCacheUsage(): CacheUsage = withContext(Dispatchers.IO) { lock.withLock { coverCache().usage() } }
    internal suspend fun coverCacheSettings(): CoverCacheSettings = withContext(Dispatchers.IO) {
        lock.withLock {
            val usage = coverCache().usage()
            try { CoverCacheSettings(usage, coverPolicy.read()) }
            catch (_: IOException) { CoverCacheSettings(usage, null, "封面缓存设置读取失败，请刷新重试。") }
        }
    }
    internal suspend fun applyCoverCachePolicy(value: CoverCachePolicy, allowed: () -> Boolean): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val job = currentCoroutineContext()
            val active = { job.ensureActive(); if (!allowed()) throw CancellationException("Expired cache settings session") }
            active()
            // Persist first; interrupted pruning must not silently restore the old policy.
            coverPolicy.write(value)
            try {
                val removed = value.bytes?.let { coverCache().trimTo(it, active) }
                active()
                if (removed == null) "封面缓存设置已保存：${value.label}"
                else "封面缓存设置已保存：${value.label}；已清理 ${removed.files} 个文件（${removed.bytes} 字节）" +
                    if (removed.failed > 0) "；${removed.failed} 个文件未清理，可刷新后再次整理" else ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "设置已保存，缓存整理未完成，请刷新后重试。" }
        }
    }
    internal suspend fun clearCoverCache(allowed: () -> Boolean): CacheCleared = withContext(Dispatchers.IO) {
        lock.withLock {
            val job = currentCoroutineContext()
            coverCache().clear(active = { job.ensureActive(); if (!allowed()) throw CancellationException("Expired cleanup session") })
        }
    }
    internal fun coverFile(key: MangaKey): File {
        checkWorld(key.world)
        require(key.sourceId == "cbz" && key.mangaId.matches(Regex("[0-9a-f-]{36}")))
        return File(coverDirectory, "${key.mangaId}.png")
    }
    private val ownedName = Regex("[0-9a-f-]{36}\\.(part|cbz)")
    private fun file(name: String): File {
        require(ownedName.matches(name))
        return File(directory, name)
    }
    private fun available(book: LocalBook) = file(book.archiveName).let { it.isFile && it.length() == book.archiveBytes }
    private fun key(book: LocalBook) = MangaKey(PrototypeWorld.SFW, book.sourceId, book.mangaId)
    private fun checkWorld(world: PrototypeWorld) { if (world != PrototypeWorld.SFW) throw CbzException(ImportFailure.WORLD) }
    private suspend fun recover() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create private import directory")
        val books = store.localData { it.localBooks() }
        val committed = books.map { it.archiveName }.toSet()
        val covers = books.map { "${it.mangaId}.png" }.toSet()
        val orphans = directory.listFiles().orEmpty().filter { ownedName.matches(it.name) && it.name !in committed && it.absolutePath !in activeImports } +
            coverDirectory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f-]{36}\\.png(\\.new|\\.bak)?")) && it.name.removeSuffix(".new").removeSuffix(".bak") !in covers }
        if (orphans.any { !it.delete() && it.exists() }) throw IOException("书目已更新，部分副本或缓存未清理，请重试。")
    }
    /** SQLite is the commit point; orphan recovery finishes interrupted file cleanup. */
    suspend fun remove(key: MangaKey, allowed: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        lock.withLock {
            val job = currentCoroutineContext()
            store.localData { dao ->
                job.ensureActive()
                if (!allowed()) throw CancellationException("Expired removal session")
                dao.removeBookTags("local", key.sourceId, key.mangaId)
                dao.deleteVisit("local", key.sourceId, key.mangaId)
                dao.removeBook(key.sourceId, key.mangaId) // FK cascades pages and progress; retry is idempotent.
            }
            withContext(NonCancellable) { recover() }
        }
    }
    suspend fun cards(world: PrototypeWorld): List<MangaCard> = withContext(Dispatchers.IO) {
        checkWorld(world)
        lock.withLock {
            recover()
            store.localData { dao -> dao.localBooks().map { MangaCard(key(it), it.title, dao.localPages(it.sourceId, it.mangaId).size, available(it), it.favorite, dao.progress(it.sourceId, it.mangaId), it.importedAt,
                ComicInfo(it.metadataTitle, it.author, it.volume, it.summary)) } }
        }
    }
    suspend fun detail(key: MangaKey): MangaDetail? = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        lock.withLock {
            recover()
            store.localData { dao -> dao.localBook(key.sourceId, key.mangaId)?.let { book ->
                val pages = dao.localPages(key.sourceId, key.mangaId).map {
                    PageResource(key, "cbz-1", it.entryName, it.ordinal, it.entryName, it.mimeType, it.width, it.height)
                }
                MangaDetail(MangaCard(key, book.title, pages.size, available(book), book.favorite, dao.progress(key.sourceId, key.mangaId), book.importedAt,
                    ComicInfo(book.metadataTitle, book.author, book.volume, book.summary)), Chapter(key, "cbz-1", pages), book.archiveBytes, book.sha256,
                    book.defaultTitle, book.coverPageId, ComicInfo(book.metadataTitle, book.author, book.volume, book.summary))
            } }
        }
    }
    suspend fun setFavorite(key: MangaKey, favorite: Boolean, allowed: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        lock.withLock {
            val job = currentCoroutineContext()
            store.localData { dao ->
                job.ensureActive()
                if (!allowed()) throw CancellationException("Expired favorite session")
                if (dao.setFavorite(key.sourceId, key.mangaId, favorite) != 1) throw IOException("未找到本地书目，请刷新后重试")
            }
        }
    }
    /** Update only presentation fields; the existing book identity, pages and progress remain intact. */
    suspend fun editInformation(key: MangaKey, title: String, coverPageId: String?, allowed: () -> Boolean) = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        require(key.sourceId == "cbz")
        val displayTitle = title.trim()
        require(displayTitle.isNotEmpty() && displayTitle.length <= 200 && displayTitle.none { it == '\n' || it == '\r' })
        lock.withLock {
            val context = currentCoroutineContext()
            fun active() { context.ensureActive(); if (!allowed()) throw CancellationException("Expired book editor session") }
            active()
            store.localData { dao ->
                active()
                val book = dao.localBook(key.sourceId, key.mangaId) ?: throw IOException("本地书目已不存在")
                if (coverPageId != null && dao.localPage(key.sourceId, key.mangaId, coverPageId) == null)
                    throw IOException("所选封面页已不存在，请重新读取书目")
                if (book.coverPageId != coverPageId) {
                    // All cover reads share this lock. Delete before DB commit so interruption cannot
                    // leave a new selection paired with old pixels. Cache failure leaves metadata unchanged.
                    val cached = coverFile(key)
                    android.util.AtomicFile(cached).delete()
                    if (listOf(cached, File(cached.path + ".new"), File(cached.path + ".bak")).any { it.exists() })
                        throw IOException("封面缓存清理失败，书目信息未保存，请重试。")
                }
                dao.updateBook(book.copy(title = displayTitle, coverPageId = coverPageId))
                dao.updateLocalVisitTitle(key.sourceId, key.mangaId, displayTitle)
                active()
            }
            active()
        }
    }
    suspend fun cover(detail: MangaDetail): Bitmap? = cover(detail.card.key)
    suspend fun inspectComicInfo(key: MangaKey, allowed: () -> Boolean): ComicInfoResult = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        lock.withLock {
            val context = currentCoroutineContext()
            val checkpoint = { context.ensureActive(); if (!allowed()) throw CancellationException("Expired metadata session") }
            checkpoint()
            val book = store.localData { it.localBook(key.sourceId, key.mangaId) } ?: throw IOException("本地书目已不存在")
            if (!available(book)) throw CbzException(ImportFailure.MISSING)
            ComicInfoReader.read(file(book.archiveName), checkpoint).also { checkpoint() }
        }
    }
    /** Explicit enrichment only fills absent metadata; never changes custom display fields. */
    suspend fun supplementComicInfo(key: MangaKey, info: ComicInfo, allowed: () -> Boolean) = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        require(key.sourceId == "cbz")
        lock.withLock {
            val context = currentCoroutineContext()
            fun check() { context.ensureActive(); if (!allowed()) throw CancellationException("Expired metadata session") }
            check()
            store.localData { dao ->
                check()
                val book = dao.localBook(key.sourceId, key.mangaId) ?: throw IOException("本地书目已不存在")
                dao.updateBook(book.copy(metadataTitle = book.metadataTitle?.takeIf { it.isNotBlank() } ?: info.title,
                    author = book.author?.takeIf { it.isNotBlank() } ?: info.author,
                    volume = book.volume?.takeIf { it.isNotBlank() } ?: info.volume,
                    summary = book.summary?.takeIf { it.isNotBlank() } ?: info.summary))
                check()
            }
            check()
        }
    }
    /** Resolve the current selection rather than trusting an old detail snapshot. */
    suspend fun cover(key: MangaKey): Bitmap? = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        lock.withLock {
            recover()
            val input = store.localData { dao ->
                dao.localBook(key.sourceId, key.mangaId)?.let { book ->
                    book to (book.coverPageId?.let { dao.localPage(key.sourceId, key.mangaId, it)
                        ?: throw IOException("封面页已不存在，请编辑书目信息恢复默认封面") }
                        ?: dao.firstLocalPage(key.sourceId, key.mangaId))
                }
            } ?: return@withLock null
            val (book, page) = input
            if (!available(book)) throw CbzException(ImportFailure.MISSING)
            page ?: return@withLock null
            decodeCover(book, page.entryName, page.width, page.height)
        }
    }
    /** Caller holds the library lock; cache, archive and bitmap ownership match both entry points. */
    private suspend fun decodeCover(book: LocalBook, entryName: String, width: Int, height: Int): Bitmap? {
        val job = currentCoroutineContext()
        job.ensureActive()
        val cached = coverFile(key(book))
        // A failed preference read must not grow the disk cache or prevent reading the original copy.
        val policy = try { coverPolicy.read() } catch (_: IOException) { CoverCachePolicy.MEMORY_ONLY }
        if (policy != CoverCachePolicy.MEMORY_ONLY && cached.isFile) android.graphics.BitmapFactory.decodeFile(cached.path)?.let {
            cached.setLastModified(System.currentTimeMillis())
            return it
        }
        val bitmap = ZipFile(file(book.archiveName)).use { CbzArchive.decode(it, entryName, width, height) }
            ?: throw CbzException(ImportFailure.CORRUPT)
        if (policy == CoverCachePolicy.MEMORY_ONLY) return bitmap
        // Cache is disposable: an unavailable cache must not make a readable book fail.
        try {
            job.ensureActive()
            check(coverDirectory.isDirectory || coverDirectory.mkdirs())
            val atomic = android.util.AtomicFile(cached)
            val stream = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                atomic.finishWrite(stream)
            } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
            // Bitmap already belongs to the caller; removing even this file does not invalidate it.
            policy.bytes?.let { coverCache().trimTo(it, active = { job.ensureActive() }) }
        } catch (cancelled: CancellationException) { bitmap.recycle(); throw cancelled }
        catch (_: IOException) { } catch (_: IllegalStateException) { }
        return bitmap
    }
    suspend fun saveProgress(key: MangaKey, pageId: String, fraction: Float, mode: ReadingMode, modeExplicit: Boolean = true,
        allowed: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        checkWorld(key.world)
        require(fraction.isFinite() && fraction in 0f..1f)
        val job = currentCoroutineContext()
        store.localData { dao ->
            job.ensureActive()
            if (!allowed()) throw CancellationException("Expired reader session")
            val page = dao.localPages(key.sourceId, key.mangaId).firstOrNull { it.entryName == pageId }
                ?: throw IOException("阅读页已不存在，请重新打开书目")
            dao.putProgress(ReadingProgress(key.sourceId, key.mangaId, "cbz-1", page.entryName,
                page.ordinal, fraction, mode.name, System.currentTimeMillis(), modeExplicit))
        }
    }

    /** Region output is bounded; no decoded book cache and at most two concurrent decodes. */
    suspend fun readPage(page: PageResource, top: Int = 0, bottom: Int = page.height,
        targetWidth: Int = 1080, wholePage: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        checkWorld(page.manga.world)
        require(top >= 0 && bottom > top && bottom <= page.height)
        readerDecodes.withPermit {
            currentCoroutineContext().ensureActive()
            val book = store.localData { dao ->
                val actual = dao.localPages(page.manga.sourceId, page.manga.mangaId).firstOrNull { it.entryName == page.id }
                    ?: throw IOException("未找到正文页")
                if (actual.entryName != page.entryName || actual.width != page.width || actual.height != page.height) throw IOException("正文页信息已变化")
                dao.localBook(page.manga.sourceId, page.manga.mangaId)
            } ?: throw IOException("未找到本地书目")
            if (!available(book)) throw CbzException(ImportFailure.MISSING)
            val widthLimit = targetWidth.coerceIn(256, 2048)
            var sample = 1
            while (page.width / sample > widthLimit || (bottom - top).toLong() / sample * (page.width / sample) > 2_097_152L ||
                (wholePage && (bottom - top) / sample > 4096)) sample *= 2
            val bitmap = com.flux.manga.traceWork("FluxDecodeRegion") { ZipFile(file(book.archiveName)).use { zip ->
                val entry = zip.getEntry(page.entryName) ?: throw CbzException(ImportFailure.CORRUPT)
                zip.getInputStream(entry).use { input ->
                    val decoder = android.graphics.BitmapRegionDecoder.newInstance(input) ?: throw CbzException(ImportFailure.CORRUPT)
                    try {
                        if (decoder.width != page.width || decoder.height != page.height) throw CbzException(ImportFailure.CORRUPT)
                        decoder.decodeRegion(android.graphics.Rect(0, top, page.width, bottom),
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw CbzException(ImportFailure.CORRUPT)
                    } finally { decoder.recycle() }
                }
            }
            }
            try { currentCoroutineContext().ensureActive(); bitmap }
            catch (cancelled: CancellationException) { bitmap.recycle(); throw cancelled }
        }
    }
    suspend fun import(world: PrototypeWorld, source: CbzSource, allowed: () -> Boolean = { true }): ImportResult =
        importWithPreview(world, source, null, allowed)

    suspend fun importWithPreview(world: PrototypeWorld, source: CbzSource,
        preview: (suspend (CbzImportPreview) -> Boolean)?, allowed: () -> Boolean): ImportResult = withContext(Dispatchers.IO) {
        checkWorld(world)
        if (!source.displayName.endsWith(".cbz", ignoreCase = true)) throw CbzException(ImportFailure.NOT_CBZ)
        val job = currentCoroutineContext()
        val checkpoint = { job.ensureActive(); if (!allowed()) throw CancellationException("Expired import session") }
        val id = UUID.randomUUID().toString()
        val partial = file("$id.part")
        val destination = file("$id.cbz")
        try {
            val prepared = lock.withLock {
                activeImports += partial.absolutePath
                recover(); checkpoint()
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val opened = try { source.open() } catch (_: FileNotFoundException) { throw CbzException(ImportFailure.PERMISSION) }
                opened.use { input ->
                    output(partial).use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > CbzArchive.MAX_ARCHIVE) throw CbzException(ImportFailure.LIMIT)
                            digest.update(buffer, 0, count); sink.write(buffer, 0, count)
                        }
                        (sink as? FileOutputStream)?.fd?.sync()
                    }
                }
                val pages = CbzArchive.scan(partial, checkpoint)
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                checkpoint()
                PreparedCbz(pages, hash, bytes, ComicInfoReader.read(partial, checkpoint),
                    store.localData { it.localByHash(hash) != null })
            }
            // No library lock or DB connection is held while a person reads the preview.
            val adopt = preview?.invoke(CbzImportPreview(source.displayName, prepared.metadata, prepared.existing)) == true
            checkpoint()
            lock.withLock {
                checkpoint()
                store.localData { dao ->
                    checkpoint()
                    val existing = dao.localByHash(prepared.hash)
                    if (existing != null && available(existing)) ImportResult(key(existing), true)
                    else {
                        if (!partial.renameTo(destination)) throw IOException("Cannot publish private archive")
                        val info = prepared.metadata.info.takeIf { adopt && existing == null }
                        val fileTitle = source.displayName.substringBeforeLast('.').take(200).ifBlank { "未命名 CBZ" }
                        val book = LocalBook(mangaId = existing?.mangaId ?: id,
                            title = existing?.title ?: info?.title ?: fileTitle,
                            archiveName = destination.name, sha256 = prepared.hash, archiveBytes = prepared.bytes, importedAt = System.currentTimeMillis(),
                            favorite = existing?.favorite ?: false,
                            defaultTitle = existing?.defaultTitle ?: fileTitle, coverPageId = existing?.coverPageId,
                            metadataTitle = existing?.metadataTitle ?: info?.title, author = existing?.author ?: info?.author,
                            volume = existing?.volume ?: info?.volume, summary = existing?.summary ?: info?.summary)
                        if (existing == null) {
                            dao.insertBook(book)
                            dao.insertPages(prepared.pages.mapIndexed { index, p -> LocalPage(mangaId = book.mangaId, ordinal = index + 1, entryName = p.name, mimeType = p.mime, width = p.width, height = p.height) })
                        } else dao.updateBook(book) // Same bytes repair the missing copy without changing IDs/page resources.
                        checkpoint()
                        ImportResult(key(book), false, repaired = existing != null)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CbzException) { throw failure }
        catch (_: SecurityException) { throw CbzException(ImportFailure.PERMISSION) }
        catch (_: ZipException) { throw CbzException(ImportFailure.CORRUPT) }
        catch (failure: IOException) {
            val noSpace = generateSequence<Throwable>(failure) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
            throw CbzException(if (noSpace) ImportFailure.NO_SPACE else ImportFailure.IO)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                lock.withLock {
                    activeImports -= partial.absolutePath
                    partial.delete()
                    // A cancellation can arrive after SQLite commits but before withContext returns.
                    // Check the durable record before deleting a renamed file; failed DB reads keep it for recovery.
                    try { recover() } catch (_: IOException) { }
                }
            }
        }
    }
}

private data class PreparedCbz(val pages: List<ArchivePage>, val hash: String, val bytes: Long,
    val metadata: ComicInfoResult, val existing: Boolean)
