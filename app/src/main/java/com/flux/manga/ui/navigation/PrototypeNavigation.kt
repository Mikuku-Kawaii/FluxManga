package com.flux.manga.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.rememberNavBackStack

@Serializable
enum class MainTab {
    HOME, LIBRARY, TAGS,
    // Serialized names from navigation v1; never render as bottom tabs.
    SEARCH, DISCOVER;
    companion object { val primary = listOf(HOME, LIBRARY, TAGS) }
}

internal fun MainTab.currentTab(): MainTab = when (this) {
    MainTab.SEARCH -> MainTab.LIBRARY
    MainTab.DISCOVER -> MainTab.HOME
    else -> this
}

// Each space owns its own navigation and persisted data.
@Serializable
enum class PrototypeWorld { SFW, NSFW }

@Serializable
data class TabRoot(val tab: MainTab) : NavKey

@Serializable
data object Search : NavKey

@Serializable
data object OnlineSearch : NavKey

@Serializable
data class AuthorSearch(val world: PrototypeWorld, val name: String, val local: Boolean = false) : NavKey

@Serializable
enum class DiscoverySection(val title: String) { RECOMMENDATIONS("图源推荐"), UPDATES("最近更新"), VISITS("最近观看") }
@Serializable
data class DiscoveryList(val section: DiscoverySection) : NavKey

@Serializable
enum class ShelfSection { ALL, HISTORY }
@Serializable
data class ShelfList(val section: ShelfSection) : NavKey
@Serializable
data class TagResults(val tagId: String) : NavKey

@Serializable
data class SourceTagResults(val world: PrototypeWorld, val sourceId: String, val tag: String, val label: String? = null) : NavKey

@Serializable
data class TagTarget(val sourceId: String, val id: String, val category: Boolean = false)
@Serializable
data class UnifiedTagResults(val world: PrototypeWorld, val label: String, val targets: List<TagTarget>) : NavKey

@Serializable
data object AppSettings : NavKey
@Serializable
data object LibrarySettings : NavKey
@Serializable
data object PrivacyMaintenanceSettings : NavKey
@Serializable
data object DataProtection : NavKey
@Serializable
data object PrivacySettings : NavKey
@Serializable
data object AppearanceSettings : NavKey
@Serializable
data object StartupSettings : NavKey
@Serializable
data object AboutSettings : NavKey
@Serializable
data object OpenSourceLicenses : NavKey
@Serializable
data class LicenseEntryRoute(val id: String) : NavKey
@Serializable
data class LicenseDocumentRoute(val entryId: String, val hash: String) : NavKey
@Serializable
data object AppUpdates : NavKey
@Serializable
data object ReadingSettings : NavKey
@Serializable
data object ReadingStatisticsRoute : NavKey
@Serializable
data object RecordExportRoute : NavKey
@Serializable
data object PortableBackupRoute : NavKey
@Serializable
data class LibraryGroups(val bookKey: String? = null) : NavKey
@Serializable
data object DownloadStorageSettings : NavKey
@Serializable
data object SpaceStorageSettings : NavKey

@Serializable
data object FfiProbe : NavKey

@Serializable
data object GlassLab : NavKey

@Serializable
data object LocalLibrary : NavKey

@Serializable
data object DownloadQueue : NavKey

@Serializable
data class PrivateDownloadsRoute(val sourceId: String, val mangaId: String) : NavKey
@Serializable
data class PrivateOfflineReaderRoute(val sourceId: String, val mangaId: String, val chapterId: String) : NavKey

@Serializable
data class DownloadChapters(val sourceId: String, val mangaId: String) : NavKey

@Serializable
data class DownloadedBook(val sourceId: String, val mangaId: String) : NavKey
@Serializable
data class DownloadedReader(val sourceId: String, val mangaId: String, val chapterId: String) : NavKey


@Serializable
data object OnlineSources : NavKey

@Serializable
data object JmSettingsRoute : NavKey
@Serializable
data class JmBrowseRoute(val favorites: Boolean = false, val query: String = "", val sourceId: String? = null) : NavKey
@Serializable
data class JmBookRoute(val sourceId: String, val mangaId: String) : NavKey
@Serializable
data class JmReaderRoute(val sourceId: String, val mangaId: String, val chapterId: String?) : NavKey

