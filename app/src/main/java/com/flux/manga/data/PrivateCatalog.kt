package com.flux.manga.data

import android.content.Context
import android.graphics.Bitmap
import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

internal data class PrivateCatalogTag(val name: String, val query: String, val category: Boolean = false)

/** Display feeds share the existing paginator; returned works retain their actual account identity.
 * Each request owns and closes its private client, including cancellation and background revocation. */
internal class PrivateCatalog(private val app: Context, private val session: WorldSession) {
    private val identity = session.identity.value
    private val data = WorldDataStore(app)
    companion object {
        const val PICA = "picacg-unconfigured"
        const val EX = "exhentai-unconfigured"
        val feeds = listOf(JM_SOURCE, PICA, EH_SOURCE, EX)
        private val covers = Semaphore(2)
    }
    private fun active() {
        if (identity.world != PrototypeWorld.NSFW || !session.accepts(identity) || !session.state.value.foreground) throw CancellationException()
    }
    private fun card(source: String, id: String, title: String, author: String = "") = MangaCard(MangaKey(PrototypeWorld.NSFW, source, id), title, null, true, author = author)
    private fun next(page: Int?) = page?.let { SourceContinuation.Next(SourcePageRequest.Page(it.toUInt())) } ?: SourceContinuation.End
    suspend fun load(feed: String, query: String, page: SourcePageRequest, mode: String = "search"): SourceCardPage = coroutineScope {
        active()
        val number = when (page) { SourcePageRequest.Start -> 1; is SourcePageRequest.Page -> page.number.toInt(); else -> 1 }
        val result = when (feed) {
            JM_SOURCE -> {
                val config = JmStore(app, session).settings()
                JmClient(session, config, config.account?.source ?: JM_SOURCE, this).use { client ->
                    val batch = when (mode) {
                        "recommendations" -> client.recommendations()
                        "updates" -> client.catalog(number, popular = false)
                        "category" -> client.catalog(number, category = query)
                        else -> client.search(query, number)
                    }
                    SourceCardPage(batch.books.map { card(client.source, it.id, it.title, it.author).copy(updatedAt = it.updatedAt.takeIf(String::isNotBlank)) }, next(batch.next))
                }
            }
            PICA -> {
                val account = PicaStore(app, session).account() ?: throw IOException("登录后可加载作品，请前往图源管理")
                PicaClient(session, account, this).use { client ->
                    val batch = when (mode) {
                        "recommendations" -> client.recommendations()
                        "updates" -> client.catalog("", number)
                        "category" -> client.catalog(query, number)
                        else -> client.search(query, number)
                    }
                    SourceCardPage(batch.items.map { card(account.source, it.id, it.title, it.author).copy(updatedAt = it.updatedAt.takeIf(String::isNotBlank)) }, next(batch.next))
                }
            }
            else -> {
                val site = if (feed == EX) GallerySite.EX else GallerySite.EH
                val config = EhStore(app, session, site).settings()
                if (site == GallerySite.EX && config.account == null) throw IOException("请先在图源管理独立登录 ExHentai")
                EhClient(session, config.account, this, site).use { client ->
                    val cursor = (page as? SourcePageRequest.Cursor)?.token
                    val batch = client.browse(false, query, "all", cursor)
                    SourceCardPage(batch.books.map { card(site.source(config.account), it.id, it.title) },
                        batch.next?.let { SourceContinuation.Next(SourcePageRequest.Cursor(it)) } ?: SourceContinuation.End)
                }
            }
        }
        ensureActive(); active(); result
    }
    /** Reuse the existing directory contracts for visible home cards; never count image pages as episodes. */
    suspend fun update(card: MangaCard): Pair<String, String> = coroutineScope {
        active()
        val result = if (isPicaSource(card.key.sourceId)) {
            val account = PicaStore(app, session).account()?.takeIf { it.source == card.key.sourceId }
                ?: throw IOException("请登录原账号")
            PicaClient(session, account, this).use { client ->
                val last = client.allChapters(card.key.mangaId).lastOrNull()
                last?.updatedAt.orEmpty() to (last?.let { "第${it.order}话" }.orEmpty())
            }
        } else {
            val settings = JmStore(app, session).settings()
            if (card.key.sourceId != JM_SOURCE && card.key.sourceId != settings.account?.source) throw IOException("请登录原账号")
            JmClient(session, if (card.key.sourceId == JM_SOURCE) settings.copy(account = null) else settings, card.key.sourceId, this).use { client ->
                val book = client.detail(card.key.mangaId)
                book.updatedAt to (if (book.chapters.size == 1) "第1话" else book.chapters.lastOrNull()?.title.orEmpty())
            }
        }
        ensureActive(); active(); result
    }
    suspend fun tags(feed: String): List<PrivateCatalogTag> = coroutineScope {
        active()
        val result = when (feed) {
            JM_SOURCE -> {
                val config = JmStore(app, session).settings()
                JmClient(session, config, config.account?.source ?: JM_SOURCE, this).use { it.tags() }
            }
            PICA -> {
                val account = PicaStore(app, session).account() ?: throw IOException("登录后可读取分类，请前往图源管理")
                PicaClient(session, account, this).use { client ->
                    client.categories().map { PrivateCatalogTag(it, it, category = true) }
                }
            }
            else -> error("Unsupported tag directory")
        }
        ensureActive(); active(); result
    }
    suspend fun cover(source: String, id: String): Bitmap = covers.withPermit { coroutineScope {
        active()
        when {
            isJmSource(source) -> {
                val config = JmStore(app, session).settings()
                if (source != JM_SOURCE && source != config.account?.source) throw IOException("请登录原账号")
                JmClient(session, if (source == JM_SOURCE) config.copy(account = null) else config, source, this).use { jmCover(data, session, it, id) }
            }
            isPicaSource(source) -> {
                val account = PicaStore(app, session).account()?.takeIf { it.source == source } ?: throw IOException("请登录原账号")
                PicaClient(session, account, this).use { picaCover(data, session, it, id) }
            }
            else -> {
                val site = GallerySite.forSource(source)
                val config = EhStore(app, session, site).settings()
                if (source != EH_SOURCE && source != site.source(config.account)) throw IOException("请登录原账号")
                EhClient(session, if (source == EH_SOURCE) null else config.account, this, site).use { ehCover(data, session, it, id) }
            }
        }
    } }
}
