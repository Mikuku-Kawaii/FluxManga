package com.flux.manga.data

import android.content.Context
import android.graphics.BitmapFactory
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

internal fun downloadFiles(context: Context) = DownloadFiles(File(WorldDataStore(context).directory(PrototypeWorld.SFW),"downloads"))
internal class WorldDownloadNetwork(private val context: Context, private val session: WorldSession) : DownloadNetwork {
    override suspend fun pages(job: ChapterDownload) = OnlineReaderRepository(context,session,job.source,job.manga,false).pages(job.chapter)
    override suspend fun image(job: ChapterDownload, page: SourcePageResource, directory: File): DownloadImage {
        var owned: SourceImageFile?=null
        try {
            return withContext(Dispatchers.IO) {
                val file=OnlineReaderRepository(context,session,job.source,job.manga,false).downloadFile(page,directory)
                owned=file
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(file.path(),bounds)
                if(bounds.outWidth<=0 || bounds.outHeight<=0) throw IOException("下载图片无法解码")
                ensureActive(); DownloadImage(File(file.path())) { file.close() }
            }
        } catch(e: Exception) { owned?.close(); throw e }
    }
}
