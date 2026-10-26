package com.flux.manga.data

import android.util.AtomicFile
import java.io.File
import java.io.IOException

internal enum class CoverCachePolicy(val label: String, val bytes: Long?) {
    UNLIMITED("不限制（保持现有行为）", null),
    MEMORY_ONLY("不保存磁盘封面缓存", 0L),
    SMALL("32 MiB", 32L * 1024 * 1024),
    MEDIUM("64 MiB", 64L * 1024 * 1024),
    LARGE("128 MiB", 128L * 1024 * 1024),
}

internal data class CoverCacheSettings(val usage: CacheUsage, val policy: CoverCachePolicy?, val error: String? = null)

/** Non-sensitive SFW preference, scoped to the same namespace as its cache. Caller holds the CBZ lock. */
internal class CoverCachePolicyStore(private val file: File) {
    fun read(): CoverCachePolicy {
        if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
            return CoverCachePolicy.UNLIMITED
        val name = AtomicFile(file).openRead().use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 64) throw IOException("封面缓存设置无法读取")
            bytes.toString(Charsets.UTF_8)
        }
        return CoverCachePolicy.entries.firstOrNull { it.name == name }
            ?: throw IOException("封面缓存设置无法识别，请保留文件并重试读取")
    }

    fun write(value: CoverCachePolicy) {
        val parent = file.parentFile ?: throw IOException("封面缓存设置目录不可用")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("封面缓存设置目录不可用")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.name.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        if (read() != value) throw IOException("封面缓存设置写入结果待核对")
    }
}
