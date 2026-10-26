package com.flux.manga.update

import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val MAX_MANIFEST = 256 * 1024
internal const val MAX_APK = 1024L * 1024 * 1024
internal data class UpdateArtifact(val version: Long, val name: String, val minSdk: Int,
    val abis: Set<String>, val size: Long, val sha256: String, val url: String, val notes: String) {
    val stem get() = "$version-$sha256"
    val payload get() = UpdatePayload(size, sha256, url)
}
internal data class UpdatePayload(val size: Long, val sha256: String, val url: String)
internal data class UpdatePatch(val targetVersion: Long, val targetHash: String,
    val baseVersion: Long, val baseHash: String, val format: String, val payload: UpdatePayload) {
    val stem get() = "$targetVersion-$targetHash-$baseHash-${payload.sha256}"
}
internal data class UpdateManifest(val packageName: String, val channel: String, val expires: Long,
    val artifacts: List<UpdateArtifact>, val patches: List<UpdatePatch> = emptyList())
internal data class UpdateDevice(val packageName: String, val version: Long, val sdk: Int, val abis: List<String>)
internal sealed interface UpdateChoice {
    data class Available(val artifact: UpdateArtifact) : UpdateChoice
    data object Current : UpdateChoice
    data object Incompatible : UpdateChoice
}
internal fun httpsUrl(value: String): String {
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) { "更新地址必须是无账号信息的 HTTPS 地址" }
    return value
}
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun sha256(file: File, checkpoint: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(65536).use { input ->
        val buffer = ByteArray(65536)
        while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Signature covers exact UTF-8 payload bytes, before parsing any download URLs. */
internal object UpdateProtocol {
    fun verify(envelope: ByteArray, publicKey: String, nowSeconds: Long): UpdateManifest {
        require(envelope.size <= MAX_MANIFEST) { "更新清单过大" }
        val lines = envelope.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        require(lines.size == 3 && lines[0] == "flux-update-envelope-v1") { "不支持的更新清单格式" }
        val payload = Base64.getDecoder().decode(lines[1])
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))) as RSAPublicKey
        require(key.modulus.bitLength() >= 2048) { "发布公钥强度不足" }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key); verifier.update(payload)
        require(verifier.verify(Base64.getDecoder().decode(lines[2]))) { "更新清单签名不匹配" }
        val fields = payload.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        val v2 = fields[0] == "flux-update-v2"
        require(fields.size in 5..(if (v2) 100 else 36) && (v2 || fields[0] == "flux-update-v1")) { "不支持的更新清单内容" }
        val packageName = fields[1].removePrefix("package=")
        val channel = fields[2].removePrefix("channel=")
        require(fields[1].startsWith("package=") && packageName.matches(Regex("[A-Za-z0-9_.]{1,180}")))
        require(fields[2].startsWith("channel=") && channel.matches(Regex("[a-zA-Z0-9_-]{1,40}")))
        require(fields[3].startsWith("expires="))
        val expires = fields[3].removePrefix("expires=").toLong()
        require(expires > nowSeconds) { "发布清单已过期，请重新检查或联系发布者" }
        val records = fields.drop(4)
        require(records.all { it.startsWith("apk\t") || (v2 && it.startsWith("patch\t")) }) { "未知产物类型" }
        val artifacts = records.filter { it.startsWith("apk\t") }.map { line ->
            val f = line.split('\t'); require(f.size == 9 && f[0] == "apk") { "产物字段不完整" }
            val a = UpdateArtifact(f[1].toLong(), f[2], f[3].toInt(), f[4].split(',').toSet(),
                f[5].toLong(), f[6], httpsUrl(f[7]), Base64.getDecoder().decode(f[8]).toString(Charsets.UTF_8))
            require(a.version > 0 && a.name.length in 1..120 && a.minSdk >= 31 && a.size in 1..MAX_APK)
            require(a.sha256.matches(Regex("[a-f0-9]{64}")) && a.abis.isNotEmpty() && a.abis.all { it in setOf("arm64-v8a", "x86_64") })
            require(a.notes.length <= 16000)
            a
        }
        require(artifacts.size in 1..32) { "完整包数量不正确" }
        require(artifacts.flatMap { a -> a.abis.map { a.version to it } }.distinct().size == artifacts.sumOf { it.abis.size }) { "同版本 ABI 产物重复" }
        val patches = records.filter { it.startsWith("patch\t") }.map { line ->
            val f = line.split('\t'); require(f.size == 9) { "补丁字段不完整" }
            val p = UpdatePatch(f[1].toLong(), f[2], f[3].toLong(), f[4], f[5],
                UpdatePayload(f[6].toLong(), f[7], httpsUrl(f[8])))
            require(p.baseVersion > 0 && p.baseVersion < p.targetVersion && p.payload.size in 1..MAX_APK)
            require(listOf(p.targetHash, p.baseHash, p.payload.sha256).all { it.matches(Regex("[a-f0-9]{64}")) })
            require(p.format.matches(Regex("[a-z0-9-]{1,32}")))
            require(artifacts.any { it.version == p.targetVersion && it.sha256 == p.targetHash }) { "补丁缺少对应完整包" }
            p
        }
        require(patches.size <= 64 && patches.map { Triple(it.targetVersion, it.targetHash, it.baseHash) }.distinct().size == patches.size) { "补丁记录重复或过多" }
        return UpdateManifest(packageName, channel, expires, artifacts, patches)
    }
    fun choose(manifest: UpdateManifest, device: UpdateDevice, channel: String): UpdateChoice {
        require(manifest.packageName == device.packageName && manifest.channel == channel) { "清单包名或发布渠道不匹配" }
        val newer = manifest.artifacts.filter { it.version > device.version }
        if (newer.isEmpty()) return UpdateChoice.Current
        val compatible = newer.filter { it.minSdk <= device.sdk && it.abis.any { abi -> abi in device.abis } }
        val chosen = compatible.sortedWith(compareByDescending<UpdateArtifact> { it.version }
            .thenBy { a -> device.abis.indexOfFirst { it in a.abis } }).firstOrNull()
        return chosen?.let(UpdateChoice::Available) ?: UpdateChoice.Incompatible
    }
    fun patch(manifest: UpdateManifest, target: UpdateArtifact, baseVersion: Long, baseHash: String): UpdatePatch? =
        manifest.patches.filter { it.targetVersion == target.version && it.targetHash == target.sha256 &&
            it.baseVersion == baseVersion && it.baseHash == baseHash && it.format == "bsdiff40" && it.payload.size < target.size }
            .minByOrNull { it.payload.size }
}

