package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.DiscoverySection
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

/** Session-owned read models. No hidden page composition, visit writes or reader downloads. */
internal class PreloadedPages(private val app: Application, private val session: WorldSession) : ViewModel() {
    private val identity = session.identity.value
    private val localStore = ViewModelStore()
    private var networkStore = ViewModelStore()
    val revision = MutableStateFlow(0)
    private var watching: Job? = null
    private var secondary: Job? = null
    private var configuration: SourceDiscoveryModel? = null
    private val feeds = mutableMapOf<Pair<String?, DiscoverySection>, MixedDiscoveryModel>()
    private val directories = mutableMapOf<String?, UnifiedTagsModel>()
    private var privateDirectory: PrivateTagsModel? = null
    private var dictionary: GalleryTagsModel? = null
    private var recentVisits: VisitsModel? = null
    private val searches = mutableMapOf<String?, AggregateSearchModel>()
    private val coverWarmers = mutableMapOf<MixedDiscoveryModel, Job>()
    private var running = false
    private var configurationFingerprint: String? = null
    private var configurationCheck: Job? = null

    private inline fun <reified T : ViewModel> obtain(store: ViewModelStore, key: String, crossinline create: () -> T): T =
        ViewModelProvider.create(object : ViewModelStoreOwner { override val viewModelStore = store },
            viewModelFactory { initializer { create() } })[key, T::class]

