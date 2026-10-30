package com.flux.manga.data

import android.graphics.Bitmap

/** One process budget for retained decoded images. UI-owned pixels are never recycled here. */
internal class DisplayBitmapStore<K : Any>(private val reader: Boolean = false, private val preview: Boolean = false) {
    private val owner = Any()
    fun get(key: K): Bitmap? = synchronized(entries) { entries[owner to key]?.bitmap }
    fun put(key: K, bitmap: Bitmap) = synchronized(entries) {
        val id = owner to key
        entries.remove(id)?.let { bytes -= it.bitmap.allocationByteCount }
        if (bitmap.allocationByteCount <= capacity) {
            entries[id] = Entry(bitmap, reader, preview)
            bytes += bitmap.allocationByteCount
        }
        while (bytes > capacity) {
            // While reading, distant covers go first, then full-size tiles, then small previews.
            val victim = entries.entries.firstOrNull { !it.value.reader }
                ?: entries.entries.firstOrNull { !it.value.preview } ?: entries.entries.first()
            bytes -= victim.value.bitmap.allocationByteCount
            entries.remove(victim.key)
        }
    }
    fun remove(key: K) = synchronized(entries) { entries.remove(owner to key)?.also { bytes -= it.bitmap.allocationByteCount }?.bitmap }
    fun evictAll() = synchronized(entries) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.first === owner) { bytes -= entry.value.bitmap.allocationByteCount; iterator.remove() }
        }
    }
    companion object {
        val capacity = minOf(Runtime.getRuntime().maxMemory() / 4, 128L * 1024 * 1024).toInt()
        private data class Entry(val bitmap: Bitmap, val reader: Boolean, val preview: Boolean)
        private val entries = LinkedHashMap<Pair<Any, Any>, Entry>(32, .75f, true)
        private var bytes = 0L
        fun trimMemory(level: Int) = synchronized(entries) {
            val target = if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) 0L else capacity / 2L
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                val iterator = entries.iterator()
                while (bytes > target && iterator.hasNext()) {
                    val entry = iterator.next(); bytes -= entry.value.bitmap.allocationByteCount; iterator.remove()
                }
            }
        }
    }
}
