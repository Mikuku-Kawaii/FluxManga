package com.flux.manga.data

import android.content.Context
import android.net.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One callback per live WorldSession download owner; no polling, boot receiver or new scheduler. */
internal class DownloadNetworkMonitor(context: Context, private val controller: DownloadController) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val preferences = context.getSharedPreferences("download-network", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(DownloadNetworkPolicy.entries.firstOrNull {
        it.name == preferences.getString("SFW.policy", null) } ?: DownloadNetworkPolicy.UNMETERED)
    val policy = mutable.asStateFlow()
    private var closed = false
    private var network: Network? = manager.activeNetwork
    private var caps = manager.getNetworkCapabilities(network)
    private var blocked = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(value: Network) { network = value; caps = null; blocked = false; update() }
        override fun onCapabilitiesChanged(value: Network, capabilities: NetworkCapabilities) {
            if (network == value) { caps = capabilities; update() }
        }
        override fun onBlockedStatusChanged(value: Network, status: Boolean) { if (network == value) { blocked = status; update() } }
        override fun onLost(value: Network) { if (network == value) { network = null; caps = null; update() } }
    }
    init { update(); manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }
    private fun update() {
        if (!closed) controller.networkChanged(downloadNetworkBlock(mutable.value,
            network != null && !blocked && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true))
        // Do not require public-internet validation: an authorized LAN Komga may be reachable without it.
    }
    fun set(value: DownloadNetworkPolicy) {
        if (closed) return
        preferences.edit { putString("SFW.policy", value.name) }; mutable.value = value; update()
    }
    fun close() { closed = true; manager.unregisterNetworkCallback(callback) }
}
