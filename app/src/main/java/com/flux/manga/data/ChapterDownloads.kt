package com.flux.manga.data

import java.io.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal enum class DownloadStatus { QUEUED, RUNNING, PAUSED, FAILED, COMPLETE, CANCELLED }
internal data class DownloadPage(val id: String, val bytes: Long = 0, val hash: String = "")
internal data class ChapterDownload(val id: String, val source: String, val manga: String, val chapter: String,
    val title: String, val status: DownloadStatus = DownloadStatus.QUEUED, val pages: List<DownloadPage> = emptyList(),
    val error: String? = null) {
    val key get() = downloadKey(source, manga, chapter)
    val completed get() = pages.count { it.hash.isNotEmpty() }
    val bytes get() = pages.sumOf { it.bytes }
}
internal fun downloadKey(source: String, manga: String, chapter: String) = MessageDigest.getInstance("SHA-256")
    .digest(listOf(source, manga, chapter).joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }

internal object DownloadCodec {
    fun encode(jobs: List<ChapterDownload>): ByteArray {
        require(jobs.size <= 200 && jobs.map { it.key }.distinct().size == jobs.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeInt(jobs.size)
            jobs.forEach { j ->
                require(j.id == UUID.fromString(j.id).toString() && j.source.isNotBlank() && j.manga.isNotBlank() && j.chapter.isNotBlank())
                require(listOf(j.source,j.manga,j.chapter).all { it.toByteArray().size <= 512 } && j.title.toByteArray().size <= 4096)
                require(j.pages.size <= 5000 && j.pages.map { it.id }.distinct().size == j.pages.size)
                out.writeUTF(j.id); out.writeUTF(j.source); out.writeUTF(j.manga); out.writeUTF(j.chapter); out.writeUTF(j.title)
                out.writeUTF(j.status.name); out.writeUTF(j.error.orEmpty()); out.writeInt(j.pages.size)
                j.pages.forEach { p ->
                    require(p.id.isNotEmpty() && p.id.toByteArray().size <= 512 && p.bytes in 0..32L*1024*1024)
                    require((p.bytes == 0L && p.hash.isEmpty()) || (p.bytes > 0 && p.hash.matches(Regex("[0-9a-f]{64}"))))
                    out.writeUTF(p.id); out.writeLong(p.bytes); out.writeUTF(p.hash)
                }
                require(j.status != DownloadStatus.COMPLETE || (j.pages.isNotEmpty() && j.completed == j.pages.size))
            }
        }
        return bytes.toByteArray().also { require(it.size <= 8*1024*1024) }
    }
    fun decode(bytes: ByteArray): List<ChapterDownload> {
        require(bytes.size <= 8*1024*1024)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1); val n = input.readInt(); require(n in 0..200)
            val jobs = List(n) {
                val id=input.readUTF(); val source=input.readUTF(); val manga=input.readUTF(); val chapter=input.readUTF(); val title=input.readUTF()
                val status=DownloadStatus.valueOf(input.readUTF()); val error=input.readUTF().ifEmpty { null }; val count=input.readInt(); require(count in 0..5000)
                ChapterDownload(id,source,manga,chapter,title,status,List(count) { DownloadPage(input.readUTF(),input.readLong(),input.readUTF()) },error)
            }
            require(input.available()==0); encode(jobs).fill(0); jobs
        }
    }
}
internal class DownloadStore(private val db: AccountImportDatabase) {
    private val slot="chapter-download-queue-v1"
    private fun AccountImportRows.jobs(): List<ChapterDownload> {
        val bytes=read(slot) ?: return emptyList()
        return try { DownloadCodec.decode(bytes) } catch (e: Exception) { throw IOException("下载队列无法读取，原记录保留", e) } finally { bytes.fill(0) }
    }
    private fun AccountImportRows.save(jobs: List<ChapterDownload>) {
        val bytes=DownloadCodec.encode(jobs); try { write(slot,bytes) } finally { bytes.fill(0) }
    }
    suspend fun list(allowed: () -> Boolean) = db.transaction(allowed) { it.jobs() }
    suspend fun enqueue(source: String, manga: String, chapters: List<Pair<String,String>>, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        require(chapters.isNotEmpty() && chapters.size <= 200)
        val jobs=rows.jobs().toMutableList()
        chapters.forEach { (id,title) ->
            val key=downloadKey(source,manga,id)
            if (jobs.none { it.key==key && it.status!=DownloadStatus.CANCELLED }) {
                jobs.removeAll { it.key==key }
                jobs.add(ChapterDownload(UUID.randomUUID().toString(),source,manga,id,
                    listOfNotNull(rows.book(source,manga)?.title?.takeIf { it.isNotBlank() },title).joinToString(" · ").take(1024)))
            }
        }
        rows.save(jobs); jobs
    }
    suspend fun change(id: String, allowed: () -> Boolean, transform: (ChapterDownload) -> ChapterDownload?): List<ChapterDownload> = db.transaction(allowed) { rows ->
        val jobs=rows.jobs(); require(jobs.any { it.id==id })
        val next=jobs.mapNotNull { if(it.id==id) transform(it) else it }; rows.save(next); next
    }
    suspend fun recover(allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        val jobs=rows.jobs().map { if(it.status in listOf(DownloadStatus.QUEUED,DownloadStatus.RUNNING)) it.copy(status=DownloadStatus.PAUSED,error="已中断，请继续下载") else it }
        rows.save(jobs); jobs
    }
}

