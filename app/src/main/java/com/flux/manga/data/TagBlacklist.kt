package com.flux.manga.data

import android.content.Context
import com.flux.manga.session.WorldSession
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

internal fun normalizedBlockedTag(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)

/** Private rules use the same encrypted, world-owned storage as source configuration. */
internal class TagBlacklist(context: Context, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    private fun active() = session.accepts(identity) && session.state.value.foreground
    suspend fun read(): List<String> {
        val bytes = data.session(identity.world, SLOT, ::active) ?: return emptyList()
        return try {
            val rows = JSONArray(bytes.toString(Charsets.UTF_8))
            List(rows.length()) { rows.getString(it) }
        } finally { bytes.fill(0) }
    }
    suspend fun save(tags: List<String>): List<String> {
        val values = tags.map(String::trim).filter(String::isNotEmpty).distinctBy(::normalizedBlockedTag)
        val bytes = JSONArray(values).toString().toByteArray()
        try { data.putSession(identity.world, SLOT, bytes, ::active) }
        finally { bytes.fill(0) }
        return values
    }
    companion object { const val SLOT = "tag-blacklist-v1" }
}
