package com.flux.manga.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

internal enum class UpdatePhase { UNCONFIGURED, IDLE, CHECKING, CURRENT, INCOMPATIBLE, AVAILABLE, DOWNLOADING, PAUSED, VERIFYING, APPLYING, READY, INSTALLING, ERROR }
internal data class AppUpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val artifact: UpdateArtifact? = null,
    val bytes: Long = 0, val message: String? = null, val automatic: Boolean = false, val installationMessage: String? = null,
    val patch: UpdatePatch? = null, val assembledBytes: Long = 0) {
    val transferSize get() = patch?.payload?.size ?: artifact?.size ?: 0L
    val busy get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.APPLYING, UpdatePhase.INSTALLING)
}

/** Public application release state, never a world/account session or business database. */
internal class AppUpdateModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("application-updates", 0)
    private val root = File(app.filesDir, "application-updates")
    private val staging = File(root, "staging")
    private val ready = File(root, "ready")
    private val envelopeFile = File(root, "manifest.signed")
    private val transport = HttpsUpdateTransport()
    private var manifest: UpdateManifest? = null
    private val mutable = MutableStateFlow(AppUpdateState(automatic = prefs.getBoolean("automatic", false)))
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    @Volatile private var foreground = false
    val configured get() = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank() && BuildConfig.UPDATE_PUBLIC_KEY.isNotBlank()
    val currentVersion get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val channel get() = BuildConfig.UPDATE_CHANNEL
    private val device get() = UpdateDevice(getApplication<Application>().packageName, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

    init {
        operation = viewModelScope.launch {
            withContext(Dispatchers.IO) { cleanupInstalled() }
            if (!configured) mutable.update { it.copy(phase = UpdatePhase.UNCONFIGURED, message = "此构建尚未配置可信发布渠道。发布地址、公钥及正式签名产物准备好后才能检查更新。") }
            else {
                // Cached envelopes must pass the same signature, expiry and channel checks.
                runCatching { withContext(Dispatchers.IO) { if (envelopeFile.isFile) { require(envelopeFile.length() <= MAX_MANIFEST); accept(envelopeFile.readBytes()) } } }
                    .onFailure { mutable.update { s -> s.copy(phase = UpdatePhase.ERROR, message = "缓存清单失效，请重新检查") } }
                if (coldCheck.compareAndSet(false, true)) {
                    delay(750)
                    checkInternal()
                }
            }
        }
    }
    fun foreground(value: Boolean) {
        foreground = value
        if (!value && mutable.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.APPLYING)) pause()
    }
    fun automatic(value: Boolean) {
        prefs.edit { putBoolean("automatic", value) }
        mutable.update { it.copy(automatic = value) }
        if (!value && autoTransfer) pause()
    }
    @Volatile private var autoTransfer = false
    fun check() = launchOperation { checkInternal() }
    private suspend fun checkInternal() {
        if (!configured) return
        mutable.update { it.copy(phase = UpdatePhase.CHECKING, message = null) }
        try {
            withContext(Dispatchers.IO) {
                val caller = currentCoroutineContext()
                val bytes = readUpdateManifest(transport, BuildConfig.UPDATE_MANIFEST_URL) { caller.ensureActive() }
                accept(bytes)
                root.mkdirs()
                val temp = File(root, "manifest.tmp")
                temp.outputStream().use { it.write(bytes); it.fd.sync() }
                java.nio.file.Files.move(temp.toPath(), envelopeFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            if (mutable.value.automatic && foreground && unmetered() && mutable.value.phase == UpdatePhase.AVAILABLE && mutable.value.bytes == 0L &&
                prefs.getLong("paused-version", 0) != mutable.value.artifact?.version) downloadInternal(true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { currentCoroutineContext().ensureActive(); mutable.update { it.copy(phase = UpdatePhase.ERROR, message = message(e, "更新检查失败，请重试")) } }
    }
    private fun accept(bytes: ByteArray) {
        val parsed = UpdateProtocol.verify(bytes, BuildConfig.UPDATE_PUBLIC_KEY, now())
        val choice = UpdateProtocol.choose(parsed, device, channel)
        val newest = parsed.artifacts.maxOf { it.version }
        require(newest >= prefs.getLong("$channel.highest-version", 0)) { "清单版本低于此前已验证版本" }
        prefs.edit { putLong("$channel.highest-version", newest) }
        manifest = parsed
        when (choice) {
            is UpdateChoice.Available -> {
                val a = choice.artifact
                val patch = selectPatch(parsed, a)
                cleanupObsolete(a, patch)
                mutable.update { it.copy(patch = patch, assembledBytes = 0) }
                // Existing ready file is rechecked before either display as ready or installation.
                val file = readyFile(a)
                if (file.isFile) {
                    try { verifyApk(file, a); mutable.update { it.copy(phase = UpdatePhase.READY, artifact = a, bytes = a.size, patch = null, message = null) } }
                    catch (_: Exception) { mutable.update { it.copy(phase = UpdatePhase.ERROR, artifact = a, bytes = 0, message = "已有更新包校验失败，请清理后重试") } }
                } else mutable.update { it.copy(phase = UpdatePhase.AVAILABLE, artifact = a,
                    bytes = (patch?.let(::patchFile) ?: partFile(a)).length().coerceAtMost(patch?.payload?.size ?: a.size), message = null) }
            }
            UpdateChoice.Current -> mutable.update { it.copy(phase = UpdatePhase.CURRENT, artifact = null, patch = null, bytes = 0, message = "当前渠道已是最新版本") }
            UpdateChoice.Incompatible -> mutable.update { it.copy(phase = UpdatePhase.INCOMPATIBLE, artifact = null, patch = null, bytes = 0, message = "存在新版本，但不适用于此系统或 ABI") }
        }
    }
    fun download() = launchOperation { downloadInternal(false) }
    fun downloadFull() = launchOperation {
        val a = mutable.value.artifact ?: return@launchOperation
        useFullPackage(a, "已选择完整包下载")
        downloadInternal(false)
    }
    private fun selectPatch(parsed: UpdateManifest, a: UpdateArtifact): UpdatePatch? {
        val info = getApplication<Application>().applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty() || partFile(a).exists() || readyFile(a).exists() ||
            prefs.getString("full-only-target", null) == a.stem) return null
        if (parsed.patches.none { it.targetVersion == a.version && it.targetHash == a.sha256 &&
                it.baseVersion == device.version && it.format == "bsdiff40" && it.payload.size < a.size }) return null
        val base = installedApk()
        if (!base.isFile || base.length() !in 1..MAX_APK) return null
        // Android's installed APK is immutable for this process; a package replacement kills it.
        return UpdateProtocol.patch(parsed, a, device.version, sha256(base))
    }
    private fun useFullPackage(a: UpdateArtifact, reason: String) {
        prefs.edit { putString("full-only-target", a.stem) }
        mutable.update { it.copy(patch = null, bytes = partFile(a).length(), assembledBytes = 0, message = reason) }
    }
    private suspend fun downloadInternal(automatic: Boolean) {
        val a = mutable.value.artifact ?: return
        if (!automatic) prefs.edit { remove("paused-version") }
        autoTransfer = automatic
        mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, message = null, assembledBytes = 0) }
        try {
            withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                fun checkpoint() {
                    job.ensureActive()
                    if (!foreground) throw CancellationException("Update left foreground")
                    require(manifest?.let { it.expires > now() && a in it.artifacts } == true) { "清单已过期，请重新检查" }
                    if (automatic && !unmetered()) throw CancellationException("Update network changed")
                }
                checkpoint()
                var previous = 0L
                fun received(count: Long) {
                    val tick = System.nanoTime()
                    if (count == mutable.value.transferSize || tick - previous >= 200_000_000) {
                        previous = tick; mutable.update { it.copy(bytes = count) }
                    }
                }
                var completed: File? = null
                var fallback = false
                val patch = mutable.value.patch
                if (patch != null) {
                    staging.mkdirs()
                    require(android.os.StatFs(staging.absolutePath).availableBytes >=
                        (patch.payload.size - patchFile(patch).length()).coerceAtLeast(0) + a.size + 16L * 1024 * 1024) { "补丁合成空间不足，可改用完整包" }
                    // Network failures keep patch bytes for explicit resume. Only synthesis failures fall back.
                    UpdateTransfer(transport).download(patch.payload, patchFile(patch), ::checkpoint, ::received)
                    checkpoint()
                    try {
                        mutable.update { it.copy(phase = UpdatePhase.APPLYING, assembledBytes = 0) }
                        previous = 0L
                        BsdiffPatch.apply(installedApk(), patchFile(patch), mergingFile(a), a.size, ::checkpoint) { count ->
                            val tick = System.nanoTime()
                            if (count == a.size || tick - previous >= 200_000_000) {
                                previous = tick; mutable.update { it.copy(assembledBytes = count) }
                            }
                        }
                        mutable.update { it.copy(phase = UpdatePhase.VERIFYING) }
                        verifyApk(mergingFile(a), a, ::checkpoint)
                        completed = mergingFile(a)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        checkpoint()
                        useFullPackage(a, "补丁合成未完成，已改用完整包")
                        fallback = true
                    }
                }
                if (completed == null) {
                    require(!mergingFile(a).exists() || mergingFile(a).delete()) { "无法清理未完成合成文件" }
                    mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING) }
                    previous = 0L
                    UpdateTransfer(transport).download(a, partFile(a), ::checkpoint, ::received)
                    mutable.update { it.copy(phase = UpdatePhase.VERIFYING, bytes = a.size) }
                    verifyApk(partFile(a), a, ::checkpoint)
                    completed = partFile(a)
                }
                checkpoint(); ready.mkdirs()
                java.nio.file.Files.move(completed.toPath(), readyFile(a).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                patch?.let { patchFile(it).delete() }
                mutable.update { it.copy(phase = UpdatePhase.READY, patch = null, bytes = a.size, assembledBytes = 0,
                    message = if (fallback) "已改用完整包并完成校验，可稍后安装" else "更新包已校验，可稍后从此处安装") }
            }
        } catch (e: CancellationException) {
            mutable.update { it.copy(phase = UpdatePhase.PAUSED, bytes = (it.patch?.let(::patchFile) ?: partFile(a)).length(),
                assembledBytes = 0, message = if (it.patch != null) "已暂停，保留增量包；继续时重新合成"
                    else "已暂停，保留下载字节；可继续或清理") }; throw e
        } catch (e: Exception) {
            mutable.update { it.copy(phase = UpdatePhase.ERROR, bytes = (it.patch?.let(::patchFile) ?: partFile(a)).length(), message = message(e, "下载或校验失败，可重试或清理")) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { mergingFile(a).delete() }
            autoTransfer = false
        }
    }
    fun pause() {
        mutable.value.artifact?.let { prefs.edit { putLong("paused-version", it.version) } }
        mutable.update { it.copy(message = "正在暂停并保存下载字节…") }
        operation?.cancel()
        transport.cancel()
    }
    fun clearDownload() = launchOperation {
        val a = mutable.value.artifact ?: return@launchOperation
        withContext(Dispatchers.IO) {
            val files = listOf(partFile(a), readyFile(a), mergingFile(a)) +
                (manifest?.patches.orEmpty().filter { it.targetVersion == a.version && it.targetHash == a.sha256 }.map(::patchFile))
            files.forEach { require(!it.exists() || it.delete()) { "无法清理更新文件，请重试" } }
        }
        mutable.update { it.copy(phase = UpdatePhase.AVAILABLE, bytes = 0, assembledBytes = 0, message = "仅清理此更新包，应用与书库保持") }
    }
    /** Called only from the settings route after reader exit has awaited its existing progress writer. */
    fun prepareInstall(launch: (Intent) -> Unit) = launchOperation {
        val a = mutable.value.artifact ?: return@launchOperation
        mutable.update { it.copy(phase = UpdatePhase.VERIFYING, message = null) }
        withContext(Dispatchers.IO) {
            val ctx = currentCoroutineContext()
            require(manifest?.let { it.expires > now() && a in it.artifacts } == true) { "发布清单已过期，请重新检查" }
            verifyApk(readyFile(a), a) { ctx.ensureActive() }
            require(android.os.StatFs(ready.absolutePath).availableBytes >= a.size + 16L * 1024 * 1024) { "安装暂存空间不足" }
        }
        require(foreground) { "请回到更新设置后安装" }
        val app = getApplication<Application>()
        require(app.packageManager.canRequestPackageInstalls()) { "请先允许安装此来源的应用，然后重新点击安装" }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", readyFile(a))
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        prefs.edit { putLong("install-target", a.version) }
        mutable.update { it.copy(phase = UpdatePhase.INSTALLING, message = "请在系统页面确认安装。") }
        launch(intent)
    }
    fun installationResult(result: Int) {
        // A self-update usually kills this process; only next-launch version identity proves success.
        prefs.edit { remove("install-target") }
        mutable.update { it.copy(phase = UpdatePhase.READY, message = if (result == android.app.Activity.RESULT_CANCELED)
            "已取消或未完成安装，更新包保留，可重试" else if (result == android.app.Activity.RESULT_OK) "系统已返回安装结果，下次启动确认版本。" else "系统安装失败，当前版本保留，可重试") }
    }
    fun launchError() { mutable.update { it.copy(phase = UpdatePhase.READY, message = "无法打开系统安装页面，请检查系统安装功能") } }
    private fun launchOperation(block: suspend () -> Unit) {
        if (operation?.isCompleted == false || mutable.value.phase == UpdatePhase.INSTALLING) return
        transport.begin()
        operation = viewModelScope.launch {
            try { block() } catch (e: CancellationException) {
                if (mutable.value.phase == UpdatePhase.CHECKING) mutable.update { it.copy(phase = UpdatePhase.IDLE, message = "已停止检查更新") }
                if (mutable.value.phase == UpdatePhase.VERIFYING) mutable.update { it.copy(phase = UpdatePhase.PAUSED, message = "已暂停校验，可重新检查并继续") }
                throw e
            }
            catch (e: Exception) { mutable.update { it.copy(phase = UpdatePhase.ERROR, message = message(e, "操作失败，请重试")) } }
        }
    }
    private fun verifyApk(file: File, a: UpdateArtifact, checkpoint: () -> Unit = {}) {
        verifyTarget(file, a, checkpoint)
        val pm = getApplication<Application>().packageManager
        val target = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("无法读取有效 APK")
        val installed = pm.getPackageInfo(device.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val abis = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
        requireApkIdentity(target.packageName, target.longVersionCode, target.applicationInfo?.minSdkVersion ?: 0, abis,
            installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty(),
            target.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty(), device, a)
    }
    private fun cleanupInstalled() {
        // Only own numeric-version + SHA names; never traverse user content or databases.
        val pattern = ownedUpdateFile
        listOf(staging, ready).forEach { dir -> dir.listFiles()?.forEach { file ->
            val match = pattern.matchEntire(file.name)
            if (file.isFile && match != null && match.groupValues[1].toLongOrNull()?.let { it <= device.version } == true) file.delete()
        } }
        val attempted = prefs.getLong("install-target", 0)
        if (attempted > 0) {
            prefs.edit { remove("install-target") }
            mutable.update { it.copy(installationMessage = if (device.version >= attempted) "已运行升级后的版本" else "上次安装未完成，可重新检查并继续") }
        }
    }
    private fun cleanupObsolete(a: UpdateArtifact, patch: UpdatePatch?) {
        val keep = setOf(partFile(a).name, readyFile(a).name, patch?.let(::patchFile)?.name)
        listOf(staging, ready).forEach { dir -> dir.listFiles()?.forEach { file ->
            if (file.isFile && ownedUpdateFile.matches(file.name) && file.name !in keep)
                require(file.delete()) { "无法清理旧更新包，请重试" }
        } }
    }
    private fun partFile(a: UpdateArtifact) = File(staging, "${a.stem}.part")
    private fun readyFile(a: UpdateArtifact) = File(ready, "${a.stem}.apk")
    private fun patchFile(p: UpdatePatch) = File(staging, "${p.stem}.patch")
    private fun mergingFile(a: UpdateArtifact) = File(staging, "${a.stem}.merging")
    private fun installedApk() = File(getApplication<Application>().applicationInfo.sourceDir)
    private val ownedUpdateFile get() = Regex("(\\d+)-[a-f0-9]{64}(?:\\.(?:apk|part|merging)|-[a-f0-9]{64}-[a-f0-9]{64}\\.patch)")
    private fun unmetered(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    private fun message(e: Exception, fallback: String): String = when (e) {
        is java.net.SocketTimeoutException -> "更新连接或读取超时，请检查网络后重试"
        is java.net.UnknownHostException -> "无法解析更新服务器地址，请检查网络或 DNS"
        is javax.net.ssl.SSLException -> "更新服务器安全连接失败，请检查系统时间与网络"
        is java.net.ConnectException -> "无法连接更新服务器，请检查网络后重试"
        is java.io.IOException -> "更新文件或网络读取失败（${e.javaClass.simpleName}），可重试下载"
        is IllegalArgumentException, is IllegalStateException ->
            e.message?.takeIf { it.any { c -> c in '\u4e00'..'\u9fff' } && !it.contains("https:") }?.take(240) ?: fallback
        else -> "$fallback（${e.javaClass.simpleName}）"
    }
    private fun now() = System.currentTimeMillis() / 1000
    override fun onCleared() { operation?.cancel(); transport.cancel(); super.onCleared() }
    companion object { private val coldCheck = AtomicBoolean(false) }
}
