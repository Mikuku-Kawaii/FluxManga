package com.flux.manga.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException

internal class KomgaAccess(context: Context, private val session: WorldSession, val sourceId: String) {
    val data = WorldDataStore(context)
    private val identity = session.identity.value
    fun isActive() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    fun active() { if (!isActive()) throw CancellationException() }
    suspend fun client(): WorldKomgaClient {
        active()
        val config = KomgaSettingsStore(data).load()
        currentCoroutineContext().ensureActive(); active()
        if (config == null || config.sourceId != sourceId) throw IOException("图源配置已改变，请返回图源设置")
        return session.komgaSources.get(sourceId) ?: session.komgaSources.configure(config)
    }
}

internal data class OnlineShelfItem(val book: OnlineBook, val progress: OnlineReadingProgress?, val available: Boolean, val information: OnlineBookInformation? = null) {
    val title get() = (information?.title?.takeIf(String::isNotBlank) ?: book.title).ifBlank { "作品 ${book.mangaId}（书名待更新）" }
    val key get() = "${book.sourceId}:${book.mangaId}"
}

internal class OnlineLibrary(context: Context, private val session: WorldSession) {
    private val data = WorldDataStore(context)
    private val identity = session.identity.value
    private val app = context.applicationContext
    val privateWorld = identity.world == PrototypeWorld.NSFW
    val visibility = session.state
    val coverCache = session.pageCovers()
    private fun active() { if (identity.world != PrototypeWorld.SFW || !session.accepts(identity)) throw CancellationException() }
    companion object {
        private val covers = Semaphore(2)
        private var prepared = false
        @Synchronized private fun prepare(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建封面缓存")
            if (!prepared) {
                directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("flux-image-[0-9]+-[0-9]+\\.tmp")) }?.forEach { it.delete() }
                prepared = true
            }
        }
    }
    suspend fun remember(manga: SourceManga, favorite: Boolean? = null): OnlineBook = data.localData { dao ->
        active()
        require(manga.key.world == SourceWorld.SFW)
        val old = dao.onlineBook(manga.key.sourceId, manga.key.mangaId)
        OnlineBook(manga.key.sourceId, manga.key.mangaId, manga.title, favorite ?: old?.favorite ?: false,
            System.currentTimeMillis()).also { dao.putOnlineBook(it); dao.updateVisitTitle(it.sourceId, it.mangaId, it.title) }
    }
    suspend fun book(sourceId: String, mangaId: String): OnlineBook? = data.localData { active(); it.onlineBook(sourceId, mangaId) }
    suspend fun favorite(book: OnlineBook, value: Boolean) = data.localData { dao ->
        active()
        dao.putOnlineBook((dao.onlineBook(book.sourceId, book.mangaId) ?: book).copy(favorite = value))
    }
    suspend fun shelf(): List<OnlineShelfItem> {
        active()
        val config = KomgaSettingsStore(data).load()
        val result = data.localData { dao ->
            active()
            val progress = dao.onlineProgressRows().associateBy { it.sourceId to it.mangaId }
            val books = dao.onlineBooks().associateBy { it.sourceId to it.mangaId }.toMutableMap()
            progress.forEach { (key, value) -> books.putIfAbsent(key, OnlineBook(value.sourceId, value.mangaId, "", false, value.updatedAt)) }
            books.map { (key, value) -> OnlineShelfItem(value, progress[key], isBaoziSource(value.sourceId) || isCopySource(value.sourceId) || value.sourceId == config?.sourceId) }
                .sortedByDescending { it.progress?.updatedAt ?: it.book.updatedAt }
        }
        currentCoroutineContext().ensureActive(); active()
        val information = OnlineBookInformationStore(data, session).readAll(result.map { it.book })
        return result.map { it.copy(information = information[it.book.sourceId to it.book.mangaId]) }
    }
    fun cachedCover(sourceId: String, mangaId: String): Bitmap? =
        if (session.accepts(identity)) coverCache.peek(sourceId, mangaId) else null

    suspend fun cover(sourceId: String, mangaId: String, useSavedCover: Boolean = true): Bitmap {
        if (!session.accepts(identity)) throw CancellationException()
        // Explicit metadata refresh bypasses both caches. Normal page/warmup reads share disk
        // decoding as well as network work, so navigation doesn't download the same cover again.
        if (!useSavedCover) return remoteCover(sourceId, mangaId)
        return coverCache.get(sourceId, mangaId,
            readCached = { OnlineBookInformationStore(data, session).cover(sourceId, mangaId) }) {
            val bitmap = remoteCover(sourceId, mangaId)
            try {
                val bytes = withContext(Dispatchers.Default) {
                    java.io.ByteArrayOutputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("封面保存失败")
                        output.toByteArray()
                    }
                }
                try { data.putResource(informationCover(identity.world, sourceId, mangaId), bytes) {
                    session.accepts(identity) && session.state.value.foreground
                } } finally { bytes.fill(0) }
                bitmap
            } catch (failure: Exception) { bitmap.recycle(); throw failure }
        }
    }
    private suspend fun remoteCover(sourceId: String, mangaId: String): Bitmap {
        if (identity.world == PrototypeWorld.NSFW) {
            if (!session.accepts(identity)) throw CancellationException()
            require(isPrivateComicSource(sourceId))
            return PrivateCatalog(app, session).cover(sourceId, mangaId)
        }
        active()
        if (isBaoziSource(sourceId)) return OnlineSourceCovers(app as android.app.Application, session, sourceId = sourceId).loadRemote(mangaId)
        if (isCopySource(sourceId)) return OnlineSourceCovers(app as android.app.Application, session, sourceId).loadRemote(mangaId)
        val access = KomgaAccess(app, session, sourceId)
        val client = access.client()
        var decoded: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                covers.withPermit {
                    val directory = File(data.cacheDirectory(PrototypeWorld.SFW), "online-cover")
                    prepare(directory)
                    client.coverFile(SourceMangaKey(SourceWorld.SFW, sourceId, mangaId), directory.path).use { file ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path(), bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("封面无法解码")
                        var sample = 1
                        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 512) sample *= 2
                        val bitmap = BitmapFactory.decodeFile(file.path(), BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw IOException("封面无法解码")
                        decoded = bitmap
                        currentCoroutineContext().ensureActive(); access.active()
                        bitmap
                    }
                }
            }
        } catch (failure: Exception) { decoded?.recycle(); throw failure }
    }
}
