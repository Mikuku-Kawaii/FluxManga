package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun baoziFailure(error: Exception): String = when (error) {
    is SourceException.AuthenticationRequired -> "请打开网页登录，完成网站验证。"
    is SourceException.InvalidResponse -> "包子页面无法识别，可能是验证页或站点结构已变化，请稍后重试"
    is SourceException.Timeout -> "包子漫画请求超时，可以重试"
    is SourceException.Transport -> "无法连接包子漫画，请检查网络后重试。"
    is SourceException.NotFound -> "包子漫画未找到此作品"
    is SourceException.RateLimited -> "请求过于频繁，请稍后重试"
    is SourceException.InvalidRequest -> "请求无效，请检查输入或返回重新进入"
    is SourceException.HttpStatus -> if (error.status.toInt() == 403) "官网验证已过期，请在图源管理重新连接包子网页登录后刷新"
        else "包子漫画暂不可用（HTTP ${error.status}）"
    else -> "包子漫画加载失败，请重试"
}
internal data class BaoziDetailState(val book: BaoziBook? = null, val busy: Boolean = false,
    val attempted: Boolean = false, val error: String? = null, val notice: String? = null,
    val favorite: Boolean = false, val hasProgress: Boolean = false, val saving: Boolean = false, val progress: OnlineReadingProgress? = null)

internal class BaoziModel(private val app: Application, private val session: WorldSession, private val mangaId: String?, private val sourceId: String = BaoziEndpoint.SOURCE_ID) : ViewModel() {
    val library = OnlineLibrary(app, session)
    private val data = WorldDataStore(app)
    private val identity = session.identity.value
    private val detailCache = mangaId?.let { DetailPageCache(app, session, sourceId, it) }
    private fun active() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    private suspend fun source() = withContext(Dispatchers.IO) {
        if (!active()) throw CancellationException()
        session.baoziSources.client(app, sourceId).source
    }
    var submitted by mutableStateOf<String?>(null); private set
    val feed = DiscoveryFeedController(viewModelScope, ::active, ::baoziFailure) { page ->
        source().search(requireNotNull(submitted), page)
    }
    fun search(query: String): Boolean {
        if (!active() || (feed.state.value.busy && submitted == query.trim())) return false
        submitted = query.trim().take(200); feed.restart(); return true
    }
    private val mutable = MutableStateFlow(BaoziDetailState())
    val detail = mutable.asStateFlow()
    private var work: Job? = null
    private var revision = 0L
    private var visible = false
    init {
        viewModelScope.launch {
            session.baoziAccount(app).state.map { it.recoveryVersion }.distinctUntilChanged().drop(1).collect {
                if (visible && active()) {
                    if (detail.value.error != null && detail.value.notice == null) refreshDetail()
                    val result = feed.state.value
                    if (!result.busy && result.notice == null) {
                        if (result.appendError != null) feed.more()
                        else if (result.error != null) feed.refresh()
                    }
                }
            }
        }
    }
    fun start() {
        visible = true
        session.baoziAccount(app).retryRecovery()
        if (mangaId != null && !detail.value.attempted) refreshDetail()
        else if (mangaId != null && !detail.value.busy) work = viewModelScope.launch {
            try { updateLocal() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) mutable.value = detail.value.copy(notice = "本机状态读取失败，可刷新重试") }
        }
    }
    private suspend fun updateLocal() {
        val id = mangaId ?: return
        val state = data.localData {
            if (!active()) throw CancellationException()
            (it.onlineBook(sourceId, id)?.favorite ?: false) to it.onlineProgress(sourceId, id)
        }
        currentCoroutineContext().ensureActive(); if (!active()) throw CancellationException()
        mutable.value = detail.value.copy(favorite = state.first, hasProgress = state.second != null, progress = state.second)
    }
    fun favorite() {
        val book = detail.value.book ?: return
        if (!active() || detail.value.saving) return
        val target = !detail.value.favorite
        mutable.value = detail.value.copy(saving = true, notice = null)
        viewModelScope.launch {
            try {
                library.remember(SourceManga(SourceMangaKey(SourceWorld.SFW, sourceId, book.card.key.mangaId), book.card.title, null, null, null), target)
                ensureActive(); if (active()) mutable.value = detail.value.copy(favorite = target, saving = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) mutable.value = detail.value.copy(saving = false, notice = "收藏状态未确认，请刷新核对后重试") }
        }
    }
    fun refreshDetail() {
        if (mangaId == null || !active() || detail.value.busy) return
        val ticket = ++revision
        mutable.value = detail.value.copy(attempted = true, busy = true, error = null, notice = null)
        work = viewModelScope.launch {
            try {
                if (detail.value.book == null) detailCache?.read()?.let { json ->
                    ensureActive(); if (!active() || ticket != revision) return@launch
                    mutable.value = detail.value.copy(book = baoziDetail(json, MangaKey(identity.world, sourceId, mangaId)))
                    updateLocal()
                }
                val book = source().detail(mangaId)
                ensureActive(); if (active() && ticket == revision) {
                    mutable.value = detail.value.copy(book = book, busy = false, attempted = true)
                    library.remember(SourceManga(SourceMangaKey(SourceWorld.SFW, sourceId, mangaId), book.card.title, null, null, null))
                    updateLocal()
                    detailCache?.write(detailJson(book))
                    detailCache?.information(detailInformation(book))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureActive(); if (active() && ticket == revision) mutable.value = detail.value.copy(busy = false, error = baoziFailure(failure))
            }
        }
    }
    fun cancelDetail() {
        revision++; work?.cancel(); work = null
        val old = detail.value
        mutable.value = old.copy(busy = false, notice = if (old.busy) "请求已取消，可重试加载" else old.notice)
    }
    fun leave() { visible = false; feed.cancel(); cancelDetail() }
}