@Serializable
data object PicaSettingsRoute : NavKey
@Serializable
data class PicaBrowseRoute(val kind: String = "search", val value: String = "", val sourceId: String? = null) : NavKey
@Serializable
data class PicaBookRoute(val sourceId: String, val mangaId: String) : NavKey
@Serializable
data class PicaReaderRoute(val sourceId: String, val mangaId: String, val chapterId: String?) : NavKey

@Serializable
data class EhInteractionRoute(val sourceId: String, val mangaId: String, val favorites: Boolean) : NavKey

@Serializable
data object EhSettingsRoute : NavKey
@Serializable
data class GalleryTagsRoute(val ex: Boolean = false, val sourceId: String? = null) : NavKey
@Serializable
data class EhBrowseRoute(val favorites: Boolean = false, val query: String = "", val sourceId: String? = null) : NavKey
@Serializable
data class EhBookRoute(val sourceId: String, val mangaId: String) : NavKey
@Serializable
data class EhReaderRoute(val sourceId: String, val mangaId: String) : NavKey

@Serializable
data object ExSettingsRoute : NavKey
@Serializable
data class ExBrowseRoute(val favorites: Boolean = false, val query: String = "", val sourceId: String? = null) : NavKey
@Serializable
data class ExBookRoute(val sourceId: String, val mangaId: String) : NavKey
@Serializable
data class ExReaderRoute(val sourceId: String, val mangaId: String) : NavKey

@Serializable
data object KomgaConfiguration : NavKey

@Serializable
data object KomgaAccountImport : NavKey

@Serializable
data object CopyAccountSettings : NavKey

@Serializable
data object BaoziAccountSettings : NavKey

@Serializable
data object OnlineLibrary : NavKey

@Serializable
data class BaoziBookRoute(val sourceId: String, val mangaId: String) : NavKey

@Serializable
data class BaoziReaderRoute(val sourceId: String, val mangaId: String, val chapterId: String? = null) : NavKey

@Serializable
data object CopyMangaBrowse : NavKey

@Serializable
data class CopyMangaBook(val sourceId: String, val mangaId: String) : NavKey

@Serializable
data class CopyMangaReader(val sourceId: String, val mangaId: String, val chapterId: String? = null) : NavKey

@Serializable
data class KomgaBrowse(val sourceId: String) : NavKey

@Serializable
data class KomgaBook(val sourceId: String, val mangaId: String) : NavKey

@Serializable
data class KomgaReader(val sourceId: String, val mangaId: String, val chapterId: String? = null) : NavKey

@Serializable
data class LocalBookDetail(val mangaId: String) : NavKey

@Serializable
data class LocalBookReader(val mangaId: String, val bookmarkId: String? = null) : NavKey

@Serializable
data class MangaDetail(
    val tab: MainTab,
    val world: PrototypeWorld,
    val sourceId: String,
    val mangaId: String,
) : NavKey

@Serializable
data class MangaReader(
    val tab: MainTab,
    val world: PrototypeWorld,
    val sourceId: String,
    val mangaId: String,
    val chapterId: String,
) : NavKey

