package com.flux.manga.ui.pages

import androidx.compose.runtime.*
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun copyMangaFailure(error: Exception): String = when (error) {
    is SourceException.LoginRejected -> if (error.reason == "client_restricted")
        "图源暂时限制内容访问（210），提示更新官方 App 并等待限制解除。已登录账号和书库保留，不需要反复登录。"
        else "图源拒绝本次请求（${error.code}）"
    is SourceException.AuthenticationRequired -> "请在图源管理中验证账号后重试。"
    is SourceException.InvalidResponse -> "图源响应无法识别，接口可能已变化，请稍后重试"
    is SourceException.NotFound -> "图源上未找到此作品"
    is SourceException.Timeout -> "请求超时，可以重试"
    is SourceException.Transport -> "无法连接拷贝漫画，请检查网络；当前服务地址可能不可用"
    is SourceException.RateLimited -> "请求过于频繁，请稍后重试"
    is SourceException.HttpStatus -> "图源暂不可用（HTTP ${error.status}）"
    is SourceException.InvalidRequest -> "请求无效，请检查搜索词或返回重新进入"
    else -> "加载失败，请重试"
}
internal data class CopyDetailState(val detail: SourceDetail? = null, val busy: Boolean = false,
    val attempted: Boolean = false, val error: String? = null, val notice: String? = null,
    val themes: List<CopyTheme>? = null, val groups: List<CopyChapterGroup>? = null, val hasProgress: Boolean = false, val progress: OnlineReadingProgress? = null,
    val favorite: Boolean = false, val saving: Boolean = false, val localReady: Boolean = false)

