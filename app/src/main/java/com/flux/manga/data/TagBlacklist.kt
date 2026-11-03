package com.flux.manga.data

import android.content.Context
import com.flux.manga.session.WorldSession
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

internal fun normalizedBlockedTag(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)

internal data class TitleBlacklistRules(val enabled: Boolean = false, val keywords: List<String> = emptyList())

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
    suspend fun readTitles(): TitleBlacklistRules {
        val bytes = data.session(identity.world, TITLE_SLOT, ::active) ?: return TitleBlacklistRules()
        return try {
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            val rows = value.getJSONArray("keywords")
            TitleBlacklistRules(value.getBoolean("enabled"), List(rows.length()) { rows.getString(it) })
        } finally { bytes.fill(0) }
    }
    suspend fun saveTitles(rules: TitleBlacklistRules): TitleBlacklistRules {
        val saved = rules.copy(keywords = rules.keywords.map(String::trim).filter(String::isNotEmpty).distinctBy(::normalizedBlockedTag))
        val bytes = JSONObject().put("enabled", saved.enabled).put("keywords", JSONArray(saved.keywords)).toString().toByteArray()
        try { data.putSession(identity.world, TITLE_SLOT, bytes, ::active) }
        finally { bytes.fill(0) }
        return saved
    }
    companion object {
        const val SLOT = "tag-blacklist-v1"
        const val TITLE_SLOT = "title-blacklist-v1"
    }
}
