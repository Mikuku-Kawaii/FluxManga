package com.flux.manga.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.sqlite.db.SupportSQLiteDatabase
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.storage.StorageCipher
import com.flux.manga.storage.storageIdentity
import kotlinx.coroutines.*
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.*
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Deliberate logical allowlist: never export sealed source sessions or device keys. */
internal enum class BackupTable(val table: String, columns: String) {
    BOOKS("local_books", "sourceId,mangaId,title,archiveName,sha256,archiveBytes,importedAt,favorite,defaultTitle,coverPageId,metadataTitle,author,volume,summary"),
    PAGES("local_pages", "sourceId,mangaId,ordinal,entryName,mimeType,width,height"),
    ONLINE("online_books", "sourceId,mangaId,title,favorite,updatedAt"),
    BOOKMARKS("local_bookmarks", "id,sourceId,mangaId,pageId,ordinal,fraction,mode,note,createdAt,updatedAt"),
    PROGRESS("reading_progress", "sourceId,mangaId,chapterId,pageId,ordinal,fraction,mode,updatedAt,modeExplicit"),
    ONLINE_PROGRESS("online_reading_progress", "sourceId,mangaId,chapterId,pageId,ordinal,fraction,mode,updatedAt,modeExplicit"),
    CHAPTERS("chapter_reading", "sourceId,mangaId,chapterId,pageId,ordinal,fraction,updatedAt,completed"),
    GROUPS("user_tags", "id,name,nameKey"),
    LINKS("tag_books", "tagId,kind,sourceId,mangaId"),
    SEARCH("search_history", "sourceId,query,usedAt"),
    VISITS("browse_visits", "kind,sourceId,mangaId,title,visitedAt"),
    TIME("reading_time", "sessionId,day,kind,sourceId,mangaId,title,durationMs"),
    PREFERENCE("reading_preferences", "id,mode");
    val columns = columns.split(',')
    val primaryKey: List<String> get() = when (this) {
        BOOKMARKS, GROUPS, PREFERENCE -> listOf("id")
        LINKS -> listOf("tagId", "kind", "sourceId", "mangaId")
        SEARCH -> listOf("sourceId", "query")
        VISITS -> listOf("kind", "sourceId", "mangaId")
        TIME -> listOf("sessionId", "day")
        CHAPTERS -> listOf("sourceId", "mangaId", "chapterId")
        PAGES -> listOf("sourceId", "mangaId", "ordinal")
        else -> listOf("sourceId", "mangaId")
    }
}

internal data class BackupSummary(val world: PrototypeWorld, val createdAt: Long,
    val counts: Map<String, Long>, val contentBytes: Long) {
    val records get() = counts.filterKeys { it != "local_pages" }.values.sum()
    val books get() = counts.getValue("local_books") + counts.getValue("online_books")
}
internal data class RestoreSummary(val inserted: Long, val retained: Long)
private data class BackupContent(val id: String, val name: String, val hash: String, val bytes: Long)
private val uuidName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private const val BACKUP_FORMAT = "fluxmanga-library-1"
private const val MAX_BACKUP_METADATA = 256L * 1024 * 1024
private fun validKomgaBinding(slot: String, source: String): Boolean =
    slot.startsWith("komga-user-source:") && uuidName.matches(slot.removePrefix("komga-user-source:")) &&
        source.startsWith("komga-") && uuidName.matches(source.removePrefix("komga-"))
private fun bindingValue(bytes: ByteArray, secret: ByteArray, slot: String): String {
    val clear = StorageCipher.open(bytes, secret, storageIdentity("SFW", slot, "session"))
    return try { clear.toString(Charsets.UTF_8) } finally { clear.fill(0) }
}