internal class CopyMangaModel(private val app: Application, private val session: WorldSession, private val saved: SavedStateHandle,
    private val mangaId: String? = null, private val manualSearch: Boolean = false, private val sourceId: String = CopyMangaEndpoint.SOURCE_ID) : ViewModel() {
    val covers = OnlineSourceCovers(app, session, sourceId)
    private val library = OnlineLibrary(app, session)
    private val data = WorldDataStore(app)
    private val identity = session.identity.value
    private val detailCache = mangaId?.let { DetailPageCache(app, session, sourceId, it) }
    var query by mutableStateOf(saved.get<String>("copy-query").orEmpty()); private set
    var submitted by mutableStateOf(saved.get<String>("copy-submitted").orEmpty()); private set
    fun query(value: String) { query = value.take(200); saved["copy-query"] = query }
    private var searchRequested = false
    private fun active() = identity.world == com.flux.manga.ui.navigation.PrototypeWorld.SFW && session.accepts(identity)
    private suspend fun client(): WorldCopyMangaClient = withContext(Dispatchers.IO) {
        if (!active()) throw CancellationException("Expired source session")
        copyMangaClient(session, sourceId)
    }
    val feed = DiscoveryFeedController(viewModelScope, ::active, ::copyMangaFailure) { page ->
        val client = client()
        if (submitted.isEmpty()) client.recommendations(page) else client.search(submitted, page)
    }
    fun search() {
        val value = query.trim()
        if (value.isEmpty()) return
        searchRequested = true
        submitted = value; saved["copy-submitted"] = value; feed.restart()
    }
    fun recommendations() { submitted = ""; saved["copy-submitted"] = ""; feed.restart() }
    val chapters = CopyChaptersController(viewModelScope, ::active,
        restore = { group -> detailCache?.read("chapters:$group")?.let { json ->
            CopyChaptersState(groupId = group, items = sourceChapters(json, SourceMangaKey(SourceWorld.SFW, sourceId, requireNotNull(mangaId))),
                next = continuation(json), loaded = true, total = chapterTotal(json))
        } },
        save = { value -> detailCache?.write(chaptersJson(value.items, value.next, value.total), "chapters:${value.groupId}") }
    ) { group, page ->
        client().chapters(requireNotNull(mangaId), group, page)
    }
    fun selectGroup(id: String) {
        if (detail.value.groups?.any { it.id == id } != true) return
        saved["copy-group"] = id; chapters.select(id)
    }
    private val mutableDetail = MutableStateFlow(CopyDetailState())
    val detail = mutableDetail.asStateFlow()
    private var work: Job? = null
    private var revision = 0L
    fun start() {
        if (mangaId == null) { if (!manualSearch || searchRequested) feed.start() }
        else if (!detail.value.attempted) refreshDetail()
        else {
            chapters.start()
            if (!detail.value.busy && !detail.value.saving) work = viewModelScope.launch {
                try { updateProgress() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableDetail.value = detail.value.copy(notice = "续读状态读取失败，可刷新重试") }
            }
        }
    }
    private suspend fun updateProgress() {
        val id = mangaId ?: return
        val (progress, book) = data.localData { if (!active()) throw CancellationException(); it.onlineProgress(sourceId, id) to it.onlineBook(sourceId, id) }
        currentCoroutineContext().ensureActive(); if (!active()) throw CancellationException()
        mutableDetail.value = detail.value.copy(hasProgress = progress != null, progress = progress, favorite = book?.favorite == true, localReady = true)
    }
    fun favorite() {
        val manga = detail.value.detail?.manga ?: return
        if (!active() || detail.value.saving || detail.value.busy || !detail.value.localReady) return
        val target = !detail.value.favorite
        mutableDetail.value = detail.value.copy(saving = true, notice = null)
        viewModelScope.launch {
            try {
                val book = library.remember(manga, target)
                ensureActive(); if (active()) mutableDetail.value = detail.value.copy(favorite = book.favorite, saving = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) mutableDetail.value = detail.value.copy(saving = false, localReady = false,
                notice = "收藏状态未确认，请刷新核对后重试") }
        }
    }
    fun refreshDetail() {
        if (mangaId == null || detail.value.busy || detail.value.saving || !active()) return
        val ticket = ++revision
        mutableDetail.value = detail.value.copy(busy = true, attempted = true, error = null, notice = null, localReady = false)
        work = viewModelScope.launch {
            try {
                if (detail.value.detail == null) detailCache?.read()?.let { json ->
                    ensureActive(); if (!active() || ticket != revision) return@launch
                    val cached = copyDetail(json, identity.sourceContext(sourceId))
                    mutableDetail.value = detail.value.copy(detail = cached.detail, themes = cached.themes, groups = cached.groups)
                    val group = cached.groups?.firstOrNull { it.id == saved.get<String>("copy-group") } ?: cached.groups?.firstOrNull()
                    chapters.select(group?.id)
                    updateProgress()
                }
                val result = client().book(mangaId)
                ensureActive(); if (!active() || ticket != revision) return@launch
                mutableDetail.value = detail.value.copy(detail = result.detail, themes = result.themes, groups = result.groups, attempted = true, busy = false)
                val chosen = result.groups?.firstOrNull { it.id == saved.get<String>("copy-group") } ?: result.groups?.firstOrNull()
                saved["copy-group"] = chosen?.id
                chapters.select(chosen?.id)
                library.remember(result.detail.manga)
                updateProgress()
                detailCache?.write(detailJson(result))
                detailCache?.information(detailInformation(result.detail))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive(); if (!active() || ticket != revision) return@launch
                mutableDetail.value = detail.value.copy(busy = false, error = copyMangaFailure(error))
            }
        }
    }
    fun cancelDetail() { stopDetail(true) }
    private fun stopDetail(explicit: Boolean) {
        revision++; work?.cancel(); work = null
        val old = detail.value
        mutableDetail.value = old.copy(busy = false,
            attempted = if (!explicit && old.busy && old.detail == null) false else old.attempted,
            notice = if (explicit && old.busy) "请求已取消，可重试加载" else old.notice)
    }
    fun leave() { feed.leave(); chapters.leave(); stopDetail(false) }
}
