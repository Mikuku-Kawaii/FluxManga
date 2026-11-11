package com.flux.manga.data

import android.content.Context
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

// Deliberately not data classes: credential values must not appear in generated toString().
internal class BrowserPassword(val origin: String, val username: String, val password: String)
internal class BrowserPasswordBook(val entries: List<BrowserPassword> = emptyList(), val excluded: Set<String> = emptySet())

internal fun passwordOrigin(url: String?): String? = try {
    val uri = URI(url ?: "")
    if (uri.scheme != "https" || uri.host == null || uri.userInfo != null) null
    else "https://${uri.host.lowercase()}${if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"}"
} catch (_: Exception) { null }

/** Separate from disposable WebView cookies and source auto-login; never included in portable backups. */
internal class BrowserPasswordStore(context: Context, private val session: WorldSession, source: String) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context.applicationContext)
    private val slot = "browser-passwords-v1:" + MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun active() = session.accepts(identity) && session.state.value.foreground
    suspend fun read(): BrowserPasswordBook {
        if (!active()) throw CancellationException()
        val bytes = data.session(identity.world, slot, ::active) ?: return BrowserPasswordBook()
        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val entries = json.getJSONArray("entries")
            val excluded = json.getJSONArray("excluded")
            BrowserPasswordBook(List(entries.length()) { i -> entries.getJSONObject(i).let {
                BrowserPassword(it.getString("origin"), it.getString("username"), it.getString("password"))
            } }, List(excluded.length()) { excluded.getString(it) }.toSet())
        } finally { bytes.fill(0) }
    }
    suspend fun write(book: BrowserPasswordBook) {
        val bytes = JSONObject().put("entries", JSONArray().apply {
            book.entries.forEach { put(JSONObject().put("origin", it.origin).put("username", it.username).put("password", it.password)) }
        }).put("excluded", JSONArray(book.excluded.toList())).toString().toByteArray()
        try { data.putSession(identity.world, slot, bytes, ::active) } finally { bytes.fill(0) }
    }
}
