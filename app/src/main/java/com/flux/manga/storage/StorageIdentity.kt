package com.flux.manga.storage

/** Existing payload AAD: UTF-16 string length prefixes, encoded as UTF-8. Do not change on-disk keys. */
internal fun storageIdentity(vararg parts: String): ByteArray =
    parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
