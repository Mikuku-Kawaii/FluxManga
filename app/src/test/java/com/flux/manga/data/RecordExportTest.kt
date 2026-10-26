package com.flux.manga.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RecordExportTest {
    private class Sink : RecordExportSink {
        var header: ExportRecord? = null
        val arrays = linkedMapOf<String, MutableList<ExportRecord>>()
        var current: MutableList<ExportRecord>? = null
        var complete = false
        override fun begin(header: ExportRecord) { this.header = header }
        override fun beginArray(name: String) { current = mutableListOf<ExportRecord>().also { arrays[name] = it } }
        override fun record(fields: ExportRecord) { requireNotNull(current).add(fields) }
        override fun endArray() { current = null }
        override fun finish(counts: Map<String, Long>) {
            assertEquals(arrays.mapValues { it.value.size.toLong() }, counts)
            complete = true
        }
    }
    private fun source(selectedTable: ExportTable = ExportTable.LOCAL_BOOKS, values: List<ExportRecord> = emptyList()) = object : RecordExportRows {
        override fun page(table: ExportTable, offset: Int) = if (table == selectedTable) values.drop(offset).take(EXPORT_PAGE_SIZE) else emptyList()
    }

    @Test fun emptyExportIsExplicitAndHasAllCategories() {
        val sink = Sink()
        writeRecordExport("SFW", 123, source(), sink) {}
        assertEquals(6, sink.arrays.size)
        assertEquals("SFW", sink.header!!["world"])
        assertEquals(false, sink.header!!["supportsRestore"])
        assertEquals(false, sink.header!!["encrypted"])
        assertTrue(sink.complete)
    }

    @Test fun multipleFullPagesAndFinalPartialPageAreNotTruncatedOrDuplicated() {
        for (size in listOf(128, 256, 257)) {
            val records = (1..size).map { mapOf("mangaId" to it.toString()) }
            val sink = Sink()
            writeRecordExport("SFW", 123, source(values = records), sink) {}
            assertEquals(records, sink.arrays["localBooks"])
        }
    }

    @Test fun sourceAndKindIdentityArePreservedEvenForSameTitlesAndIds() {
        val local = LocalBook(mangaId = "1", title = "same", archiveName = "private/path.cbz", sha256 = "a", archiveBytes = 1, importedAt = 2)
        val online = OnlineBook("cbz", "1", "same", true, 3)
        assertNotEquals(local.exportRecord()["kind"], online.exportRecord()["kind"])
        assertEquals("cbz", online.exportRecord()["sourceId"])
        assertEquals("another", online.copy(sourceId = "another").exportRecord()["sourceId"])
    }

    @Test fun bookFieldsExcludeAppPathsAndSecretTables() {
        val row = LocalBook(mangaId = "id", title = "书名\n\"😀", archiveName = "do-not-export/secret.cbz",
            sha256 = "hash", archiveBytes = 5, importedAt = 10, author = "作者", summary = "简介")
        val fields = row.exportRecord()
        assertFalse(fields.containsKey("archiveName"))
        assertFalse(fields.values.contains(row.archiveName))
        assertEquals(row.title, fields["title"])
        assertEquals(row.author, fields["author"])
        assertEquals(row.summary, fields["summary"])
        assertEquals(setOf("localBooks", "onlineBooks", "bookmarks", "localProgress", "onlineProgress", "chapterReadings"), ExportTable.entries.map { it.field }.toSet())
    }

    @Test fun bookmarksKeepUnicodeNotesExactPositionAndStableId() {
        val note = "行一\n引号\"\t\\零\u0000😀"
        val bookmark = LocalBookmark("uuid", "cbz", "1", "p/03.jpg", 3, .375f, "STRIP", note, 1, 2)
        val record = bookmark.exportRecord()
        assertEquals(note, record["note"])
        assertEquals(.375f, record["fraction"])
        assertEquals("uuid", record["id"])
        assertEquals("p/03.jpg", record["pageId"])
        assertEquals(2L, record["updatedAt"])
    }

    @Test fun progressDoesNotNormalizeModeOrFabricateCompletion() {
        val local = ReadingProgress("cbz", "1", "local", "page", 8, .8f, "VERTICAL", 7, false).exportRecord()
        assertEquals(false, local["modeExplicit"])
        assertEquals("VERTICAL", local["mode"])
        val online = OnlineReadingProgress("source", "1", "chapter", "page", 8, .8f, "RTL", 7, true).exportRecord()
        assertEquals("online", online["kind"])
        assertEquals("chapter", online["chapterId"])
        val chapter = ChapterReading("source", "1", "chapter", null, null, 0f, 10, true).exportRecord()
        assertNull(chapter["pageId"]); assertNull(chapter["ordinal"])
        assertEquals(true, chapter["completed"])
    }

    @Test fun worldsHaveSeparateHeadersAndRecords() {
        val public = Sink(); val private = Sink()
        writeRecordExport("SFW", 1, source(values = listOf(mapOf("title" to "public"))), public) {}
        writeRecordExport("NSFW", 1, source(values = listOf(mapOf("title" to "private synthetic"))), private) {}
        assertEquals("NSFW", private.header!!["world"])
        assertEquals("public", public.arrays["localBooks"]!!.single()["title"])
        assertEquals("private synthetic", private.arrays["localBooks"]!!.single()["title"])
        assertThrows(IllegalArgumentException::class.java) { writeRecordExport("ALL", 1, source(), Sink()) {} }
    }

    @Test fun invalidatedSessionStopsBeforeReadingAnything() {
        var called = false
        val rows = object : RecordExportRows {
            override fun page(table: ExportTable, offset: Int): List<ExportRecord> { called = true; return emptyList() }
        }
        val sink = Sink()
        assertThrows(CancellationException::class.java) { writeRecordExport("SFW", 0, rows, sink) { throw CancellationException() } }
        assertFalse(called); assertNull(sink.header); assertFalse(sink.complete)
    }

    @Test fun cancellationMidPageCannotEmitCompletion() {
        val sink = Sink()
        assertThrows(CancellationException::class.java) {
            writeRecordExport("SFW", 0, source(values = (1..150).map { mapOf("id" to it) }), sink) {
                if ((sink.arrays["localBooks"]?.size ?: 0) >= 3) throw CancellationException()
            }
        }
        assertEquals(3, sink.arrays["localBooks"]!!.size); assertFalse(sink.complete)
    }

    @Test fun failedPageDoesNotTurnIntoAnEmptySuccessfulExport() {
        val sink = Sink()
        val rows = object : RecordExportRows {
            override fun page(table: ExportTable, offset: Int): List<ExportRecord> = throw IOException("synthetic failure")
        }
        assertThrows(IOException::class.java) { writeRecordExport("SFW", 0, rows, sink) {} }
        assertFalse(sink.complete)
    }

    @Test fun cancellationBetweenOutputChunksStopsFurtherBytes() {
        val output = ByteArrayOutputStream()
        val checked = CheckedExportOutput(output) { if (output.size() >= 8192) throw CancellationException() }
        assertThrows(CancellationException::class.java) { checked.write(ByteArray(30_000), 0, 30_000) }
        assertEquals(8192, output.size())
    }

    @Test fun writeFailureIsNotSwallowed() {
        val broken = object : java.io.OutputStream() { override fun write(b: Int) { throw IOException("full") } }
        assertThrows(IOException::class.java) { CheckedExportOutput(broken) {}.write(1) }
    }
}
