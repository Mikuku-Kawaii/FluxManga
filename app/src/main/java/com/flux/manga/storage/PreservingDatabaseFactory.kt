package com.flux.manga.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/* SQLCipher forwarding adapter adapted from SupportHelper.java at ffe6c588b06ab042d60264f1f0da79a7ac56db0f.
 * Copyright (c) 2008-2023 ZETETIC LLC. BSD-3-Clause; full terms in
 * docs/third-party/sqlcipher-preserving-adapter/LICENSE. Changes: Kotlin forwarding,
 * preserve-on-corruption error handler, no minimum-version deletion. No new database engine.
 */
internal class PreservingDatabaseFactory(private val password: ByteArray? = null) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val secret = password
        if (secret == null) {
            val preserving = object : SupportSQLiteOpenHelper.Callback(callback.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)
                override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(db, oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(db, oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)
                override fun onCorruption(db: SupportSQLiteDatabase) { throw android.database.sqlite.SQLiteException("Database rejected; original files retained") }
            }
            return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(preserving).noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false).build())
        }
        val helper = object : SQLiteOpenHelper(configuration.context, configuration.name, secret, null,
            callback.version, 0, DatabaseErrorHandler { _, failure ->
                // Rethrow the SQLite exception so SQLCipher closes its failed connection instead of retrying open.
                throw failure
            }, null, false) {
            override fun onConfigure(db: SQLiteDatabase) = callback.onConfigure(db)
            override fun onCreate(db: SQLiteDatabase) = callback.onCreate(db)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SQLiteDatabase) = callback.onOpen(db)
        }
        return object : SupportSQLiteOpenHelper {
            override val databaseName: String? get() = configuration.name
            override val writableDatabase: SupportSQLiteDatabase get() = helper.writableDatabase
            override val readableDatabase: SupportSQLiteDatabase get() = helper.readableDatabase
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = helper.setWriteAheadLoggingEnabled(enabled)
            override fun close() = helper.close()
        }
    }
}
