package com.flux.manga.data

internal enum class DownloadNetworkPolicy(val label: String) { UNMETERED("仅非计费网络"), ANY("允许计费网络") }
internal fun downloadNetworkBlock(policy: DownloadNetworkPolicy, connected: Boolean, unmetered: Boolean): String? = when {
    !connected -> "等待可用网络"
    policy == DownloadNetworkPolicy.UNMETERED && !unmetered -> "等待非计费网络"
    else -> null
}
