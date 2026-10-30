package com.flux.manga.ui.pages

import android.content.Context
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import org.json.JSONArray
import org.json.JSONObject

/** Only remote metadata lives here. Favorites, progress and chapter read marks always come from Room. */
internal class DetailPageCache(private val context: Context, private val session: WorldSession, private val source: String, private val id: String) {
    private val identity = session.identity.value
    private val informationStore = OnlineBookInformationStore(WorldDataStore(context), session)
    private val version = if (isCopyAccount(source)) "detail-copy-api-v2" else "detail-v1"
    private val prefix = "$version:${source.length}:$source${id.length}:$id:"
    private val slots = mutableMapOf<String, CatalogPageCache>()
    private fun cache(slot: String) = slots.getOrPut(slot) { CatalogPageCache(context, session, prefix + slot, identity, includeBlacklist = false, source = source) }
    suspend fun read(slot: String = "book") = cache(slot).read()
    suspend fun <T> decode(slot: String = "book", parse: (JSONObject) -> T): T? =
        read(slot)?.let { json -> readDisplayCache { parse(json) } }
    suspend fun write(value: JSONObject, slot: String = "book") {
        // No disk rewrite when only the current request/session generation changed.
        val target = cache(slot)
        if (target.read()?.toString() != value.toString()) target.write(value)
        value.optJSONArray("chapters")?.takeIf { value.optJSONObject("next") == null }?.let { chapters ->
            val rows = List(chapters.length()) { chapters.getJSONObject(it) }
            val ordered = if (rows.all { it.has("ordinal") }) rows.sortedBy { it.getLong("ordinal") }
                else if (rows.all { it.has("order") }) rows.sortedBy { it.getInt("order") } else rows
            informationStore.chapters(source, id, if (slot.startsWith("chapters:")) slot else "default",
                ordered.map { if (it.has("id")) it.getString("id") else it.getInt("order").toString() })
        }
    }
    suspend fun information(value: OnlineBookInformation) {
        val previous = informationStore.read(source, id)
        informationStore.save(source, id, value.copy(coverReady = previous?.coverReady ?: false))
    }
}

internal fun detailInformation(b: SourceDetail) = OnlineBookInformation(b.manga.title, b.metadata.authors.joinToString(" / ") { it.name },
    b.description.orEmpty(), b.tags.orEmpty(), b.metadata.status.orEmpty(), b.metadata.updatedAt.orEmpty(), b.metadata.partial,
    contentUpdated = b.metadata.updateKind == SourceUpdateKind.CONTENT)
internal fun detailInformation(b: JmBook) = OnlineBookInformation(b.title, b.author, b.description, b.tags)
internal fun detailInformation(b: PicaBook) = OnlineBookInformation(b.title, listOf(b.author, b.artist).filter(String::isNotBlank).distinct().joinToString(" / "),
    b.description, (b.tags + b.categories).distinct(), if (b.finished) "已完结" else "连载中")
internal fun detailInformation(b: EhBook) = OnlineBookInformation(b.title,
    summary = listOf(b.subtitle, b.uploader.takeIf(String::isNotBlank)?.let { "上传者：$it" }.orEmpty()).filter(String::isNotBlank).joinToString("\n"),
    tags = (listOf(b.category).filter(String::isNotBlank) + b.tags).distinct(), status = "${b.pages} 页")
internal fun detailInformation(b: BaoziBook) = OnlineBookInformation(b.card.title, b.author, b.description, b.tags.orEmpty(),
    b.metadata.status.orEmpty(), b.metadata.updatedAt.orEmpty(), b.metadata.partial,
    contentUpdated = b.metadata.updateKind == SourceUpdateKind.CONTENT)

private fun JSONObject.text(name: String) = if (isNull(name)) null else getString(name)
private fun JSONObject.number(name: String) = if (isNull(name)) null else getLong(name).toUInt()
private fun JSONObject.strings(name: String): List<String>? = optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it) } }
private fun <T> List<T>.json(encode: (T) -> JSONObject) = JSONArray().also { a -> forEach { a.put(encode(it)) } }
private fun <T> JSONArray.rows(decode: (JSONObject) -> T) = List(length()) { decode(getJSONObject(it)) }
private fun List<String>?.jsonStrings(): Any = this?.let(::JSONArray) ?: JSONObject.NULL

internal fun detailJson(book: JmBook) = JSONObject().put("id", book.id).put("title", book.title).put("author", book.author)
    .put("description", book.description).put("tags", JSONArray(book.tags))
    .put("chapters", book.chapters.json { JSONObject().put("id", it.id).put("title", it.title) })
internal fun jmDetail(json: JSONObject) = JmBook(json.getString("id"), json.getString("title"), json.getString("author"),
    json.getString("description"), json.strings("tags").orEmpty(), json.getJSONArray("chapters").rows { JmChapter(it.getString("id"), it.getString("title")) })

internal fun detailJson(book: PicaBook) = JSONObject().put("id", book.id).put("title", book.title).put("cover", book.cover)
    .put("author", book.author).put("artist", book.artist).put("description", book.description).put("tags", JSONArray(book.tags))
    .put("categories", JSONArray(book.categories)).put("finished", book.finished)
