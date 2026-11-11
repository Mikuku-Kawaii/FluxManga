package com.flux.manga.ui.pages

import com.flux.manga.core.SourceException
import com.flux.manga.data.PublicWebSite
import com.flux.manga.data.sourceNetworkFailure
import java.io.IOException

internal fun publicWebFailure(error: Exception, source: String): String {
    val name = PublicWebSite.of(source)?.label ?: "图源"
    val network = error as? SourceException.Network ?: error.cause as? SourceException.Network
    if (source == PublicWebSite.MANHUAGUI.sourceId && network?.kind in setOf("tls", "tls_eof")) {
        return "漫画柜安全连接失败，请关闭 VPN／代理后重试"
    }
    return sourceNetworkFailure(error) ?: when (error) {
        is SourceException.AuthenticationRequired -> "$name 登录已失效或访问受限，请在图源管理检查账号"
        is SourceException.NotFound -> "$name 未找到此作品或章节"
        is SourceException.RateLimited -> "$name 请求频繁，请稍后重试"
        is SourceException.Timeout -> "$name 请求超时，请重试"
        is SourceException.HttpStatus -> if (error.status.toInt() == 403) "$name 拒绝访问，可能需要网站验证或当前网络无法访问"
            else "$name 暂不可用（HTTP ${error.status}）"
        is SourceException.Transport -> "无法连接$name，请检查网络后重试"
        is IOException -> error.message ?: "$name 加载失败，请重试"
        else -> "$name 页面无法识别，可能是验证页或站点格式已变化"
    }
}
