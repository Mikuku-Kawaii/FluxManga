package com.flux.manga.data

import android.content.Context
import android.net.Uri
import com.flux.manga.session.WorldSession
import com.flux.manga.storage.BoundedResourceInput
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

internal const val GALLERY_TAG_RELEASES = "https://github.com/EhTagTranslation/Database/releases"
internal const val GALLERY_TAG_LICENSE = "https://github.com/EhTagTranslation/Database/blob/6c228f5ac76ca415e897dcc074f7e77c2afa14c2/LICENSE.md"
private const val TAG_LIMIT = 100_000
private const val BYTE_LIMIT = 16 * 1024 * 1024

internal data class GalleryTag(val namespace: String, val original: String, val name: String) {
    val key get() = "$namespace:$original"
    val query get() = "$namespace:\"$original$\""
}
internal data class GalleryTagMatches(val entries: List<GalleryTag>, val more: Boolean)
internal class GalleryTagDictionary(val revision: String, val digest: String,
    val namespaces: Map<String, String>, val entries: List<GalleryTag>) {
    private val byKey = entries.associateBy { it.key }
    val namespaceKeys = entries.map { it.namespace }.distinct()
    fun label(raw: String): String = byKey[raw]?.let { "${namespaces[it.namespace] ?: it.namespace}：${it.name} · $raw" } ?: raw
    suspend fun search(input: String, namespace: String?, limit: Int): GalleryTagMatches = withContext(Dispatchers.Default) {
        val query = input.trim()
        val result = ArrayList<GalleryTag>()
        for (entry in entries) {
            ensureActive()
            if ((namespace == null || entry.namespace == namespace) &&
                (query.isEmpty() || entry.name.contains(query, true) || entry.key.contains(query, true))) {
                result.add(entry)
                if (result.size > limit) break
            }
        }
        GalleryTagMatches(result.take(limit), result.size > limit)
    }
    fun bytes(): ByteArray = JSONObject().put("format", 1).put("revision", revision).put("digest", digest)
        .put("namespaces", JSONObject(namespaces)).put("entries", JSONArray().also { rows ->
            entries.forEach { rows.put(JSONArray().put(it.namespace).put(it.original).put(it.name)) }
        }).toString().toByteArray(Charsets.UTF_8)

    companion object {
        private fun tag(namespace: String, original: String, name: String): GalleryTag {
            require(namespace.matches(Regex("[a-z]+")) && original.isNotBlank() && original.length <= 200 &&
                original.none { it.isISOControl() || it == '"' || it == '\\' || it == '$' } && name.isNotBlank() && name.length <= 512) { "词库包含不支持的标签字段" }
            return GalleryTag(namespace, original, name)
        }
        fun parse(bytes: ByteArray, check: () -> Unit): GalleryTagDictionary {
            val json = JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            require(json.getInt("version") == 7) { "仅支持 EhTagTranslation v7 的 db.text.json" }
            val revision = json.getJSONObject("head").getString("sha")
            require(revision.matches(Regex("[0-9a-f]{40}"))) { "词库缺少有效版本标识" }
            val groups = json.getJSONArray("data")
            val namespaces = linkedMapOf<String, String>(); val entries = linkedMapOf<String, GalleryTag>()
            val seen = mutableSetOf<String>()
            for (index in 0 until groups.length()) {
                check()
                val group = groups.getJSONObject(index); val namespace = group.getString("namespace")
                require(namespace.matches(Regex("[a-z]+")) && seen.add(namespace)) { "词库命名空间无效或重复" }
                val values = group.getJSONObject("data")
                for (original in values.keys()) {
                    check()
                    val name = values.getJSONObject(original).getString("name")
                    if (namespace == "rows") {
                        require(original.matches(Regex("[a-z]+")) && name.length <= 512)
                        namespaces[original] = name
                    } else {
                        val entry = tag(namespace, original, name)
                        entries[entry.key] = entry
                        require(entries.size <= TAG_LIMIT) { "词库超过十万标签上限" }
                    }
                }
            }
            require(entries.isNotEmpty()) { "词库没有可用标签" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return GalleryTagDictionary(revision, digest, namespaces, entries.values.toList())
        }
        fun stored(bytes: ByteArray): GalleryTagDictionary {
            val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("format") == 1)
            val names = json.getJSONObject("namespaces"); val rows = json.getJSONArray("entries")
            require(rows.length() in 1..TAG_LIMIT)
            return GalleryTagDictionary(json.getString("revision"), json.getString("digest"),
                names.keys().asSequence().associateWith { names.getString(it) },
                (0 until rows.length()).map { i -> val row = rows.getJSONArray(i); tag(row.getString(0), row.getString(1), row.getString(2)) })
        }
    }
}

internal class GalleryTagStore(private val context: Context, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity)
    suspend fun load(): GalleryTagDictionary? = withContext(Dispatchers.IO) {
        val bytes = data.galleryTagData(::active) ?: return@withContext null
        try { GalleryTagDictionary.stored(bytes) } finally { bytes.fill(0) }
    }
    suspend fun preview(uri: Uri): GalleryTagDictionary = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val caller = currentCoroutineContext()
        fun checkActive() { caller.ensureActive(); if (!active() || !session.state.value.foreground) throw CancellationException() }
        val bytes = context.contentResolver.openInputStream(uri)?.use { BoundedResourceInput.read(it, BYTE_LIMIT, ::checkActive) }
            ?: throw IOException("无法打开所选词库")
        try { GalleryTagDictionary.parse(bytes, ::checkActive) } finally { bytes.fill(0) }
    }
    suspend fun save(dictionary: GalleryTagDictionary) = withContext(Dispatchers.IO) {
        val bytes = dictionary.bytes()
        try { data.putGalleryTagData(bytes, ::active) } finally { bytes.fill(0) }
    }
    suspend fun remove() = data.putGalleryTagData(null, ::active)
    suspend fun enabled(): Boolean {
        val bytes = data.session(identity.world, "gallery-tag-display-v1", ::active) ?: return true
        return try { bytes.firstOrNull() != 0.toByte() } finally { bytes.fill(0) }
    }
    suspend fun enabled(value: Boolean) = data.putSession(identity.world, "gallery-tag-display-v1", byteArrayOf(if (value) 1 else 0), ::active)
}