class PrototypeNavigation(
    selected: MutableState<String>,
    val world: PrototypeWorld,
    private val onSelect: (MainTab) -> Unit,
    val stacks: Map<MainTab, NavBackStack<NavKey>>,
) {
    private var selectedName by selected
    val selectedTab: MainTab get() = MainTab.entries.firstOrNull { it.name == selectedName }?.currentTab() ?: MainTab.HOME
    val currentStack: NavBackStack<NavKey> get() = stacks.getValue(selectedTab)
    val isPrimaryRoot: Boolean get() = currentStack.size == 1 && currentStack.last() == TabRoot(selectedTab) && selectedTab in MainTab.primary

    fun select(tab: MainTab) { selectedName = tab.currentTab().name; onSelect(tab.currentTab()) }

    fun openDetail(mangaId: String) {
        currentStack.add(MangaDetail(selectedTab, world, "sample", mangaId))
    }

    fun openSettings() { if (currentStack.last() != AppSettings) currentStack.add(AppSettings) }
    fun openLibrarySettings() { if (currentStack.last() == AppSettings) currentStack.add(LibrarySettings) }
    fun openPrivacyMaintenance() { if (currentStack.last() == AppSettings) currentStack.add(PrivacyMaintenanceSettings) }
    fun openDataProtection() { if (currentStack.last() in listOf(AppSettings, LibrarySettings, PrivacyMaintenanceSettings)) currentStack.add(DataProtection) }
    fun openPrivacySettings() { if (currentStack.last() in listOf(AppSettings, PrivacyMaintenanceSettings)) currentStack.add(PrivacySettings) }
    fun openAppearanceSettings() { if (currentStack.last() == AppSettings) currentStack.add(AppearanceSettings) }
    fun openStartupSettings() { if (currentStack.last() == AppSettings) currentStack.add(StartupSettings) }
    fun openLibraryGroups(bookKey: String? = null) { if (currentStack.last() != LibraryGroups(bookKey)) currentStack.add(LibraryGroups(bookKey)) }
    fun openDownloadStorage() { if (currentStack.last() != DownloadStorageSettings) currentStack.add(DownloadStorageSettings) }
    fun openSpaceStorage() { if (currentStack.last() != SpaceStorageSettings) currentStack.add(SpaceStorageSettings) }
    fun openReadingSettings() { if (currentStack.last() == AppSettings) currentStack.add(ReadingSettings) }
    fun openReadingStatistics() { if (currentStack.last() != ReadingStatisticsRoute) currentStack.add(ReadingStatisticsRoute) }
    fun openRecordExport() { if (currentStack.last() != RecordExportRoute) currentStack.add(RecordExportRoute) }
    fun openPortableBackup() { if (currentStack.last() == DataProtection) currentStack.add(PortableBackupRoute) }
    fun openAppUpdates() { if (currentStack.last() in listOf(AppSettings, AboutSettings, PrivacyMaintenanceSettings)) currentStack.add(AppUpdates) }
    fun openAboutSettings() { if (currentStack.last() == AppSettings) currentStack.add(AboutSettings) }
    fun openLicenses() { if (currentStack.last() == AboutSettings) currentStack.add(OpenSourceLicenses) }
    fun openLicenseEntry(id: String) { if (currentStack.last() == OpenSourceLicenses) currentStack.add(LicenseEntryRoute(id)) }
    fun openLicenseDocument(hash: String) {
        val entry = currentStack.last() as? LicenseEntryRoute ?: return
        currentStack.add(LicenseDocumentRoute(entry.id, hash))
    }
    fun openFfiProbe() { currentStack.add(FfiProbe) }
    fun openSearch() { if (currentStack.last() != Search) currentStack.add(Search) }
    fun openAuthorSearch(name: String) {
        val route = AuthorSearch(world, name.trim())
        if (route.name.isNotEmpty() && currentStack.last() != route) currentStack.add(route)
    }
    fun openLocalAuthorSearch(name: String) {
        val route = AuthorSearch(world, name.trim(), local = true)
        if (route.name.isNotEmpty() && currentStack.last() != route) currentStack.add(route)
    }
    fun openDiscovery(section: DiscoverySection) { if (currentStack.last() != DiscoveryList(section)) currentStack.add(DiscoveryList(section)) }
    fun openOnlineSearch() {
        if (currentStack.last() != OnlineSearch) currentStack.add(OnlineSearch)
    }
    fun openJmSettings() { if (world == PrototypeWorld.NSFW && currentStack.last() != JmSettingsRoute) currentStack.add(JmSettingsRoute) }
    fun openPicaSettings() { if (world == PrototypeWorld.NSFW && currentStack.last() != PicaSettingsRoute) currentStack.add(PicaSettingsRoute) }
    fun openEhInteraction(source: String, manga: String, favorites: Boolean) {
        if (world == PrototypeWorld.NSFW && (com.flux.manga.data.isEhSource(source) || com.flux.manga.data.isExSource(source)))
            currentStack.add(EhInteractionRoute(source, manga, favorites))
    }
    fun openGalleryTags(ex: Boolean, source: String? = null) {
        if (world == PrototypeWorld.NSFW) currentStack.add(GalleryTagsRoute(ex, source))
    }
    fun openExSettings() { if (world == PrototypeWorld.NSFW && currentStack.last() != ExSettingsRoute) currentStack.add(ExSettingsRoute) }
    fun openExBrowse(favorites: Boolean = false, query: String = "", source: String? = null) {
        if (world == PrototypeWorld.NSFW) {
            if (!favorites && query.isNotBlank()) openGalleryTag(source ?: com.flux.manga.data.PrivateCatalog.EX, query)
            else currentStack.add(ExBrowseRoute(favorites, query, source))
        }
    }
    fun openEhSettings() { if (world == PrototypeWorld.NSFW && currentStack.last() != EhSettingsRoute) currentStack.add(EhSettingsRoute) }
    fun openEhBrowse(favorites: Boolean = false, query: String = "", source: String? = null) {
        if (world == PrototypeWorld.NSFW) {
            if (!favorites && query.isNotBlank()) openGalleryTag(source ?: com.flux.manga.data.EH_SOURCE, query)
            else currentStack.add(EhBrowseRoute(favorites, query, source))
        }
    }
    private fun openGalleryTag(source: String, query: String) {
        // Strip only the quoted single-tag syntax used by detail/dictionary links for other sites.
        val tagName = Regex("^[a-zA-Z]+:\"([^\"]+)\"$").matchEntire(query)?.groupValues?.get(1)?.removeSuffix("$") ?: query
        openUnifiedTag(tagName, listOf(TagTarget(source, query)))
    }
    fun openPicaList(kind: String, value: String, source: String? = null) {
        if (world == PrototypeWorld.NSFW) {
            if (value.isNotBlank() && (kind == "search" || kind == "category"))
                openUnifiedTag(value, listOf(TagTarget(source ?: com.flux.manga.data.PrivateCatalog.PICA, value, kind == "category")))
            else currentStack.add(PicaBrowseRoute(kind, value, source))
        }
    }
    fun openJmBrowse() { if (world == PrototypeWorld.NSFW) currentStack.add(JmBrowseRoute()) }
    fun openJmFavorites() { if (world == PrototypeWorld.NSFW) currentStack.add(JmBrowseRoute(favorites = true)) }
    fun openJmTag(source: String, tag: String) {
        if (world == PrototypeWorld.NSFW) openUnifiedTag(tag, listOf(TagTarget(source, tag)))
    }
    fun openShelf(section: ShelfSection) {
        if (currentStack.last() != ShelfList(section)) currentStack.add(ShelfList(section))
    }
    fun openNamedTag(sourceId: String, id: String, label: String) {
        if (world == PrototypeWorld.SFW) currentStack.add(SourceTagResults(world, sourceId, id, label))
    }
    fun openUnifiedTag(label: String, targets: List<TagTarget>) {
        if (targets.isNotEmpty() || world == PrototypeWorld.NSFW) currentStack.add(UnifiedTagResults(world, label, targets))
    }
    fun openSourceTag(sourceId: String, tag: String) {
        val route = SourceTagResults(world, sourceId, tag)
        if (world == PrototypeWorld.SFW && currentStack.last() != route) currentStack.add(route)
    }
    fun openTagDiscovery() {
        if (currentStack.last() is TagResults) back()
        val target = stacks.getValue(MainTab.TAGS)
        target.clear()
        target.add(TabRoot(MainTab.TAGS))
        select(MainTab.TAGS)
    }
    fun openReader(detail: MangaDetail) {
        if (currentStack.last() == detail) {
            currentStack.add(MangaReader(detail.tab, detail.world, detail.sourceId, detail.mangaId, "chapter-1"))
        }
    }
    fun openGlassLab() { currentStack.add(GlassLab) }
    fun openLocalLibrary() { if (world == PrototypeWorld.SFW) currentStack.add(LocalLibrary) }
    fun openKomgaConfiguration() { if (world == PrototypeWorld.SFW && currentStack.last() != KomgaConfiguration) currentStack.add(KomgaConfiguration) }
    fun openKomgaImport() { if (world == PrototypeWorld.SFW && currentStack.last() != KomgaAccountImport) currentStack.add(KomgaAccountImport) }
    fun openDownloadedBook(source: String, manga: String) { if(world==PrototypeWorld.SFW) currentStack.add(DownloadedBook(source,manga)) }
    fun openDownloadedReader(source: String, manga: String, chapter: String) { if(world==PrototypeWorld.SFW) currentStack.add(DownloadedReader(source,manga,chapter)) }
    fun openDownloads() { if(currentStack.last()!=DownloadQueue) currentStack.add(DownloadQueue) }
    fun openPrivateDownloads(source: String, manga: String) { if (world == PrototypeWorld.NSFW) currentStack.add(PrivateDownloadsRoute(source, manga)) }
    fun openPrivateOfflineReader(source: String, manga: String, chapter: String) { if (world == PrototypeWorld.NSFW) currentStack.add(PrivateOfflineReaderRoute(source, manga, chapter)) }
    fun openDownloadChapters(source: String, manga: String) { if(world==PrototypeWorld.SFW) currentStack.add(DownloadChapters(source,manga)) }
    fun openCopyAccount() { if (world == PrototypeWorld.SFW && currentStack.last() != CopyAccountSettings) currentStack.add(CopyAccountSettings) }
    fun openBaoziAccount() { if (world == PrototypeWorld.SFW && currentStack.last() != BaoziAccountSettings) currentStack.add(BaoziAccountSettings) }
    fun openSources() { if (currentStack.last() != OnlineSources) currentStack.add(OnlineSources) }
    fun openOnlineLibrary() { if (world == PrototypeWorld.SFW) currentStack.add(OnlineLibrary) else currentStack.add(ShelfList(ShelfSection.ALL)) }
    fun openCopyManga() { if (world == PrototypeWorld.SFW && currentStack.last() != CopyMangaBrowse) currentStack.add(CopyMangaBrowse) }
    fun openCopyMangaBook(sourceId: String, mangaId: String) {
        if (world == PrototypeWorld.SFW) currentStack.add(CopyMangaBook(sourceId, mangaId))
    }
    fun openKomga(sourceId: String) { if (world == PrototypeWorld.SFW) currentStack.add(KomgaBrowse(sourceId)) }
    fun openCopyMangaReader(sourceId: String, mangaId: String, chapterId: String?) {
        if (world == PrototypeWorld.SFW && com.flux.manga.data.isCopySource(sourceId)) currentStack.add(CopyMangaReader(sourceId, mangaId, chapterId))
    }
    fun openKomgaBook(sourceId: String, mangaId: String) {
        if (com.flux.manga.data.isExSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(ExBookRoute(sourceId, mangaId))
            return
        }
        if (com.flux.manga.data.isEhSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(EhBookRoute(sourceId, mangaId))
            return
        }
        if (com.flux.manga.data.isPicaSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(PicaBookRoute(sourceId, mangaId))
            return
        }
        if (com.flux.manga.data.isJmSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(JmBookRoute(sourceId, mangaId))
            return
        }
        if (com.flux.manga.data.isBaoziSource(sourceId)) {
            if (world == PrototypeWorld.SFW) currentStack.add(BaoziBookRoute(sourceId, mangaId))
            return
        }
        if (com.flux.manga.data.isCopySource(sourceId)) { openCopyMangaBook(sourceId, mangaId); return }
        if (world == PrototypeWorld.SFW) currentStack.add(KomgaBook(sourceId, mangaId))
    }
    fun openKomgaReader(sourceId: String, mangaId: String, chapterId: String?) {
        if (com.flux.manga.data.isExSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(ExReaderRoute(sourceId, mangaId))
            return
        }
        if (com.flux.manga.data.isEhSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(EhReaderRoute(sourceId, mangaId))
            return
        }
        if (com.flux.manga.data.isPicaSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(PicaReaderRoute(sourceId, mangaId, chapterId))
            return
        }
        if (com.flux.manga.data.isJmSource(sourceId)) {
            if (world == PrototypeWorld.NSFW) currentStack.add(JmReaderRoute(sourceId, mangaId, chapterId))
            return
        }
        if (com.flux.manga.data.isBaoziSource(sourceId)) {
            if (world == PrototypeWorld.SFW) currentStack.add(BaoziReaderRoute(sourceId, mangaId, chapterId))
            return
        }
        if (com.flux.manga.data.isCopySource(sourceId)) { openCopyMangaReader(sourceId, mangaId, chapterId); return }
        if (world == PrototypeWorld.SFW) currentStack.add(KomgaReader(sourceId, mangaId, chapterId))
    }
    fun openLocalBook(id: String) { if (world == PrototypeWorld.SFW) currentStack.add(LocalBookDetail(id)) }
    fun openLocalReader(id: String) { if (world == PrototypeWorld.SFW) currentStack.add(LocalBookReader(id)) }
    fun openLocalBookmark(id: String, bookmarkId: String) {
        if (world == PrototypeWorld.SFW) currentStack.add(LocalBookReader(id, bookmarkId))
    }

    fun back() {
        if (currentStack.size > 1) currentStack.removeAt(currentStack.lastIndex)
        else select(MainTab.HOME)
    }
}

