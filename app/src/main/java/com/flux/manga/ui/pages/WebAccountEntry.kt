package com.flux.manga.ui.pages

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Website-owned registration; returning is not proof of an API session. */
@Composable
internal fun WebAccountEntry(url: String, note: String, enabled: Boolean = true,
    connect: (suspend (AccountWebPage, String, String) -> Unit)? = null,
    autoConnectKey: (suspend (AccountWebPage, String, String) -> String?)? = null,
    afterConnected: () -> Unit = {}, accountNameLabel: String? = null, initialAccountName: String = "",
    webSite: AccountWebSite? = null,
    restoreBaozi: (suspend () -> com.flux.manga.data.BaoziSavedAccount?)? = null) {
    var opened by remember(url) { mutableStateOf(false) }
    var setupAutoLogin by remember(url) { mutableStateOf(false) }
    val uri = remember(url) { Uri.parse(url.trim()) }
    val valid = uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReferenceButton(onClick = { setupAutoLogin = false; opened = true }, enabled = enabled && valid, modifier = Modifier.fillMaxWidth()) { Text("应用内网页登录 / 注册") }
        if (restoreBaozi != null) TextButton(onClick = { setupAutoLogin = true; opened = true }, enabled = enabled && valid) { Text("设置自动登录") }
        Text(if (valid) uri.host.orEmpty() else "请先填写有效的 HTTPS 服务地址",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (opened && valid) {
        val site = remember(url, webSite) { webSite ?: AccountWebSite("网页账号", url, setOf(requireNotNull(uri.host).lowercase())) }
        AccountWebLogin(site, close = { opened = false }, connect = connect, autoConnectKey = autoConnectKey,
            afterConnected = afterConnected, accountNameLabel = accountNameLabel, initialAccountName = initialAccountName,
            restoreBaozi = restoreBaozi, setupBaoziAutoLogin = setupAutoLogin)
    }
}

