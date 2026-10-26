package com.flux.manga.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class StorageProtectionTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val aad = "FluxManga:M1-payload:1:fixture.NSFW.payload".toByteArray()
    private val plain = ByteArray(32) { (255 - it).toByte() }
    // Independent construction of the existing on-disk layout, not a new writer round-trip.
    private fun legacyEnvelope(): ByteArray {
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return byteArrayOf(1) + iv + cipher.doFinal(plain)
    }
    @Test fun readsExistingV1Envelope() {
        assertArrayEquals(plain, SecretEnvelope.open(legacyEnvelope(), SecretKeySpec(key, "AES"), aad))
    }
    @Test fun newWriterRetainsLegacyLayoutAndRandomizesNonce() {
        val first = StorageCipher.seal(plain, key, aad); val second = StorageCipher.seal(plain, key, aad)
        assertEquals(61, first.size); assertEquals(1.toByte(), first[0]); assertFalse(first.contentEquals(second))
        val legacy = Cipher.getInstance("AES/GCM/NoPadding")
        legacy.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, first.copyOfRange(1, 13)))
        legacy.updateAAD(aad)
        assertArrayEquals(plain, legacy.doFinal(first.copyOfRange(13, first.size)))
    }
    @Test fun rejectsTamperedCiphertextTagAndNonceWithoutMutatingInput() {
        for (index in listOf(1, 13, 60)) {
            val modified = legacyEnvelope().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val before = modified.copyOf()
            assertThrows(SecretKeyException::class.java) { SecretEnvelope.open(modified, SecretKeySpec(key, "AES"), aad) }
            assertArrayEquals(before, modified)
        }
    }
    @Test fun rejectsWrongKeyPurposeAndAlias() {
        val sealed = legacyEnvelope()
        assertThrows(SecretKeyException::class.java) { SecretEnvelope.open(sealed, SecretKeySpec(ByteArray(32), "AES"), aad) }
        for (other in listOf("FluxManga:M1-database:1:fixture.NSFW.payload", "FluxManga:M1-payload:1:fixture.SFW.payload")) {
            assertThrows(SecretKeyException::class.java) { SecretEnvelope.open(sealed, SecretKeySpec(key, "AES"), other.toByteArray()) }
        }
    }
    @Test fun rejectsTruncatedAndUnknownVersionEnvelopes() {
        for (size in listOf(0, 1, 28, 60, 62, 1024)) assertThrows(SecretKeyException::class.java) { SecretEnvelope.validate(ByteArray(size)) }
        assertThrows(SecretKeyException::class.java) { SecretEnvelope.validate(legacyEnvelope().also { it[0] = 2 }) }
    }
    private fun identity(vararg parts: String) = parts.joinToString("") { "${it.length}:$it" }.toByteArray()
    @Test fun payloadCannotCrossWorldSourceBookOrResource() {
        val owner = arrayOf("NSFW", "source-a", "book-1", "page-1")
        val content = "synthetic private content".toByteArray()
        val sealed = StorageCipher.seal(content, key, identity(*owner))
        assertArrayEquals(content, StorageCipher.open(sealed, key, identity(*owner)))
        for (index in owner.indices) {
            val other = owner.copyOf().also { it[index] += "other" }
            assertThrows(StorageAccessException::class.java) { StorageCipher.open(sealed, key, identity(*other)) }
        }
    }
    @Test fun emptyCredentialPayloadIsStillAuthenticated() {
        val sealed = StorageCipher.seal(byteArrayOf(), key, aad)
        assertEquals(29, sealed.size); assertEquals(0, StorageCipher.open(sealed, key, aad).size)
        assertThrows(StorageAccessException::class.java) { StorageCipher.open(sealed.copyOf(28), key, aad) }
    }
    @Test fun readOnlySelectsCommittedBackupWithoutRepairingFiles() {
        val base = File(temp.root, "key"); val backup = File(base.path + ".bak"); val pending = File(base.path + ".new")
        base.writeBytes(byteArrayOf(5)); backup.writeBytes(legacyEnvelope()); pending.writeBytes(byteArrayOf(7))
        val before = temp.root.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertEquals(backup, SecretEnvelope.readOnlyFile(base))
        assertArrayEquals(legacyEnvelope(), SecretEnvelope.readBounded(backup))
        assertEquals(before, temp.root.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }
    @Test fun interruptedEnvelopeIsNotMistakenForNewStorage() {
        val base = File(temp.root, "key"); File(base.path + ".new").writeBytes(byteArrayOf(1))
        assertTrue(SecretEnvelope.hasArtifacts(base)); assertNull(SecretEnvelope.readOnlyFile(base)); assertFalse(base.exists())
    }
    @Test fun oversizeEnvelopeIsRejectedWithoutChangingIt() {
        val file = temp.newFile(); file.writeBytes(ByteArray(100_000) { 1 })
        assertThrows(SecretKeyException::class.java) { SecretEnvelope.readBounded(file) }
        assertEquals(100_000L, file.length())
    }
    @Test fun missingDatabaseWithAnySidecarIsPreservedAndRejected() {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val file = File(temp.root, "db$suffix.db"); val sidecar = File(file.path + suffix)
            sidecar.writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(StorageAccessException::class.java) { DatabaseArtifacts.requireUsableMain(file) }
            assertFalse(file.exists()); assertArrayEquals(byteArrayOf(1, 2, 3), sidecar.readBytes())
        }
    }
    @Test fun emptyDatabaseCannotBeSilentlyRecreated() {
        val file = temp.newFile()
        assertThrows(StorageAccessException::class.java) { DatabaseArtifacts.requireUsableMain(file) }
        assertTrue(file.exists()); assertEquals(0L, file.length())
    }
    @Test fun newAndExistingDatabaseChecksDoNotWrite() {
        val file = File(temp.root, "db"); DatabaseArtifacts.requireUsableMain(file); assertFalse(file.exists())
        file.writeBytes(byteArrayOf(1, 2, 3)); DatabaseArtifacts.requireUsableMain(file)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
    @Test fun classifiedMessagesDoNotExposePathsSqlOrCredentials() {
        for (reason in SecretKeyFailure.entries) {
            val message = storageMessage(IOException("token=secret /private/path SELECT titles", SecretKeyException(reason)))
            assertFalse(message.contains("secret")); assertFalse(message.contains("/private")); assertFalse(message.contains("SELECT"))
            assertNotEquals(StorageProblem.IO, storageProblem(SecretKeyException(reason)))
        }
        assertEquals(StorageProblem.IO, storageProblem(IOException("source secret")))
    }
    @Test fun missingDatabaseWithOldKeysDoesNotBecomeNewStorage() {
        val file = File(temp.root, "old.db")
        assertThrows(StorageAccessException::class.java) { DatabaseArtifacts.requireUsableMain(file, hasKeyArtifacts = true) }
        assertFalse(file.exists())
    }
    private class KeyPermanentlyInvalidatedException : java.security.GeneralSecurityException()
    @Test fun invalidationInsideProviderWrapperIsNotLabeledTemporary() {
        val outer = java.security.UnrecoverableKeyException().apply { initCause(KeyPermanentlyInvalidatedException()) }
        assertEquals(StorageProblem.KEY_INVALIDATED, storageProblem(IOException(outer)))
        assertEquals(StorageProblem.KEY_UNAVAILABLE, storageProblem(java.security.UnrecoverableKeyException()))
    }

}
