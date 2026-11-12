package com.flux.manga.ui.pages

import androidx.compose.runtime.*
import com.flux.manga.data.SourceBypassNetwork
import com.flux.manga.data.SourceKind
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SourceNetworkSettings(session: WorldSession, kind: SourceKind) {
    val identity = session.identity.value
    var checked by remember(kind, identity) { mutableStateOf(SourceBypassNetwork.enabled(kind)) }
    var saving by remember(kind) { mutableStateOf(false) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val feedback = LocalRequestFeedback.current
    fun active() = session.accepts(identity) && session.state.value.foreground
    SettingsGroup {
        SettingsSwitchRow("绕过 VPN", "仅此图源的接口、图片及内置网页登录尝试直连。VPN 需允许绕过；对新请求生效。",
            checked, { value ->
                if (!saving && active()) {
                    feedback?.beginAttempt(); error = null; saving = true
                    scope.launch {
                        try {
                            SourceBypassNetwork.set(kind, value, ::active)
                            if (active()) checked = SourceBypassNetwork.enabled(kind)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { if (active()) error = "网络设置保存失败，请重试" }
                        finally { saving = false }
                    }
                }
            }, enabled = !saving)
    }
    error?.let { RequestFeedback(it) }
}
