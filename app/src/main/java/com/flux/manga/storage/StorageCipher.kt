package com.flux.manga.storage

import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Existing v1 bytes and AAD are unchanged: version + 12-byte nonce + ciphertext + 16-byte tag. */
internal object StorageCipher {
    fun seal(value: ByteArray, secret: SecretKey, aad: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, secret); updateAAD(aad)
        val nonce = iv
        check(nonce.size == 12)
        val output = ByteArray(Math.addExact(13, getOutputSize(value.size)))
        try {
            output[0] = 1
            nonce.copyInto(output, 1)
            val written = doFinal(value, 0, value.size, output, 13)
            if (written + 13 == output.size) output
            else output.copyOf(written + 13).also { output.fill(0) }
        } catch (failure: Throwable) { output.fill(0); throw failure }
    }
    fun open(value: ByteArray, secret: SecretKey, aad: ByteArray): ByteArray {
        if (value.size < 29 || value[0] != 1.toByte()) throw StorageAccessException(StorageProblem.PAYLOAD_INVALID)
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, value.copyOfRange(1, 13)))
                updateAAD(aad); doFinal(value, 13, value.size - 13)
            }
        } catch (failure: AEADBadTagException) { throw StorageAccessException(StorageProblem.PAYLOAD_INVALID, failure) }
    }
    fun seal(value: ByteArray, secret: ByteArray, aad: ByteArray) = seal(value, SecretKeySpec(secret, "AES"), aad)
    fun open(value: ByteArray, secret: ByteArray, aad: ByteArray) = open(value, SecretKeySpec(secret, "AES"), aad)
}

internal object SecretEnvelope {
    fun validate(bytes: ByteArray) {
        if (bytes.size != 61 || bytes[0] != 1.toByte()) throw SecretKeyException(SecretKeyFailure.INVALID_ENVELOPE)
    }
    fun open(bytes: ByteArray, master: SecretKey, aad: ByteArray): ByteArray {
        validate(bytes)
        try { return StorageCipher.open(bytes, master, aad) }
        catch (_: StorageAccessException) { throw SecretKeyException(SecretKeyFailure.INVALID_ENVELOPE) }
    }
    /** Diagnostic reads must not invoke AtomicFile recovery (rename/delete) or allocate for an unbounded file. */
    fun readOnlyFile(base: File): File? = File(base.path + ".bak").takeIf { it.exists() } ?: base.takeIf { it.exists() }
    fun hasArtifacts(base: File) = readOnlyFile(base) != null || File(base.path + ".new").exists()
    fun readBounded(file: File): ByteArray = file.inputStream().use { input ->
        val bytes = ByteArray(62)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        bytes.copyOf(size).also(::validate)
    }
}
