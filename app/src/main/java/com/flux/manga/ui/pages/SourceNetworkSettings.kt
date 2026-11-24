package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.flux.manga.data.SourceBypassNetwork
import com.flux.manga.data.SourceKind
import com.flux.manga.data.SourceNetworkMode
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SourceNetworkSettings(session: WorldSession, kind: SourceKind) {
    val identity = session.identity.value
    var mode by remember(kind, identity) { mutableStateOf(SourceBypassNetwork.mode(kind)) }
    var saving by remember(kind) { mutableStateOf(false) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val feedback = LocalRequestFeedback.current
    fun active() = session.accepts(identity) && session.state.value.foreground
    fun select(value: SourceNetworkMode) {
        if (value == mode || saving || !active()) return
        feedback?.beginAttempt(); error = null; saving = true
        scope.launch {
            try {
                SourceBypassNetwork.set(kind, value, ::active)
                if (active()) mode = SourceBypassNetwork.mode(kind)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) error = "网络设置保存失败，请重试" }
            finally { saving = false }
        }
    }
    SettingsGroup("网络线路") {
        Column(Modifier.selectableGroup()) {
            SettingsChoiceRow("跟随系统", mode == SourceNetworkMode.SYSTEM, { select(SourceNetworkMode.SYSTEM) },
                description = "使用系统网络和图源已有的备用地址。", enabled = !saving)
            SettingsChoiceRow("自动换线", mode == SourceNetworkMode.AUTO, { select(SourceNetworkMode.AUTO) },
                description = "接口或图片读取连接失败时尝试直连，可能使用移动数据。VPN 需允许绕过。", enabled = !saving)
            SettingsChoiceRow("绕过 VPN", mode == SourceNetworkMode.DIRECT, { select(SourceNetworkMode.DIRECT) },
                description = "此图源始终尝试直连，包括内置网页登录。VPN 需允许绕过。", enabled = !saving)
        }
    }
    error?.let { RequestFeedback(it) }
}