@Composable
fun rememberPrototypeNavigation(world: PrototypeWorld, initialTab: MainTab, processSession: String, onSelect: (MainTab) -> Unit): PrototypeNavigation {
    val selected = rememberSaveable { mutableStateOf(initialTab.name) }
    // Keep these three positional save slots in v1 order so old serialized roots can
    // be decoded and migrated before NavDisplay sees them.
    val home = rememberNavBackStack(TabRoot(MainTab.HOME))
    val library = rememberNavBackStack(TabRoot(MainTab.LIBRARY))
    val tags = rememberNavBackStack(TabRoot(MainTab.TAGS))
    val restoredProcess = rememberSaveable { mutableStateOf(processSession) }
    return remember {
        val restored = restoreNavigation(selected.value, listOf(home.toList(), library.toList(), tags.toList()),
            world, restoredProcess.value != processSession)
        listOf(home, library, tags).forEachIndexed { index, stack ->
            val routes = if (restoredProcess.value != processSession && MainTab.primary[index] == initialTab)
                listOf(TabRoot(initialTab)) else restored.stacks.getValue(MainTab.primary[index])
            if (stack.toList() != routes) { stack.clear(); stack.addAll(routes) }
        }
        selected.value = (if (restoredProcess.value != processSession) initialTab else restored.selected).name
        restoredProcess.value = processSession
        onSelect(MainTab.valueOf(selected.value))
        PrototypeNavigation(selected, world, onSelect, mapOf(
            MainTab.HOME to home,
            MainTab.LIBRARY to library,
            MainTab.TAGS to tags,
        ))
    }
}

