package com.flux.manga.data

import android.content.Context
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal data class PrivateFavoritesTask(
    val id: String, val cursor: String? = null, val pages: Int = 0,
    val added: Int = 0, val existing: Int = 0, val done: Boolean = false,
)

private data class FavoritesBatch(val books: List<Pair<String, String>>, val next: String?)

/** Reuses the account-import transaction: book inserts and the next cursor commit together. */
internal class PrivateFavoritesImport(context: Context, private val session: WorldSession, private val source: String) {
    private val identity = session.identity.value
    private val data = WorldDataStore(context)
    private val db = data.accountImportDatabase(PrototypeWorld.NSFW)
    private val taskSlot = "private-favorites-import-v1:$source"
    private val accountSlot = when {
        isJmSource(source) && source != JM_SOURCE -> "jm-settings-v1"
        isPicaSource(source) -> "picacg-account-v1"
        isExSource(source) -> "exhentai-settings-v1"
        isEhSource(source) && source != EH_SOURCE -> "ehentai-settings-v1"
        else -> throw IllegalArgumentException("收藏导入需要已登录的图源账号")
    }
    private fun active() = identity.world == PrototypeWorld.NSFW && session.accepts(identity) && session.state.value.foreground

    private fun AccountImportRows.task(): PrivateFavoritesTask? {
        val bytes = read(taskSlot) ?: return null
        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1)
            PrivateFavoritesTask(json.getString("id"), if (json.isNull("cursor")) null else json.getString("cursor"),
                json.getInt("pages"), json.getInt("added"), json.getInt("existing"), json.getBoolean("done"))
        } finally { bytes.fill(0) }
    }
    private fun AccountImportRows.save(task: PrivateFavoritesTask) {
        val bytes = JSONObject().put("version", 1).put("id", task.id).put("cursor", task.cursor ?: JSONObject.NULL)
            .put("pages", task.pages).put("added", task.added).put("existing", task.existing).put("done", task.done)
            .toString().toByteArray()
        try { write(taskSlot, bytes) } finally { bytes.fill(0) }
    }
    suspend fun task(): PrivateFavoritesTask? = db.transaction(::active) { it.task() }

    private fun AccountImportRows.requireAccount(fingerprint: ByteArray) {
        val bytes = read(accountSlot) ?: throw IOException("账号已退出，请重新登录后续办")
        try {
            if (!MessageDigest.isEqual(fingerprint, MessageDigest.getInstance("SHA-256").digest(bytes)))
                throw IOException("账号或连接设置已变化，请返回图源管理后续办")
        } finally { bytes.fill(0) }
    }

    suspend fun run(owner: CoroutineScope, rescan: Boolean = false, update: (PrivateFavoritesTask) -> Unit) {
        // Read one credential snapshot; both the client and commit guard use this exact snapshot.
        val bytes = db.transaction(::active) { it.read(accountSlot) } ?: throw IOException("请先登录此图源账号")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes)
        val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
        when {
            isJmSource(source) -> {
                val a = json.optJSONObject("account") ?: throw IOException("请先登录 JMComic")
                val account = JmAccount(a.getString("uid"), a.getString("username"), a.getString("cookie"))
                if (account.source != source) throw IOException("请登录此任务所属的 JMComic 账号")
                val settings = JmSettings(json.getString("api"), json.getString("image"), account)
                require(settings.api in JM_APIS && settings.image in JM_IMAGES)
                JmClient(session, settings, source, owner).use { client ->
                    drain(fingerprint, rescan, update) { cursor ->
                        val page = client.favorites("0", cursor?.toInt() ?: 1)
                        FavoritesBatch(page.books.map { it.id to it.title }, page.next?.toString())
                    }
                }
            }
            isPicaSource(source) -> {
                val account = PicaAccount(json.getString("uid"), json.getString("name"), json.getString("token"))
                if (account.source != source) throw IOException("请登录此任务所属的 PicACG 账号")
                PicaClient(session, account, owner).use { client ->
                    drain(fingerprint, rescan, update) { cursor ->
                        val page = client.favorites(cursor?.toInt() ?: 1)
                        FavoritesBatch(page.items.map { it.id to it.title }, page.next?.toString())
                    }
                }
            }
            else -> {
                val site = GallerySite.forSource(source)
                val a = json.optJSONObject("account") ?: throw IOException("请先登录 ${site.label}")
                val account = EhAccount(a.getString("uid"), a.getString("hash"), a.getString("name"),
                    if (site == GallerySite.EX) a.getString("igneous") else "")
                if (site.source(account) != source) throw IOException("请登录此任务所属的 ${site.label} 账号")
                EhClient(session, account, owner, site).use { client ->
                    // EX always requests EX itself; no EH response is used as proof of access.
                    drain(fingerprint, rescan, update) { cursor ->
                        val page = client.browse(true, "", "all", cursor)
                        FavoritesBatch(page.books.map { it.id to it.title }, page.next)
                    }
                }
            }
        }
    }

    private suspend fun drain(fingerprint: ByteArray, rescan: Boolean, update: (PrivateFavoritesTask) -> Unit,
        fetch: suspend (String?) -> FavoritesBatch) {
        var task = db.transaction(::active) { rows ->
            rows.requireAccount(fingerprint)
            rows.task()?.takeUnless { rescan } ?: PrivateFavoritesTask(UUID.randomUUID().toString()).also { rows.save(it) }
        }
        update(task)
        val visited = mutableSetOf<String?>()
        while (!task.done) {
            currentCoroutineContext().ensureActive()
            visited.add(task.cursor)
            val batch = fetch(task.cursor)
            if (batch.next != null && batch.next in visited) throw IOException("图源返回重复分页，进度已保留，请稍后续办")
            val expected = task
            task = db.transaction(::active) { rows ->
                rows.requireAccount(fingerprint)
                if (rows.task() != expected) throw IOException("导入任务已变化，请重新打开导入页")
                var added = expected.added
                var existing = expected.existing
                batch.books.distinctBy { it.first }.forEach { (id, title) ->
                    val old = rows.book(source, id)
                    val progress = rows.hasProgress(source, id)
                    if (old != null || progress) existing++ else added++
                    if (old == null) rows.putBook(OnlineBook(source, id, title, !progress, System.currentTimeMillis()))
                }
                expected.copy(cursor = batch.next, pages = expected.pages + 1, added = added, existing = existing,
                    done = batch.next == null).also { rows.save(it) }
            }
            update(task)
        }
    }
}
