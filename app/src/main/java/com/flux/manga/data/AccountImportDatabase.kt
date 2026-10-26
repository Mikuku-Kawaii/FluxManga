package com.flux.manga.data

/** The same transaction owns encrypted state, batch position and book inserts. */
internal interface AccountImportRows {
    fun read(slot: String): ByteArray?
    fun write(slot: String, value: ByteArray)
    fun book(source: String, id: String): OnlineBook?
    fun hasProgress(source: String, id: String): Boolean
    fun putBook(book: OnlineBook)
}
internal interface AccountImportDatabase {
    suspend fun <T> transaction(allowed: () -> Boolean, action: (AccountImportRows) -> T): T
}

