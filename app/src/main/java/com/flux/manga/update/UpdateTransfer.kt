package com.flux.manga.update

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal interface UpdateResponse : Closeable {
    val status: Int
    val contentRange: String?
    val contentLength: Long
    val body: InputStream
}
internal fun interface UpdateTransport { fun open(url: String, offset: Long): UpdateResponse }
internal class HttpsUpdateTransport : UpdateTransport {
    override fun open(url: String, offset: Long): UpdateResponse {
        var current = httpsUrl(url)
        repeat(6) { hop ->
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 15000
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("User-Agent", "FluxManga-Updater/1")
                if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
                val status = c.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(hop < 5) { "更新重定向过多" }
                    current = httpsUrl(URL(URL(current), c.getHeaderField("Location") ?: error("更新重定向地址缺失")).toString())
                    c.disconnect()
                } else {
                    require(c.contentEncoding == null || c.contentEncoding == "identity") { "更新响应编码不支持" }
                    return object : UpdateResponse {
                        override val status = status
                        override val contentRange = c.getHeaderField("Content-Range")
                        override val contentLength = c.contentLengthLong
                        override val body: InputStream get() = c.inputStream
                        override fun close() = c.disconnect()
                    }
                }
            } catch (e: Exception) { c.disconnect(); throw e }
        }
        error("更新重定向失败")
    }
}
internal fun readUpdateManifest(transport: UpdateTransport, url: String): ByteArray = transport.open(url, 0).use { r ->
    require(r.status == 200) { "清单请求失败（${r.status}）" }
    require(r.contentLength <= MAX_MANIFEST) { "更新清单过大" }
    r.body.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            require(output.size() + n <= MAX_MANIFEST) { "更新清单过大" }; output.write(buffer, 0, n)
        }
        output.toByteArray()
    }
}
internal class UpdateTransfer(private val transport: UpdateTransport) {
    fun download(artifact: UpdateArtifact, part: File, checkpoint: () -> Unit, progress: (Long) -> Unit,
        availableBytes: () -> Long = { part.parentFile!!.usableSpace }) =
        download(artifact.payload, part, checkpoint, progress, availableBytes)

    fun download(artifact: UpdatePayload, part: File, checkpoint: () -> Unit, progress: (Long) -> Unit,
        availableBytes: () -> Long = { part.parentFile!!.usableSpace }) {
        // File.getUsableSpace queries this directory directly. NIO getFileStore walks
        // ancestors/mount entries which an Android app UID may not be allowed to read.
        val directory = part.parentFile!!
        require(directory.isDirectory || directory.mkdirs()) { "无法创建更新下载目录" }
        require(availableBytes() >= (artifact.size - part.length()).coerceAtLeast(0) + 16L * 1024 * 1024) { "更新空间不足，请腾出空间后重试" }
        if (part.length() > artifact.size) require(part.delete()) { "无法重置更新临时文件" }
        if (part.length() == artifact.size) {
            if (sha256(part, checkpoint) == artifact.sha256) { progress(artifact.size); return }
            // A complete but corrupt file cannot be resumed. Retry starts a fresh copy.
            require(part.delete()) { "无法重置损坏的更新临时文件" }
            progress(0)
        }
        checkpoint()
        transport.open(artifact.url, part.length()).use { r ->
            checkpoint()
            require(r.status == 200 || r.status == 206) { "更新下载请求失败（HTTP ${r.status}）" }
            val offset = resumeOffset(r.status, r.contentRange, part.length(), artifact.size)
            require(r.contentLength == -1L || r.contentLength == artifact.size - offset) { "响应长度不匹配" }
            require(availableBytes() >= artifact.size - offset + 16L * 1024 * 1024) { "更新空间不足" }
            RandomAccessFile(part, "rw").use { output ->
                output.setLength(offset); output.seek(offset)
                var bytes = offset; progress(bytes)
                try {
                    r.body.use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkpoint(); val n = input.read(buffer); if (n < 0) break
                            checkpoint(); require(bytes + n <= artifact.size) { "下载超出声明大小" }
                            output.write(buffer, 0, n); bytes += n; progress(bytes)
                        }
                    }
                } finally { output.fd.sync() }
            }
        }
        verifyPayload(part, artifact, checkpoint)
    }
}
