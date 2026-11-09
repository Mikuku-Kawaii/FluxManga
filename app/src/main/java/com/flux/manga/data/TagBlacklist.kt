package com.flux.manga.data

import android.content.Context
import com.flux.manga.session.WorldSession
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import java.util.Locale

internal fun normalizedBlockedTag(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)

internal data class TagBlacklistRules(val enabled: Boolean = true, val tags: List<String> = emptyList())
internal data class TitleBlacklistRules(val enabled: Boolean = false, val keywords: List<String> = emptyList())

/** Private rules use the same encrypted, world-owned storage as source configuration. */
internal class TagBlacklist(context: Context, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    private fun active() = session.accepts(identity) && session.state.value.foreground
    suspend fun read(): TagBlacklistRules {
        val bytes = data.session(identity.world, SLOT, ::active) ?: return TagBlacklistRules()
        return try {
            val value = JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue()
            // Earlier versions stored only a tag array and always applied its rules.
            val rows = if (value is JSONArray) value else (value as JSONObject).getJSONArray("tags")
            TagBlacklistRules(enabled = value !is JSONObject || value.getBoolean("enabled"),
                tags = List(rows.length()) { rows.getString(it) })
        } finally { bytes.fill(0) }
    }
    suspend fun save(rules: TagBlacklistRules): TagBlacklistRules {
        val saved = rules.copy(tags = rules.tags.map(String::trim).filter(String::isNotEmpty).distinctBy(::normalizedBlockedTag))
        val bytes = JSONObject().put("enabled", saved.enabled).put("tags", JSONArray(saved.tags)).toString().toByteArray()
        try { data.putSession(identity.world, SLOT, bytes, ::active) }
        finally { bytes.fill(0) }
        return saved
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
