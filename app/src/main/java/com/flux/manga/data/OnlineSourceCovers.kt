package com.flux.manga.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flux.manga.session.WorldSession
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Lists use the shared memory/disk cache; only the raw transport owns temporary encoded files. */
internal class OnlineSourceCovers(private val app: Application, private val session: WorldSession, private val sourceId: String = CopyMangaEndpoint.SOURCE_ID) {
    init { require(isCopySource(sourceId) || isBaoziSource(sourceId)) }
    private val baozi get() = isBaoziSource(sourceId)
    private val identity = session.identity.value
    companion object { private val slots = Semaphore(2) }
    private fun active() { if (!session.accepts(identity)) throw CancellationException("Expired source session") }
    private val library = OnlineLibrary(app, session)
    val coverRevision get() = library.coverCache.revision
    fun cached(id: String): Bitmap? = library.cachedCover(sourceId, id)
    suspend fun load(id: String): Bitmap = library.cover(sourceId, id)
    internal suspend fun loadRemote(id: String): Bitmap {
        var decoded: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                slots.withPermit {
                    ensureActive(); active()
                    val directory = File(app.cacheDir, "sfw/${if (baozi) "baozi-covers" else "copy-covers"}/${identity.processSession}-${identity.generation}/$sourceId")
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Image directory unavailable")
                    val output = if (baozi) session.baoziSources.client(app, sourceId).coverFile(id, directory.path)
                        else copyMangaClient(app, session, sourceId).coverFile(id, directory.path)
                    output.use { file ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path(), bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Image decode failed")
                        var sample = 1
                        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 512) sample *= 2
                        val bitmap = BitmapFactory.decodeFile(file.path(), BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw IOException("Image decode failed")
                        decoded = bitmap
                        ensureActive(); active()
                        bitmap
                    }
                }
            }
        } catch (error: Exception) {
            decoded?.recycle(); throw error
        }
    }
}
