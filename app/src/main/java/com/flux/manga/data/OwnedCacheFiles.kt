package com.flux.manga.data

import java.io.File
import java.io.IOException
import java.nio.file.Files

internal data class CacheUsage(val files: Int = 0, val bytes: Long = 0)
internal data class CacheCleared(val files: Int, val bytes: Long, val failed: Int)
/** Only direct, known generated files. Never follow links or recursively remove a directory. */
internal class OwnedCacheFiles(private val directory: File, private val names: Regex) {
    private fun owned(): List<File> {
        if (!directory.exists()) return emptyList()
        // Android may canonicalize /data/user/0 to /data/data. Resolve the parent alias,
        // while still rejecting a redirected cache directory or linked cache entry.
        if (!directory.isDirectory || directory.canonicalFile != File(directory.absoluteFile.parentFile!!.canonicalFile, directory.name) || Files.isSymbolicLink(directory.toPath()))
            throw IOException("缓存目录无法安全读取")
        return (directory.listFiles() ?: throw IOException("无法读取缓存清单")).filter {
            names.matches(it.name) && !Files.isSymbolicLink(it.toPath()) && it.isFile && it.canonicalFile.parentFile == directory.canonicalFile
        }
    }
    fun usage(): CacheUsage { val files = owned(); return CacheUsage(files.size, files.sumOf { it.length() }) }
    /** Oldest access timestamps first; only successful removals reduce the measured usage. */
    fun trimTo(maxBytes: Long, active: () -> Unit): CacheCleared {
        require(maxBytes >= 0)
        val files = owned()
        var remaining = files.sumOf { it.length() }
        if (maxBytes > 0 && remaining <= maxBytes) return CacheCleared(0, 0, 0)
        var count = 0; var bytes = 0L; var failed = 0
        for (file in files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
            if (maxBytes > 0 && remaining <= maxBytes) break
            active()
            val size = file.length()
            if (file.delete()) { count++; bytes += size; remaining -= size }
            else if (file.exists()) failed++
            else remaining -= size
        }
        return CacheCleared(count, bytes, failed)
    }
    fun clear(active: () -> Unit, delete: (File) -> Boolean = { it.delete() }): CacheCleared = clearRetaining(active, { false }, delete)
    fun clearRetaining(active: () -> Unit, retain: (File) -> Boolean, delete: (File) -> Boolean = { it.delete() }): CacheCleared {
        var count = 0; var bytes = 0L; var failed = 0
        for (file in owned()) {
            active()
            if (retain(file)) continue
            // The owner holds its writer lock from enumeration through deletion.
            val size = file.length()
            if (delete(file)) { count++; bytes += size } else if (file.exists()) failed++
        }
        return CacheCleared(count, bytes, failed)
    }
}
