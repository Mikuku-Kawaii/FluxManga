package com.flux.manga.update

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateProtocolTest {
    private val device = UpdateDevice("com.flux.manga", 1, 31, listOf("arm64-v8a"))
    private val apk = UpdateArtifact(2, "test", 31, setOf("arm64-v8a"), 12, "a".repeat(64), "https://example.invalid/app.apk", "说明")
    private fun payload(a: UpdateArtifact = apk, channel: String = "stable", expires: Long = 200): String =
        "flux-update-v1\npackage=com.flux.manga\nchannel=$channel\nexpires=$expires\n" +
            "apk\t${a.version}\t${a.name}\t${a.minSdk}\t${a.abis.joinToString(",")}\t${a.size}\t${a.sha256}\t${a.url}\t${Base64.getEncoder().encodeToString(a.notes.toByteArray())}\n"
    private fun envelope(payload: String): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key.private); update(payload.toByteArray()) }.sign()
        return "flux-update-envelope-v1\n${Base64.getEncoder().encodeToString(payload.toByteArray())}\n${Base64.getEncoder().encodeToString(signature)}\n".toByteArray()
    }
    private fun verify(bytes: ByteArray = envelope(payload()), now: Long = 100) = UpdateProtocol.verify(bytes, Base64.getEncoder().encodeToString(key.public.encoded), now)
    private fun rejects(block: () -> Unit) { assertThrows(Exception::class.java) { block() } }
    @Test fun authenticManifestChoosesCompatibleHigherVersionAndKeepsNotes() {
        val manifest = verify()
        assertEquals(apk, (UpdateProtocol.choose(manifest, device, "stable") as UpdateChoice.Available).artifact)
    }
    @Test fun tamperingAndWrongSigningKeyAreRejectedBeforeUrlsAreUsed() {
        val signed = envelope(payload()).toString(Charsets.UTF_8).split('\n').toMutableList()
        signed[1] = Base64.getEncoder().encodeToString(payload(apk.copy(url = "https://evil.invalid/new.apk")).toByteArray())
        rejects { verify(signed.joinToString("\n").toByteArray()) }
        rejects { UpdateProtocol.verify(envelope(payload()), Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded), 100) }
    }
    @Test fun expiryChannelPackageAndMalformedRecordsFailClosed() {
        rejects { verify(now = 200) }
        rejects { UpdateProtocol.choose(verify(), device, "preview") }
        rejects { UpdateProtocol.choose(verify(), device.copy(packageName = "other"), "stable") }
        rejects { verify(envelope(payload().replace("apk\t", "patch\t"))) }
        rejects { verify(envelope(payload(apk.copy(size = MAX_APK + 1)))) }
        rejects { verify(envelope(payload(apk.copy(sha256 = "not-a-hash")))) }
    }
    @Test fun newerButIncompatibleIsNotMisreportedAsLatest() {
        assertEquals(UpdateChoice.Incompatible, UpdateProtocol.choose(verify(envelope(payload(apk.copy(minSdk = 33)))), device, "stable"))
        assertEquals(UpdateChoice.Incompatible, UpdateProtocol.choose(verify(envelope(payload(apk.copy(abis = setOf("x86_64"))))), device, "stable"))
        assertEquals(UpdateChoice.Current, UpdateProtocol.choose(verify(), device.copy(version = 2), "stable"))
    }
    @Test fun downgradeUrlsAndDuplicateAbiArtifactsAreRejected() {
        listOf("http://example.invalid/app.apk", "https://user:password@example.invalid/app.apk", "https://example.invalid/app.apk#fragment").forEach {
            rejects { verify(envelope(payload(apk.copy(url = it)))) }
        }
        rejects { verify(envelope(payload() + payload().lineSequence().first { it.startsWith("apk") } + "\n")) }
        rejects { verify(ByteArray(MAX_MANIFEST + 1)) }
    }
    @Test fun apkIdentityRequiresExactCurrentSignerSetAndDeclaredMetadata() {
        fun check(pkg: String = device.packageName, version: Long = 2, sdk: Int = 31,
                  abis: Set<String> = setOf("arm64-v8a"), signers: Set<String> = setOf("signer")) =
            requireApkIdentity(pkg, version, sdk, abis, setOf("signer"), signers, device, apk)
        check()
        rejects { check(pkg = "another.app") }; rejects { check(version = 1) }
        rejects { check(sdk = 33) }; rejects { check(abis = setOf("x86_64")) }
        rejects { check(signers = emptySet()) }; rejects { check(signers = setOf("other")) }
        rejects { check(signers = setOf("signer", "injected")) }
    }
    @Test fun releaseHelperSignsExactPayloadAndRefusesOutputReplacement() {
        val root = java.nio.file.Files.createTempDirectory("flux-manifest-tool").toFile()
        try {
            val input = java.io.File(root, "payload.txt").apply { writeText(payload(expires = System.currentTimeMillis() / 1000 + 3600)) }
            val privateKey = java.io.File(root, "synthetic-key.pk8").apply { writeBytes(key.private.encoded) }
            val output = java.io.File(root, "manifest.signed")
            val script = listOf(java.io.File("../scripts/SignUpdateManifest.java"), java.io.File("scripts/SignUpdateManifest.java")).first { it.isFile }.absolutePath
            fun run(): Pair<Int, String> {
                val process = ProcessBuilder(java.io.File(System.getProperty("java.home"), "bin/java").absolutePath,
                    script, input.absolutePath, privateKey.absolutePath, output.absolutePath).redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                return process.waitFor() to log
            }
            val result = run(); assertEquals(result.second, 0, result.first)
            assertEquals(apk, (UpdateProtocol.choose(verify(output.readBytes()), device, "stable") as UpdateChoice.Available).artifact)
            val original = output.readBytes()
            assertNotEquals(0, run().first); assertArrayEquals(original, output.readBytes())
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }
    companion object { private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
}
