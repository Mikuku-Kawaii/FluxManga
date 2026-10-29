package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private class KomgaConfigurationChanged : Exception()

internal fun komgaFailure(error: Exception): String = when (error) {
    is KomgaConfigurationChanged -> "图源配置已变更或尚未保存，请返回图源设置重新进入"
    is SourceException.AuthenticationRequired -> "认证失败，请返回图源设置更新认证信息"
    is SourceException.Timeout -> "请求超时，可以重试"
    is SourceException.Transport -> "无法连接服务，请检查网络、服务地址与证书"
    is SourceException.RateLimited -> "服务请求过于频繁，请稍后重试"
    is SourceException.NotFound -> "服务上未找到此作品"
    is SourceException.InvalidResponse -> "服务响应无法识别，请核对 Komga 版本"
    is SourceException.InvalidRequest -> "服务地址或请求无效，请检查配置"
    is SourceException.HttpStatus -> "服务暂不可用（HTTP ${error.status}）"
    else -> "读取或保存失败，已有配置与书库保留，请重试"
}

internal class KomgaSettingsModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val store = KomgaSettingsStore(WorldDataStore(app))
    // Credentials intentionally live only in this ViewModel, never SavedStateHandle or a route.
    var address by mutableStateOf(""); private set
    var apiKey by mutableStateOf(""); private set
    var auth by mutableStateOf(KomgaAuth.API_KEY); private set
    var username by mutableStateOf(""); private set
    fun auth(value: KomgaAuth) { if (!busy) { auth = value; apiKey = "" } }
    fun username(value: String) { if (!busy) username = value.take(256) }
    var savedAddress by mutableStateOf<String?>(null); private set
    var sourceId by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var loaded by mutableStateOf(false); private set
    private var work: Job? = null
    private var saved: KomgaConfiguration? = null
    fun address(value: String) { if (!busy) address = value.take(2048) }
    fun apiKey(value: String) { if (!busy) apiKey = value.take(4096) }
    private fun active() { check(identity.world == PrototypeWorld.SFW); if (!session.accepts(identity)) throw CancellationException() }
    fun load() {
        if (loaded || busy) return
        busy = true
        work = viewModelScope.launch {
            try {
                active()
                val value = store.loadRecord()
                ensureActive(); active()
                saved = value; savedAddress = value?.baseUrl; sourceId = value?.takeIf { it.hasCredential }?.sourceId
                if (address.isBlank()) { address = value?.baseUrl.orEmpty(); auth = value?.auth ?: KomgaAuth.API_KEY; username = value?.username.orEmpty() }
                loaded = true; message = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = komgaFailure(error) }
            finally { busy = false }
        }
    }
    fun save() {
        if (busy || !loaded) return
        val base = try { normalizeKomgaUrl(address) } catch (error: IllegalArgumentException) { message = error.message; return }
        val token = apiKey.ifBlank { saved?.takeIf { it.baseUrl == base && it.auth == auth && it.username == username }?.apiKey.orEmpty() }
        if (token.isEmpty() || token.any { it == '\r' || it == '\n' } || (auth == KomgaAuth.BASIC && (username.isBlank() || ':' in username))) {
            message = "请输入有效的认证信息；修改地址或认证方式后需重新输入"; return
        }
        busy = true; message = "正在验证连接…"
        work = viewModelScope.launch {
            try {
                active()
                val candidate = KomgaConfiguration("komga-${UUID.randomUUID()}", base, token, auth, username)
                val user = WorldKomgaClient(session, candidate).use { it.accountId() }
                ensureActive(); active()
                saving = true; message = "连接成功，正在加密保存…"
                // Finish an already-started commit even if the settings entry is popped.
                // The store still checks the captured WorldSession before writing.
                val configuration = withContext(NonCancellable) {
                    store.accounts.activate(candidate, user) { session.accepts(identity) }.also {
                        session.invalidateKomgaAccount()
                        session.komgaSources.close()
                    }
                }
                ensureActive(); active()
                saved = configuration; sourceId = configuration.sourceId; savedAddress = base
                address = base; apiKey = ""; message = "连接已验证，配置已加密保存"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (saving) loaded = false; message = komgaFailure(error) }
            finally { busy = false; saving = false }
        }
    }
    suspend fun connectWebSession(token: String) {
        if (busy || !loaded) throw java.io.IOException("请等待当前连接操作完成")
        val base = normalizeKomgaUrl(address)
        busy = true
        try {
            active()
            val candidate = KomgaConfiguration("komga-${UUID.randomUUID()}", base, token, KomgaAuth.SESSION)
            val user = WorldKomgaClient(session, candidate).use { it.accountId() }
            currentCoroutineContext().ensureActive(); active()
            saving = true
            val configuration = withContext(NonCancellable) {
                store.accounts.activate(candidate, user) { session.accepts(identity) }.also {
                    session.invalidateKomgaAccount(); session.komgaSources.close()
                }
            }
            currentCoroutineContext().ensureActive(); active()
            saved = configuration; sourceId = configuration.sourceId; savedAddress = base
            address = base; auth = KomgaAuth.SESSION; username = configuration.username; apiKey = ""
            message = "网页账号已验证并加密保存"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (saving) loaded = false; throw java.io.IOException(komgaFailure(error)) }
        finally { busy = false; saving = false }
    }
    fun cancel() {
        if (saving) return
        work?.cancel(); message = "已取消，输入保留"
    }
    fun logout() {
        val current = saved ?: return
        if (busy || !loaded || !current.hasCredential) return
        busy = true; saving = true
        work = viewModelScope.launch {
            try {
                store.accounts.logout(current) { session.accepts(identity) }
                session.invalidateKomgaAccount(); session.komgaSources.close()
                saved = current.withoutCredential(); sourceId = null; apiKey = ""
                message = "已退出，已导入书目、进度及任务保留；重新验证同一账号后可继续"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { loaded = false; message = komgaFailure(error) }
            finally { busy = false; saving = false }
        }
    }
    fun leave() { loaded = false; apiKey = ""; if (!saving) work?.cancel() }
    override fun onCleared() { apiKey = ""; saved = null }
}

