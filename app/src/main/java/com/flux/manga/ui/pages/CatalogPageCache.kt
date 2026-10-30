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
internal class CatalogPageCache(private val context: Context, private val session: WorldSession, private val slot: String,
    private val identity: com.flux.manga.session.SessionIdentity = session.identity.value,
    private val includeBlacklist: Boolean = true, private val source: String? = null) {
    private val data = WorldDataStore(context)
    private fun active() = session.accepts(identity) && session.state.value.foreground
    private fun key(query: String, owner: String) = ResourceKey(SampleKey(identity.world, "catalog-pages", "$slot|$owner|$query"), "catalog-page-v1")
    private var owner: String? = null
    private suspend fun owner() = owner ?: (source?.let { sourceCacheIdentity(context, session, it) }
        ?: catalogConfiguration(data, session, includeBlacklist)).also { owner = it }

    suspend fun read(query: String = "", configuration: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        val current = configuration ?: owner()
        val bytes = readDisplayCache { data.resource(key(query, current), ::active) }
            ?: (if (source == null) readDisplayCache { data.resource(ResourceKey(SampleKey(identity.world, "catalog-pages", slot), "catalog-page-v1"), ::active) } else null)
            ?: return@withContext null
        try {
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            if (value.getString("owner") == current && value.getString("query") == query) value.getJSONObject("data") else null
        } catch (_: org.json.JSONException) { null }
        finally { bytes.fill(0) }
    }

    suspend fun <T> decode(query: String = "", parse: (JSONObject) -> T): T? =
        read(query)?.let { json -> readDisplayCache { parse(json) } }

    suspend fun write(value: JSONObject, query: String = "") = withContext(Dispatchers.IO) {
        val current = owner()
        val bytes = JSONObject().put("owner", current).put("query", query).put("data", value).toString().toByteArray()
        try { data.putResource(key(query, current), bytes, ::active) } finally { bytes.fill(0) }
    }

    suspend fun feed(query: String): DiscoveryFeedState? {
        val json = read(query) ?: return null
        val saved = readDisplayCache { decodeFeed(json) } ?: return null
        val filtered = TagBlacklistFilter(context.applicationContext as android.app.Application, session)
            .apply(SourceCardPage(saved.cards, saved.next))
        return saved.copy(cards = filtered.cards, unfilteredCards = saved.cards)
    }

    private fun decodeFeed(json: JSONObject): DiscoveryFeedState {
        val rows = json.getJSONArray("cards")
        return DiscoveryFeedState(cards = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            MangaCard(MangaKey(identity.world, row.getString("source"), row.getString("id")), row.getString("title"),
                if (row.isNull("pages")) null else row.getInt("pages"), row.getBoolean("available"), author = row.getString("author"),
                updatedAt = row.optString("updatedAt").takeUnless { it.isBlank() || it == "null" },
                latestChapter = row.optString("latestChapter").takeUnless { it.isBlank() || it == "null" },
                sourceTags = row.optJSONArray("sourceTags")?.let { tags -> List(tags.length()) { tags.getString(it) } })
        }, next = continuation(json), attempted = true, loaded = true, fetchedAt = json.optLong("fetchedAt"))
    }

    suspend fun saveFeed(query: String, state: DiscoveryFeedState) {
        val cards = JSONArray()
        state.unfilteredCards.ifEmpty { state.cards }.forEach { card -> cards.put(JSONObject().put("source", card.key.sourceId).put("id", card.key.mangaId)
            .put("title", card.title).put("pages", card.pageCount ?: JSONObject.NULL)
            .put("available", card.available).put("author", card.author)
            .put("updatedAt", card.updatedAt ?: JSONObject.NULL).put("latestChapter", card.latestChapter ?: JSONObject.NULL)
            .put("sourceTags", card.sourceTags?.let { JSONArray(it) } ?: JSONObject.NULL)) }
        write(withContinuation(JSONObject().put("cards", cards).put("fetchedAt", state.fetchedAt), state.next), query)
    }
}

/** Compare decrypted configuration, not randomized ciphertext; merely visiting settings is unchanged. */
internal suspend fun catalogConfiguration(data: WorldDataStore, session: WorldSession, includeBlacklist: Boolean = true): String {
    val identity = session.identity.value
    val slots = if (identity.world == PrototypeWorld.SFW)
        listOf("komga-user-configuration-v1", "copy-account-v1", "baozi-active-account-v1")
    else listOf("jm-settings-v1", "picacg-account-v1", "ehentai-settings-v1", "exhentai-settings-v1")
    return data.accountImportDatabase(identity.world).transaction({ session.accepts(identity) }) { rows ->
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data.sourceSelection(identity.world).fingerprint().toByteArray())
        if (includeBlacklist) digest.update("known-tags-only-v2".toByteArray())
        (slots + if (includeBlacklist) listOf(TagBlacklist.SLOT) else emptyList()).forEach { slot ->
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
        else -> throw org.json.JSONException("Unknown saved continuation")
    })
}

internal fun tagsJson(state: SourceTagsState) = withContinuation(JSONObject().put("tags", JSONArray(state.tags)), state.next)
internal fun tagsState(json: JSONObject, query: String): SourceTagsState {
    val tags = json.getJSONArray("tags")
    return SourceTagsState(query, List(tags.length()) { tags.getString(it) }, continuation(json), attempted = true, loaded = true)
}
