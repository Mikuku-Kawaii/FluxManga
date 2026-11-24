package com.flux.manga.data

import com.flux.manga.core.SourceException

/** Fixed, non-sensitive reasons from the native transport; never include native URLs or headers. */
internal fun sourceNetworkFailure(error: Exception): String? {
    val network = error as? SourceException.Network ?: (error.cause as? SourceException.Network) ?: return null
    return when (network.kind) {
        "vpn_bypass" -> "图源直连失败，请检查 Wi-Fi／移动网络及 VPN 是否允许绕过"
        "gallery_forum_challenge" -> "论坛要求浏览器验证，暂时无法确认账号身份，请稍后重试"
        "gallery_challenge" -> "画廊站点要求浏览器验证，请稍后重试"
        "dns" -> "图源域名解析失败，请检查网络后重试"
        "tls" -> "图源安全连接失败，请检查设备时间或更换可用线路"
        "tls_eof" -> "图源安全握手被中断，请尝试切换 VPN／代理线路或关闭后重试"
        "reset" -> "图源连接被重置，请重试或更换可用线路"
        "refused" -> "图源拒绝连接，请稍后重试或更换可用线路"
        else -> "无法建立图源连接，请检查网络后重试"
    }
}
