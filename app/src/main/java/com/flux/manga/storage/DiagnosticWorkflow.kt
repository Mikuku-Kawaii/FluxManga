package com.flux.manga.storage

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class DiagnosticStage(val label: String) {
    PREPARING("正在准备数据库副本"), CHECKING("正在检查数据库结构"),
    SESSIONS("正在验证加密记录"), MIGRATING("正在检查副本迁移"), CLEANING("正在清理检查副本")
}
internal data class DiagnosticSnapshot(val bytes: Long, val password: ByteArray?, val payload: ByteArray, val world: String)
internal data class DatabaseDiagnosticReport(
    val version: Int? = null,
    val originalVersion: Int? = null,
    val encryptedRecords: Int? = null,
    val migrationChecked: Boolean = false,
    val snapshotBytes: Long = 0,
    val checkedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val cleanupRequired: Boolean = false,
)

/** Snapshot ownership and cancellation; the platform inspector alone opens SQLite/SQLCipher. */
internal class DiagnosticWorkflow(
    private val currentVersion: Int,
    private val inspectCopy: suspend (File, DiagnosticSnapshot, (DiagnosticStage) -> Unit) -> Pair<Int, Int?>,
) {
    private val lock = Mutex()
    private fun main(directory: File) = File(directory, "snapshot.db")
    private fun files(directory: File) = main(directory).let { DatabaseArtifacts.sidecars(it) + it }

    /** No mkdir, database open, key access or deletion on page entry. */
    suspend fun hasCopy(directory: File): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            directory.exists() && (!directory.isDirectory || files(directory).any { it.exists() })
        }
    }
    private fun removeCopy(directory: File): Boolean {
        if (directory.exists() && !directory.isDirectory) return false
        var removed = true
        // Deliberately only the generated main/sidecar names; no recursive cleanup.
        files(directory).forEach { file ->
            if (file.exists() && (!file.isFile || !file.delete())) removed = false
        }
        return removed
    }
    suspend fun cleanup(directory: File): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { removeCopy(directory) }
    }
    suspend fun inspect(
        directory: File,
        prepare: suspend (File) -> DiagnosticSnapshot,
        onStage: (DiagnosticStage) -> Unit,
        onCleanup: (Boolean) -> Unit,
        migrate: ((File, ByteArray?, () -> Unit) -> Unit)? = null,
    ): DatabaseDiagnosticReport = withContext(Dispatchers.IO) {
        lock.withLock {
            var snapshot: DiagnosticSnapshot? = null
            var cleanupRequired = false
            var originalVersion: Int? = null
            val result = try {
                onStage(DiagnosticStage.PREPARING)
                if (!removeCopy(directory)) throw IOException("Diagnostic cleanup required")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic directory unavailable")
                val file = main(directory)
                // The prepare callback owns/wipes its secrets until returning this snapshot.
                val prepared = prepare(file)
                snapshot = prepared
                currentCoroutineContext().ensureActive()
                onStage(DiagnosticStage.CHECKING)
                var checked = inspectCopy(file, prepared, onStage)
                originalVersion = checked.first
                if (migrate != null) {
                    if (checked.first !in 1..currentVersion) throw StorageAccessException(StorageProblem.DATABASE_VERSION)
                    val context = currentCoroutineContext()
                    onStage(DiagnosticStage.MIGRATING)
                    context.ensureActive()
                    migrate(file, prepared.password) { context.ensureActive() }
                    context.ensureActive()
                    onStage(DiagnosticStage.CHECKING)
                    checked = inspectCopy(file, prepared, onStage)
                    if (checked.first != currentVersion) throw StorageAccessException(StorageProblem.DATABASE_VERSION)
                }
                currentCoroutineContext().ensureActive()
                DatabaseDiagnosticReport(version = checked.first, originalVersion = originalVersion,
                    encryptedRecords = checked.second, migrationChecked = migrate != null, snapshotBytes = prepared.bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                DatabaseDiagnosticReport(originalVersion = originalVersion, error = storageMessage(failure))
            } finally {
                snapshot?.password?.fill(0)
                snapshot?.payload?.fill(0)
                // Even a cancelled/throwing progress observer cannot skip owned-file cleanup.
                try { onStage(DiagnosticStage.CLEANING) } finally {
                    cleanupRequired = !removeCopy(directory)
                    onCleanup(cleanupRequired)
                }
            }
            result.copy(cleanupRequired = cleanupRequired)
        }
    }
}
