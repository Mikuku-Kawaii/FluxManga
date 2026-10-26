package com.flux.manga.ui.pages

import android.content.Context
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Last successful lists only; no credentials, reader progress or screenshots in the payload.
 * WorldDataStore supplies atomic cache files, private encryption and generation checks. */
internal class CatalogPageCache(context: Context, private val session: WorldSession, private val slot: String,
    private val identity: com.flux.manga.session.SessionIdentity = session.identity.value) {
    private val data = WorldDataStore(context)
    private fun active() = session.accepts(identity) && session.state.value.foreground
    private val key = ResourceKey(SampleKey(identity.world, "catalog-pages", slot), "catalog-page-v1")
    private var owner: String? = null
    private suspend fun owner() = owner ?: catalogConfiguration(data, session).also { owner = it }

    suspend fun read(query: String = ""): JSONObject? = withContext(Dispatchers.IO) {
        val current = owner()
        val bytes = data.resource(key, ::active) ?: return@withContext null
        try {
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            if (value.getString("owner") == current && value.getString("query") == query) value.getJSONObject("data") else null
        } finally { bytes.fill(0) }
    }

    suspend fun write(value: JSONObject, query: String = "") = withContext(Dispatchers.IO) {
        val bytes = JSONObject().put("owner", owner()).put("query", query).put("data", value).toString().toByteArray()
        try { data.putResource(key, bytes, ::active) } finally { bytes.fill(0) }
    }

    suspend fun feed(query: String): DiscoveryFeedState? = read(query)?.let { json ->
        val rows = json.getJSONArray("cards")
        DiscoveryFeedState(cards = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            MangaCard(MangaKey(identity.world, row.getString("source"), row.getString("id")), row.getString("title"),
                if (row.isNull("pages")) null else row.getInt("pages"), row.getBoolean("available"), author = row.getString("author"))
        }, next = continuation(json), attempted = true, loaded = true)
    }

    suspend fun saveFeed(query: String, state: DiscoveryFeedState) {
        val cards = JSONArray()
        state.cards.forEach { card -> cards.put(JSONObject().put("source", card.key.sourceId).put("id", card.key.mangaId)
            .put("title", card.title).put("pages", card.pageCount ?: JSONObject.NULL)
            .put("available", card.available).put("author", card.author)) }
        write(withContinuation(JSONObject().put("cards", cards), state.next), query)
    }
}

/** Compare decrypted configuration, not randomized ciphertext; merely visiting settings is unchanged. */
internal suspend fun catalogConfiguration(data: WorldDataStore, session: WorldSession): String {
    val identity = session.identity.value
    val slots = if (identity.world == PrototypeWorld.SFW)
        listOf("komga-user-configuration-v1", "copy-account-v1", "baozi-active-account-v1")
    else listOf("jm-settings-v1", "picacg-account-v1", "ehentai-settings-v1", "exhentai-settings-v1")
    return data.accountImportDatabase(identity.world).transaction({ session.accepts(identity) }) { rows ->
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data.sourceSelection(identity.world).fingerprint().toByteArray())
        slots.forEach { slot ->
            digest.update(slot.toByteArray())
            val bytes = rows.read(slot)
            try { digest.update((bytes?.size ?: -1).toString().toByteArray()); if (bytes != null) digest.update(bytes) }
            finally { bytes?.fill(0) }
        }
        if (identity.world == PrototypeWorld.SFW) {
            val active = rows.read("baozi-active-account-v1")
            val source = try { active?.toString(Charsets.UTF_8) } finally { active?.fill(0) }
            if (source != null && com.flux.manga.data.isBaoziAccount(source)) {
                val bytes = rows.read("baozi-account-v1:$source")
                try {
                    if (bytes != null) {
                        val account = com.flux.manga.data.BaoziAccountCodec.decode(bytes)
                        // Import cursor updates do not invalidate discovery; refreshed gate state does.
                        listOf(account.token, account.browserSession?.origin, account.browserSession?.cookie,
                            account.browserSession?.userAgent).forEach { value ->
                            val raw = value?.toByteArray()
                            try { digest.update((raw?.size ?: -1).toString().toByteArray()); if (raw != null) digest.update(raw) }
                            finally { raw?.fill(0) }
                        }
                    }
                } finally { bytes?.fill(0) }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun withContinuation(json: JSONObject, next: SourceContinuation): JSONObject {
    val request = (next as? SourceContinuation.Next)?.request
    return json.put("next", when (request) {
        null -> JSONObject.NULL
        SourcePageRequest.Start -> JSONObject().put("kind", "start")
        is SourcePageRequest.Page -> JSONObject().put("kind", "page").put("number", request.number.toLong())
        is SourcePageRequest.Cursor -> JSONObject().put("kind", "cursor").put("token", request.token)
    })
}

internal fun continuation(json: JSONObject): SourceContinuation {
    val next = json.optJSONObject("next") ?: return SourceContinuation.End
    return SourceContinuation.Next(when (next.getString("kind")) {
        "start" -> SourcePageRequest.Start
        "page" -> SourcePageRequest.Page(next.getLong("number").toUInt())
        "cursor" -> SourcePageRequest.Cursor(next.getString("token"))
        else -> error("Unknown saved continuation")
    })
}

internal fun tagsJson(state: SourceTagsState) = withContinuation(JSONObject().put("tags", JSONArray(state.tags)), state.next)
internal fun tagsState(json: JSONObject, query: String): SourceTagsState {
    val tags = json.getJSONArray("tags")
    return SourceTagsState(query, List(tags.length()) { tags.getString(it) }, continuation(json), attempted = true, loaded = true)
}
