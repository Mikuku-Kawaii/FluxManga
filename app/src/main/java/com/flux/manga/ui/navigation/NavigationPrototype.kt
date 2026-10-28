package com.flux.manga.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.flux.manga.R
import com.flux.manga.traceMeasure
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.glass.*
import com.flux.manga.ui.pages.SampleDetailPage
import com.flux.manga.ui.pages.SampleReaderPage
import com.flux.manga.ui.pages.SampleRootPage
import com.flux.manga.ui.probe.FfiProbePage

@Composable
internal fun tabTitle(tab: MainTab): String = stringResource(when (tab) {
    MainTab.HOME -> R.string.tab_home
    MainTab.TAGS -> R.string.tab_tags
    MainTab.SEARCH -> R.string.tab_search
    MainTab.LIBRARY -> R.string.tab_library
    MainTab.DISCOVER -> R.string.tab_discover
})

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NavigationPrototype(navigation: PrototypeNavigation, coreStatus: String, session: WorldSession,
                        switchWorld: (Boolean, Offset?) -> Unit, motion: WorldMotion,
                        effects: DockEffects, samplePages: Boolean, changeSamplePages: (Boolean) -> Unit,
                        pickCbz: () -> Unit, pendingCbz: List<android.net.Uri>, consumeCbz: (List<android.net.Uri>) -> Unit,
                        updates: com.flux.manga.update.AppUpdateModel, installUpdate: () -> Unit, updatePermission: () -> Unit,
                        enhancementFailure: String?, onEnhancementFailure: (String) -> Unit,
                        feedbackState: com.flux.manga.ui.pages.RequestFeedbackState? = null,
                        changeEffects: (DockEffects) -> Unit) {
    val privacy = com.flux.manga.privacy.LocalPrivacyController.current
    val sessionState by session.state.collectAsState()
    val identity by session.identity.collectAsState()
    val preloaded = com.flux.manga.ui.pages.preloadedPages(session)
    com.flux.manga.ui.pages.BaoziSessionRecovery(session) { preloaded.baoziRecovered() }
    val currentRoute = navigation.currentStack.last()
    val routeMotion = rememberNavigationMotion(currentRoute, navigation.selectedTab)
    var previousRoute by remember(identity) { mutableStateOf<NavKey?>(null) }
    LaunchedEffect(currentRoute, preloaded) {
        // Account/configuration pages can change the meaning of an unchanged source ID.
        val changedSources = when (previousRoute) {
            OnlineSources, KomgaConfiguration, KomgaAccountImport, BaoziAccountSettings, CopyAccountSettings,
            JmSettingsRoute, PicaSettingsRoute, EhSettingsRoute, ExSettingsRoute, PortableBackupRoute -> true
            else -> false
        }
        if (changedSources && previousRoute != currentRoute) preloaded.configurationChanged()
        if (previousRoute != currentRoute && previousRoute in listOf(SpaceStorageSettings, PortableBackupRoute))
            preloaded.localDataChanged()
        previousRoute = currentRoute
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars
    val view = LocalView.current
    val platformBars = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.systemBars())
    // Compose starts with zero Insets. Measuring a restored list in that taller viewport
    // would clamp an end-of-list anchor before the real system bars arrive.
    val insetsReady = listOf(bars.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr),
        bars.getTop(density), bars.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr), bars.getBottom(density)) ==
        platformBars?.let { listOf(it.left, it.top, it.right, it.bottom) }
    // Only gate the first measurement. Immersive bar animations temporarily put Compose
    // and platform Insets out of sync; removing the reader here would restore the bars
    // in onDispose and repeatedly recreate the same page.
    var initialInsetsReceived by remember(view) { mutableStateOf(false) }
    if (insetsReady && !initialInsetsReceived) SideEffect { initialInsetsReceived = true }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val isRoot = navigation.currentStack.size == 1
    val glassLab = navigation.currentStack.last() == GlassLab
    val fullscreenReader = when (navigation.currentStack.last()) {
        is LocalBookReader, is KomgaReader, is CopyMangaReader, is BaoziReaderRoute,
        is JmReaderRoute, is PicaReaderRoute, is EhReaderRoute, is ExReaderRoute,
        is DownloadedReader, is PrivateOfflineReaderRoute -> true
        else -> false
    }
    val imeVisible = WindowInsets.isImeVisible
    val showDock = navigation.isPrimaryRoot && !imeVisible
    val minimumDockHeight = liquidDockHeight()
    var dockHeight by remember(density.density, density.fontScale) { mutableStateOf(minimumDockHeight) }
    val bottomClearance = if (showDock) dockHeight + 28.dp else 12.dp
    val palette = if (navigation.world == PrototypeWorld.SFW) GlassTokens.Light else GlassTokens.Dark
    var settingsOpen by remember { mutableStateOf(false) }
    var dockBounds by remember { mutableStateOf<Rect?>(null) }
    val scrolls = remember { mutableStateMapOf<MainTab, () -> Unit>() }
    val observeScroll: () -> Unit = { scrolls[navigation.selectedTab]?.invoke(); Unit }
    fun reportList(tab: MainTab, state: LazyListState) {
        scrolls[tab] = { state.firstVisibleItemIndex; state.firstVisibleItemScrollOffset; Unit }
    }
    BackHandler(enabled = isRoot && navigation.selectedTab != MainTab.HOME && !imeVisible) { navigation.back() }

    @Composable fun Shelf(section: ShelfSection? = null,
                          scrollOwner: MainTab = navigation.selectedTab, searchMode: Boolean = false, initialAuthor: String = "") {
        fun go(action: () -> Unit) { keyboard?.hide(); focus.clearFocus(); action() }
        com.flux.manga.ui.pages.ReferenceShelfPage(session, if (section == null && !searchMode) bottomClearance else 20.dp,
            open = { book -> go { if (book.online) navigation.openKomgaBook(book.key.sourceId, book.key.mangaId) else navigation.openLocalBook(book.key.mangaId) } },
            read = { book -> go { if (book.online) navigation.openKomgaReader(book.key.sourceId, book.key.mangaId, null) else navigation.openLocalReader(book.key.mangaId) } },
            addCbz = { go { navigation.openLocalLibrary(); pickCbz() } }, localLibrary = { go(navigation::openLocalLibrary) },
            onlineLibrary = { go(navigation::openOnlineLibrary) }, sources = { go(navigation::openSources) }, account = { go(navigation::openBaoziAccount) }, komgaAccount = { go(navigation::openKomgaImport) }, copyAccount = { go(navigation::openCopyAccount) }, search = { go(navigation::openSearch) },
            offline = { book -> go { navigation.openDownloadedBook(book.key.sourceId,book.key.mangaId) } }, downloads = navigation::openDownloads,
            settings = { go(navigation::openSettings) }, samples = { changeSamplePages(true); if (section != null || searchMode) navigation.back() },
            probe = navigation::openFfiProbe, glass = navigation::openGlassLab, failSwitch = { switchWorld(true, null) },
            list = { go { navigation.openShelf(it) } }, reportScroll = { scrolls[scrollOwner] = it },
            section = section, back = { if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else navigation.back() },
            switchError = sessionState.error, searchMode = searchMode, storage = navigation::openSpaceStorage,
            statistics = navigation::openReadingStatistics, exportRecords = navigation::openRecordExport, initialAuthor = initialAuthor)
    }
    val provider = entryProvider<NavKey> {
        entry<TabRoot> { root ->
            if (root.tab == MainTab.LIBRARY && !samplePages) {
                Shelf(scrollOwner = root.tab)
            } else if (root.tab == MainTab.TAGS) {
                com.flux.manga.ui.pages.SourceDiscoveryPage(session, true, bottomClearance,
                    navigation::openOnlineSearch, navigation::openSources,
                    navigation::openSettings, sessionState.error,
                    section = navigation::openDiscovery, openBook = navigation::openKomgaBook, openTag = navigation::openSourceTag, openTags = navigation::openUnifiedTag,
                    visit = { if (it.kind == "local") navigation.openLocalBook(it.mangaId) else navigation.openKomgaBook(it.sourceId, it.mangaId) }) { reportList(root.tab, it) }
            } else if (!samplePages) {
                com.flux.manga.ui.pages.SourceDiscoveryPage(session, false, bottomClearance,
                    navigation::openOnlineSearch, navigation::openSources,
                    navigation::openSettings, sessionState.error,
                    section = navigation::openDiscovery, openBook = navigation::openKomgaBook, openTag = navigation::openSourceTag, openTags = navigation::openUnifiedTag,
                    visit = { if (it.kind == "local") navigation.openLocalBook(it.mangaId) else navigation.openKomgaBook(it.sourceId, it.mangaId) }) { reportList(root.tab, it) }
            } else {
            SampleRootPage(root.tab, coreStatus, session, navigation::openDetail, navigation::openFfiProbe,
                navigation::openGlassLab, navigation::openLocalLibrary, bottomClearance) { reportList(root.tab, it) }
            }
        }
        entry<ShelfList> { Shelf(it.section) }
        entry<SourceTagResults> { route ->
            com.flux.manga.ui.pages.SourceTagBooksPage(session, route.sourceId, route.tag,
                navigation::back, navigation::openSources, navigation::openKomgaBook, route.label ?: route.tag)
        }
        entry<UnifiedTagResults> { route ->
            com.flux.manga.ui.pages.MixedTagBooksPage(session, route, navigation::back, navigation::openSources, navigation::openKomgaBook)
        }
        entry<TagResults> {
            com.flux.manga.ui.pages.LegacyTagPage(navigation::back, navigation::openTagDiscovery)
        }
        entry<DiscoveryList> { route ->
            if (route.section == DiscoverySection.VISITS) com.flux.manga.ui.pages.VisitsPage(session, navigation::back,
                { if (it.kind == "local") navigation.openLocalBook(it.mangaId) else navigation.openKomgaBook(it.sourceId, it.mangaId) }, navigation::openSources)
            else com.flux.manga.ui.pages.DiscoverySectionPage(session, route.section, navigation::back, navigation::openSources, navigation::openKomgaBook)
        }
        entry<BaoziBookRoute> { route ->
            com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) {
            com.flux.manga.ui.pages.BaoziPage(session, navigation::back, navigation::openKomgaBook, mangaId = route.mangaId, sourceId = route.sourceId, read = { navigation.openKomgaReader(route.sourceId, route.mangaId, it) }, openTag = navigation::openNamedTag, download = { navigation.openDownloadChapters(route.sourceId,route.mangaId) }, searchAuthor = navigation::openAuthorSearch)
            }
        }
        entry<BaoziReaderRoute> { route ->
            com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) {
            com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, route.chapterId, navigation::back)
            }
        }
        entry<CopyMangaBrowse> {
            com.flux.manga.ui.pages.CopyMangaPage(session, navigation::back, navigation::openCopyMangaBook, sources = navigation::openSources)
        }
        entry<CopyMangaBook> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) {
            com.flux.manga.ui.pages.CopyMangaPage(session, navigation::back, navigation::openCopyMangaBook, route.sourceId, route.mangaId, read = { navigation.openCopyMangaReader(route.sourceId, route.mangaId, it) }, openTag = navigation::openNamedTag, download = { navigation.openDownloadChapters(route.sourceId,route.mangaId) }, searchAuthor = navigation::openAuthorSearch)
        } }
        entry<CopyMangaReader> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) {
            com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, route.chapterId, navigation::back)
        } }
        entry<OnlineSearch> {
            com.flux.manga.ui.pages.OnlineSearchPage(session, navigation::back,
                navigation::openSources, navigation::openKomgaBook)
        }
        entry<AuthorSearch> { route ->
            if (route.world == navigation.world) {
                if (route.local) Shelf(searchMode = true, initialAuthor = route.name)
                else com.flux.manga.ui.pages.OnlineSearchPage(session, navigation::back,
                    navigation::openSources, navigation::openKomgaBook, initialQuery = "作者：${route.name}")
            }
        }
        entry<Search> {
            if (!samplePages) {
                Shelf(searchMode = true)
            } else Column {
                TextButton(onClick = navigation::back) { Text("返回") }
                SampleRootPage(MainTab.SEARCH, coreStatus, session, navigation::openDetail, navigation::openFfiProbe,
                    navigation::openGlassLab, navigation::openLocalLibrary, 12.dp) { }
            }
        }
        entry<DownloadedBook> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.DownloadedBookPage(session,route.sourceId,route.mangaId,navigation::back,
            { navigation.openDownloadedReader(route.sourceId,route.mangaId,it) }, { navigation.openDownloadChapters(route.sourceId,route.mangaId) },navigation::openDownloads) } }
        entry<DownloadedReader> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.OnlineReaderPage(session,route.sourceId,route.mangaId,route.chapterId,navigation::back,offlineOnly=true) } }
        entry<DownloadQueue> {
            if (navigation.world == PrototypeWorld.NSFW) com.flux.manga.ui.pages.PrivateDownloadsPage(session, back = navigation::back, read = navigation::openPrivateOfflineReader)
            else com.flux.manga.ui.pages.DownloadQueuePage(session,navigation::back,navigation::openDownloadedBook) { source,manga,chapter -> navigation.openDownloadedReader(source,manga,chapter) }
        }
        entry<PrivateDownloadsRoute> { route -> com.flux.manga.ui.pages.PrivateDownloadsPage(session, route.sourceId, route.mangaId, navigation::back, navigation::openPrivateOfflineReader) }
        entry<PrivateOfflineReaderRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, route.chapterId, navigation::back, offlineOnly = true) } }
        entry<DownloadChapters> { route -> com.flux.manga.ui.pages.DownloadChaptersPage(session,route.sourceId,route.mangaId,navigation::back,navigation::openDownloads) }
        entry<AppSettings> {
            com.flux.manga.ui.pages.SettingsPage(navigation::back,
                navigation::openAppearanceSettings, navigation::openSources, navigation::openAboutSettings, navigation::openReadingSettings, navigation::openLibrarySettings, navigation::openPrivacyMaintenance,
                session = session, effects = effects, downloads = navigation::openDownloads,
                history = { navigation.openDiscovery(DiscoverySection.VISITS) }, statistics = navigation::openReadingStatistics,
                storage = navigation::openDownloadStorage, startup = navigation::openStartupSettings)
        }
        entry<LibrarySettings> {
            com.flux.manga.ui.pages.LibrarySettingsPage(navigation::back,
                navigation::openDownloads, navigation::openReadingStatistics, navigation::openRecordExport, navigation::openSpaceStorage,
                session = session, localLibrary = if (navigation.world == PrototypeWorld.SFW) navigation::openLocalLibrary else null,
                onlineLibrary = navigation::openOnlineLibrary, protection = navigation::openDataProtection)
        }
        entry<PrivacyMaintenanceSettings> {
            com.flux.manga.ui.pages.PrivacyMaintenanceSettingsPage(navigation::back, navigation::openPrivacySettings,
                navigation::openDataProtection, navigation::openAppUpdates)
        }
        entry<DataProtection> { com.flux.manga.ui.pages.DataProtectionPage(session, navigation::back, navigation::openPortableBackup) }
        entry<PortableBackupRoute> { com.flux.manga.ui.pages.PortableBackupPage(session, navigation::back) }
        entry<PrivacySettings> {
            com.flux.manga.privacy.LocalPrivacyController.current?.let {
                com.flux.manga.ui.pages.PrivacySettingsPage(it, navigation::back)
            }
        }
        entry<AppearanceSettings> {
            com.flux.manga.ui.pages.AppearanceSettingsPage(effects, changeEffects, navigation::back, enhancementFailure)
        }
        entry<StartupSettings> { com.flux.manga.ui.pages.StartupSettingsPage(navigation::back) }
        entry<LibraryGroups> { androidx.compose.runtime.LaunchedEffect(Unit) { navigation.back() } }
        entry<DownloadStorageSettings> { com.flux.manga.ui.pages.DownloadStorageSettingsPage(navigation::back, navigation::openDownloads, navigation::openSpaceStorage) }
        entry<SpaceStorageSettings> { com.flux.manga.ui.pages.SpaceStoragePage(session, navigation::back, navigation::openDownloads) }
        entry<ReadingSettings> { com.flux.manga.ui.pages.ReadingSettingsPage(session, navigation::back) }
        entry<ReadingStatisticsRoute> { com.flux.manga.ui.pages.ReadingStatisticsPage(session, navigation::back) }
        entry<RecordExportRoute> { com.flux.manga.ui.pages.RecordExportPage(session, navigation::back) }
        entry<AppUpdates> { com.flux.manga.ui.pages.AppUpdatePage(updates, navigation::back, installUpdate, updatePermission) }
        entry<AboutSettings> { com.flux.manga.ui.pages.AboutSettingsPage(navigation::back, navigation::openLicenses, updates = navigation::openAppUpdates) {
            TextButton(onClick = {
                navigation.select(MainTab.LIBRARY)
                while (navigation.currentStack.size > 1) navigation.back()
                changeSamplePages(true)
            }) { Text("固定样本验证") }
            TextButton(onClick = navigation::openFfiProbe) { Text("FFI 验证") }
            TextButton(onClick = navigation::openGlassLab) { Text("玻璃实验") }
            TextButton(onClick = { switchWorld(true, null) }) { Text("模拟准备失败") }
        } }
        entry<OpenSourceLicenses> { com.flux.manga.ui.pages.OpenSourceLicensesPage(navigation::back, navigation::openLicenseEntry) }
        entry<LicenseEntryRoute> { route -> com.flux.manga.ui.pages.LicenseEntryPage(route.id, navigation::back, navigation::openLicenseDocument) }
        entry<LicenseDocumentRoute> { route -> com.flux.manga.ui.pages.LicenseDocumentPage(route.entryId, route.hash, navigation::back) }
        entry<FfiProbe> { FfiProbePage(back = navigation::back) }
        entry<OnlineSources> { com.flux.manga.ui.pages.SourceManagementPage(navigation.world == PrototypeWorld.SFW, navigation::back, navigation::openKomgaConfiguration, navigation::openBaoziAccount, navigation::openCopyAccount, navigation::openJmSettings, navigation::openPicaSettings, navigation::openEhSettings, navigation::openExSettings,
            session = session) }
        entry<EhInteractionRoute> { route -> com.flux.manga.ui.pages.EhInteractionPage(session, route.sourceId, route.mangaId,
            route.favorites, navigation::back, { if (com.flux.manga.data.isExSource(route.sourceId)) navigation.openExSettings() else navigation.openEhSettings() }) }
        entry<GalleryTagsRoute> { route -> com.flux.manga.ui.pages.GalleryTagsPage(session,
            if (route.ex) com.flux.manga.data.GallerySite.EX else com.flux.manga.data.GallerySite.EH, navigation::back,
            { query -> if (route.ex) navigation.openExBrowse(false, query, route.sourceId) else navigation.openEhBrowse(false, query, route.sourceId) }) }
        entry<ExSettingsRoute> { com.flux.manga.ui.pages.EhSettingsPage(session, navigation::back, { navigation.openExBrowse(it) }, site = com.flux.manga.data.GallerySite.EX, tags = { navigation.openGalleryTags(true) }) }
        entry<ExBrowseRoute> { route -> com.flux.manga.ui.pages.EhBrowsePage(session, navigation::back, navigation::openExSettings,
            navigation::openKomgaBook, route.favorites, route.query, route.sourceId, site = com.flux.manga.data.GallerySite.EX, tags = { navigation.openGalleryTags(true, route.sourceId) }, library = navigation::openOnlineLibrary) }
        entry<ExBookRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.EhDetailPage(session, route.sourceId, route.mangaId, navigation::back,
            { navigation.openKomgaReader(route.sourceId, route.mangaId, "gallery") }, { navigation.openExBrowse(false, it, route.sourceId) }, site = com.flux.manga.data.GallerySite.EX, tags = { navigation.openGalleryTags(true, route.sourceId) }, interactions = { navigation.openEhInteraction(route.sourceId, route.mangaId, it) }, download = { navigation.openPrivateDownloads(route.sourceId, route.mangaId) }, searchAuthor = navigation::openAuthorSearch) } }
        entry<ExReaderRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, "gallery", navigation::back) } }
        entry<EhSettingsRoute> { com.flux.manga.ui.pages.EhSettingsPage(session, navigation::back, { navigation.openEhBrowse(it) }, tags = { navigation.openGalleryTags(false) }) }
        entry<EhBrowseRoute> { route -> com.flux.manga.ui.pages.EhBrowsePage(session, navigation::back, navigation::openEhSettings,
            navigation::openKomgaBook, route.favorites, route.query, route.sourceId, tags = { navigation.openGalleryTags(false, route.sourceId) }, library = navigation::openOnlineLibrary) }
        entry<EhBookRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.EhDetailPage(session, route.sourceId, route.mangaId, navigation::back,
            { navigation.openKomgaReader(route.sourceId, route.mangaId, "gallery") }, { navigation.openEhBrowse(false, it, route.sourceId) }, tags = { navigation.openGalleryTags(false, route.sourceId) }, interactions = { navigation.openEhInteraction(route.sourceId, route.mangaId, it) }, download = { navigation.openPrivateDownloads(route.sourceId, route.mangaId) }, searchAuthor = navigation::openAuthorSearch) } }
        entry<EhReaderRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, "gallery", navigation::back) } }
        entry<PicaSettingsRoute> { com.flux.manga.ui.pages.PicaSettingsPage(session, navigation::back, { kind, value -> navigation.openPicaList(kind, value) }) }
        entry<PicaBrowseRoute> { route -> com.flux.manga.ui.pages.PicaBrowsePage(session, navigation::back, navigation::openPicaSettings,
            navigation::openKomgaBook, navigation::openPicaList, route.kind, route.value, route.sourceId, library = navigation::openOnlineLibrary) }
        entry<PicaBookRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.PicaDetailPage(session, route.sourceId, route.mangaId, navigation::back,
            { navigation.openKomgaReader(route.sourceId, route.mangaId, it) }, { kind, value -> navigation.openPicaList(kind, value, route.sourceId) }, download = { navigation.openPrivateDownloads(route.sourceId, route.mangaId) }, searchAuthor = navigation::openAuthorSearch) } }
        entry<PicaReaderRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, route.chapterId, navigation::back) } }
        entry<JmSettingsRoute> { com.flux.manga.ui.pages.JmSettingsPage(session, navigation::back, navigation::openJmBrowse, navigation::openJmFavorites) }
        entry<JmBrowseRoute> { route -> com.flux.manga.ui.pages.JmBrowsePage(session, navigation::back, navigation::openJmSettings,
            navigation::openKomgaBook, route.favorites, route.query, source = route.sourceId, library = navigation::openOnlineLibrary) }
        entry<JmBookRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.JmDetailPage(session, route.sourceId, route.mangaId, navigation::back,
            { navigation.openKomgaReader(route.sourceId, route.mangaId, it) }, { navigation.openJmTag(route.sourceId, it) }, download = { navigation.openPrivateDownloads(route.sourceId, route.mangaId) }, searchAuthor = navigation::openAuthorSearch) } }
        entry<JmReaderRoute> { route -> com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) { com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, route.chapterId, navigation::back) } }
        entry<CopyAccountSettings> { com.flux.manga.ui.pages.CopyAccountPage(session, navigation::back, navigation::openKomgaBook, navigation::openOnlineLibrary) }
        entry<BaoziAccountSettings> { com.flux.manga.ui.pages.BaoziAccountPage(session, navigation::back, navigation::openKomgaBook, navigation::openOnlineLibrary) }
        entry<KomgaConfiguration> { com.flux.manga.ui.pages.KomgaSettingsPage(session, navigation::back, navigation::openKomga, navigation::openKomgaImport) }
        entry<KomgaAccountImport> { com.flux.manga.ui.pages.KomgaAccountPage(session, navigation::back, navigation::openKomgaConfiguration, navigation::openKomgaBook, navigation::openOnlineLibrary) }
        entry<OnlineLibrary> { com.flux.manga.ui.pages.OnlineLibraryPage(session, navigation::back,
            navigation::openKomgaBook, { source, id -> navigation.openKomgaReader(source, id, null) }, navigation::openSources) }
        entry<KomgaBrowse> { route ->
            com.flux.manga.ui.pages.KomgaContentPage(session, route.sourceId, back = navigation::back,
                open = { navigation.openKomgaBook(route.sourceId, it) }, sources = navigation::openKomgaConfiguration)
        }
        entry<KomgaBook> { route ->
            com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) {
                com.flux.manga.ui.pages.KomgaContentPage(session, route.sourceId, route.mangaId, navigation::back,
                    read = { navigation.openKomgaReader(route.sourceId, route.mangaId, it) }, openTag = navigation::openSourceTag, download = { navigation.openDownloadChapters(route.sourceId,route.mangaId) }, searchAuthor = navigation::openAuthorSearch)
            }
        }
        entry<KomgaReader> { route ->
            com.flux.manga.ui.pages.VisitedPage(session, "online", route.sourceId, route.mangaId) {
                com.flux.manga.ui.pages.OnlineReaderPage(session, route.sourceId, route.mangaId, route.chapterId, navigation::back)
            }
        }
        entry<GlassLab> { GlassLabPage(back = navigation::back) }
        entry<LocalLibrary> { com.flux.manga.ui.pages.LocalLibraryPage(session, back = navigation::back, open = navigation::openLocalBook, read = navigation::openLocalReader,
            pickCbz = pickCbz, pendingCbz = pendingCbz, consumeCbz = consumeCbz) }
        entry<LocalBookDetail> { route -> com.flux.manga.ui.pages.VisitedPage(session, "local", "cbz", route.mangaId) {
            com.flux.manga.ui.pages.LocalLibraryPage(session, route.mangaId, navigation::back, navigation::openLocalBook, navigation::openLocalReader,
                pickCbz = pickCbz, readBookmark = navigation::openLocalBookmark, searchAuthor = navigation::openLocalAuthorSearch)
        } }
        entry<LocalBookReader> { route -> com.flux.manga.ui.pages.VisitedPage(session, "local", "cbz", route.mangaId) {
            com.flux.manga.ui.pages.LocalReaderPage(session, route.mangaId, navigation::back, route.bookmarkId)
        } }
        entry<MangaDetail> { detail -> SampleDetailPage(detail, session, navigation::back) { navigation.openReader(detail) } }
        entry<MangaReader> { reader -> SampleReaderPage(reader, session, navigation::back) }
    }
    val entriesByTab = MainTab.primary.associateWith { tab ->
        key(tab) {
            rememberDecoratedNavEntries(
                backStack = navigation.stacks.getValue(tab),
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                    rememberViewModelStoreNavEntryDecorator<NavKey>()), entryProvider = provider,
            )
        }
    }
    if (!insetsReady && !initialInsetsReceived) return
    val requestFeedback = remember(identity) {
        (feedbackState ?: com.flux.manga.ui.pages.RequestFeedbackState()).also { it.reset() }
    }
    com.flux.manga.ui.pages.RequestFeedbackHost(requestFeedback,
        active = sessionState.foreground && lifecycle.isAtLeast(Lifecycle.State.RESUMED),
        route = navigation.currentStack.lastOrNull(),
        motionEnabled = !effects.reduceMotion && !effects.lowEffects && android.animation.ValueAnimator.areAnimatorsEnabled()) {
    Scaffold(
        containerColor = if (fullscreenReader) com.flux.manga.ui.pages.ReaderBackground else palette.background,
        topBar = {
            if (isRoot && samplePages) Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .semantics { isTraversalGroup = true; traversalIndex = 0f }) {
                FlowRow {
                    if (navigation.selectedTab != MainTab.TAGS) TextButton(onClick = navigation::openSearch,
                        modifier = Modifier.testTag("open-search")) { Text("搜索") }
                    TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); settingsOpen = true },
                        modifier = Modifier.testTag("dock-settings")) { Text("显示效果") }
                    TextButton(onClick = { switchWorld(true, null) }, modifier = Modifier.testTag("switch-failure")) { Text("模拟准备失败") }
                    if (navigation.world == PrototypeWorld.SFW) TextButton(onClick = {
                        keyboard?.hide(); focus.clearFocus(); navigation.openSources()
                    }, modifier = Modifier.testTag("open-sources")) { Text("图源") }
                    if (navigation.world == PrototypeWorld.SFW) TextButton(onClick = {
                        keyboard?.hide(); focus.clearFocus(); navigation.openOnlineLibrary()
                    }) { Text("在线书库") }
                    if (samplePages) TextButton(onClick = { changeSamplePages(false) }, modifier = Modifier.testTag("local-mode")) { Text("返回书库界面") }
                }
                sessionState.error?.let { com.flux.manga.ui.pages.RequestFeedback(it) }
            }
        },
        modifier = Modifier.traceMeasure("Scaffold").semantics { isTraversalGroup = true },
        contentWindowInsets = if (glassLab || fullscreenReader) WindowInsets(0) else WindowInsets.safeDrawing,
    ) { padding ->
        // Keep the content slot stable when toggling effects: navigation/page state is not recreated.
        GlassBackdropHost(
            modifier = Modifier.fillMaxSize().testTag("navigation-host"), sourceKey = identity,
            enabled = showDock && !motion.pending && effects.backdropEnabled && sessionState.foreground && lifecycle.isAtLeast(Lifecycle.State.RESUMED),
            blur = effects.blurEnabled, saturation = effects.saturationEnabled,
            motionPadding = with(density) { 64.dp.toPx() },
            sampleBounds = dockBounds, background = if (fullscreenReader) com.flux.manga.ui.pages.ReaderBackground else palette.background,
            observeContentChanges = observeScroll,
            content = {
                val pageMotion = !effects.reduceMotion && !effects.lowEffects && !motion.pending &&
                    android.animation.ValueAnimator.areAnimatorsEnabled() && sessionState.foreground &&
                    sessionState.phase == com.flux.manga.session.SwitchPhase.Stable && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
                CompositionLocalProvider(com.flux.manga.ui.pages.LocalReferenceMotionEnabled provides pageMotion) {
                NavDisplay(entries = entriesByTab.getValue(navigation.selectedTab), onBack = {
                    if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else navigation.back()
                },
                    transitionSpec = { com.flux.manga.ui.pages.referencePageTransition(pageMotion,
                        kind = routeMotion.kind, tabDirection = routeMotion.tabDirection) },
                    popTransitionSpec = { com.flux.manga.ui.pages.referencePageTransition(pageMotion, back = true,
                        kind = routeMotion.kind, tabDirection = routeMotion.tabDirection) },
                    predictivePopTransitionSpec = { com.flux.manga.ui.pages.referencePageTransition(pageMotion, back = true,
                        kind = if (fullscreenReader) com.flux.manga.ui.pages.ReferenceTransitionKind.Reader
                            else com.flux.manga.ui.pages.ReferenceTransitionKind.Page) },
                    modifier = when {
                        fullscreenReader -> Modifier.fillMaxSize().background(com.flux.manga.ui.pages.ReaderBackground).imePadding()
                        glassLab -> Modifier.fillMaxSize().padding(bottom = if (showDock)
                            bottomClearance + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() else 0.dp)
                        isRoot -> Modifier.fillMaxSize().background(palette.background)
                            .padding(padding).consumeWindowInsets(padding).imePadding()
                        // Root lists reserve their own scrollable clearance. Child pages reserve it here.
                        else -> Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                            .padding(bottom = if (showDock) bottomClearance else 0.dp)
                    }.traceMeasure("Navigation").semantics { isTraversalGroup = true; traversalIndex = 1f })
                }
            },
            overlay = { backgroundGraphicsLayer, filtered, origin, host ->
                if (showDock && !motion.pending) key(identity) { LiquidBottomBar(navigation.world, navigation.selectedTab,
                    onSelect = { keyboard?.hide(); focus.clearFocus(); navigation.select(it) },
                    onSwitch = { switchWorld(false, null) }, motion = motion,
                    longPressToEnter = privacy?.longPress == true, switchEnabled = privacy?.busy != true,
                    onSwitchAt = { switchWorld(false, it) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(bottom = 12.dp),
                    effects = if (sessionState.foreground && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) effects else effects.copy(lowEffects = true),
                    enhancementFailure = enhancementFailure, onEnhancementFailure = onEnhancementFailure,
                    source = backgroundGraphicsLayer, filtered = filtered, filteredOrigin = origin, host = host,
                    observeContentChanges = observeScroll,
                    positioned = { local ->
                        if (host?.isAttached == true) dockBounds = Rect(host.localPositionOf(local, Offset.Zero),
                            Size(local.size.width.toFloat(), local.size.height.toFloat()))
                    }, onHeightChanged = { dockHeight = it }) }
            },
        )
    }
    if (settingsOpen) DockEffectsDialog(effects, changeEffects, enhancementFailure) { settingsOpen = false }
    }
}