internal fun picaDetail(j: JSONObject) = PicaBook(j.getString("id"), j.getString("title"), j.getString("cover"), j.getString("author"),
    j.getString("artist"), j.getString("description"), j.strings("tags").orEmpty(), j.strings("categories").orEmpty(), j.getBoolean("finished"))
internal fun picaChaptersJson(rows: List<PicaChapter>) = JSONObject().put("chapters", rows.json { JSONObject().put("order", it.order).put("title", it.title) })
internal fun picaChapters(j: JSONObject) = j.getJSONArray("chapters").rows { PicaChapter(it.getInt("order"), it.getString("title")) }

internal fun detailJson(book: EhBook) = JSONObject().put("id", book.id).put("title", book.title).put("cover", book.cover)
    .put("subtitle", book.subtitle).put("uploader", book.uploader).put("category", book.category).put("pages", book.pages).put("tags", JSONArray(book.tags))
internal fun ehDetail(j: JSONObject) = EhBook(j.getString("id"), j.getString("title"), j.getString("cover"), j.getString("subtitle"),
    j.getString("uploader"), j.getString("category"), j.getInt("pages"), j.strings("tags").orEmpty())

private fun metadataJson(m: SourceWorkMetadata) = JSONObject().put("authors", m.authors.json { JSONObject().put("name", it.name).put("role", it.role ?: JSONObject.NULL) })
    .put("status", m.status ?: JSONObject.NULL).put("updated", m.updatedAt ?: JSONObject.NULL)
    .put("kind", m.updateKind?.name ?: JSONObject.NULL).put("partial", m.partial)
private fun metadata(j: JSONObject) = SourceWorkMetadata(j.getJSONArray("authors").rows { SourceAuthor(it.getString("name"), it.text("role")) },
    j.text("status"), j.text("updated"), j.text("kind")?.let(SourceUpdateKind::valueOf), j.getBoolean("partial"))

internal fun detailJson(book: SourceDetail) = JSONObject().put("id", book.manga.key.mangaId).put("title", book.manga.title)
    .put("pages", book.manga.pageCount?.toLong() ?: JSONObject.NULL).put("description", book.description ?: JSONObject.NULL)
    .put("tags", book.tags.jsonStrings()).put("metadata", metadataJson(book.metadata))
internal fun sourceDetail(j: JSONObject, context: SourceContext) = SourceDetail(context,
    SourceManga(SourceMangaKey(context.world, context.sourceId, j.getString("id")), j.getString("title"), j.number("pages"), null, null, null),
    j.text("description"), j.strings("tags"), metadata(j.getJSONObject("metadata")))
internal fun chaptersJson(rows: List<SourceChapter>, next: SourceContinuation, total: Long?) = withContinuation(JSONObject()
    .put("chapters", rows.json { JSONObject().put("id", it.id).put("title", it.title).put("ordinal", it.ordinal.toLong()).put("pages", it.pageCount?.toLong() ?: JSONObject.NULL) })
    .put("total", total ?: JSONObject.NULL), next)
internal fun sourceChapters(j: JSONObject, key: SourceMangaKey) = j.getJSONArray("chapters").rows {
    SourceChapter(key, it.getString("id"), it.getString("title"), it.getLong("ordinal").toUInt(), it.number("pages"))
}
internal fun chapterTotal(j: JSONObject) = if (j.isNull("total")) null else j.getLong("total")

internal fun detailJson(book: CopyMangaBook) = JSONObject().put("detail", detailJson(book.detail))
    .put("groups", book.groups?.json { JSONObject().put("id", it.id).put("title", it.title).put("count", it.chapterCount?.toLong() ?: JSONObject.NULL) } ?: JSONObject.NULL)
    .put("themes", book.themes?.json { JSONObject().put("name", it.name).put("path", it.pathWord) } ?: JSONObject.NULL)
internal fun copyDetail(j: JSONObject, context: SourceContext): CopyMangaBook {
    val detail = sourceDetail(j.getJSONObject("detail"), context)
    return CopyMangaBook(detail, j.optJSONArray("groups")?.rows { CopyChapterGroup(detail.manga.key, it.getString("id"), it.getString("title"), it.number("count")) },
        j.optJSONArray("themes")?.rows { CopyTheme(it.getString("name"), it.getString("path")) })
}

internal fun detailJson(book: BaoziBook) = JSONObject().put("id", book.card.key.mangaId).put("title", book.card.title)
    .put("author", book.author).put("description", book.description).put("tags", book.tags.jsonStrings())
    .put("metadata", metadataJson(book.metadata)).put("chapterError", book.chapterError ?: JSONObject.NULL)
    .put("chapters", book.chapters?.json { JSONObject().put("id", it.id).put("title", it.title) } ?: JSONObject.NULL)
internal fun baoziDetail(j: JSONObject, key: MangaKey) = BaoziBook(MangaCard(key, j.getString("title"), null, true, author = j.getString("author")),
    j.getString("author"), j.getString("description"), j.optJSONArray("chapters")?.rows { BaoziChapter(key, it.getString("id"), it.getString("title")) },
    j.text("chapterError"), j.strings("tags"), metadata(j.getJSONObject("metadata")))
