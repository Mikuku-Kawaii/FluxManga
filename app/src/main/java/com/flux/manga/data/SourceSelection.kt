package com.flux.manga.data

import android.content.Context
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SourceKind(val label: String, val world: PrototypeWorld) {
    KOMGA("Komga", PrototypeWorld.SFW), COPY("拷贝漫画", PrototypeWorld.SFW), BAOZI("包子漫画", PrototypeWorld.SFW),
    GUAZI("瓜子漫画", PrototypeWorld.SFW), KUAIKAN("快看漫画", PrototypeWorld.SFW), MANHUAGUI("漫画柜", PrototypeWorld.SFW),
    JM("JMComic", PrototypeWorld.NSFW), PICA("PicACG", PrototypeWorld.NSFW),
    EH("E-Hentai", PrototypeWorld.NSFW), EX("ExHentai", PrototypeWorld.NSFW);

    companion object {
        fun of(source: String): SourceKind? = when {
            source.startsWith("komga-") -> KOMGA
            isCopySource(source) -> COPY
            isBaoziSource(source) -> BAOZI
            source == PublicWebSite.GUAZI.sourceId -> GUAZI
            source == PublicWebSite.KUAIKAN.sourceId -> KUAIKAN
            source == PublicWebSite.MANHUAGUI.sourceId -> MANHUAGUI
            isJmSource(source) -> JM
            source == PrivateCatalog.PICA || isPicaSource(source) -> PICA
            isEhSource(source) -> EH
            source == PrivateCatalog.EX || isExSource(source) -> EX
            else -> null
        }
    }
}

/** Non-secret discovery preferences only; account/session data stays in its existing store. */
internal class SourceSelection(context: Context, private val world: PrototypeWorld) {
    private val preferences = context.applicationContext.getSharedPreferences("source-selection-${world.name.lowercase()}", Context.MODE_PRIVATE)
    // Missing preferences use the initial source for this space; explicit choices always win.
    fun enabled(kind: SourceKind) = kind.world == world &&
        preferences.getBoolean(kind.name, kind == SourceKind.COPY || kind == SourceKind.JM)
    fun enabled(source: String) = SourceKind.of(source)?.let(::enabled) ?: false
    fun snapshot() = SourceKind.entries.filter { it.world == world }.associateWith(::enabled)
    fun bypassVpn(kind: SourceKind) = kind.world == world && preferences.getBoolean("bypass-vpn-${kind.name}", false)
    suspend fun setBypassVpn(kind: SourceKind, value: Boolean, allowed: () -> Boolean) = withContext(Dispatchers.IO) {
        require(kind.world == world)
        if (!allowed()) throw CancellationException("Expired source settings session")
        val previous = bypassVpn(kind)
        if (!preferences.edit().putBoolean("bypass-vpn-${kind.name}", value).commit()) {
            preferences.edit().putBoolean("bypass-vpn-${kind.name}", previous).apply()
            throw IOException("网络设置保存失败，请重试")
        }
    }
    fun copyHomeGeneralOnly() = world == PrototypeWorld.SFW && preferences.getBoolean("copy-home-general-only", true)
    fun fingerprint() = snapshot().entries.joinToString(";") { "${it.key.name}=${it.value}" } +
        if (world == PrototypeWorld.SFW) ";copy-home-general-only=${copyHomeGeneralOnly()}" else ""

    suspend fun setCopyHomeGeneralOnly(enabled: Boolean, allowed: () -> Boolean) = withContext(Dispatchers.IO) {
        require(world == PrototypeWorld.SFW)
        if (!allowed()) throw CancellationException("Expired source settings session")
        val previous = copyHomeGeneralOnly()
        if (!preferences.edit().putBoolean("copy-home-general-only", enabled).commit()) {
            preferences.edit().putBoolean("copy-home-general-only", previous).apply()
            throw IOException("首页过滤设置保存失败，请重试")
        }
    }

    suspend fun set(kind: SourceKind, enabled: Boolean, allowed: () -> Boolean) = withContext(Dispatchers.IO) {
        require(kind.world == world)
        if (!allowed()) throw CancellationException("Expired source settings session")
        val previous = this@SourceSelection.enabled(kind)
        if (!preferences.edit().putBoolean(kind.name, enabled).commit()) {
            preferences.edit().putBoolean(kind.name, previous).apply()
            throw IOException("图源开关保存失败，请重试")
        }
    }
}
