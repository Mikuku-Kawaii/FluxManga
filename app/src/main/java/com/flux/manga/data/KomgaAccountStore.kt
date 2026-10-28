package com.flux.manga.data

import com.flux.manga.core.*
import java.io.*
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal enum class KomgaAuth { API_KEY, BASIC, SESSION }
internal class KomgaConfiguration(val sourceId: String, val baseUrl: String, internal val apiKey: String,
    val auth: KomgaAuth = KomgaAuth.API_KEY, val username: String = "", val userId: String? = null) {
    val hasCredential get() = apiKey.isNotEmpty()
    fun withoutCredential() = KomgaConfiguration(sourceId, baseUrl, "", auth, username, userId)
}
internal fun validKomgaId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,256}"))
internal fun sameKomgaCredential(a: KomgaConfiguration, b: KomgaConfiguration) =
    a.sourceId == b.sourceId && a.baseUrl == b.baseUrl && a.auth == b.auth && a.username == b.username && a.apiKey == b.apiKey && a.userId == b.userId
internal data class KomgaImportTask(val id: String, val list: KomgaAuthorizedList, val favorite: Boolean,
    val selection: List<KomgaImportEntry>?, val page: Int = 0, val cursor: Int = 0,
    val added: Int = 0, val existing: Int = 0, val done: Boolean = false)

/** Version 2 replaces only the configuration payload, not the database schema. */
internal object KomgaAccountCodec {
    private fun validate(c: KomgaConfiguration) {
        require(c.sourceId.matches(Regex("komga-[0-9a-f-]{36}")) && c.apiKey.length <= 4096 && c.username.length <= 256)
        require(c.userId == null || validKomgaId(c.userId))
        normalizeKomgaUrl(c.baseUrl)
    }
    private fun validate(t: KomgaImportTask) {
        require(validKomgaId(t.list.id) && t.list.name.isNotBlank() && t.list.name.toByteArray().size <= 4096 && t.page >= 0 && t.cursor >= 0 && t.added >= 0 && t.existing >= 0)
        require(t.added.toLong() + t.existing == t.cursor.toLong())
        require(t.selection == null || (t.selection.size in 1..1000 && t.cursor <= t.selection.size))
        t.selection?.forEach(::validate)
    }
    private fun validate(e: KomgaImportEntry) {
        require(validKomgaId(e.seriesId) && (e.bookId == null || validKomgaId(e.bookId!!)) && e.title.isNotBlank() && e.title.toByteArray().size <= 4096)
    }
    fun configuration(c: KomgaConfiguration): ByteArray = write { out ->
        validate(c)
        out.writeInt(2); out.writeUTF(c.sourceId); out.writeUTF(c.baseUrl); out.writeUTF(c.auth.name)
        out.writeUTF(c.username); out.writeUTF(c.apiKey); out.writeUTF(c.userId.orEmpty())
    }
    fun configuration(bytes: ByteArray): KomgaConfiguration = read(bytes) { input ->
        require(input.readInt() == 2)
        val id = input.readUTF(); val base = input.readUTF(); val auth = KomgaAuth.valueOf(input.readUTF())
        val name = input.readUTF(); val key = input.readUTF(); val user = input.readUTF().ifEmpty { null }
        KomgaConfiguration(id, normalizeKomgaUrl(base), key, auth, name, user).also(::validate)
    }
    private fun DataOutputStream.entry(e: KomgaImportEntry) {
        validate(e)
        writeUTF(e.seriesId); writeUTF(e.title); writeUTF(e.bookId.orEmpty()); writeLong(e.position.toLong())
    }
    private fun DataInputStream.entry() = KomgaImportEntry(readUTF(), readUTF(), readUTF().ifEmpty { null }, readLong().also { require(it in 0..UInt.MAX_VALUE.toLong()) }.toUInt())
    fun task(t: KomgaImportTask?): ByteArray = write { out ->
        out.writeInt(1); out.writeBoolean(t != null)
        if (t != null) {
            validate(t)
            out.writeUTF(t.id); out.writeUTF(t.list.kind.name); out.writeUTF(t.list.id); out.writeUTF(t.list.name)
            out.writeBoolean(t.list.ordered); out.writeBoolean(t.list.filtered); out.writeBoolean(t.favorite)
            out.writeInt(t.page); out.writeInt(t.cursor); out.writeInt(t.added); out.writeInt(t.existing); out.writeBoolean(t.done)
            out.writeInt(t.selection?.size ?: -1); t.selection?.forEach { out.entry(it) }
        }
    }
    fun task(bytes: ByteArray): KomgaImportTask? = read(bytes) { input ->
        require(input.readInt() == 1)
        if (!input.readBoolean()) null else {
            val id = input.readUTF(); val kind = KomgaListKind.valueOf(input.readUTF())
            val list = KomgaAuthorizedList(input.readUTF(), input.readUTF(), kind, input.readBoolean(), input.readBoolean())
            val favorite = input.readBoolean(); val page = input.readInt(); val cursor = input.readInt()
            val added = input.readInt(); val existing = input.readInt(); val done = input.readBoolean(); val n = input.readInt()
            require(n == -1 || n in 1..1000)
            KomgaImportTask(id, list, favorite, if (n == -1) null else List(n) { input.entry() }, page, cursor, added, existing, done)
                .also(::validate)
        }
    }
    private fun write(block: (DataOutputStream) -> Unit) = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(block) }.toByteArray()
    private fun <T> read(bytes: ByteArray, block: (DataInputStream) -> T): T {
        require(bytes.size <= 8 * 1024 * 1024)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input -> block(input).also { require(input.available() == 0) } }
    }
}

