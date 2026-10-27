package com.flux.manga.ui.pages

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld

/** One silent recovery for all Baozi requests while this world session is in the foreground. */
@Composable
internal fun BaoziSessionRecovery(session: WorldSession, restored: () -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    if (identity.world != PrototypeWorld.SFW) return
    val context = LocalContext.current.applicationContext
    val model = remember(session, identity) { session.baoziAccount(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val occupied = accountWebLoginOccupied()
    var opened by remember(model) { mutableStateOf(false) }
    LaunchedEffect(state.reconnectRequested, lifecycle, occupied) {
        if (!state.reconnectRequested || lifecycle != Lifecycle.State.RESUMED) opened = false
        else if (!occupied) opened = true
    }
    if (opened && lifecycle == Lifecycle.State.RESUMED && state.reconnectRequested) {
        AccountWebLogin(BaoziWebSite, close = model::dismissReconnect,
            connect = { page, _, name ->
                val (token, browser) = page.baoziSession()
                model.connectWebSession(name, token, browser, page.baoziAutoLogin)
            },
            autoConnectKey = { page, _, name ->
                val origin = page.baoziOrigin()
                page.cookie(origin, "TSID").takeIf { it.isNotBlank() && name.isNotBlank() }?.let { "$origin:$name:$it" }
            }, restoreBaozi = model::savedWebAccount, afterConnected = restored,
            backgroundRecoveryFailure = model::reconnectFailed)
    }
}
