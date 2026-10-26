package com.flux.manga

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import android.util.JsonWriter
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/** Synthetic exports only. Existing business records are compared in memory, never exported/logged.
 * Does not launch an Activity, reset a database, delete fixtures or change any device setting. */
@RunWith(AndroidJUnit4::class)
class RecordExportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store() = WorldDataStore(context, "export-${UUID.randomUUID()}")
    private val note = "合成备注 \"引号\" \\ 斜线\n换行\t制表\u0001控制字符 😀"

    private suspend fun seed(data: WorldDataStore, world: PrototypeWorld) = data.worldData(world) { dao ->
        repeat(257) { index ->
            val id = index.toString().padStart(4, '0')
            dao.insertBook(LocalBook(mangaId = id, title = "${world.name} 合成 $id", archiveName = "missing-$id.cbz",
                sha256 = "hash-$id", archiveBytes = 100, importedAt = 123, author = "合成作者", summary = note))
            dao.insertLocalBookmark(LocalBookmark("bookmark-$id", "cbz", id, "page-$id", index, .375f, "STRIP", note, 123, 456))
            dao.putProgress(ReadingProgress("cbz", id, "chapter", "page-$id", index, .375f, "STRIP", 789, false))
        }
        dao.putOnlineBook(OnlineBook("synthetic", "same-id", "${world.name} 在线合成", true, 123))
        dao.putOnlineProgress(OnlineReadingProgress("synthetic", "orphan", "chapter", "page", 7, .25f, "RTL", 456))
        dao.putChapterReading(ChapterReading("synthetic", "same-id", "completed", null, null, 0f, 456, true))
        dao.putSession(SourceSession("do-not-export", byteArrayOf(7, 8, 9)))
    }

    private suspend fun records(data: WorldDataStore, world: PrototypeWorld) = data.recordExportData(world, { true }) { dao ->
        val rows = DatabaseExportRows(dao)
        ExportTable.entries.associateWith { table -> buildList {
            var offset = 0
            do {
                val page = rows.page(table, offset); addAll(page); offset += page.size
            } while (page.size == EXPORT_PAGE_SIZE)
        } }
    }

    @Test fun syntheticTwoWorldsExportThroughAndroidProviderAndReadBack(): Unit = runBlocking {
        val data = store()
        val resolver = context.contentResolver
        for (world in PrototypeWorld.entries) {
            seed(data, world)
            val before = records(data, world)
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "FluxManga-synthetic-export-${UUID.randomUUID()}.json")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }))
            val counts = exportRecordDocument(data, resolver, uri, world, { true }, {})
            val text = resolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            assertEquals(world.name, json.getString("world"))
            assertEquals(774L, counts.values.sum())
            assertTrue(json.getBoolean("complete"))
            assertFalse(json.getBoolean("encrypted")); assertFalse(json.getBoolean("supportsRestore"))
            assertEquals(257, json.getJSONArray("localBooks").length())
            assertTrue(json.getJSONArray("localBooks").getJSONObject(0).getString("title").startsWith(world.name))
            assertEquals(note, json.getJSONArray("bookmarks").getJSONObject(0).getString("note"))
            val progress = json.getJSONArray("localProgress").getJSONObject(0)
            assertEquals(.375, progress.getDouble("fraction"), .00001); assertFalse(progress.getBoolean("modeExplicit"))
            assertEquals("orphan", json.getJSONArray("onlineProgress").getJSONObject(0).getString("mangaId"))
            assertTrue(json.getJSONArray("chapterReadings").getJSONObject(0).isNull("pageId"))
            assertFalse(text.contains("do-not-export")); assertFalse(text.contains("missing-"))
            assertEquals(before, records(data, world))
        }
    }

    @Test fun androidWriterStopsOnExpiredSessionAndOutputFailure(): Unit = runBlocking {
        val data = store(); seed(data, PrototypeWorld.SFW)
        val before = records(data, PrototypeWorld.SFW)
        var allowed = true
        val bytes = ByteArrayOutputStream()
        val writer = JsonWriter(OutputStreamWriter(CheckedExportOutput(bytes) {
            if (!allowed) throw CancellationException("synthetic expiry")
        }, Charsets.UTF_8))
        try {
            data.recordExportData(PrototypeWorld.SFW, { allowed }) { dao ->
                writeRecordExport("SFW", 1, DatabaseExportRows(dao), JsonRecordExportSink(writer) { allowed = false }) {
                    if (!allowed) throw CancellationException("synthetic expiry")
                }
            }
            fail("Expired writer completed")
        } catch (_: CancellationException) { }
        assertFalse(bytes.toString("UTF-8").contains("\"complete\":true"))
        val failed = JsonWriter(OutputStreamWriter(object : OutputStream() {
            override fun write(value: Int) { throw IOException("synthetic full output") }
        }, Charsets.UTF_8))
        try {
            data.recordExportData(PrototypeWorld.SFW, { true }) { dao ->
                writeRecordExport("SFW", 1, DatabaseExportRows(dao), JsonRecordExportSink(failed) {}, {})
            }
            failed.flush(); fail("Failed writer completed")
        } catch (_: IOException) { }
        assertEquals(before, records(data, PrototypeWorld.SFW))
    }

    @Test fun freshEmptyExportDoesNotSeedSampleRows(): Unit = runBlocking {
        val data = store()
        for (world in PrototypeWorld.entries) {
            val bytes = ByteArrayOutputStream()
            val writer = JsonWriter(OutputStreamWriter(bytes, Charsets.UTF_8))
            data.recordExportData(world, { true }) { dao ->
                assertTrue((dao as WorldDao).catalog("sample").isEmpty())
                writeRecordExport(world.name, 1, DatabaseExportRows(dao), JsonRecordExportSink(writer) {}, {})
            }
            writer.flush()
            val result = JSONObject(bytes.toString("UTF-8"))
            assertTrue(result.getBoolean("complete"))
            assertEquals(0, result.getJSONArray("localBooks").length())
            assertEquals(0, result.getJSONObject("counts").getInt("bookmarks"))
        }
    }

    // Raw read-only snapshots preserve old column sets across the additive migrations. Hashes
    // remain in memory; only table counts and schema versions are returned to instrumentation.
    private data class TableSnapshot(val columns: List<String>, val rows: List<String>)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun snapshot(query: (String) -> Cursor, previous: Map<String, TableSnapshot>? = null): Map<String, TableSnapshot> {
        val tables = previous?.keys?.toList() ?: query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            require(table.matches(Regex("[a-z_]+")))
            val columns = previous?.get(table)?.columns ?: query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() }
            val rows = query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table`").use { cursor -> buildList {
                while (cursor.moveToNext()) add(hash(buildString {
                    for (column in columns.indices) {
                        val type = cursor.getType(column)
                        val value = when (type) {
                            Cursor.FIELD_TYPE_NULL -> ""
                            Cursor.FIELD_TYPE_BLOB -> hash(cursor.getBlob(column))
                            else -> cursor.getString(column)
                        }
                        append(type).append(':').append(value.length).append(':').append(value)
                    }
                }.toByteArray(Charsets.UTF_8)))
            }.sorted() }
            TableSnapshot(columns, rows)
        }
    }

    private fun <T> raw(data: WorldDataStore, world: PrototypeWorld, block: ((String) -> Cursor) -> T): T {
        val file = data.databaseFile(world)
        if (world == PrototypeWorld.SFW) {
            return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY,
                android.database.DatabaseErrorHandler { error("Existing database rejected; never delete") }).use { db -> block { db.rawQuery(it, null) } }
        }
        System.loadLibrary("sqlcipher")
        val key = data.keys(world, "database").loadExistingReadOnly()
        try {
            return CipherDatabase.openDatabase(file.path, key, null, CipherDatabase.OPEN_READONLY,
                net.zetetic.database.DatabaseErrorHandler { _, failure -> throw failure }, null).use { db -> block { db.query(SimpleSQLiteQuery(it)) } }
        } finally { key.fill(0) }
    }

    @Test fun existingBusinessRecordsSurviveCurrentRoomOpening(): Unit = runBlocking {
        val data = WorldDataStore(context)
        for (world in PrototypeWorld.entries) {
            if (!data.databaseFile(world).exists()) continue
            val before = raw(data, world) { snapshot(it) }
            val version = raw(data, world) { query -> query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) } }
            data.recordExportData(world, { true }) { it.exportCounts() }
            assertEquals("Existing records must survive current Room opening", before, raw(data, world) { snapshot(it, before) })
            val afterVersion = raw(data, world) { query -> query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) } }
            assertEquals(14, afterVersion)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Preserved ${world.name}: schema $version->$afterVersion, ${before.size} tables, ${before.values.sumOf { it.rows.size }} rows\n")
            })
        }
    }
}
