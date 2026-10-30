package com.flux.manga.data

import com.flux.manga.core.*
import org.json.JSONArray
import org.json.JSONObject

internal fun readerPagesJson(pages: List<SourcePageResource>) = JSONObject().put("pages", JSONArray().also { rows ->
    pages.forEach { page -> rows.put(JSONObject().put("chapter", page.chapterId).put("id", page.id)
        .put("ordinal", page.ordinal.toLong()).put("uri", page.uri).put("mime", page.mimeType ?: JSONObject.NULL)
        .put("width", page.width?.toLong() ?: JSONObject.NULL).put("height", page.height?.toLong() ?: JSONObject.NULL)) }
})
internal fun readerPages(json: JSONObject, manga: SourceMangaKey, context: SourceContext): List<SourcePageResource> {
    val rows = json.getJSONArray("pages")
    return List(rows.length()) { index -> rows.getJSONObject(index).let {
        SourcePageResource(manga, it.getString("chapter"), it.getString("id"), it.getLong("ordinal").toUInt(),
            it.getString("uri"), if (it.isNull("mime")) null else it.getString("mime"),
            if (it.isNull("width")) null else it.getLong("width").toUInt(),
            if (it.isNull("height")) null else it.getLong("height").toUInt(), context)
    } }
}