/** Resume bytes are never trusted until the full signed target hash is checked. */
internal fun resumeOffset(status: Int, range: String?, requested: Long, total: Long): Long {
    if (status == 200) return 0
    require(status == 206 && requested > 0) { "更新下载响应异常（$status）" }
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.orEmpty()) ?: error("续传范围缺失")
    require(match.groupValues[1].toLong() == requested && match.groupValues[2].toLong() == total - 1 && match.groupValues[3].toLong() == total) { "续传文件或范围已改变" }
    return requested
}
internal fun verifyTarget(file: File, artifact: UpdateArtifact, checkpoint: () -> Unit = {}) {
    verifyPayload(file, artifact.payload, checkpoint)
}
internal fun verifyPayload(file: File, payload: UpdatePayload, checkpoint: () -> Unit = {}) {
    require(file.length() == payload.size) { "下载长度不匹配" }
    require(sha256(file, checkpoint) == payload.sha256) { "更新文件校验失败，请清理后重新下载" }
}
internal fun requireApkIdentity(actualPackage: String, actualVersion: Long, actualMinSdk: Int,
    actualAbis: Set<String>, installedSigners: Set<String>, targetSigners: Set<String>,
    device: UpdateDevice, artifact: UpdateArtifact) {
    require(actualPackage == device.packageName && actualVersion == artifact.version && actualVersion > device.version) { "APK 包名或版本不匹配" }
    require(actualMinSdk == artifact.minSdk && actualMinSdk <= device.sdk && device.abis.any { it in actualAbis } && actualAbis.containsAll(artifact.abis)) { "APK 系统或 ABI 不匹配" }
    // Deliberately fail closed on key rotation until a release rotation policy is established.
    require(installedSigners.isNotEmpty() && installedSigners == targetSigners) { "APK 签名与当前应用不一致" }
}