/** Directory and every persistent file belong exclusively to one generated download job. */
internal class DownloadFiles(private val root: File, private val space: () -> Long = { root.usableSpace }) {
    fun directory(job: ChapterDownload): File {
        require(job.id == UUID.fromString(job.id).toString())
        val base=root.canonicalFile; val dir=File(base,job.id).canonicalFile
        require(dir.parentFile==base); return dir
    }
    fun file(job: ChapterDownload, index: Int): File { require(index in job.pages.indices); return File(directory(job),"$index.page") }
    fun valid(job: ChapterDownload, index: Int): Boolean {
        val p=job.pages[index]; val f=file(job,index)
        return p.hash.isNotEmpty() && f.isFile && f.length()==p.bytes && digest(f)==p.hash
    }
    fun save(job: ChapterDownload, index: Int, from: File, allowed: () -> Boolean): DownloadPage {
        val dir=directory(job); if(!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建下载目录")
        val length=from.length(); if(length !in 1..32L*1024*1024) throw IOException("下载图片大小不符合限制")
        if(space()<length+4L*1024*1024) throw IOException("存储空间不足，请释放空间后重试")
        val staging=File(dir,"$index.part")
        try {
            FileInputStream(from).use { input -> FileOutputStream(staging).use { output ->
                val buffer=ByteArray(64*1024); var total=0L
                while(true) {
                    if(!allowed()) throw CancellationException()
                    val count=input.read(buffer); if(count<0) break
                    total+=count; if(total>32L*1024*1024) throw IOException("图片过大")
                    output.write(buffer,0,count)
                }
                if(total!=length) throw IOException("下载图片不完整")
                output.fd.sync()
            } }
            if(!allowed()) throw CancellationException()
            val hash=digest(staging)
            java.nio.file.Files.move(staging.toPath(),file(job,index).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return DownloadPage(job.pages[index].id,length,hash)
        } finally { staging.delete() }
    }
    fun cleanTemporary(job: ChapterDownload) {
        directory(job).listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9]+\\.part|flux-image-[0-9]+-[0-9]+\\.tmp")) }?.forEach {
            if(!it.delete()) throw IOException("下载临时文件清理失败")
        }
    }
    fun clear(job: ChapterDownload) {
        val dir=directory(job)
        // Deliberately not recursive: no original files, reader cache or adjacent job is touched.
        dir.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9]+\\.(page|part)|flux-image-[0-9]+-[0-9]+\\.tmp")) }?.forEach {
            if(!it.delete()) throw IOException("下载文件清理未完成，可重试")
        }
        if(dir.exists() && dir.listFiles()?.isEmpty()==true) dir.delete()
    }
    private fun digest(file: File): String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(64*1024); while(true) { val n=input.read(bytes); if(n<0) break; md.update(bytes,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Persist identities, rebuild only the current process context. No URL or credential is restored. */
internal fun downloadedResources(job: ChapterDownload, context: com.flux.manga.core.SourceContext): List<com.flux.manga.core.SourcePageResource> {
    require(job.status==DownloadStatus.COMPLETE && context.world==com.flux.manga.core.SourceWorld.SFW && context.sourceId==job.source)
    val key=com.flux.manga.core.SourceMangaKey(context.world,job.source,job.manga)
    return job.pages.mapIndexed { index,p -> com.flux.manga.core.SourcePageResource(key,job.chapter,p.id,(index+1).toUInt(),"download:${job.id}:$index",null,null,null,context) }
}
