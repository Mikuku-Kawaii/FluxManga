package com.flux.manga.data

import android.content.Context
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SourceKind(val label: String, val world: PrototypeWorld) {
    KOMGA("Komga", PrototypeWorld.SFW), COPY("拷贝漫画", PrototypeWorld.SFW), BAOZI("包子漫画", PrototypeWorld.SFW),
    JM("JMComic", PrototypeWorld.NSFW), PICA("PicACG", PrototypeWorld.NSFW),
    EH("E-Hentai", PrototypeWorld.NSFW), EX("ExHentai", PrototypeWorld.NSFW);

    companion object {
        fun of(source: String): SourceKind? = when {
            source.startsWith("komga-") -> KOMGA
            isCopySource(source) -> COPY
            isBaoziSource(source) -> BAOZI
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
    fun fingerprint() = snapshot().entries.joinToString(";") { "${it.key.name}=${it.value}" }

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