    fun discovery(): SourceDiscoveryModel = configuration ?: obtain(networkStore, "configuration") {
        SourceDiscoveryModel(app, session)
    }.also { configuration = it }
    fun mixed(komga: String?, section: DiscoverySection): MixedDiscoveryModel {
        // Recommendations never depend on Komga configuration: start them immediately at launch.
        val source = if (section == DiscoverySection.RECOMMENDATIONS) null else komga
        val model = feeds.getOrPut(source to section) {
            obtain(networkStore, "feed:$source:$section") { MixedDiscoveryModel(app, session, source, section) }
        }
        if (running && model !in coverWarmers) coverWarmers[model] = viewModelScope.launch {
            model.feed.state.map { mixedCards(model.feed.sources, it.results).take(3).map { it.key } }
                .distinctUntilChanged().collectLatest { keys -> coroutineScope {
                    keys.forEach { key -> launch {
                        try { model.library.cover(key.sourceId, key.mangaId) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        // Optional warming never records a failed image as loaded. The visible
                        // cover retains its existing error/retry flow if fetching still fails.
                        catch (_: Exception) { }
                    } }
                } }
        }
        return model
    }
    fun tags(komga: String?): UnifiedTagsModel = directories.getOrPut(komga) {
        obtain(networkStore, "tags:$komga") { UnifiedTagsModel(app, session, komga) }
    }
    fun privateTags(): PrivateTagsModel = privateDirectory ?: obtain(networkStore, "private-tags") {
        PrivateTagsModel(app, session)
    }.also { privateDirectory = it }
    fun galleryTags(): GalleryTagsModel = dictionary ?: obtain(localStore, "gallery-tags") {
        GalleryTagsModel(app, session)
    }.also { dictionary = it }
    fun shelf(): ShelfViewModel = obtain(localStore, "shelf") { ShelfViewModel(app, session, SavedStateHandle()) }
    fun groups(): TagsViewModel = obtain(localStore, "groups") { TagsViewModel(app, session) }
    fun visits(): VisitsModel = recentVisits ?: obtain(localStore, "visits") {
        VisitsModel(app, session, 20)
    }.also { recentVisits = it }
    fun search(komga: String?): AggregateSearchModel = searches.getOrPut(komga) {
        obtain(networkStore, "search:$komga") { AggregateSearchModel(app, session, komga) }
    }
    fun searchHistory(): SearchHistoryModel = obtain(localStore, "search-history") {
        SearchHistoryModel(app, session, SavedStateHandle())
    }
    fun reading(): ReadingSettingsModel = obtain(localStore, "reading-settings") {
        ReadingSettingsModel(app, session, SavedStateHandle())
    }
    fun statistics(): ReadingStatisticsModel = obtain(localStore, "statistics") {
        ReadingStatisticsModel(app, session, SavedStateHandle())
    }
    fun storage(): SpaceStorageModel = obtain(localStore, "storage") { SpaceStorageModel(app, session) }

    fun start() {
        if (running || !session.accepts(identity) || !session.state.value.foreground) return
        running = true
        // Returning to the foreground keeps yesterday's cards visible while each source updates.
        feeds.values.forEach { model -> model.feed.state.value.results.filterValues { it.loaded }.keys.forEach(model.feed::refresh) }
        if (configurationFingerprint == null && configurationCheck?.isActive != true) configurationCheck = viewModelScope.launch {
            try { configurationFingerprint = catalogConfiguration(com.flux.manga.data.WorldDataStore(app), session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Discovery/settings retain their own visible storage error. */ }
        }
        // Begin the homepage before any destination is composed or system Insets have arrived.
        mixed(null, DiscoverySection.RECOMMENDATIONS).start()
        discovery().start()
        watching = viewModelScope.launch {
            discovery().state.filter { it.status != DiscoveryStatus.LOADING }.collect { config ->
                if (!running || !session.accepts(identity)) return@collect
                mixed(config.sourceId, DiscoverySection.RECOMMENDATIONS).start()
                mixed(config.sourceId, DiscoverySection.UPDATES).start()
                if (identity.world == PrototypeWorld.SFW) tags(config.sourceId).start()
                else {
                    privateTags().start()
                    val selection = com.flux.manga.data.SourceSelection(app, identity.world)
                    if (selection.enabled(com.flux.manga.data.SourceKind.EH) || selection.enabled(com.flux.manga.data.SourceKind.EX)) galleryTags().start()
                }
            }
        }
        // Local reads run independently; a slow/unconfigured source never delays the app shell.
        shelf(); visits().refresh(silent = true); searchHistory(); reading()
        secondary = viewModelScope.launch {
            val config = discovery().state.first { it.status != DiscoveryStatus.LOADING }
            // Defer disk inventory/statistics until the home request has settled, including failure.
            val recommendations = mixed(config.sourceId, DiscoverySection.RECOMMENDATIONS)
            recommendations.feed.state.first {
                recommendations.feed.sources.isEmpty() || (it.results.isNotEmpty() && !it.busy)
            }
            if (running && session.accepts(identity)) { statistics().start(); storage() }
        }
    }

    fun pause() {
        running = false
        // Stopping page warmers is not a cache invalidation. WorldSession owns privacy revocation.
        watching?.cancel(); watching = null
        secondary?.cancel(); secondary = null
        coverWarmers.values.forEach { it.cancel() }; coverWarmers.clear()
        configuration?.leave()
        feeds.values.forEach { it.feed.pause() }
        directories.values.forEach { it.leave() }
        privateDirectory?.leave()
        dictionary?.stop() // Existing privacy boundary: no retained tag index in background.
        recentVisits?.leave()
        searches.values.forEach { it.search.pause() }
    }

    fun localDataChanged() {
        visits().refresh(); searchHistory().refresh(); shelf().refresh()
        reading().load(); statistics().refresh(); storage().refresh()
    }

    fun baoziRecovered() {
        if (!running || !session.accepts(identity)) return
        // Pending reads replay themselves. Only retry already-failed Baozi feeds, preserving other sources.
        (feeds.values.map { it.feed } + searches.values.map { it.search }).forEach { controller ->
            controller.state.value.results.forEach { (source, result) ->
                if (com.flux.manga.data.isBaoziSource(source) && !result.busy && result.notice == null) {
                    if (result.appendError != null) controller.more(source)
                    else if (result.error != null) controller.refresh(source)
                }
            }
        }
    }

    /** Merely opening a settings page must not throw away loaded feeds. */
    fun configurationChanged() {
        val previousCheck = configurationCheck
        configurationCheck = viewModelScope.launch {
            previousCheck?.join()
            val fingerprint = try { catalogConfiguration(com.flux.manga.data.WorldDataStore(app), session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (fingerprint != null && fingerprint == configurationFingerprint) return@launch
            configurationFingerprint = fingerprint
            resetNetwork()
        }
    }

    fun sourceSelectionChanged() {
        configurationCheck?.cancel(); configurationCheck = null
        configurationFingerprint = null
        resetNetwork()
    }

    private fun resetNetwork() {
        val resume = running
        pause()
        networkStore.clear(); networkStore = ViewModelStore()
        configuration = null; feeds.clear(); directories.clear(); searches.clear(); privateDirectory = null
        revision.value++
        if (resume) start()
        shelf().refresh()
    }

    override fun onCleared() {
        pause(); networkStore.clear(); localStore.clear()
    }
}

internal fun preloadedPages(app: Application, session: WorldSession): PreloadedPages {
    val identity = session.identity.value
    return ViewModelProvider.create(session.pageOwner, viewModelFactory {
        initializer { PreloadedPages(app, session) }
    })["preloaded-pages:${identity.world}:${identity.generation}", PreloadedPages::class]
}

@Composable
internal fun preloadedPages(session: WorldSession): PreloadedPages {
    val app = LocalContext.current.applicationContext as Application
    val pages = remember(session.pageOwner, session.identity.value) { preloadedPages(app, session) }
    // Rebind visible consumers when configuration changes; metadata never enters saved state.
    pages.revision.collectAsState().value
    return pages
}

@Composable
internal fun PreloadSessionPages(session: WorldSession, enabled: Boolean) {
    // Preparing replaces pageOwner before committing the target world. Creating a model
    // there would bind it to the outgoing identity and leave its reads permanently revoked.
    // Leaving this composition pauses the existing owner without allocating a new one.
    if (!enabled) return
    val pages = preloadedPages(session)
    LaunchedEffect(pages) { pages.start() }
    DisposableEffect(pages) { onDispose { pages.pause() } }
}
