package com.flux.manga.ui.pages

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException

/** Read saved identity only. Listing sources must not sign in or probe every service. */
@Composable
internal fun sourceAccountSummary(kind: SourceKind, session: WorldSession?): String {
    if (session == null) return "账号与收藏导入"
    val context = LocalContext.current.applicationContext
    val identity by session.identity.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var summary by remember(kind, identity) { mutableStateOf("正在读取账号…") }
    LaunchedEffect(kind, identity, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || identity.world != kind.world) return@LaunchedEffect
        fun active() = session.accepts(identity) && session.state.value.foreground
        try {
            val data = WorldDataStore(context)
            val name: String? = when (kind) {
                SourceKind.COPY -> CopyAccountStore(data.accountImportDatabase()).current(::active)
                    ?.takeIf { it.hasCredential }?.username
                SourceKind.BAOZI -> BaoziAccountStore(data.accountImportDatabase()).current(::active)
                    ?.takeIf { !it.token.isNullOrBlank() }?.username
                SourceKind.KOMGA -> KomgaSettingsStore(data).load()?.let { "服务器账号" }
                SourceKind.JM -> JmStore(context, session).settings().account?.username
                SourceKind.PICA -> PicaStore(context, session).account()?.name
                SourceKind.EH -> EhStore(context, session, GallerySite.EH).settings().account?.name
                SourceKind.EX -> EhStore(context, session, GallerySite.EX).settings().account?.name
            }
            if (active()) summary = if (name != null) "已保存账号${name.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
                else if (kind == SourceKind.KOMGA) "尚未配置服务器" else "未连接账号"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (active()) summary = "账号记录读取失败，点此处理"
        }
    }
    return summary
}
