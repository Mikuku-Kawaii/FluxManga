package com.flux.manga.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

typealias ProbeKeyFailure = SecretKeyFailure
typealias ProbeKeyException = SecretKeyException
typealias ProbeKeyStore = WrappedSecretStore

/** Version 1: version byte + 12-byte IV + 32-byte wrapped secret + 16-byte GCM tag. */
class WrappedSecretStore(directory: File, val alias: String, private val purpose: String = "M0") {
    val envelopeFile = File(directory, "database-key.wrapped")
    private val envelope = AtomicFile(envelopeFile)
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val aad get() = "FluxManga:$purpose:1:$alias".toByteArray(Charsets.UTF_8)

    internal fun loadExistingReadOnly(): ByteArray {
        val file = SecretEnvelope.readOnlyFile(envelopeFile)
            ?: throw SecretKeyException(if (SecretEnvelope.hasArtifacts(envelopeFile)) SecretKeyFailure.INVALID_ENVELOPE else SecretKeyFailure.MISSING_ENVELOPE)
        val bytes = SecretEnvelope.readBounded(file)
        val master = keyStore.getKey(alias, null) as? SecretKey
            ?: throw SecretKeyException(SecretKeyFailure.MISSING_KEYSTORE_KEY)
        return SecretEnvelope.open(bytes, master, aad)
    }

    // Caller serializes initialization. The app currently has one process.
    fun loadOrCreate(databaseExists: Boolean): ByteArray {
        if (SecretEnvelope.readOnlyFile(envelopeFile) != null) {
            // Validate without modifying AtomicFile remnants before normal recovery is allowed.
            val checked = loadExistingReadOnly()
            try { envelope.openRead().close() } catch (failure: Exception) { checked.fill(0); throw failure }
            return checked
        }
        if (SecretEnvelope.hasArtifacts(envelopeFile)) throw SecretKeyException(SecretKeyFailure.INVALID_ENVELOPE)
        if (databaseExists) throw SecretKeyException(SecretKeyFailure.MISSING_ENVELOPE)
        check(envelopeFile.parentFile!!.isDirectory || envelopeFile.parentFile!!.mkdirs())
        val master = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val bytes = StorageCipher.seal(password, master, aad)
            val stream = envelope.startWrite()
            try {
                stream.write(bytes)
                envelope.finishWrite(stream)
            } catch (failure: Exception) {
                envelope.failWrite(stream)
                throw failure
            }
            return password
        } catch (failure: Exception) {
            password.fill(0)
            throw failure
        }
    }
}