internal data class KomgaContentState(
    val cards: List<MangaCard> = emptyList(), val detail: SourceDetail? = null,
    val chapters: List<SourceChapter> = emptyList(),
    val next: SourceContinuation = SourceContinuation.End,
    val busy: Boolean = false, val loaded: Boolean = false,
    val hasProgress: Boolean = false, val progress: OnlineReadingProgress? = null,
    val favorite: Boolean = false, val savingFavorite: Boolean = false,
    val error: String? = null, val appendError: String? = null, val notice: String? = null,
    val chapterTotal: Long? = null,
)

internal class KomgaContentModel(app: Application, private val session: WorldSession,
    private val sourceId: String, private val mangaId: String?, private val savedState: SavedStateHandle,
    private val manualSearch: Boolean = false
) : AndroidViewModel(app) {
    private val blacklist = TagBlacklistFilter(app, session)
    private val identity = session.identity.value
    private val detailCache = mangaId?.let { DetailPageCache(app, session, sourceId, it) }
    private val store = KomgaSettingsStore(WorldDataStore(app))
    val library = OnlineLibrary(app, session)
    private val mutableState = MutableStateFlow(KomgaContentState())
    val state = mutableState.asStateFlow()
    var query by mutableStateOf(savedState.get<String>("komga-query").orEmpty()); private set
    var submitted by mutableStateOf(savedState.get<String>("komga-submitted").orEmpty()); private set
    val isBrowsingAll: Boolean get() = submitted.isBlank()
    private var work: Job? = null
    fun query(value: String) { query = value.take(200); savedState["komga-query"] = query }
    private fun active() {
        if (identity.world != PrototypeWorld.SFW || !session.accepts(identity)) throw CancellationException()
    }
    private suspend fun client(): WorldKomgaClient {
        active()
        val config = store.load()
        currentCoroutineContext().ensureActive(); active()
        if (config == null || config.sourceId != sourceId) throw KomgaConfigurationChanged()
        return session.komgaSources.get(sourceId) ?: session.komgaSources.configure(config)
    }
    fun start() {
        if (manualSearch && mangaId == null) return // Restored text is not a fresh search submission.
        if (!state.value.loaded && !state.value.busy) fetch(false)
        else if (mangaId != null) work = viewModelScope.launch {
            try { updateProgress() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Existing content remains usable; retry refresh also retries progress. */ }
        }
    }
    private suspend fun updateProgress() {
        val id = mangaId ?: return
        val progress = WorldDataStore(getApplication<Application>()).localData { it.onlineProgress(sourceId, id) }
        currentCoroutineContext().ensureActive(); active()
        val book = library.book(sourceId, id)
        mutableState.value = state.value.copy(hasProgress = progress != null, progress = progress, favorite = book?.favorite == true)
    }
    fun favorite() {
        val manga = state.value.detail?.manga ?: return
        if (state.value.savingFavorite) return
        val target = !state.value.favorite
        mutableState.value = state.value.copy(savingFavorite = true)
        viewModelScope.launch {
            try {
                val book = library.remember(manga, target)
                ensureActive(); active()
                mutableState.value = state.value.copy(favorite = book.favorite,
                    notice = if (book.favorite) "已收藏到在线书库" else "已取消收藏，阅读进度保留")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(notice = "收藏状态保存失败，请刷新后重试") }
            finally { mutableState.value = state.value.copy(savingFavorite = false) }
        }
    }
    fun search() {
        if (state.value.busy && submitted == query.trim()) return
        work?.cancel()
        submitted = query.trim(); savedState["komga-submitted"] = submitted
        mutableState.value = KomgaContentState()
        fetch(false)
    }
    fun refresh() { if (!state.value.busy) fetch(false) }
    fun more() { if (!state.value.busy && state.value.next is SourceContinuation.Next) fetch(true) }
    private fun fetch(append: Boolean) {
        val request = if (append) (state.value.next as? SourceContinuation.Next)?.request ?: return else SourcePageRequest.Start
        mutableState.value = state.value.copy(busy = true, error = null, appendError = null, notice = null)
        work = viewModelScope.launch {
            try {
                val client = client()
                if (mangaId == null) {
                    val result = blacklist.apply(client.search(submitted, request))
                    ensureActive(); active()
                    mutableState.value = state.value.copy(cards = (if (append) state.value.cards + result.cards else result.cards)
                        .distinctBy { it.key }, next = result.continuation, busy = false, loaded = true)
                } else {
                    val key = SourceMangaKey(SourceWorld.SFW, sourceId, mangaId)
                    if (!append) {
                        if (state.value.detail == null) detailCache?.read()?.let { json ->
                            ensureActive(); active()
                            val cached = sourceDetail(json.getJSONObject("detail"), identity.sourceContext(sourceId))
                            mutableState.value = state.value.copy(detail = cached, chapters = sourceChapters(json, key),
                                next = continuation(json), chapterTotal = chapterTotal(json), loaded = true)
                            updateProgress()
                        }
                        val detail = client.detail(key)
                        ensureActive(); active()
                        mutableState.value = state.value.copy(detail = detail)
                        library.remember(detail.manga)
                        updateProgress()
                        detailCache?.information(detailInformation(detail))
                    }
                    val result = client.chapters(key, request)
                    ensureActive(); active()
                    mutableState.value = state.value.copy(chapters = (if (append) state.value.chapters + result.items else result.items)
                        .distinctBy { it.id }, next = result.continuation, busy = false, loaded = true, chapterTotal = result.total?.toLong())
                    state.value.detail?.let { detail -> detailCache?.write(chaptersJson(state.value.chapters, state.value.next, state.value.chapterTotal)
                        .put("detail", detailJson(detail))) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive(); active()
                mutableState.value = if (append) state.value.copy(busy = false, appendError = komgaFailure(error))
                    else state.value.copy(busy = false, error = komgaFailure(error))
            }
        }
    }
    fun cancel() {
        work?.cancel()
        mutableState.value = state.value.copy(busy = false, notice = "请求已取消")
    }
    fun leave() { work?.cancel(); mutableState.value = state.value.copy(busy = false) }
}
