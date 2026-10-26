package com.flux.manga.data

import android.content.ContentResolver
import android.net.Uri
import android.util.JsonWriter
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter

internal class CheckedExportOutput(private val target: OutputStream, private val check: () -> Unit) : OutputStream() {
    override fun write(value: Int) { check(); target.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            check()
            val count = minOf(8192, end - cursor)
            target.write(bytes, cursor, count)
            cursor += count
        }
    }
    override fun flush() { check(); target.flush() }
    override fun close() = target.close()
}

internal class JsonRecordExportSink(private val json: JsonWriter, private val progress: (Long) -> Unit) : RecordExportSink {
    private var written = 0L
    private fun fields(fields: ExportRecord) {
        fields.forEach { (key, value) ->
            json.name(key)
            when (value) {
                null -> json.nullValue()
                is String -> json.value(value)
                is Boolean -> json.value(value)
                is Number -> json.value(value)
                else -> error("Unsupported export field")
            }
        }
    }
    override fun begin(header: ExportRecord) { json.beginObject(); fields(header) }
    override fun beginArray(name: String) { json.name(name).beginArray() }
    override fun record(fields: ExportRecord) {
        json.beginObject(); fields(fields); json.endObject()
        written++
        if (written % EXPORT_PAGE_SIZE == 0L) progress(written)
    }
    override fun endArray() { json.endArray() }
    override fun finish(counts: Map<String, Long>) {
        json.name("counts").beginObject(); fields(counts); json.endObject()
        json.name("complete").value(true).endObject()
        progress(written)
    }
}

internal suspend fun exportRecordDocument(store: WorldDataStore, resolver: ContentResolver, uri: Uri,
    world: PrototypeWorld, allowed: () -> Boolean, progress: (Long) -> Unit): Map<String, Long> = withContext(Dispatchers.IO) {
    val caller = currentCoroutineContext()
    fun check() {
        caller.ensureActive()
        if (!allowed()) throw CancellationException("Expired record export")
    }
    check()
    require(uri.scheme == "content")
    // The only accepted URI comes from the outstanding CreateDocument request.
    // No app-owned plaintext temporary file, original CBZ or persisted URI grant is used.
    val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Document output unavailable")
    output.use { raw ->
        val json = JsonWriter(OutputStreamWriter(CheckedExportOutput(raw, ::check), Charsets.UTF_8).buffered())
        json.setIndent("  ")
        val result = store.recordExportData(world, allowed) { dao ->
            writeRecordExport(world.name, System.currentTimeMillis(), DatabaseExportRows(dao),
                JsonRecordExportSink(json, progress), ::check)
        }
        // Flush only after success. On failure discard buffered text and close the raw stream once.
        check(); json.flush()
        result
    }.also { check() }
}
