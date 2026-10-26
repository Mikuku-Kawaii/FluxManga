package com.flux.manga.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.Closeable
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "probe_samples")
data class ProbeSample(@PrimaryKey val id: Int, val text: String)

@Dao
interface ProbeSampleDao {
    @Insert fun insert(sample: ProbeSample)
    @Query("SELECT * FROM probe_samples WHERE id = :id") fun find(id: Int): ProbeSample?
}

@Database(entities = [ProbeSample::class], version = 1, exportSchema = true)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun samples(): ProbeSampleDao
}

/** M0 fixture only: no business databases or world switching. Call off the main thread. */
class StorageProbe(context: Context, name: String) {
    private val appContext = context.applicationContext
    init { require(name.matches(Regex("[a-z0-9-]+"))) }
    val directory = File(appContext.noBackupFilesDir, "m0-storage/$name").apply { mkdirs() }
    val plainFile = File(directory, "plain.db")
    val encryptedFile = File(directory, "encrypted.db")
    val keys = ProbeKeyStore(directory, "${appContext.packageName}.m0.storage.$name")

    fun openPlain(): ProbeDatabase = builder(plainFile).build()

    fun openEncrypted(): EncryptedProbe {
        System.loadLibrary("sqlcipher")
        val password = keys.loadOrCreate(encryptedFile.exists())
        try {
            val database = builder(encryptedFile)
                .openHelperFactory(SupportOpenHelperFactory(password)).build()
            return EncryptedProbe(database, password)
        } catch (failure: Exception) {
            password.fill(0)
            throw failure
        }
    }

    private fun builder(file: File) = Room.databaseBuilder(
        appContext, ProbeDatabase::class.java, file.absolutePath,
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
}

/** The factory retains the byte array; retain it until Room has closed, then wipe our copy. */
class EncryptedProbe internal constructor(
    val database: ProbeDatabase,
    private val password: ByteArray,
) : Closeable {
    override fun close() {
        try { database.close() } finally { password.fill(0) }
    }
}
