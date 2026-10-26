package com.flux.manga.data

import com.flux.manga.core.CopyCollectedEntry
import java.io.*
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal fun isCopyAccount(source: String) = source.matches(Regex("copymanga-account-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
internal fun isCopySource(source: String) = source == CopyMangaEndpoint.SOURCE_ID || isCopyAccount(source)
internal enum class CopyAccountService(val base: String) {
    LEGACY(CopyMangaEndpoint.BASE_URL), OFFICIAL("https://mapi.mangacopy.com/"), WEB("https://mangacopy.com/")
}
internal fun copyAccountId(user: String, service: CopyAccountService = CopyAccountService.LEGACY) = "copymanga-account-${UUID.nameUUIDFromBytes((service.base + "\n" + user).toByteArray())}"
internal class CopyAccount(val userId: String, val username: String, internal val token: String, val service: CopyAccountService = CopyAccountService.LEGACY) {
    val sourceId get() = copyAccountId(userId, service)
    val hasCredential get() = token.isNotEmpty()
    fun withoutCredential() = CopyAccount(userId, username, "", service)
}
internal data class CopyImportTask(val id: String, val favorite: Boolean, val selection: List<CopyCollectedEntry>?,
    val offset: Int = 0, val cursor: Int = 0, val added: Int = 0, val existing: Int = 0, val done: Boolean = false)
internal fun sameCopyAccount(a: CopyAccount?, b: CopyAccount) = a?.userId == b.userId && a.token == b.token && a.service == b.service

/** Only source_session encrypted payloads change; no new database or schema. */
internal object CopyAccountCodec {
    private fun validate(c: CopyAccount) {
        require(c.userId.matches(Regex("[A-Za-z0-9_-]{1,256}")) && c.username.isNotBlank() && c.username.toByteArray().size <= 256)
        require(c.token.length <= 4096 && c.token.all { it.code in 33..126 })
    }
    fun validate(e: CopyCollectedEntry) {
        require(e.mangaId.matches(Regex("[A-Za-z0-9_-]{1,256}")) && e.title.isNotBlank() && e.title.toByteArray().size <= 4096)
        require(e.collectionId.toULongOrNull() != null)
    }
    private fun validate(t: CopyImportTask) {
        require(t.offset >= 0 && t.offset % 21 == 0 && t.cursor >= 0 && t.added >= 0 && t.existing >= 0)
        require(t.added.toLong() + t.existing == t.cursor.toLong())
        require(t.selection == null || (t.selection.size in 1..1000 && t.cursor <= t.selection.size))
        t.selection?.forEach(::validate)
    }
    fun account(c: CopyAccount): ByteArray = write { out ->
        validate(c); out.writeInt(2); out.writeUTF(c.userId); out.writeUTF(c.username); out.writeUTF(c.token); out.writeUTF(c.service.name)
    }
    fun account(bytes: ByteArray): CopyAccount = read(bytes) { input ->
        val version = input.readInt(); require(version in 1..2)
        val id = input.readUTF(); val name = input.readUTF(); val token = input.readUTF()
        CopyAccount(id, name, token, if (version == 1) CopyAccountService.LEGACY else CopyAccountService.valueOf(input.readUTF())).also(::validate)
    }
    fun task(t: CopyImportTask?): ByteArray = write { out ->
        out.writeInt(1); out.writeBoolean(t != null)
        if (t != null) {
            validate(t); out.writeUTF(t.id); out.writeBoolean(t.favorite); out.writeInt(t.offset); out.writeInt(t.cursor)
            out.writeInt(t.added); out.writeInt(t.existing); out.writeBoolean(t.done); out.writeInt(t.selection?.size ?: -1)
            t.selection?.forEach { out.writeUTF(it.mangaId); out.writeUTF(it.title); out.writeUTF(it.collectionId); out.writeBoolean(it.folder) }
        }
    }
    fun task(bytes: ByteArray): CopyImportTask? = read(bytes) { input ->
        require(input.readInt() == 1)
        if (!input.readBoolean()) null else {
            val id = input.readUTF(); val favorite = input.readBoolean(); val offset = input.readInt(); val cursor = input.readInt()
            val added = input.readInt(); val existing = input.readInt(); val done = input.readBoolean(); val n = input.readInt()
            require(n == -1 || n in 1..1000)
            CopyImportTask(id, favorite, if (n == -1) null else List(n) {
                CopyCollectedEntry(input.readUTF(), input.readUTF(), input.readUTF(), input.readBoolean()).also(::validate)
            }, offset, cursor, added, existing, done).also(::validate)
        }
    }
    private fun write(block: (DataOutputStream) -> Unit) = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(block) }.toByteArray()
    private fun <T> read(bytes: ByteArray, block: (DataInputStream) -> T): T {
        require(bytes.size <= 8 * 1024 * 1024)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input -> block(input).also { require(input.available() == 0) } }
    }
}