/** Credentials and import checkpoints use the same existing encrypted transaction bridge. */
internal class KomgaAccountStore(private val db: AccountImportDatabase,
    private val legacy: (ByteArray) -> KomgaConfiguration = { throw IOException("Legacy configuration needs Android decoder") }) {
    private val slot = "komga-user-configuration-v1"
    private fun AccountImportRows.config(): KomgaConfiguration? {
        val bytes = read(slot) ?: return null
        return try { if (bytes.firstOrNull() == '{'.code.toByte()) legacy(bytes) else KomgaAccountCodec.configuration(bytes) }
        catch (_: Exception) { throw IOException("配置读取失败，请重试。") } finally { bytes.fill(0) }
    }
    private fun AccountImportRows.save(c: KomgaConfiguration) {
        val bytes = KomgaAccountCodec.configuration(c)
        try { write(slot, bytes) } finally { bytes.fill(0) }
    }
    suspend fun current(allowed: () -> Boolean) = db.transaction(allowed) { it.config() }
    suspend fun save(c: KomgaConfiguration, allowed: () -> Boolean) = db.transaction(allowed) { it.save(c) }
    suspend fun activate(candidate: KomgaConfiguration, user: String, allowed: () -> Boolean): KomgaConfiguration = db.transaction(allowed) { rows ->
        require(validKomgaId(user) && candidate.hasCredential)
        val previous = rows.config()
        val mapping = "komga-user-source:" + UUID.nameUUIDFromBytes((candidate.baseUrl + "\n" + user).toByteArray())
        val oldId = rows.read(mapping)?.let { bytes -> try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) } }
        val same = previous != null && previous.baseUrl == candidate.baseUrl &&
            (previous.userId == user || (previous.userId == null && previous.auth == candidate.auth && previous.apiKey == candidate.apiKey && previous.username == candidate.username))
        val source = if (same) previous!!.sourceId else oldId ?: "komga-${UUID.randomUUID()}"
        val saved = KomgaConfiguration(source, candidate.baseUrl, candidate.apiKey, candidate.auth, candidate.username, user)
        rows.save(saved); rows.write(mapping, source.toByteArray()); saved
    }
    private fun AccountImportRows.requireConfig(expected: KomgaConfiguration) {
        if (config()?.let { sameKomgaCredential(it, expected) } != true || !expected.hasCredential) throw CancellationException("Komga account changed")
    }
    private fun taskSlot(source: String) = "komga-import-task:$source"
    private fun AccountImportRows.task(source: String): KomgaImportTask? {
        val bytes = read(taskSlot(source)) ?: return null
        return try { KomgaAccountCodec.task(bytes) } catch (_: Exception) { throw IOException("导入记录无法读取") } finally { bytes.fill(0) }
    }
    private fun AccountImportRows.saveTask(source: String, task: KomgaImportTask?) {
        val bytes = KomgaAccountCodec.task(task)
        try { write(taskSlot(source), bytes) } finally { bytes.fill(0) }
    }
    suspend fun task(source: String, allowed: () -> Boolean) = db.transaction(allowed) { it.task(source) }
    suspend fun logout(expected: KomgaConfiguration, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        rows.requireConfig(expected); rows.save(expected.withoutCredential())
    }
    suspend fun begin(c: KomgaConfiguration, list: KomgaAuthorizedList, selection: List<KomgaImportEntry>?, favorite: Boolean, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        rows.requireConfig(c); require(rows.task(c.sourceId)?.done != false)
        KomgaImportTask(UUID.randomUUID().toString(), list, favorite, selection).also { rows.saveTask(c.sourceId, it) }
    }
    suspend fun commit(c: KomgaConfiguration, expected: KomgaImportTask, entries: List<KomgaImportEntry>, next: Int?, allowed: () -> Boolean): KomgaImportTask = db.transaction(allowed) { rows ->
        rows.requireConfig(c); val task = requireNotNull(rows.task(c.sourceId))
        require(task == expected && !task.done && entries.size <= 20 && (next == null || next == task.page + 1))
        if (task.selection != null) require(entries == task.selection.drop(task.cursor).take(20))
        var added = task.added; var existing = task.existing
        for (entry in entries) {
            if (!allowed()) throw CancellationException()
            require(validKomgaId(entry.seriesId) && entry.title.isNotBlank())
            val old = rows.book(c.sourceId, entry.seriesId); val progress = rows.hasProgress(c.sourceId, entry.seriesId)
            if (old != null || progress) existing++ else added++
            if (old == null) rows.putBook(OnlineBook(c.sourceId, entry.seriesId, entry.title, if (progress) false else task.favorite, System.currentTimeMillis()))
            // One slot per list member: another list never overwrites this association.
            val ref = "komga-import-ref:${c.sourceId}:${task.list.kind}:${task.list.id}:${entry.bookId ?: entry.seriesId}"
            val bytes = (entry.seriesId + "\n" + entry.bookId.orEmpty() + "\n" + entry.position + "\n" + task.list.name).toByteArray()
            try { rows.write(ref, bytes) } finally { bytes.fill(0) }
        }
        val cursor = task.cursor + entries.size
        task.copy(page = next ?: task.page, cursor = cursor, added = added, existing = existing,
            done = if (task.selection != null) cursor == task.selection.size else next == null).also { rows.saveTask(c.sourceId, it) }
    }
    suspend fun finish(c: KomgaConfiguration, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        rows.requireConfig(c); rows.saveTask(c.sourceId, null)
    }
    suspend fun rescan(c: KomgaConfiguration, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        rows.requireConfig(c); val t = requireNotNull(rows.task(c.sourceId)); require(t.selection == null)
        t.copy(page = 0, cursor = 0, added = 0, existing = 0, done = false).also { rows.saveTask(c.sourceId, it) }
    }
}
