package com.flux.manga.data

import android.content.Context
import com.flux.manga.session.WorldSession
import java.security.MessageDigest

/** Stable identity, not a hash of rotating tokens, import cursors or unrelated sources. */
internal suspend fun sourceCacheIdentity(context: Context, session: WorldSession, source: String): String {
    val identity = session.identity.value
    val data = WorldDataStore(context)
    val account = when {
        source.startsWith("jmcomic-") -> JmStore(context, session).settings().account?.source ?: JM_SOURCE
        source.startsWith("picacg-") -> PicaStore(context, session).account()?.source ?: PrivateCatalog.PICA
        source.startsWith("ehentai-") || source.startsWith("exhentai-") -> {
            val site = if (source.startsWith("exhentai-")) GallerySite.EX else GallerySite.EH
            site.source(EhStore(context, session, site).settings().account)
        }
        source.startsWith("komga-") -> KomgaSettingsStore(data).load()?.let {
            "${it.sourceId}|${it.baseUrl}|${it.userId ?: it.username.ifBlank { it.apiKey }}"
        }.orEmpty()
        isCopyAccount(source) -> CopyAccountStore(data.accountImportDatabase()).current { session.accepts(identity) }?.sourceId.orEmpty()
        isBaoziSource(source) -> {
            val bytes = data.session(identity.world, "baozi-active-account-v1") { session.accepts(identity) }
            try { bytes?.toString(Charsets.UTF_8).orEmpty() } finally { bytes?.fill(0) }
        }
        else -> source
    }
    val value = "source-cache-v3|${identity.world}|$source|$account"
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
