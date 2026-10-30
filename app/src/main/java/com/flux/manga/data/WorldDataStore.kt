package com.flux.manga.data

import android.content.Context
import android.util.AtomicFile
import android.util.LruCache
import androidx.room.Room
import com.flux.manga.storage.*
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ResourceKey(val manga: SampleKey, val resourceId: String)
private data class MemoryResourceKey(val namespace: String, val resource: ResourceKey)

/** One-process fixed-data persistence. Every operation closes its DB and wipes key copies. */
class WorldDataStore(context: Context, private val namespace: String = "business") {
    private val app = context.applicationContext
    internal fun sourceSelection(world: PrototypeWorld) = SourceSelection(app, world)
    init { require(namespace.matches(Regex("[a-z0-9-]+"))) }
    companion object {
        private val ioLock = Mutex()
        private val preparedReaderDirectories = mutableSetOf<String>()
        private val catalogTrimAt = mutableMapOf<String, Long>()
        private val memoryEpoch = java.util.concurrent.atomic.AtomicLong()
        private val memory = object : LruCache<MemoryResourceKey, ByteArray>(1024 * 1024) {
            override fun sizeOf(key: MemoryResourceKey, value: ByteArray) = value.size
            override fun entryRemoved(evicted: Boolean, key: MemoryResourceKey, oldValue: ByteArray, newValue: ByteArray?) { oldValue.fill(0) }
        }
    }
    fun clearMemory() { synchronized(memory) { memoryEpoch.incrementAndGet(); memory.evictAll() } }
    fun directory(world: PrototypeWorld) = File(app.noBackupFilesDir, "$namespace/${world.name.lowercase()}")
    fun databaseFile(world: PrototypeWorld) = File(directory(world), "${world.name.lowercase()}-catalog.db")
    fun cacheDirectory(world: PrototypeWorld) = File(app.cacheDir, "$namespace/${world.name.lowercase()}")
    internal fun keys(world: PrototypeWorld, purpose: String) = WrappedSecretStore(
        File(directory(world), purpose), "${app.packageName}.$namespace.${world.name}.$purpose", "M1-$purpose",
    )
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        ioLock.withLock {
            currentCoroutineContext().ensureActive()
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (full: ReaderStorageFullException) { throw full }
            catch (failure: Exception) { throw StorageAccessException(storageProblem(failure), failure) }
        }
    }
    private fun <T> database(world: PrototypeWorld, seed: Boolean = true, action: (WorldDao, ByteArray) -> T): T =
        databaseConnection(world) { db, payload ->
            val dao = db.data()
            if (seed) dao.seed((1..60).map { StoredManga("sample", it.toString(), if (world == PrototypeWorld.SFW) "示例作品 $it" else "里空间测试样本 $it") })
            action(dao, payload)
        }

    /** Same lock, transaction, key lifetime and commit check as ordinary business operations. */
    internal suspend fun <T> portableData(world: PrototypeWorld, allowed: () -> Boolean,
        action: (androidx.sqlite.db.SupportSQLiteDatabase, ByteArray) -> T): T {
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        return io { operation.check(); databaseConnection(world) { db, secret ->
            operation.beforeCommit { action(db.openHelper.writableDatabase, secret) }
        } }
    }

    private fun <T> databaseConnection(world: PrototypeWorld, action: (WorldDatabase, ByteArray) -> T): T = com.flux.manga.traceWork("FluxDatabase:${world.name}") {
        check(directory(world).isDirectory || directory(world).mkdirs())
        val file = databaseFile(world)
        DatabaseArtifacts.requireUsableMain(file, hasKeyArtifacts = !file.exists() &&
            listOf("payload", "database").any { SecretEnvelope.hasArtifacts(keys(world, it).envelopeFile) })
        // Prepare payload keys before the first DB creation. Missing keys never erase a DB.
        val payload = keys(world, "payload").loadOrCreate(file.exists())
        var password: ByteArray? = null
        var db: WorldDatabase? = null
        try {
            val builder = Room.databaseBuilder(app, WorldDatabase::class.java, file.absolutePath)
                .addMigrations(*WORLD_MIGRATIONS)
            if (world == PrototypeWorld.NSFW) {
                System.loadLibrary("sqlcipher")
                password = keys(world, "database").loadOrCreate(file.exists())
                builder.openHelperFactory(PreservingDatabaseFactory(password))
            } else builder.openHelperFactory(PreservingDatabaseFactory())
            val opened = builder.build()
            db = opened
            try { opened.openHelper.writableDatabase }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw StorageAccessException(StorageProblem.DATABASE_REJECTED, failure)
            }
            return opened.runInTransaction<T>(java.util.concurrent.Callable {
                action(opened, payload)
            })
        } finally {
            try { db?.close() } finally { password?.fill(0); payload.fill(0) }
        }
    }
    /** File/key check only: does not open Room, migrate, seed, create keys, or repair AtomicFile. */
    internal suspend fun inspectProtection(world: PrototypeWorld): ProtectionReport = io {
        val file = databaseFile(world)
        val purposes = if (world == PrototypeWorld.NSFW) listOf("payload", "database") else listOf("payload")
        val stores = purposes.map { keys(world, it) }
        if (!file.exists() && DatabaseArtifacts.sidecars(file).none { it.exists() } &&
            stores.none { SecretEnvelope.hasArtifacts(it.envelopeFile) }) {
            ProtectionReport(ProtectionState.NOT_CREATED, "此空间尚未建立存储。")
        } else try {
            DatabaseArtifacts.requireUsableMain(file)
            if (!file.exists()) ProtectionReport(ProtectionState.INCOMPLETE,
                "数据库文件缺失，现有密钥保留。")
            else {
                stores.forEach { it.loadExistingReadOnly().fill(0) }
                ProtectionReport(ProtectionState.KEYS_READABLE,
                    "密钥可用，数据库尚未检查。")
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            ProtectionReport(ProtectionState.BLOCKED, storageMessage(failure))
        }
    }

    private fun diagnosticDirectory(world: PrototypeWorld) = File(cacheDirectory(world), "database-diagnostic")

    internal suspend fun hasDiagnosticCopy(world: PrototypeWorld): Boolean =
        DatabaseDiagnostics.hasCopy(diagnosticDirectory(world))

    internal suspend fun clearDiagnosticCopy(world: PrototypeWorld): Boolean =
        DatabaseDiagnostics.cleanup(diagnosticDirectory(world))

    internal suspend fun diagnoseProtection(
        world: PrototypeWorld,
        allowed: () -> Boolean,
        onStage: (DiagnosticStage) -> Unit,
        onCleanup: (Boolean) -> Unit,
        migrateCopy: Boolean = false,
    ): DatabaseDiagnosticReport = DatabaseDiagnostics.inspect(diagnosticDirectory(world), prepare = { target ->
        // All production Room operations use this same mutex and close their connection before releasing it.
        val context = currentCoroutineContext()
        // The diagnostic runner already uses IO. Do not return a secret across withContext's
        // cancellable dispatch boundary before the runner can take ownership of it.
        ioLock.withLock {
            fun checkActive() {
                context.ensureActive()
                if (!allowed()) throw CancellationException("Expired diagnostic session")
            }
            checkActive()
            val source = databaseFile(world)
            DatabaseArtifacts.requireUsableMain(source)
            if (!source.isFile) throw StorageAccessException(StorageProblem.DATABASE_INCOMPLETE)
            // Validate existing envelopes without AtomicFile recovery or key creation.
            val payload = keys(world, "payload").loadExistingReadOnly()
            var password: ByteArray? = null
            try {
                password = if (world == PrototypeWorld.NSFW) keys(world, "database").loadExistingReadOnly() else null
                val inputs = listOf(source, File(source.path + "-wal"), File(source.path + "-journal")).filter { it.exists() }
                if (inputs.any { !it.isFile }) throw StorageAccessException(StorageProblem.DATABASE_INCOMPLETE)
                val bytes = inputs.fold(0L) { total, file -> Math.addExact(total, file.length()) }
                // Leave working room for SQLite's snapshot-local SHM and the rest of the application.
                val required = if (migrateCopy) Math.multiplyExact(bytes, 3L) else bytes
                if (required > target.parentFile!!.usableSpace - 16L * 1024 * 1024) {
                    throw StorageAccessException(StorageProblem.DIAGNOSTIC_SPACE)
                }
                val buffer = ByteArray(64 * 1024)
                try {
                    inputs.forEach { input ->
                        val output = File(target.path + input.name.removePrefix(source.name))
                        input.inputStream().use { from -> output.outputStream().use { to ->
                            while (true) {
                                checkActive()
                                val count = from.read(buffer)
                                if (count < 0) break
                                to.write(buffer, 0, count)
                            }
                        } }
                    }
                } finally { buffer.fill(0) }
                checkActive()
                DiagnosticSnapshot(bytes, password, payload, world.name)
            } catch (failure: Throwable) {
                password?.fill(0); payload.fill(0)
                throw failure
            }
        }
    }, onStage = onStage, onCleanup = onCleanup, migrate = if (!migrateCopy) null else { file, password, checkActive ->
        checkActive()
        val migrations = WORLD_MIGRATIONS.map { migration ->
            object : androidx.room.migration.Migration(migration.startVersion, migration.endVersion) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    checkActive(); migration.migrate(db); checkActive()
                }
            }
        }.toTypedArray()
        val db = Room.databaseBuilder(app, WorldDatabase::class.java, file.absolutePath)
            .addMigrations(*migrations)
            .openHelperFactory(PreservingDatabaseFactory(password))
            .build()
        try {
            // Room runs the existing migrations / identity validation on this copy only. No seed or DAO writes.
            db.openHelper.writableDatabase
            checkActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw StorageAccessException(StorageProblem.DATABASE_REJECTED, failure)
        } finally { db.close() }
    })

    private fun resourceFiles(world: PrototypeWorld) = OwnedCacheFiles(cacheDirectory(world), Regex("[0-9a-f]{64}(\\.bak|\\.new)?"))
    private fun readerResourceFiles(world: PrototypeWorld) = OwnedCacheFiles(File(cacheDirectory(world), "reader-pages"), Regex("(?:[0-9a-f-]{36}_)?[0-9a-f]{64}(\\.bak|\\.new)?"))
    internal suspend fun prepareReaderResources(world: PrototypeWorld, session: String) = io {
        val directory = File(cacheDirectory(world), "reader-pages")
        check(directory.isDirectory || directory.mkdirs())
        val registration = "${directory.absolutePath}/$session"
        if (preparedReaderDirectories.add(registration)) {
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9a-f-]{36}_[0-9a-f]{64}(\\.bak|\\.new)?")) }
                ?.filter { "${directory.absolutePath}/${it.name.substringBefore('_')}" !in preparedReaderDirectories && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }
    internal suspend fun closeReaderResources(world: PrototypeWorld, session: String) = io {
        require(session.matches(Regex("[0-9a-f-]{36}")))
        val directory = File(cacheDirectory(world), "reader-pages")
        val result = OwnedCacheFiles(directory, Regex("${Regex.escape(session)}_[0-9a-f]{64}(\\.bak|\\.new)?")).clear(active = { })
        if (result.failed > 0) throw IOException("部分阅读临时缓存未清理完成")
        preparedReaderDirectories.remove("${directory.absolutePath}/$session")
        synchronized(memory) {
            memory.snapshot().keys.filter { it.namespace == namespace && it.resource.manga.world == world &&
                it.resource.resourceId.startsWith("reader-session:$session:") }.forEach { memory.remove(it) }
        }
    }
    private fun activeReaderFile(file: File) = "${file.parentFile!!.absolutePath}/${file.name.substringBefore('_')}" in preparedReaderDirectories
    internal suspend fun readerCacheUsage(world: PrototypeWorld): CacheUsage = io { readerResourceFiles(world).usage() }
    internal suspend fun clearReaderCache(world: PrototypeWorld, allowed: () -> Boolean): CacheCleared {
        val context = currentCoroutineContext()
        return io {
            synchronized(memory) {
                memoryEpoch.incrementAndGet()
                memory.snapshot().keys.filter { it.namespace == namespace && it.resource.manga.world == world &&
                    it.resource.resourceId.startsWith("reader-session:") && !activeReaderFile(cacheFile(it.resource)) }
                    .forEach { memory.remove(it) }
            }
            readerResourceFiles(world).clearRetaining(active = {
                context.ensureActive(); if (!allowed()) throw CancellationException("Expired cache cleanup")
            }, retain = ::activeReaderFile)
        }
    }
    internal suspend fun privateResourceUsage(): CacheUsage = io {
        val saved = resourceFiles(PrototypeWorld.NSFW).usage()
        val reader = readerResourceFiles(PrototypeWorld.NSFW).usage()
        val catalog = catalogPageFiles(PrototypeWorld.NSFW).usage()
        CacheUsage(saved.files + reader.files + catalog.files, saved.bytes + reader.bytes + catalog.bytes)
    }
    internal suspend fun clearPrivateResources(allowed: () -> Boolean): CacheCleared {
        val context = currentCoroutineContext()
        return io {
            fun active() { context.ensureActive(); if (!allowed()) throw CancellationException("Expired cache cleanup") }
            active()
            // Shared memory is namespaced, so another store cannot serve bytes from a removed cache file.
            synchronized(memory) {
                memoryEpoch.incrementAndGet()
                memory.snapshot().keys.filter { it.namespace == namespace && it.resource.manga.world == PrototypeWorld.NSFW }
                    .forEach { memory.remove(it) }
            }
            val saved = resourceFiles(PrototypeWorld.NSFW).clear(::active)
            val reader = readerResourceFiles(PrototypeWorld.NSFW).clearRetaining(::active, retain = ::activeReaderFile)
            val catalog = catalogPageFiles(PrototypeWorld.NSFW).clear(::active)
            CacheCleared(saved.files + reader.files + catalog.files, saved.bytes + reader.bytes + catalog.bytes, saved.failed + reader.failed + catalog.failed)
        }
    }

    suspend fun prepare(world: PrototypeWorld) = io { database(world) { _, _ -> Unit } }
    internal fun accountImportDatabase(world: PrototypeWorld = PrototypeWorld.SFW): AccountImportDatabase = object : AccountImportDatabase {
        override suspend fun <T> transaction(allowed: () -> Boolean, action: (AccountImportRows) -> T): T =
            guardedDatabase(world, allowed) { dao, secret ->
                val rows = object : AccountImportRows {
                    override fun read(slot: String) = dao.session(slot)?.let { open(it, secret, identity(world.name, slot, "session")) }
                    override fun write(slot: String, value: ByteArray) {
                        dao.putSession(SourceSession(slot, seal(value, secret, identity(world.name, slot, "session"))))
                    }
                    override fun book(source: String, id: String) = dao.onlineBook(source, id)
                    override fun hasProgress(source: String, id: String) = dao.onlineProgress(source, id) != null
                    override fun putBook(book: OnlineBook) = dao.putOnlineBook(book)
                }
                action(rows)
            }
    }
    internal suspend fun <T> localData(action: (WorldDao) -> T): T = io {
        database(PrototypeWorld.SFW) { dao, _ -> action(dao) }
    }
    private suspend fun <T> guardedDatabase(
        world: PrototypeWorld, allowed: () -> Boolean, seed: Boolean = true, action: (WorldDao, ByteArray) -> T,
    ): T {
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        operation.check()
        return io {
            operation.check()
            // Final check must run BEFORE Room commits, not after database() returns.
            database(world, seed) { dao, secret -> operation.beforeCommit { action(dao, secret) } }
        }
    }
    internal suspend fun <T> worldData(world: PrototypeWorld, allowed: () -> Boolean = { true }, action: (WorldDao) -> T): T =
        guardedDatabase(world, allowed) { dao, _ -> action(dao) }
    /** Standard protected Room opening; export queries never seed or modify business records. */
    internal suspend fun <T> recordExportData(world: PrototypeWorld, allowed: () -> Boolean, action: (RecordExportDao) -> T): T =
        guardedDatabase(world, allowed, seed = false) { dao, _ -> action(dao) }
    internal suspend fun readingDefault(world: PrototypeWorld): ReadingMode = worldData(world) {
        decodeReadingMode(it.defaultReadingMode()) ?: ReadingMode.LTR
    }
    internal suspend fun saveReadingDefault(world: PrototypeWorld, mode: ReadingMode, allowed: () -> Boolean) = worldData(world, allowed) {
        it.putReadingPreference(ReadingPreference(mode = mode.name))
    }
    suspend fun catalog(world: PrototypeWorld, source: String = "sample") = io {
        database(world) { dao, _ -> dao.catalog(source).map { SampleManga(world, it.sourceId, it.mangaId, it.title) } }
    }
    suspend fun history(world: PrototypeWorld, source: String) = io { database(world) { dao, _ -> dao.history(source) } }
    suspend fun remember(world: PrototypeWorld, source: String, query: String, allowed: () -> Boolean = { true }) {
        StorageOperation(currentCoroutineContext(), allowed).check()
        val value = query.trim().take(256)
        if (value.isNotEmpty()) worldData(world, allowed) { dao ->
            dao.remember(SearchHistory(source, value, System.currentTimeMillis())); dao.trimHistory(source)
        }
    }
    suspend fun clearHistory(world: PrototypeWorld, source: String, allowed: () -> Boolean = { true }) =
        worldData(world, allowed) { it.clearHistory(source) }
    suspend fun putSession(world: PrototypeWorld, source: String, value: ByteArray?, allowed: () -> Boolean = { true }) =
        guardedDatabase(world, allowed) { dao, secret ->
            if (value == null) dao.clearSession(source)
            else dao.putSession(SourceSession(source, seal(value, secret, identity(world.name, source, "session"))))
        }
    /** Caller owns a successful result and must wipe it after use. */
    suspend fun session(world: PrototypeWorld, source: String, allowed: () -> Boolean = { true }): ByteArray? =
        readOwnedSecret { retain ->
            guardedDatabase(world, allowed) { dao, secret ->
                dao.session(source)?.let { retain(open(it, secret, identity(world.name, source, "session"))) }
            }
        }
    private fun identity(vararg parts: String) = storageIdentity(*parts)
    /** User-selected tag names are not an image cache or a Room BLOB. Same private key/AAD boundary. */
    internal suspend fun galleryTagData(allowed: () -> Boolean): ByteArray? = readOwnedSecret { retain ->
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        io {
            operation.check()
            val atomic = AtomicFile(File(directory(PrototypeWorld.NSFW), "gallery-tags-v1.sealed"))
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) null
            else database(PrototypeWorld.NSFW) { _, secret ->
                val bytes = atomic.openRead().use { BoundedResourceInput.read(it, 16 * 1024 * 1024 + BoundedResourceInput.ENVELOPE_BYTES, operation::check) }
                try {
                    val result = retain(open(bytes, secret, identity("NSFW", "gallery-tags-v1", "file")))
                    operation.check(); result
                } finally { bytes.fill(0) }
            }
        }
    }
    internal suspend fun putGalleryTagData(value: ByteArray?, allowed: () -> Boolean) {
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        io {
            operation.check()
            val atomic = AtomicFile(File(directory(PrototypeWorld.NSFW), "gallery-tags-v1.sealed"))
            if (value == null) atomic.delete()
            else database(PrototypeWorld.NSFW) { _, secret ->
                require(value.size <= 16 * 1024 * 1024)
                val encrypted = seal(value, secret, identity("NSFW", "gallery-tags-v1", "file"))
                try {
                    val output = atomic.startWrite()
                    try { output.write(encrypted); operation.check(); atomic.finishWrite(output) }
                    catch (failure: Exception) { atomic.failWrite(output); throw failure }
                } finally { encrypted.fill(0) }
            }
        }
    }
    private fun aad(key: ResourceKey) = identity(key.manga.world.name, key.manga.sourceId, key.manga.mangaId, key.resourceId)
    private fun offlineFile(job: ChapterDownload, index: Int): AtomicFile {
        require(job.id == java.util.UUID.fromString(job.id).toString() && index in job.pages.indices && isPrivateComicSource(job.source))
        return AtomicFile(File(directory(PrototypeWorld.NSFW), "offline-v1/${job.id}/$index.sealed"))
    }
    private fun offlineAad(job: ChapterDownload, index: Int) = identity("NSFW", "offline-v1", job.id, job.source, job.manga, job.chapter, job.pages[index].id)
    internal suspend fun offlinePage(job: ChapterDownload, index: Int, allowed: () -> Boolean): ByteArray? = readOwnedSecret { retain ->
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        io {
            operation.check()
            val file = offlineFile(job, index)
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null else database(PrototypeWorld.NSFW) { _, secret ->
                val encrypted = file.openRead().use { BoundedResourceInput.read(it, BoundedResourceInput.PRIVATE_BYTES + 64, operation::check) }
                try { val result = retain(open(encrypted, secret, offlineAad(job, index))); operation.check(); result }
                finally { encrypted.fill(0) }
            }
        }
    }
    internal suspend fun putOfflinePage(job: ChapterDownload, index: Int, bytes: ByteArray, allowed: () -> Boolean) {
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        io {
            operation.check(); require(bytes.size <= BoundedResourceInput.PRIVATE_BYTES + 12)
            database(PrototypeWorld.NSFW) { _, secret ->
                val file = offlineFile(job, index)
                check(file.baseFile.parentFile!!.isDirectory || file.baseFile.parentFile!!.mkdirs())
                if (file.baseFile.parentFile!!.usableSpace < bytes.size + 4L * 1024 * 1024) throw IOException("存储空间不足，已完成页面保留")
                val encrypted = seal(bytes, secret, offlineAad(job, index))
                try {
                    val output = file.startWrite()
                    try { output.write(encrypted); operation.check(); file.finishWrite(output) }
                    catch (error: Exception) { file.failWrite(output); throw error }
                } finally { encrypted.fill(0) }
            }
        }
    }
    internal suspend fun clearOfflineChapter(job: ChapterDownload, allowed: () -> Boolean) {
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        io {
            operation.check()
            job.pages.indices.forEach {
                operation.check(); val file = offlineFile(job, it); file.delete()
                if (listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).any { path -> path.exists() })
                    throw IOException("下载副本清理未完成，请重试；任务记录保留")
            }
            // Only this generated job's known encrypted pages; never user originals or cache.
            if (job.pages.isNotEmpty()) offlineFile(job, 0).baseFile.parentFile?.let { if (it.listFiles()?.isEmpty() == true) it.delete() }
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun libraryCoverFiles() = OwnedCacheFiles(File(cacheDirectory(PrototypeWorld.SFW), "online-library-covers"), Regex("[0-9a-f]{64}(\\.bak|\\.new)?"))
    internal suspend fun libraryCoverUsage(): CacheUsage = io { libraryCoverFiles().usage() }
    internal suspend fun clearLibraryCovers(allowed: () -> Boolean): CacheCleared {
        val context = currentCoroutineContext()
        return io {
            fun active() { context.ensureActive(); if (!allowed()) throw CancellationException("Expired cover cleanup") }
            active()
            synchronized(memory) {
                memoryEpoch.incrementAndGet()
                memory.snapshot().keys.filter { it.namespace == namespace && it.resource.manga.world == PrototypeWorld.SFW && it.resource.resourceId == "library-cover-v1" }
                    .forEach { memory.remove(it) }
            }
            libraryCoverFiles().clear(::active)
        }
    }
    private fun catalogPageFiles(world: PrototypeWorld = PrototypeWorld.SFW) = OwnedCacheFiles(File(cacheDirectory(world), "catalog-pages"), Regex("[0-9a-f]{64}(\\.bak|\\.new)?"))
    internal suspend fun catalogPageUsage(): CacheUsage = io { catalogPageFiles().usage() }
    internal suspend fun clearCatalogPages(allowed: () -> Boolean): CacheCleared {
        val context = currentCoroutineContext()
        return io {
            fun active() { context.ensureActive(); if (!allowed()) throw CancellationException("Expired cache cleanup") }
            active()
            synchronized(memory) {
                memoryEpoch.incrementAndGet()
                memory.snapshot().keys.filter { it.namespace == namespace && it.resource.manga.world == PrototypeWorld.SFW && it.resource.resourceId == "catalog-page-v1" }
                    .forEach { memory.remove(it) }
            }
            catalogPageFiles().clear(::active)
        }
    }
    internal fun cacheFile(key: ResourceKey) = File(
        if (key.resourceId.startsWith("reader-session:")) File(cacheDirectory(key.manga.world), "reader-pages")
        else if (key.manga.world == PrototypeWorld.SFW && key.resourceId == "library-cover-v1") File(cacheDirectory(key.manga.world), "online-library-covers")
        else if (key.resourceId == "catalog-page-v1") File(cacheDirectory(key.manga.world), "catalog-pages")
        else cacheDirectory(key.manga.world),
        (if (key.resourceId.startsWith("reader-session:")) key.resourceId.removePrefix("reader-session:").substringBefore(':') + "_" else "") + hash(aad(key)))
    private fun resourceLimit(key: ResourceKey) = if (key.manga.world == PrototypeWorld.NSFW || key.resourceId.startsWith("reader-session:"))
        BoundedResourceInput.PRIVATE_BYTES else BoundedResourceInput.PUBLIC_BYTES
    internal suspend fun removeReaderResource(key: ResourceKey) = io {
        require(key.resourceId.startsWith("reader-session:"))
        synchronized(memory) { memory.remove(MemoryResourceKey(namespace, key)) }
        AtomicFile(cacheFile(key)).delete()
    }
    internal suspend fun removeCachedResource(key: ResourceKey, allowed: () -> Boolean) = io {
        if (!allowed()) throw CancellationException()
        synchronized(memory) { memory.remove(MemoryResourceKey(namespace, key)) }
        AtomicFile(cacheFile(key)).delete()
    }
    suspend fun putResource(key: ResourceKey, bytes: ByteArray, allowed: () -> Boolean = { true }) {
        val operation = StorageOperation(currentCoroutineContext(), allowed)
        operation.check()
        io {
            operation.check()
            val epoch = memoryEpoch.get()
            require(bytes.size <= resourceLimit(key))
            database(key.manga.world) { _, secret ->
                operation.check()
                val file = cacheFile(key)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                if (file.parentFile!!.usableSpace < bytes.size + 16L * 1024 * 1024)
                    throw ReaderStorageFullException()
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(if (key.manga.world == PrototypeWorld.NSFW) seal(bytes, secret, aad(key)) else bytes)
                    operation.check()
                    atomic.finishWrite(stream)
                } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
                operation.check()
                synchronized(memory) { if (bytes.size <= memory.maxSize() && allowed() && epoch == memoryEpoch.get())
                    memory.put(MemoryResourceKey(namespace, key), bytes.copyOf()) }
                if (key.resourceId == "catalog-page-v1") {
                    val path = file.parentFile!!.absolutePath
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - (catalogTrimAt[path] ?: -60_000L) >= 60_000L) {
                        catalogPageFiles(key.manga.world).trimTo(128L * 1024 * 1024, operation::check)
                        catalogTrimAt[path] = now
                    }
                }
            }
        }
    }
    suspend fun resource(key: ResourceKey, allowed: () -> Boolean = { true }): ByteArray? {
        val caller = currentCoroutineContext()
        var owned: ByteArray? = null
        fun active() { caller.ensureActive(); if (!allowed()) throw CancellationException("Expired resource read") }
        try {
            return io {
                active()
                val epoch = memoryEpoch.get()
                owned = synchronized(memory) { memory.get(MemoryResourceKey(namespace, key))?.copyOf() }
                if (owned == null) cacheFile(key).takeIf { it.exists() }?.let { file ->
                    database(key.manga.world) { _, secret ->
                        val private = key.manga.world == PrototypeWorld.NSFW
                        val limit = resourceLimit(key) + if (private) BoundedResourceInput.ENVELOPE_BYTES else 0
                        val raw = AtomicFile(file).openRead().use { BoundedResourceInput.read(it, limit, ::active) }
                        try {
                            active()
                            owned = if (private) open(raw, secret, aad(key)) else raw
                            active()
                            file.setLastModified(System.currentTimeMillis())
                            val value = requireNotNull(owned)
                            synchronized(memory) { if (value.size <= memory.maxSize() && allowed() && epoch == memoryEpoch.get())
                                memory.put(MemoryResourceKey(namespace, key), value.copyOf()) }
                        } finally { if (private || owned !== raw) raw.fill(0) }
                    }
                }
                active()
                owned
            }
        } catch (failure: Throwable) { owned?.fill(0); throw failure }
    }
    private fun seal(bytes: ByteArray, secret: ByteArray, aad: ByteArray) = StorageCipher.seal(bytes, secret, aad)
    private fun open(bytes: ByteArray, secret: ByteArray, aad: ByteArray) = StorageCipher.open(bytes, secret, aad)
}