internal class CopyAccountStore(private val db: AccountImportDatabase) {
    private val slot = "copy-account-v1"
    private fun AccountImportRows.account(): CopyAccount? {
        val bytes = read(slot) ?: return null
        return try { CopyAccountCodec.account(bytes) } catch (_: Exception) { throw IOException("账号记录无法读取") } finally { bytes.fill(0) }
    }
    private fun AccountImportRows.save(c: CopyAccount) {
        val bytes = CopyAccountCodec.account(c)
        try { write(slot, bytes) } finally { bytes.fill(0) }
    }
    suspend fun current(allowed: () -> Boolean) = db.transaction(allowed) { it.account() }
    suspend fun activate(c: CopyAccount, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        require(c.hasCredential); rows.save(c); c
    }
    private fun AccountImportRows.requireAccount(c: CopyAccount) {
        if (!c.hasCredential || !sameCopyAccount(account(), c)) throw CancellationException("Copy account changed")
    }
    private fun taskSlot(source: String) = "copy-import-task:$source"
    private fun AccountImportRows.task(source: String): CopyImportTask? {
        require(isCopyAccount(source))
        val bytes = read(taskSlot(source)) ?: return null
        return try { CopyAccountCodec.task(bytes) } catch (_: Exception) { throw IOException("导入记录无法读取") } finally { bytes.fill(0) }
    }
    private fun AccountImportRows.saveTask(source: String, task: CopyImportTask?) {
        val bytes = CopyAccountCodec.task(task)
        try { write(taskSlot(source), bytes) } finally { bytes.fill(0) }
    }
    suspend fun task(source: String, allowed: () -> Boolean) = db.transaction(allowed) { it.task(source) }
    suspend fun logout(c: CopyAccount, allowed: () -> Boolean) = db.transaction(allowed) { rows -> rows.requireAccount(c); rows.save(c.withoutCredential()) }
    suspend fun begin(c: CopyAccount, selection: List<CopyCollectedEntry>?, favorite: Boolean, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        rows.requireAccount(c); require(rows.task(c.sourceId)?.done != false)
        CopyImportTask(UUID.randomUUID().toString(), favorite, selection).also { rows.saveTask(c.sourceId, it) }
    }
    suspend fun commit(c: CopyAccount, expected: CopyImportTask, entries: List<CopyCollectedEntry>, next: Int?, allowed: () -> Boolean): CopyImportTask = db.transaction(allowed) { rows ->
        rows.requireAccount(c); val task = requireNotNull(rows.task(c.sourceId))
        require(task == expected && !task.done && entries.size <= 21 && (next == null || next.toLong() == task.offset.toLong() + 21))
        if (task.selection != null) require(entries == task.selection.drop(task.cursor).take(21))
        var added = task.added; var existing = task.existing
        for (entry in entries) {
            if (!allowed()) throw CancellationException()
            CopyAccountCodec.validate(entry)
            val old = rows.book(c.sourceId, entry.mangaId); val progress = rows.hasProgress(c.sourceId, entry.mangaId)
            if (old != null || progress) existing++ else added++
            if (old == null) rows.putBook(OnlineBook(c.sourceId, entry.mangaId, entry.title, if (progress) false else task.favorite, System.currentTimeMillis()))
            // Membership ID belongs to the filtered cloud collection, never to the manga key.
            rows.write("copy-import-ref:${c.sourceId}:free-type-1:${entry.collectionId}",
                (entry.mangaId + "\n" + entry.folder).toByteArray())
        }
        val cursor = Math.addExact(task.cursor, entries.size)
        task.copy(offset = next ?: task.offset, cursor = cursor, added = added, existing = existing,
            done = if (task.selection != null) cursor == task.selection.size else next == null).also { rows.saveTask(c.sourceId, it) }
    }
    suspend fun finish(c: CopyAccount, allowed: () -> Boolean) = db.transaction(allowed) { rows -> rows.requireAccount(c); rows.saveTask(c.sourceId, null) }
    suspend fun rescan(c: CopyAccount, allowed: () -> Boolean) = db.transaction(allowed) { rows ->
        rows.requireAccount(c); val t = requireNotNull(rows.task(c.sourceId)); require(t.selection == null)
        t.copy(offset = 0, cursor = 0, added = 0, existing = 0, done = false).also { rows.saveTask(c.sourceId, it) }
    }
}
