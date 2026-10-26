package com.flux.manga.storage

import org.junit.Assert.*
import org.junit.Test

class StorageIdentityTest {
    @Test fun matchesLegacyLengthEncodingIncludingUnicodeAndSeparators() {
        assertArrayEquals("4:NSFW8:source-a6:book-16:page-1".toByteArray(),
            storageIdentity("NSFW", "source-a", "book-1", "page-1"))
        // Emoji length is two UTF-16 code units, not four UTF-8 bytes or one code point.
        assertArrayEquals("2:😀2:中:0:".toByteArray(Charsets.UTF_8), storageIdentity("😀", "中:", ""))
        assertFalse(storageIdentity("ab", "c").contentEquals(storageIdentity("a", "bc")))
        assertFalse(storageIdentity("a:", "b").contentEquals(storageIdentity("a", ":b")))
    }
    @Test fun actualStorageIdentityRejectsCrossWorldSourceBookAndChapterReplay() {
        val owner = arrayOf("NSFW", "source:account-a", "same-book", "chapter-1/page-1")
        val secret = ByteArray(32) { (it + 1).toByte() }
        val plain = "synthetic private page".toByteArray()
        val sealed = StorageCipher.seal(plain, secret, storageIdentity(*owner))
        assertArrayEquals(plain, StorageCipher.open(sealed, secret, storageIdentity(*owner)))
        for ((index, replacement) in listOf(0 to "SFW", 1 to "source:account-b", 2 to "other-book", 3 to "chapter-2/page-1")) {
            val other = owner.copyOf().also { it[index] = replacement }
            assertThrows(StorageAccessException::class.java) { StorageCipher.open(sealed, secret, storageIdentity(*other)) }
        }
    }
    @Test fun credentialsRejectOtherWorldAndAccountSlotWithoutChangingCiphertext() {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val sealed = StorageCipher.seal("FAKE_TOKEN".toByteArray(), secret, storageIdentity("NSFW", "source-account-a", "session"))
        val snapshot = sealed.copyOf()
        for (other in listOf(storageIdentity("SFW", "source-account-a", "session"),
            storageIdentity("NSFW", "source-account-b", "session"), storageIdentity("NSFW", "source-account-a", "page"))) {
            assertThrows(StorageAccessException::class.java) { StorageCipher.open(sealed, secret, other) }
        }
        assertArrayEquals(snapshot, sealed)
    }
}
