package com.flux.manga.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import com.flux.manga.data.WORLD_DATABASE_VERSION

/** Opens only an owned snapshot. Never opens, migrates or repairs a business database. */
internal object DatabaseDiagnostics {
    private val workflow = DiagnosticWorkflow(WORLD_DATABASE_VERSION, ::inspectSnapshot)
    suspend fun hasCopy(directory: File) = workflow.hasCopy(directory)
    suspend fun cleanup(directory: File) = workflow.cleanup(directory)
    suspend fun inspect(
        directory: File,
        prepare: suspend (File) -> DiagnosticSnapshot,
        onStage: (DiagnosticStage) -> Unit,
        onCleanup: (Boolean) -> Unit,
        migrate: ((File, ByteArray?, () -> Unit) -> Unit)? = null,
    ) = workflow.inspect(directory, prepare, onStage, onCleanup, migrate)

    private suspend fun inspectSnapshot(file: File, snapshot: DiagnosticSnapshot, onStage: (DiagnosticStage) -> Unit): Pair<Int, Int?> = coroutineScope {
        val signal = CancellationSignal()
        // Native cursor work blocks an IO thread; cancel it from a separate dispatcher.
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            if (snapshot.password == null) {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY,
                    android.database.DatabaseErrorHandler {
                        throw android.database.sqlite.SQLiteException("Diagnostic database rejected")
                    }).use { db -> readReport(snapshot, signal, onStage) { sql -> db.rawQuery(sql, null, signal) } }
            } else {
                System.loadLibrary("sqlcipher")
                CipherDatabase.openDatabase(file.path, snapshot.password, null, CipherDatabase.OPEN_READONLY,
                    net.zetetic.database.DatabaseErrorHandler { _, failure -> throw failure }, null).use { db ->
                    readReport(snapshot, signal, onStage) { sql -> db.query(SimpleSQLiteQuery(sql), signal) }
                }
            }
        } finally { cancellation.cancel() }
    }

    private fun readReport(snapshot: DiagnosticSnapshot, signal: CancellationSignal,
        onStage: (DiagnosticStage) -> Unit, query: (String) -> Cursor): Pair<Int, Int?> {
        query("PRAGMA integrity_check(1)").use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) {
                throw StorageAccessException(StorageProblem.DATABASE_INTEGRITY)
            }
        }
        query("PRAGMA foreign_key_check").use { cursor ->
            if (cursor.moveToFirst()) throw StorageAccessException(StorageProblem.DATABASE_RELATIONS)
        }
        val version = query("PRAGMA user_version").use { cursor ->
            if (!cursor.moveToFirst()) throw StorageAccessException(StorageProblem.DATABASE_REJECTED)
            cursor.getInt(0)
        }
        // Unknown / newer schemas are reported without guessing their encrypted payload contract.
        if (version !in 1..WORLD_DATABASE_VERSION) return version to null
        onStage(DiagnosticStage.SESSIONS)
        var count = 0
        query("SELECT sourceId, sealedValue FROM source_session").use { cursor ->
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                val source = cursor.getString(0)
                val aad = storageIdentity(snapshot.world, source, "session")
                val sealed = cursor.getBlob(1)
                try {
                    StorageCipher.open(sealed, snapshot.payload, aad).fill(0)
                    count++
                } finally { sealed.fill(0); aad.fill(0) }
            }
        }
        return version to count
    }
}
