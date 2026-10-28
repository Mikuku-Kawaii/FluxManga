package com.flux.manga.ui.pages

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
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
    var observedRecovery by remember(model) { mutableLongStateOf(state.recoveryVersion) }
    LaunchedEffect(state.recoveryVersion) {
        if (observedRecovery != state.recoveryVersion) { observedRecovery = state.recoveryVersion; restored() }
    }
    DisposableEffect(model, lifecycle) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val resumed = lifecycle == Lifecycle.State.RESUMED
        var connected = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (usable && !connected) model.retryRecovery()
                connected = usable
            }
            override fun onLost(network: Network) { connected = false }
        }
        if (resumed) {
            model.retryRecovery()
            manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        }
        onDispose {
            if (resumed) { manager.unregisterNetworkCallback(callback); model.pauseRecovery() }
        }
    }
    var opened by remember(model) { mutableStateOf(false) }
    LaunchedEffect(state.reconnectRequested, lifecycle, occupied) {
        if (!state.reconnectRequested || lifecycle != Lifecycle.State.RESUMED) opened = false
        else if (!occupied) opened = true
    }
    if (opened && lifecycle == Lifecycle.State.RESUMED && state.reconnectRequested) {
        AccountWebLogin(BaoziWebSite, close = model::dismissReconnect,
            connect = { page, _, name ->
                val (token, browser) = page.baoziSession()
                model.connectWebSession(name, token, browser, page.baoziAutoLogin, recoveryOnly = true)
            },
            autoConnectKey = { page, _, name ->
                val origin = page.baoziOrigin()
                page.cookie(origin, "TSID").takeIf { it.isNotBlank() && name.isNotBlank() }?.let { "$origin:$name:$it" }
            }, restoreBaozi = model::savedWebAccount,
            backgroundRecoveryFailure = model::reconnectFailed)
    }
}