internal fun newRecoveryCode(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
    .let { bytes -> try { bytes.joinToString("") { "%02x".format(it) } } finally { bytes.fill(0) } }
internal fun recoveryCode(value: String): String = value.filterNot { it.isWhitespace() || it == '-' }.lowercase()
    .also { require(it.matches(Regex("[0-9a-f]{64}"))) { "恢复码应为 64 位十六进制字符" } }

internal class PortableBackup(private val store: WorldDataStore, val world: PrototypeWorld) {
    // Only encrypted staging files live here. No durable plaintext metadata for the private world.
    private val staging get() = File(store.directory(world), "portable-backup")
    fun stagedFile(): File {
        check(staging.isDirectory || staging.mkdirs()) { "无法创建备份暂存目录" }
        return File(staging, "${UUID.randomUUID()}.zip")
    }
    fun clearStaging() {
        staging.listFiles().orEmpty().filter { it.name.endsWith(".zip") && uuidName.matches(it.name.removeSuffix(".zip")) }
            .forEach { check(it.delete() || !it.exists()) { "备份暂存文件清理失败，请重试" } }
    }
    private val cbzDirectory get() = File(store.directory(world), "cbz")
    private fun content(row: ContentValues): BackupContent {
        require(world == PrototypeWorld.SFW) { "当前里世界备份不支持正文迁移" }
        val id = row.getAsString("mangaId")
        val name = row.getAsString("archiveName")
        val hash = row.getAsString("sha256")
        val bytes = row.getAsLong("archiveBytes")
        require(row.getAsString("sourceId") == "cbz" && uuidName.matches(id) &&
            name.endsWith(".cbz") && uuidName.matches(name.removeSuffix(".cbz")) &&
            hash.matches(Regex("[0-9a-f]{64}")) && bytes in 1..CbzArchive.MAX_ARCHIVE) { "备份中的本地书目无效" }
        return BackupContent(id, name, hash, bytes)
    }

    suspend fun write(output: OutputStream, code: String, allowed: () -> Boolean, progress: (String) -> Unit) {
        val caller = currentCoroutineContext()
        val check = { caller.ensureActive(); if (!allowed()) throw CancellationException("Expired backup") }
        val password = recoveryCode(code).toCharArray()
        try {
            CbzLibrary.portableAccess {
                store.portableData(world, allowed) { db, secret ->
                    ZipOutputStream(output, password).use { zip ->
                        val contents = mutableListOf<BackupContent>()
                        val counts = linkedMapOf<String, Long>()
                        zip.entry("records")
                        val metadata = object : OutputStream() {
                            private var bytes = 0L
                            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                check(); bytes += len
                                require(bytes <= MAX_BACKUP_METADATA) { "备份记录超过支持的大小" }
                                zip.write(b, off, len)
                            }
                        }
                        val json = JsonWriter(OutputStreamWriter(metadata, Charsets.UTF_8))
                        json.beginObject().name("format").value(BACKUP_FORMAT).name("world").value(world.name)
                            .name("createdAt").value(System.currentTimeMillis()).name("komgaBindings").beginArray()
                        // Account identity mappings contain opaque IDs, not credentials or server configuration.
                        if (world == PrototypeWorld.SFW) db.query("SELECT sourceId,sealedValue FROM source_session WHERE sourceId LIKE 'komga-user-source:%' ORDER BY sourceId").use { cursor ->
                            while (cursor.moveToNext()) {
                                check(); val slot = cursor.getString(0)
                                val source = bindingValue(cursor.getBlob(1), secret, slot)
                                require(validKomgaBinding(slot, source)) { "Komga 身份映射无效" }
                                json.beginObject().name("slot").value(slot).name("source").value(source).endObject()
                            }
                        }
                        json.endArray().name("tables").beginObject()
                        for (table in BackupTable.entries) {
                            check(); progress("正在备份记录：${counts.values.sum()} 条")
                            json.name(table.table).beginArray()
                            var count = 0L
                            db.query("SELECT ${table.columns.joinToString(",")} FROM ${table.table} ORDER BY rowid").use { cursor ->
                                while (cursor.moveToNext()) {
                                    check()
                                    val row = cursor.values(table)
                                    if (table == BackupTable.BOOKS) contents += content(row)
                                    json.beginObject()
                                    table.columns.forEach { column ->
                                        json.name(column)
                                        when (val value = row.get(column)) {
                                            null -> json.nullValue()
                                            is Number -> json.value(value)
                                            else -> json.value(value.toString())
                                        }
                                    }
                                    json.endObject(); count++
                                }
                            }
                            json.endArray(); counts[table.table] = count
                        }
                        json.endObject().endObject(); json.flush(); zip.closeEntry()
                        contents.forEachIndexed { index, item ->
                            check(); progress("正在备份正文 ${index + 1} / ${contents.size}")
                            val file = File(cbzDirectory, item.name)
                            require(file.isFile && file.length() == item.bytes) { "CBZ 应用副本缺失或已改变，请从原文件重新导入后备份" }
                            zip.entry("content/$index")
                            file.inputStream().use { copyContent(it, zip, item, check) }
                            zip.closeEntry()
                        }
                        // An authenticated final marker distinguishes a complete backup from a cancelled ZIP.
                        zip.entry("complete"); zip.write(BACKUP_FORMAT.toByteArray()); zip.closeEntry(); check()
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun preview(file: File, code: String, allowed: () -> Boolean, progress: (String) -> Unit): BackupSummary = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        read(file, code, { caller.ensureActive(); if (!allowed()) throw CancellationException("Expired backup preview") }, progress)
    }

    suspend fun restore(file: File, code: String, allowed: () -> Boolean, progress: (String) -> Unit): RestoreSummary {
        val caller = currentCoroutineContext()
        val check = { caller.ensureActive(); if (!allowed()) throw CancellationException("Expired restore") }
        return CbzLibrary.portableAccess {
            val published = mutableListOf<File>()
            var committed = false
            try {
                store.portableData(world, allowed) { db, secret ->
                    val merge = BackupMerge(db, secret, cbzDirectory, published)
                    read(file, code, check, progress, merge)
                    check()
                    RestoreSummary(merge.inserted, merge.retained)
                }.also { committed = true; store.clearMemory() }
            } finally {
                // A cancellation may be delivered after SQLite commits. Consult durable references before cleanup.
                if (!committed && published.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) {
                    store.portableData(world, { true }) { db, _ ->
                        published.forEach { file ->
                            val referenced = db.query("SELECT 1 FROM local_books WHERE archiveName = ?", arrayOf(file.name)).use { it.moveToFirst() }
                            if (!referenced) file.delete()
                        }
                    }
                }
            }
        }
    }

    private fun read(file: File, code: String, check: () -> Unit, progress: (String) -> Unit,
        merge: BackupMerge? = null): BackupSummary {
        val password = recoveryCode(code).toCharArray()
        try {
            ZipInputStream(file.inputStream().buffered(), password).use { zip ->
                zip.expect("records")
                // Stream rows; cap expanded metadata to reject an unbounded or corrupt archive.
                val json = JsonReader(InputStreamReader(LimitedBackupInput(zip, MAX_BACKUP_METADATA, check), Charsets.UTF_8))
                json.beginObject()
                require(json.nextName() == "format" && json.nextString() == BACKUP_FORMAT) { "不支持此备份版本" }
                require(json.nextName() == "world" && json.nextString() == world.name) { "备份空间不匹配，请切换到对应空间后重新选择" }
                require(json.nextName() == "createdAt"); val created = json.nextLong()
                require(json.nextName() == "komgaBindings"); json.beginArray()
                while (json.hasNext()) {
                    check(); json.beginObject()
                    require(json.nextName() == "slot"); val slot = json.nextString()
                    require(json.nextName() == "source"); val source = json.nextString(); json.endObject()
                    require(world == PrototypeWorld.SFW && validKomgaBinding(slot, source)) { "备份的图源身份映射无效" }
                    merge?.binding(slot, source)
                }
                json.endArray()
                require(json.nextName() == "tables"); json.beginObject()
                val contents = mutableListOf<BackupContent>()
                val counts = linkedMapOf<String, Long>()
                for (table in BackupTable.entries) {
                    check(); require(json.nextName() == table.table) { "备份记录结构不匹配" }
                    json.beginArray(); var count = 0L
                    while (json.hasNext()) {
                        check(); val row = json.row(table)
                        if (table == BackupTable.BOOKS) contents += content(row)
                        merge?.row(table, row)
                        count++
                    }
                    json.endArray(); counts[table.table] = count
                    progress("正在读取记录：${counts.values.sum()} 条")
                }
                json.endObject(); json.endObject()
                require(json.peek() == JsonToken.END_DOCUMENT) // Reads through entry EOF, including AES MAC.
                contents.forEachIndexed { index, item ->
                    check(); progress("正在处理正文 ${index + 1} / ${contents.size}")
                    zip.expect("content/$index")
                    val destination = merge?.destination(item.id)
                    if (destination == null) copyContent(zip, null, item, check)
                    else {
                        FileOutputStream(destination).use { sink -> copyContent(zip, sink, item, check); sink.fd.sync() }
                        // Reuse the importer's archive safety boundary before any database commit.
                        val pages = CbzArchive.scan(destination, check)
                        requireNotNull(merge).pages(item.id, pages)
                    }
                }
                zip.expect("complete")
                val marker = LimitedBackupInput(zip, 128, check).readBytes().toString(Charsets.UTF_8)
                require(marker == BACKUP_FORMAT && zip.nextEntry == null) { "备份未完成或包含额外内容" }
                check()
                return BackupSummary(world, created, counts, contents.sumOf { it.bytes })
            }
        } finally { password.fill('\u0000') }
    }
}

private fun ZipOutputStream.entry(name: String) = putNextEntry(ZipParameters().apply {
    fileNameInZip = name; isEncryptFiles = true; encryptionMethod = EncryptionMethod.AES
    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256; aesVersion = AesVersion.TWO
    compressionMethod = CompressionMethod.DEFLATE
})
private fun ZipInputStream.expect(name: String) {
    val entry = nextEntry ?: error("备份内容不完整")
    require(entry.fileName == name && entry.isEncrypted && entry.encryptionMethod == EncryptionMethod.AES &&
        entry.aesExtraDataRecord?.aesKeyStrength == AesKeyStrength.KEY_STRENGTH_256) { "备份条目或加密格式不匹配" }
}
private class LimitedBackupInput(private val input: InputStream, private val max: Long, private val check: () -> Unit) : InputStream() {
    private var bytes = 0L
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check(); val n = input.read(b, off, len)
        if (n > 0) { bytes += n; require(bytes <= max) { "备份条目超过支持的大小" } }
        return n
    }
}
private fun copyContent(input: InputStream, output: OutputStream?, item: BackupContent, check: () -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024); var bytes = 0L
    while (true) {
        check(); val n = input.read(buffer); if (n < 0) break
        bytes += n; require(bytes <= item.bytes) { "备份正文大小不匹配" }
        digest.update(buffer, 0, n); output?.write(buffer, 0, n)
    }
    require(bytes == item.bytes && digest.digest().joinToString("") { "%02x".format(it) } == item.hash) { "备份正文校验失败" }
}
private fun Cursor.values(table: BackupTable) = ContentValues().also { row ->
    table.columns.forEachIndexed { index, name -> when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> row.putNull(name)
        Cursor.FIELD_TYPE_INTEGER -> row.put(name, getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> row.put(name, getDouble(index))
        Cursor.FIELD_TYPE_STRING -> row.put(name, getString(index))
        else -> error("备份不包含二进制凭据")
    } }
}
private fun JsonReader.row(table: BackupTable): ContentValues {
    val row = ContentValues(); beginObject()
    for (column in table.columns) {
        require(nextName() == column) { "备份列不匹配" }
        when (peek()) {
            JsonToken.NULL -> { nextNull(); row.putNull(column) }
            JsonToken.STRING -> row.put(column, nextString())
            JsonToken.NUMBER -> {
                val number = nextString()
                number.toLongOrNull()?.let { row.put(column, it) }
                    ?: row.put(column, number.toDouble().also { require(it.isFinite()) })
            }
            else -> error("备份字段类型不受支持")
        }
    }
    endObject(); return row
}

/** Existing records win; content hashes and group names remap child identities without replacing rows. */
private class BackupMerge(private val db: SupportSQLiteDatabase, private val secret: ByteArray,
    private val directory: File, private val published: MutableList<File>) {
    var inserted = 0L; private set
    var retained = 0L; private set
    private val books = mutableMapOf<String, String>()
    private val groups = mutableMapOf<String, String>()
    private val files = mutableMapOf<String, File>()
    private val newBooks = mutableSetOf<String>()
    private val sources = mutableMapOf<String, String>()
    fun destination(id: String) = files[id]
    fun binding(slot: String, source: String) {
        val previous = db.query("SELECT sealedValue FROM source_session WHERE sourceId = ?", arrayOf(slot)).use {
            if (it.moveToFirst()) bindingValue(it.getBlob(0), secret, slot) else null
        }
        if (previous != null) require(validKomgaBinding(slot, previous)) { "目标 Komga 身份映射无效" }
        sources[source] = previous ?: source
        if (previous == null) {
            val sealed = StorageCipher.seal(source.toByteArray(), secret, storageIdentity("SFW", slot, "session"))
            db.execSQL("INSERT INTO source_session(sourceId,sealedValue) VALUES(?,?)", arrayOf(slot, sealed))
        }
    }
    fun row(table: BackupTable, row: ContentValues) {
        row.getAsString("sourceId")?.let { source ->
            if (table == BackupTable.SEARCH && source.startsWith("online:")) {
                sources[source.removePrefix("online:")]?.let { row.put("sourceId", "online:$it") }
            } else sources[source]?.let { row.put("sourceId", it) }
        }
        when (table) {
            BackupTable.BOOKS -> {
                val oldId = row.getAsString("mangaId")
                require(oldId !in books) { "备份包含重复本地书目" }
                val hash = row.getAsString("sha256")
                db.query("SELECT mangaId, sha256 FROM local_books WHERE mangaId = ? AND sourceId = 'cbz'", arrayOf(oldId)).use {
                    require(!it.moveToFirst() || it.getString(1) == hash) { "作品身份冲突，原书库保持" }
                }
                val existing = db.query("SELECT mangaId,archiveName FROM local_books WHERE sha256 = ?", arrayOf(hash)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
                }
                books[oldId] = existing?.first ?: oldId
                if (existing != null && File(directory, existing.second).exists()) { retained++; return }
                check(directory.isDirectory || directory.mkdirs())
                val dest = File(directory, "${UUID.randomUUID()}.cbz")
                require(dest.createNewFile())
                files[oldId] = dest; published += dest
                if (existing != null) {
                    // Only repair a missing application copy; never replace an existing file or its metadata.
                    db.execSQL("UPDATE local_books SET archiveName = ? WHERE sourceId = 'cbz' AND mangaId = ?", arrayOf(dest.name, existing.first))
                    retained++; return
                }
                newBooks += oldId
                row.put("archiveName", dest.name)
            }
            BackupTable.PAGES -> { /* Rebuild new page indices from the CBZ, retain existing indices. */ return }
            BackupTable.GROUPS -> {
                val id = row.getAsString("id")
                val sameName = db.query("SELECT id FROM user_tags WHERE nameKey = ?", arrayOf(row.getAsString("nameKey"))).use { if (it.moveToFirst()) it.getString(0) else null }
                val sameId = db.query("SELECT 1 FROM user_tags WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
                val target = sameName ?: if (sameId) UUID.randomUUID().toString() else id
                groups[id] = target
                if (sameName != null) { retained++; return }
                row.put("id", target)
            }
            BackupTable.LINKS -> row.put("tagId", groups[row.getAsString("tagId")] ?: error("分组关联缺失"))
            else -> Unit
        }
        val local = table in setOf(BackupTable.BOOKMARKS, BackupTable.PROGRESS) ||
            (table in setOf(BackupTable.LINKS, BackupTable.VISITS, BackupTable.TIME) && row.getAsString("kind") == "local")
        if (local) books[row.getAsString("mangaId")]?.let { row.put("mangaId", it) }
        // Statistics are cumulative checkpoints: restore must never add the same session twice.
        if (table == BackupTable.TIME) {
            val args = arrayOf(row.getAsString("sessionId"), row.getAsString("day"))
            val previous = db.query("SELECT kind,sourceId,mangaId,durationMs FROM reading_time WHERE sessionId = ? AND day = ?", args).use {
                if (!it.moveToFirst()) null else {
                    require(it.getString(0) == row.getAsString("kind") && it.getString(1) == row.getAsString("sourceId") && it.getString(2) == row.getAsString("mangaId")) { "阅读统计身份冲突" }
                    it.getLong(3)
                }
            }
            if (previous != null) {
                if (row.getAsLong("durationMs") > previous) db.execSQL("UPDATE reading_time SET durationMs = ? WHERE sessionId = ? AND day = ?",
                    arrayOf(row.getAsLong("durationMs"), args[0], args[1]))
                retained++; return
            }
        }
        val exists = db.query("SELECT 1 FROM ${table.table} WHERE ${table.primaryKey.joinToString(" AND ") { "$it = ?" }}",
            table.primaryKey.map { requireNotNull(row.getAsString(it)) { "备份记录缺少身份字段" } }.toTypedArray()).use { it.moveToFirst() }
        if (exists) retained++ else {
            db.insert(table.table, SQLiteDatabase.CONFLICT_ABORT, row)
            inserted++
        }
    }
    fun pages(id: String, pages: List<ArchivePage>) {
        if (id !in newBooks) return
        pages.forEachIndexed { index, page ->
            db.execSQL("INSERT INTO local_pages(sourceId,mangaId,ordinal,entryName,mimeType,width,height) VALUES(?,?,?,?,?,?,?)",
                arrayOf("cbz", id, index + 1, page.name, page.mime, page.width, page.height))
        }
    }
}