internal data class RestoredNavigation(val selected: MainTab, val stacks: Map<MainTab, List<NavKey>>)

/** Pure route migration; no database, credentials, files or entry ViewModels are touched. */
internal fun restoreNavigation(selectedName: String, slots: List<List<NavKey>>,
                               world: PrototypeWorld, newProcess: Boolean): RestoredNavigation {
    fun roots() = MainTab.primary.associateWith { listOf<NavKey>(TabRoot(it)) }
    val selected = MainTab.entries.firstOrNull { it.name == selectedName }
    if (slots.size != 3 || selected == null) return RestoredNavigation(MainTab.HOME, roots())
    val legacy = slots[0].firstOrNull() == TabRoot(MainTab.SEARCH) &&
        slots[1].firstOrNull() == TabRoot(MainTab.LIBRARY) &&
        slots[2].firstOrNull() == TabRoot(MainTab.DISCOVER)
    if (!legacy && slots.map { it.firstOrNull() } != MainTab.primary.map(::TabRoot)) {
        return RestoredNavigation(MainTab.HOME, roots())
    }
    fun remap(routes: List<NavKey>, tab: MainTab): List<NavKey>? {
        if (routes.drop(1).any {
            !supportedChild(it) || (it is AuthorSearch && it.world != world) || (world == PrototypeWorld.SFW && isPrivateSourceRoute(it)) || (it is UnifiedTagResults && it.world != world) || (it is SourceTagResults && it.world != world) || (it is MangaDetail && it.world != world) || (it is MangaReader && it.world != world) ||
                (world == PrototypeWorld.NSFW && !isPrivateSourceRoute(it) && it !is MangaDetail && it !is MangaReader &&
                    it !is AuthorSearch && it != Search && it != OnlineSearch && it != OnlineSources && it != DownloadQueue && it != AppSettings && it != LibrarySettings && it != PrivacyMaintenanceSettings && it != DataProtection && it != PrivacySettings && it != AppearanceSettings && it != StartupSettings && it != AboutSettings && it != OpenSourceLicenses && it !is LicenseEntryRoute && it !is LicenseDocumentRoute && it != AppUpdates && it != ReadingSettings && it != ReadingStatisticsRoute && it != RecordExportRoute && it != PortableBackupRoute && it != DownloadStorageSettings && it != SpaceStorageSettings && it !is LibraryGroups && it != FfiProbe && it != GlassLab && it !is ShelfList && it !is TagResults && it !is DiscoveryList && it !is UnifiedTagResults)
        }) return null
        return listOf(TabRoot(tab)) + routes.drop(1).map {
            when (it) {
                is MangaDetail -> it.copy(tab = tab)
                is MangaReader -> it.copy(tab = tab)
                else -> it
            }
        }
    }
    val migrated = if (legacy) {
        mapOf(MainTab.HOME to remap(slots[2], MainTab.HOME),
            MainTab.LIBRARY to if (selected == MainTab.SEARCH)
                remap(listOf(TabRoot(MainTab.LIBRARY), Search) + slots[0].drop(1), MainTab.LIBRARY)
                else remap(slots[1], MainTab.LIBRARY),
            MainTab.TAGS to listOf(TabRoot(MainTab.TAGS)))
    } else MainTab.primary.mapIndexed { index, tab -> tab to remap(slots[index], tab) }.toMap()
    if (migrated.values.any { it == null }) return RestoredNavigation(MainTab.HOME, roots())
    // Activity already drops private snapshots; also enforce that boundary in this reducer.
    if (newProcess && world == PrototypeWorld.NSFW) return RestoredNavigation(MainTab.HOME, roots())
    return RestoredNavigation(if (newProcess) MainTab.HOME else selected.currentTab(), migrated.mapValues { (tab, value) ->
        val routes = requireNotNull(value)
        // Configuration recreation keeps the reader. A new process starts at home;
        // other stacks retain safe destinations, never transient reader image state.
        when {
            newProcess && tab == MainTab.HOME -> listOf(TabRoot(MainTab.HOME))
            newProcess && routes.any { it is DownloadedReader } -> {
                val reader=routes.filterIsInstance<DownloadedReader>().last()
                listOf(TabRoot(tab),DownloadedBook(reader.sourceId,reader.mangaId))
            }
            newProcess && routes.any { it is LocalBookReader } -> listOf(TabRoot(tab), LocalLibrary)
            newProcess && routes.any { it is KomgaReader || it is CopyMangaReader || it is BaoziReaderRoute } -> listOf(TabRoot(tab), OnlineLibrary)
            else -> routes
        }
    })
}

