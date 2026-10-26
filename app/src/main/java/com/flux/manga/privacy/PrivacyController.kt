package com.flux.manga.privacy

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.compose.runtime.*

internal val LocalPrivacyController = staticCompositionLocalOf<PrivacyController?> { null }

/** Owned by the host Activity, not a retained ViewModel or global session. */
internal class PrivacyController(
    private val activity: Activity,
    private val session: () -> String,
    private val foreground: () -> Boolean,
    private val isPrivate: () -> Boolean,
    private val returnToPublic: () -> Unit,
) {
    private val preferences = activity.getSharedPreferences("privacy-entry", Activity.MODE_PRIVATE)
    var longPress by mutableStateOf(preferences.getBoolean("long-press", false)); private set
    var biometric by mutableStateOf(preferences.getBoolean("biometric", false)); private set
    var unlocked by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var availability by mutableStateOf(""); private set
    var launcher by mutableStateOf(""); private set
    private val tickets = AuthTickets()
    private var signal: CancellationSignal? = null
    val privateSpace get() = isPrivate()

    fun leavePrivateSpace() {
        if (!foreground() || !isPrivate()) return
        pause()
        message = null
        returnToPublic()
    }

    fun changeLongPress(value: Boolean) {
        preferences.edit().putBoolean("long-press", value).apply()
        longPress = value
    }
    fun dismissMessage() { message = null }
    fun clearGrant() { unlocked = false }
    fun pause() {
        tickets.cancel()
        signal?.cancel(); signal = null
        busy = false; unlocked = false
    }
    private fun capability(): Int = activity.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    fun refresh() {
        availability = capabilityText(capability()) ?: "强生物识别可用"
        launcher = runCatching {
            LauncherStyle.entries.filter(::enabled).joinToString(" / ") { it.title }.ifEmpty { "入口状态异常，请恢复默认" }
        }.getOrDefault("无法读取桌面入口，请重试")
    }
    fun changeBiometric(value: Boolean) {
        if (value == biometric) return
        authenticate("确认修改隐私验证", {
            preferences.edit().putBoolean("biometric", value).apply()
            biometric = value
            unlocked = value
            message = if (value) "已开启生物识别" else "已关闭生物识别"
        })
    }
    fun enter(success: () -> Unit, failure: () -> Unit = {}) {
        if (!biometric) { success(); return }
        authenticate("验证后进入里世界样本", { unlocked = true; success() }, failure)
    }
    private fun authenticate(title: String, success: () -> Unit, failure: () -> Unit = {}) {
        if (busy || !foreground()) return
        val unavailable = capabilityText(capability())
        if (unavailable != null) { message = unavailable; failure(); return }
        message = null
        val ticket = tickets.begin(session())
        busy = true
        fun complete(ok: Boolean, detail: String? = null) {
            if (!tickets.consume(ticket, session(), foreground())) return
            busy = false; signal = null
            if (ok) { message = null; success() } else { message = detail; failure() }
        }
        val cancellation = CancellationSignal().also { signal = it }
        try {
            BiometricPrompt.Builder(activity).setTitle(title)
                .setSubtitle("验证通过后进入里世界")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(true)
                .setNegativeButton("取消", activity.mainExecutor) { _, _ -> complete(false, "已取消验证，未授权进入") }
                .build().authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { complete(true) }
                    override fun onAuthenticationFailed() {
                        if (tickets.isCurrent(ticket, session(), foreground())) message = "未识别，请在系统提示中重试或取消"
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        complete(false, when (errorCode) {
                            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT ->
                                "生物识别已锁定，请先在系统中解锁，再重新尝试"
                            BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ->
                                "已取消验证，未授权进入"
                            else -> "验证未完成：$errString"
                        })
                    }
                })
        } catch (_: Exception) { complete(false, "无法启动系统验证，请稍后重试") }
    }
    fun enroll() {
        try { activity.startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricManager.Authenticators.BIOMETRIC_STRONG)) }
        catch (_: Exception) { message = "无法打开系统生物识别设置，请从手机设置中录入" }
    }
    private fun component(style: LauncherStyle) = ComponentName(activity, "${activity.packageName}.${style.alias}")
    private fun enabled(style: LauncherStyle): Boolean = when (activity.packageManager.getComponentEnabledSetting(component(style))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> style == LauncherStyle.STANDARD
        else -> false
    }
    fun setLauncher(style: LauncherStyle) {
        if (busy) return
        val pm = activity.packageManager
        try {
            if (Build.VERSION.SDK_INT >= 33) pm.setComponentEnabledSettings(LauncherStyle.entries.map {
                PackageManager.ComponentEnabledSetting(component(it), if (it == style) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }) else applyLauncherSelection(style) { item, enable ->
                pm.setComponentEnabledSetting(component(item), if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            message = "桌面入口已改为${style.title}；桌面可能延迟刷新"
        } catch (_: Exception) { message = "桌面入口更新未完成，请重试或恢复 FluxManga；当前入口状态见下方" }
        refresh()
    }
}

private fun capabilityText(code: Int): String? = when (code) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "未录入强生物识别，请先在系统设置中录入"
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "此设备不支持强生物识别，保持当前验证设置"
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "生物识别暂时不可用，请稍后重试"
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "生物识别需要系统安全更新"
    else -> "当前无法使用强生物识别，请检查系统设置"
}