private fun supportedChild(route: NavKey) = when (route) {
    is AuthorSearch -> true
    is PrivateDownloadsRoute, is PrivateOfflineReaderRoute -> true
    is GalleryTagsRoute, is EhInteractionRoute -> true
    ExSettingsRoute, is ExBrowseRoute, is ExBookRoute, is ExReaderRoute -> true
    EhSettingsRoute, is EhBrowseRoute, is EhBookRoute, is EhReaderRoute -> true
    PicaSettingsRoute, is PicaBrowseRoute, is PicaBookRoute, is PicaReaderRoute -> true
    JmSettingsRoute, is JmBrowseRoute, is JmBookRoute, is JmReaderRoute -> true
    Search, OnlineSearch, CopyMangaBrowse, AppSettings, LibrarySettings, PrivacyMaintenanceSettings, DataProtection, PrivacySettings, AppearanceSettings, StartupSettings, AboutSettings, OpenSourceLicenses, AppUpdates, ReadingSettings, ReadingStatisticsRoute, RecordExportRoute, PortableBackupRoute, DownloadStorageSettings, SpaceStorageSettings, FfiProbe, GlassLab, LocalLibrary, OnlineLibrary, OnlineSources, KomgaConfiguration, KomgaAccountImport, BaoziAccountSettings, CopyAccountSettings, DownloadQueue -> true
    is LicenseEntryRoute, is LicenseDocumentRoute, is LibraryGroups, is DownloadedBook, is DownloadedReader, is DownloadChapters, is BaoziReaderRoute, is BaoziBookRoute, is CopyMangaBook, is CopyMangaReader, is DiscoveryList, is UnifiedTagResults, is SourceTagResults, is TagResults, is ShelfList, is LocalBookDetail, is LocalBookReader, is KomgaBrowse, is KomgaBook, is KomgaReader,
    is MangaDetail, is MangaReader -> true
    else -> false
}

private fun isPrivateSourceRoute(route: NavKey) = route is PrivateDownloadsRoute || route is PrivateOfflineReaderRoute || route is EhInteractionRoute || route is GalleryTagsRoute || route == JmSettingsRoute || route is JmBrowseRoute || route is JmBookRoute || route is JmReaderRoute ||
    route == PicaSettingsRoute || route is PicaBrowseRoute || route is PicaBookRoute || route is PicaReaderRoute ||
    route == EhSettingsRoute || route is EhBrowseRoute || route is EhBookRoute || route is EhReaderRoute ||
    route == ExSettingsRoute || route is ExBrowseRoute || route is ExBookRoute || route is ExReaderRoute

