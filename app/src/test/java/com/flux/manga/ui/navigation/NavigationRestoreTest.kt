package com.flux.manga.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.*
import org.junit.Test

class NavigationRestoreTest {
    @Test fun recordExportRestoresOnlyItsOriginAndNeverPrivateProcessState() {
        val slots = roots().toMutableList().apply { this[1] = this[1] + RecordExportRoute }
        assertEquals(slots[1], restore("LIBRARY", slots, process = true).stacks[MainTab.LIBRARY])
        assertEquals(slots[1], restore("LIBRARY", slots, world = PrototypeWorld.NSFW).stacks[MainTab.LIBRARY])
        assertEquals(roots(), MainTab.primary.map {
            restore("LIBRARY", slots, world = PrototypeWorld.NSFW, process = true).stacks.getValue(it)
        })
    }

    @Test fun recordExportEntryFromSettingsReturnsToSettings() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.SFW, {}, stacks)
        navigation.openSettings(); navigation.openRecordExport(); navigation.openRecordExport()
        assertEquals(listOf(TabRoot(MainTab.LIBRARY), AppSettings, RecordExportRoute), navigation.currentStack.toList())
        navigation.back(); assertEquals(AppSettings, navigation.currentStack.last())
    }

    @Test fun statisticsPreserveOriginAndPrivateRecoveryBoundary() {
        val slots = roots().toMutableList().apply { this[1] = this[1] + ReadingStatisticsRoute }
        assertEquals(slots[1], restore("LIBRARY", slots).stacks[MainTab.LIBRARY])
        assertEquals(slots[1], restore("LIBRARY", slots, process = true).stacks[MainTab.LIBRARY])
        assertEquals(slots[1], restore("LIBRARY", slots, world = PrototypeWorld.NSFW).stacks[MainTab.LIBRARY])
        assertEquals(roots(), MainTab.primary.map {
            restore("LIBRARY", slots, world = PrototypeWorld.NSFW, process = true).stacks.getValue(it)
        })
    }

    @Test fun licensePagesRestorePublicStackAndReturnThroughAbout() {
        MainTab.primary.forEach { origin ->
            val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
            val navigation = PrototypeNavigation(mutableStateOf(origin.name), PrototypeWorld.SFW, {}, stacks)
            navigation.openSettings(); navigation.openAboutSettings(); navigation.openLicenses()
            navigation.openLicenseEntry("fluxmanga"); navigation.openLicenseDocument("a".repeat(64))
            val expected = listOf(TabRoot(origin), AppSettings, AboutSettings, OpenSourceLicenses,
                LicenseEntryRoute("fluxmanga"), LicenseDocumentRoute("fluxmanga", "a".repeat(64)))
            assertEquals(expected, navigation.currentStack.toList())
            assertEquals(expected, restore(origin.name, MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[origin])
            navigation.back(); assertEquals(LicenseEntryRoute("fluxmanga"), navigation.currentStack.last())
            navigation.back(); assertEquals(OpenSourceLicenses, navigation.currentStack.last())
            navigation.back(); assertEquals(AboutSettings, navigation.currentStack.last())
        }
    }

    @Test fun licenseRoutesDoNotBypassPrivateProcessRecovery() {
        val slots = roots().toMutableList().apply { this[2] = this[2] + listOf(AppSettings, AboutSettings,
            OpenSourceLicenses, LicenseEntryRoute("fluxmanga"), LicenseDocumentRoute("fluxmanga", "b".repeat(64))) }
        assertEquals(slots[2], restore("TAGS", slots, world = PrototypeWorld.NSFW).stacks[MainTab.TAGS])
        assertEquals(roots(), MainTab.primary.map {
            restore("TAGS", slots, process = true, world = PrototypeWorld.NSFW).stacks.getValue(it)
        })
    }

    @Test fun settingsBelongToTheirOriginStackAndReturnOneLevelAtATime() {
        MainTab.primary.forEach { origin ->
            val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
            val navigation = PrototypeNavigation(mutableStateOf(origin.name), PrototypeWorld.SFW, {}, stacks)
            navigation.openSettings(); navigation.openSettings()
            navigation.openAppearanceSettings(); navigation.openAppearanceSettings()
            assertEquals(listOf(TabRoot(origin), AppSettings, AppearanceSettings), navigation.currentStack.toList())
            val restored = restore(origin.name, MainTab.primary.map { stacks.getValue(it).toList() }, process = true)
            assertEquals(navigation.currentStack.toList(), restored.stacks[origin])
            navigation.back(); assertEquals(AppSettings, navigation.currentStack.last())
            navigation.openReadingSettings(); navigation.openReadingSettings()
            assertEquals(listOf(TabRoot(origin), AppSettings, ReadingSettings), navigation.currentStack.toList())
            assertEquals(navigation.currentStack.toList(), restore(origin.name, MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[origin])
            navigation.back(); assertEquals(AppSettings, navigation.currentStack.last())
            navigation.openSources(); navigation.back(); assertEquals(AppSettings, navigation.currentStack.last())
            navigation.openAppUpdates(); navigation.openAppUpdates()
            assertEquals(listOf(TabRoot(origin), AppSettings, AppUpdates), navigation.currentStack.toList())
            assertEquals(navigation.currentStack.toList(), restore(origin.name, MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[origin])
            navigation.back(); assertEquals(AppSettings, navigation.currentStack.last())
            navigation.openAboutSettings(); navigation.back(); assertEquals(AppSettings, navigation.currentStack.last())
            navigation.back(); assertEquals(TabRoot(origin), navigation.currentStack.last())
            assertEquals(origin, navigation.selectedTab)
        }
    }

    @Test fun privateSettingsSurviveConfigurationButCannotRestorePrivateProcess() {
        val slots = roots().toMutableList().apply { this[2] = this[2] + listOf(AppSettings, ReadingSettings) }
        assertEquals(slots[2], restore("TAGS", slots, world = PrototypeWorld.NSFW).stacks[MainTab.TAGS])
        val recovered = restore("TAGS", slots, process = true, world = PrototypeWorld.NSFW)
        assertEquals(MainTab.LIBRARY, recovered.selected)
        assertEquals(roots(), MainTab.primary.map { recovered.stacks.getValue(it) })
    }

    @Test fun discoverySectionsAndDirectDetailsReturnToTheirActualOrigin() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
        for (section in DiscoverySection.entries) {
            navigation.openDiscovery(section); navigation.openDiscovery(section)
            navigation.openKomgaBook("source", "uncollected")
            assertEquals(listOf(TabRoot(MainTab.HOME), DiscoveryList(section), KomgaBook("source", "uncollected")), navigation.currentStack.toList())
            assertEquals(navigation.currentStack.toList(), restore("HOME", MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[MainTab.HOME])
            navigation.back(); assertEquals(DiscoveryList(section), navigation.currentStack.last())
            navigation.back(); assertEquals(TabRoot(MainTab.HOME), navigation.currentStack.last())
        }
        navigation.openLocalBook("direct-preview"); navigation.back()
        assertEquals(TabRoot(MainTab.HOME), navigation.currentStack.last())
        val privateSlots = roots().toMutableList().apply { this[0] = this[0] + DiscoveryList(DiscoverySection.VISITS) }
        assertEquals(privateSlots[0], restore("HOME", privateSlots, world = PrototypeWorld.NSFW).stacks[MainTab.HOME])
        assertEquals(roots(), MainTab.primary.map { restore("HOME", privateSlots, world = PrototypeWorld.NSFW, process = true).stacks.getValue(it) })
    }

    @Test fun copyReaderReturnsToSelectedDetailAndColdProcessOffersExplicitContinue() {
        val source = com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
        navigation.openCopyManga(); navigation.openCopyMangaBook(source, "work")
        navigation.openCopyMangaReader(source, "work", "chapter")
        val slots = MainTab.primary.map { stacks.getValue(it).toList() }
        assertEquals(CopyMangaReader(source, "work", "chapter"), navigation.currentStack.last())
        assertEquals(slots[0], restore("HOME", slots).stacks[MainTab.HOME])
        assertEquals(listOf(TabRoot(MainTab.HOME), OnlineLibrary), restore("HOME", slots, process = true).stacks[MainTab.HOME])
        navigation.back(); assertEquals(CopyMangaBook(source, "work"), navigation.currentStack.last())
        navigation.back(); assertEquals(CopyMangaBrowse, navigation.currentStack.last())
        // Existing library/continue callbacks dispatch by source without opening Komga configuration.
        navigation.openKomgaBook(source, "work"); assertEquals(CopyMangaBook(source, "work"), navigation.currentStack.last())
        navigation.openKomgaReader(source, "work", null); assertEquals(CopyMangaReader(source, "work", null), navigation.currentStack.last())
        val privateNavigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.NSFW, {}, MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) })
        privateNavigation.openCopyMangaReader(source, "work", "chapter")
        assertEquals(TabRoot(MainTab.HOME), privateNavigation.currentStack.last())
    }

    @Test fun copyAccountImportKeepsSourceThroughDetailReaderAndRestoration() {
        val source = com.flux.manga.data.copyAccountId("synthetic-user")
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.SFW, {}, stacks)
        navigation.openCopyAccount(); navigation.openCopyAccount()
        assertEquals(listOf(TabRoot(MainTab.LIBRARY), CopyAccountSettings), navigation.currentStack.toList())
        navigation.openKomgaBook(source, "work"); navigation.openKomgaReader(source, "work", "chapter")
        assertEquals(CopyMangaReader(source, "work", "chapter"), navigation.currentStack.last())
        navigation.back(); assertEquals(CopyMangaBook(source, "work"), navigation.currentStack.last())
        navigation.back(); assertEquals(CopyAccountSettings, navigation.currentStack.last())
        assertEquals(navigation.currentStack.toList(), restore("LIBRARY", MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[MainTab.LIBRARY])
        val privateNavigation = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.NSFW, {}, MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) })
        privateNavigation.openCopyAccount(); privateNavigation.openKomgaReader(source, "work", null)
        assertEquals(TabRoot(MainTab.LIBRARY), privateNavigation.currentStack.last())
    }

    @Test fun downloadsReturnThroughSelectionToDetailAndStayPublic() {
        val source=com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID
        val stacks=MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val nav=PrototypeNavigation(mutableStateOf("LIBRARY"),PrototypeWorld.SFW,{},stacks)
        nav.openKomgaBook(source,"work"); nav.openDownloadChapters(source,"work"); nav.openDownloads(); nav.openDownloads()
        assertEquals(DownloadQueue,nav.currentStack.last())
        assertEquals(nav.currentStack.toList(),restore("LIBRARY",MainTab.primary.map { stacks.getValue(it).toList() },process=true).stacks[MainTab.LIBRARY])
        nav.back(); assertEquals(DownloadChapters(source,"work"),nav.currentStack.last())
        nav.back(); assertEquals(CopyMangaBook(source,"work"),nav.currentStack.last())
        val privateNav=PrototypeNavigation(mutableStateOf("LIBRARY"),PrototypeWorld.NSFW,{},MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) })
        privateNav.openDownloads(); privateNav.openDownloadChapters(source,"work")
        assertEquals(DownloadQueue,privateNav.currentStack.last())
    }

    @Test fun unifiedSettingsDirectoriesRetainEveryOriginButDoNotGrantSourceAccess() {
        PrototypeWorld.entries.forEach { world -> MainTab.primary.forEach { tab ->
            val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
            val nav = PrototypeNavigation(mutableStateOf(tab.name), world, {}, stacks)
            nav.openSettings(); nav.openSources(); nav.openSources()
            assertEquals(listOf(TabRoot(tab), AppSettings, OnlineSources), nav.currentStack.toList())
            assertEquals(nav.currentStack.toList(), restore(tab.name, MainTab.primary.map { stacks.getValue(it).toList() }, world = world).stacks[tab])
            if (world == PrototypeWorld.NSFW) {
                nav.openKomgaConfiguration(); nav.openCopyAccount(); nav.openBaoziAccount()
                nav.openKomgaImport(); nav.openKomgaBook("source", "book")
                assertEquals(OnlineSources, nav.currentStack.last())
            }
            nav.back(); nav.openDownloads(); nav.openDownloads()
            assertEquals(listOf(TabRoot(tab), AppSettings, DownloadQueue), nav.currentStack.toList())
            if (world == PrototypeWorld.NSFW) {
                nav.openDownloadChapters("source", "book"); nav.openDownloadedBook("source", "book")
                nav.openDownloadedReader("source", "book", "chapter")
                assertEquals(DownloadQueue, nav.currentStack.last())
            }
            nav.back(); assertEquals(AppSettings, nav.currentStack.last())
            nav.back(); assertEquals(TabRoot(tab), nav.currentStack.last())
            assertEquals(tab, nav.selectedTab)
        } }
    }

    @Test fun privateUnavailableDirectoriesRestoreOnRotationButNeverAfterProcessDeath() {
        for (route in listOf(OnlineSources, DownloadQueue)) {
            val slots = roots().toMutableList().apply { this[0] = this[0] + listOf(AppSettings, route) }
            assertEquals(slots[0], restore("HOME", slots, world = PrototypeWorld.NSFW).stacks[MainTab.HOME])
            val fresh = restore("HOME", slots, process = true, world = PrototypeWorld.NSFW)
            assertEquals(MainTab.LIBRARY, fresh.selected)
            assertEquals(roots(), MainTab.primary.map { fresh.stacks.getValue(it) })
            for (blocked in listOf(KomgaConfiguration, CopyAccountSettings, BaoziAccountSettings, DownloadChapters("s", "b"))) {
                val invalid = slots.toMutableList().apply { this[0] = this[0] + blocked }
                assertEquals(roots(), MainTab.primary.map { restore("HOME", invalid, world = PrototypeWorld.NSFW).stacks.getValue(it) })
            }
        }
    }

    private fun roots() = MainTab.primary.map { listOf<NavKey>(TabRoot(it)) }
    private fun legacy() = listOf(MainTab.SEARCH, MainTab.LIBRARY, MainTab.DISCOVER)
        .map { listOf<NavKey>(TabRoot(it)) }
    private fun restore(selected: String, slots: List<List<NavKey>>, process: Boolean = false,
                        world: PrototypeWorld = PrototypeWorld.SFW) =
        restoreNavigation(selected, slots, world, process)

    @Test fun primaryOrderAndColdStartRemainLibrary() {
        assertEquals(listOf(MainTab.HOME, MainTab.LIBRARY, MainTab.TAGS), MainTab.primary)
        val result = restore("LIBRARY", roots())
        assertEquals(MainTab.LIBRARY, result.selected)
        assertEquals(roots(), MainTab.primary.map { result.stacks.getValue(it) })
    }

    @Test fun legacyDiscoverMovesToHomeWithoutDroppingDetail() {
        val route = MangaDetail(MainTab.DISCOVER, PrototypeWorld.SFW, "sample", "7")
        val slots = legacy().toMutableList().apply { this[2] = this[2] + route }
        val result = restore("DISCOVER", slots)
        assertEquals(MainTab.HOME, result.selected)
        assertEquals(listOf(TabRoot(MainTab.HOME), route.copy(tab = MainTab.HOME)), result.stacks[MainTab.HOME])
        assertEquals(listOf(TabRoot(MainTab.TAGS)), result.stacks[MainTab.TAGS])
    }

    @Test fun selectedLegacySearchBecomesLibraryChildAndRemapsDetail() {
        val route = MangaDetail(MainTab.SEARCH, PrototypeWorld.SFW, "sample", "12")
        val slots = legacy().toMutableList().apply { this[0] = this[0] + route }
        val result = restore("SEARCH", slots)
        assertEquals(MainTab.LIBRARY, result.selected)
        assertEquals(listOf(TabRoot(MainTab.LIBRARY), Search, route.copy(tab = MainTab.LIBRARY)),
            result.stacks[MainTab.LIBRARY])
    }

    @Test fun legacyLibraryRetainsItsLocalDetail() {
        val slots = legacy().toMutableList().apply { this[1] = this[1] + LocalBookDetail("kept") }
        assertEquals(slots[1], restore("LIBRARY", slots).stacks[MainTab.LIBRARY])
    }

    @Test fun currentSearchAndReaderSurviveConfigurationRebuild() {
        val slots = roots().toMutableList().apply {
            this[0] = this[0] + listOf(Search, KomgaBook("source-a", "book"), KomgaReader("source-a", "book", "chapter"))
        }
        val result = restore("HOME", slots)
        assertEquals(slots, MainTab.primary.map { result.stacks.getValue(it) })
    }

    @Test fun shelfSubpagesRetainTheirOriginInBothWorlds() {
        val slots = roots().toMutableList().apply {
            this[1] = this[1] + listOf(ShelfList(ShelfSection.ALL), ShelfList(ShelfSection.HISTORY))
        }
        PrototypeWorld.entries.forEach { world ->
            assertEquals(slots[1], restore("LIBRARY", slots, world = world).stacks[MainTab.LIBRARY])
        }
    }
    @Test fun tagResultsRestoreWithinTheirStackAndPrivateProcessStillResets() {
        val slots = roots().toMutableList().apply { this[2] = this[2] + TagResults("user-tag-id") }
        assertEquals(slots[2], restore("TAGS", slots).stacks[MainTab.TAGS])
        assertEquals(slots[2], restore("TAGS", slots, process = true).stacks[MainTab.TAGS])
        assertEquals(slots[2], restore("TAGS", slots, world = PrototypeWorld.NSFW).stacks[MainTab.TAGS])
        assertEquals(roots(), MainTab.primary.map {
            restore("TAGS", slots, process = true, world = PrototypeWorld.NSFW).stacks.getValue(it)
        })
    }

    @Test fun processRecoveryOffersExplicitContinueForEachReaderKind() {
        val slots = roots().toMutableList().apply {
            this[0] = this[0] + listOf(Search, KomgaReader("source-a", "book", "chapter"))
            this[1] = this[1] + listOf(LocalBookDetail("local"), LocalBookReader("local"))
        }
        val result = restore("HOME", slots, process = true)
        assertEquals(listOf(TabRoot(MainTab.HOME), OnlineLibrary), result.stacks[MainTab.HOME])
        assertEquals(listOf(TabRoot(MainTab.LIBRARY), LocalLibrary), result.stacks[MainTab.LIBRARY])
    }

    @Test fun malformedOrUnknownSnapshotsFallBackToRoots() {
        val nestedRoot = roots().toMutableList().apply { this[1] = this[1] + TabRoot(MainTab.TAGS) }
        val unknown = roots().toMutableList().apply { this[0] = this[0] + object : NavKey {} }
        listOf(roots().dropLast(1), roots().reversed(), nestedRoot, unknown).forEach { slots ->
            val result = restore("HOME", slots)
            assertEquals(MainTab.LIBRARY, result.selected)
            assertEquals(roots(), MainTab.primary.map { result.stacks.getValue(it) })
        }
        assertEquals(MainTab.LIBRARY, restore("UNKNOWN", roots()).selected)
    }

    @Test fun foreignWorldRouteAndPrivateProcessStateAreNotRestored() {
        val slots = roots().toMutableList().apply {
            this[0] = this[0] + MangaDetail(MainTab.HOME, PrototypeWorld.NSFW, "sample", "1")
        }
        assertEquals(roots(), MainTab.primary.map { restore("HOME", slots).stacks.getValue(it) })
        val result = restore("HOME", slots, process = true, world = PrototypeWorld.NSFW)
        assertEquals(MainTab.LIBRARY, result.selected)
        assertEquals(roots(), MainTab.primary.map { result.stacks.getValue(it) })
    }

    @Test fun reselectKeepsDormantStacksAndBackReturnsToLibrary() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.SFW, {}, stacks)
        navigation.openSearch()
        navigation.openLocalBook("one")
        navigation.select(MainTab.HOME)
        navigation.openOnlineLibrary()
        navigation.select(MainTab.LIBRARY)
        assertEquals(LocalBookDetail("one"), navigation.currentStack.last())
        navigation.select(MainTab.LIBRARY)
        navigation.back()
        assertEquals(Search, navigation.currentStack.last())
        navigation.back()
        navigation.select(MainTab.HOME)
        assertEquals(OnlineLibrary, navigation.currentStack.last())
        navigation.back()
        navigation.back()
        assertEquals(MainTab.LIBRARY, navigation.selectedTab)
        assertEquals(1, navigation.currentStack.size)
    }

    @Test fun onlineDiscoveryAndLocalSearchRemainDifferentRoutesWithTheirOwnOrigins() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
        navigation.openOnlineSearch()
        navigation.openOnlineSearch()
        navigation.select(MainTab.LIBRARY)
        navigation.openSearch()
        val restored = restore("LIBRARY", MainTab.primary.map { stacks.getValue(it).toList() })
        assertEquals(listOf(TabRoot(MainTab.HOME), OnlineSearch), restored.stacks[MainTab.HOME])
        assertEquals(listOf(TabRoot(MainTab.LIBRARY), Search), restored.stacks[MainTab.LIBRARY])
        navigation.back()
        assertEquals(TabRoot(MainTab.LIBRARY), navigation.currentStack.last())
        navigation.select(MainTab.HOME)
        navigation.back()
        assertEquals(TabRoot(MainTab.HOME), navigation.currentStack.last())
    }

    @Test fun legacyUserTagLinkCanReturnToSourceTagRootWithoutReinterpretingItsId() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        stacks.getValue(MainTab.HOME).add(TagResults("old-private-looking-id"))
        stacks.getValue(MainTab.TAGS).add(TagResults("another-old-id"))
        val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
        navigation.openTagDiscovery()
        assertEquals(MainTab.TAGS, navigation.selectedTab)
        assertEquals(listOf(TabRoot(MainTab.TAGS)), navigation.currentStack.toList())
        assertEquals(listOf(TabRoot(MainTab.HOME)), stacks.getValue(MainTab.HOME).toList())
    }
    @Test fun sourceTagsKeepTheirOriginThroughDetailAndRecreation() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("TAGS"), PrototypeWorld.SFW, {}, stacks)
        val tag = " 标签 / \" &? "
        navigation.openSourceTag("source-a", tag)
        navigation.openSourceTag("source-a", tag)
        assertEquals(2, navigation.currentStack.size)
        navigation.openKomgaBook("source-a", "book")
        val restored = restore("TAGS", MainTab.primary.map { stacks.getValue(it).toList() })
        assertEquals(listOf(TabRoot(MainTab.TAGS), SourceTagResults(PrototypeWorld.SFW, "source-a", tag), KomgaBook("source-a", "book")), restored.stacks[MainTab.TAGS])
        navigation.back()
        assertEquals(SourceTagResults(PrototypeWorld.SFW, "source-a", tag), navigation.currentStack.last())
        navigation.back(); assertEquals(TabRoot(MainTab.TAGS), navigation.currentStack.last())
    }

    @Test fun sourceTagRoutesCannotCrossIntoPrivateWorld() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("TAGS"), PrototypeWorld.NSFW, {}, stacks)
        navigation.openSourceTag("source-a", "tag")
        assertEquals(1, navigation.currentStack.size)
        val slots = roots().toMutableList().apply { this[2] = this[2] + SourceTagResults(PrototypeWorld.SFW, "source-a", "tag") }
        val restored = restore("TAGS", slots, world = PrototypeWorld.NSFW)
        assertEquals(roots(), MainTab.primary.map { restored.stacks.getValue(it) })
    }

    @Test fun detailTagResultsReturnToOriginAndKeepReaderRouteInEachTab() {
        for (tab in MainTab.primary) {
            val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
            val navigation = PrototypeNavigation(mutableStateOf(tab.name), PrototypeWorld.SFW, {}, stacks)
            val tag = " 标签 / \" &? "
            navigation.openKomgaBook("source-a", "original")
            navigation.openSourceTag("source-a", tag)
            navigation.openKomgaBook("source-a", "result")
            navigation.openKomgaReader("source-a", "result", "chapter-2")
            val restored = restore(tab.name, MainTab.primary.map { stacks.getValue(it).toList() })
            assertEquals(stacks.getValue(tab).toList(), restored.stacks[tab])
            navigation.back(); assertEquals(KomgaBook("source-a", "result"), navigation.currentStack.last())
            navigation.back(); assertEquals(SourceTagResults(PrototypeWorld.SFW, "source-a", tag), navigation.currentStack.last())
            navigation.back(); assertEquals(KomgaBook("source-a", "original"), navigation.currentStack.last())
            navigation.openKomgaReader("source-a", "original", null)
            assertEquals(KomgaReader("source-a", "original", null), navigation.currentStack.last())
        }
    }

    @Test fun copySourceDetailReturnsToItsFeedAndSurvivesPublicRestore() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
        navigation.openDiscovery(DiscoverySection.RECOMMENDATIONS)
        navigation.openCopyManga()
        navigation.openCopyMangaBook("copy-source", "book")
        val restored = restore("HOME", MainTab.primary.map { stacks.getValue(it).toList() })
        assertEquals(navigation.currentStack.toList(), restored.stacks[MainTab.HOME])
        navigation.back(); assertEquals(CopyMangaBrowse, navigation.currentStack.last())
        navigation.back(); assertEquals(DiscoveryList(DiscoverySection.RECOMMENDATIONS), navigation.currentStack.last())
        val privateStacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val private = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.NSFW, {}, privateStacks)
        private.openCopyManga(); private.openCopyMangaBook("copy-source", "book")
        assertEquals(1, private.currentStack.size)
        val invalid = roots().toMutableList().apply { this[0] = this[0] + CopyMangaBrowse + CopyMangaBook("copy-source", "book") }
        assertEquals(roots(), MainTab.primary.map { restore("HOME", invalid, world = PrototypeWorld.NSFW).stacks.getValue(it) })
    }
    @Test fun copyPreviewSearchAndRecommendationReaderReturnToTheirActualOrigin() {
        val source = com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID
        for (origin in listOf<NavKey>(TabRoot(MainTab.HOME), OnlineSearch, DiscoveryList(DiscoverySection.RECOMMENDATIONS))) {
            val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
            val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
            if (origin != TabRoot(MainTab.HOME)) stacks.getValue(MainTab.HOME).add(origin)
            navigation.openKomgaBook(source, "same-id")
            assertEquals(CopyMangaBook(source, "same-id"), navigation.currentStack.last())
            navigation.openKomgaReader(source, "same-id", "chapter")
            assertEquals(CopyMangaReader(source, "same-id", "chapter"), navigation.currentStack.last())
            assertEquals(navigation.currentStack.toList(), restore("HOME", MainTab.primary.map { stacks.getValue(it).toList() }).stacks[MainTab.HOME])
            navigation.back(); assertEquals(CopyMangaBook(source, "same-id"), navigation.currentStack.last())
            navigation.back(); assertEquals(origin, navigation.currentStack.last())
        }
    }
    @Test fun aggregateSameIdResultsDispatchBySourceAndReturnToOnlineSearch() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
        navigation.openOnlineSearch()
        val copy = com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID
        for (source in listOf("komga-test", copy)) {
            navigation.openKomgaBook(source, "same")
            navigation.openKomgaReader(source, "same", "chapter")
            val expected = if (source == copy) CopyMangaReader(source, "same", "chapter") else KomgaReader(source, "same", "chapter")
            assertEquals(expected, navigation.currentStack.last())
            assertEquals(navigation.currentStack.toList(), restore("HOME", MainTab.primary.map { stacks.getValue(it).toList() }).stacks[MainTab.HOME])
            navigation.back()
            assertEquals(if (source == copy) CopyMangaBook(source, "same") else KomgaBook(source, "same"), navigation.currentStack.last())
            navigation.back(); assertEquals(OnlineSearch, navigation.currentStack.last())
        }
    }
    @Test fun baoziDetailKeepsSearchOriginAcrossRestoreAndRejectsPrivateWorld() {
        val source = com.flux.manga.data.BaoziEndpoint.SOURCE_ID
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val navigation = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
        navigation.openOnlineSearch(); navigation.openKomgaBook(source, "same")
        assertEquals(BaoziBookRoute(source, "same"), navigation.currentStack.last())
        assertEquals(navigation.currentStack.toList(), restore("HOME", MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[MainTab.HOME])
        navigation.back(); assertEquals(OnlineSearch, navigation.currentStack.last())
        val privateStacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val private = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.NSFW, {}, privateStacks)
        private.openKomgaBook(source, "same"); assertEquals(1, private.currentStack.size)
    }
    @Test fun baoziReaderReturnsThroughDetailsAndNewProcessRecoversToLibrary() {
        val source = com.flux.manga.data.BaoziEndpoint.SOURCE_ID
        for (origin in listOf<NavKey>(OnlineSearch, SourceTagResults(PrototypeWorld.SFW, source, "冒险"), DiscoveryList(DiscoverySection.UPDATES), OnlineLibrary)) {
            val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
            val nav = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.SFW, {}, stacks)
            stacks.getValue(MainTab.HOME).add(origin)
            nav.openKomgaBook(source, "sample"); nav.openKomgaReader(source, "sample", "0_1")
            assertEquals(BaoziReaderRoute(source, "sample", "0_1"), nav.currentStack.last())
            val saved = MainTab.primary.map { stacks.getValue(it).toList() }
            assertEquals(nav.currentStack.toList(), restore("HOME", saved).stacks[MainTab.HOME])
            assertEquals(listOf(TabRoot(MainTab.HOME), OnlineLibrary), restore("HOME", saved, process = true).stacks[MainTab.HOME])
            nav.back(); assertEquals(BaoziBookRoute(source, "sample"), nav.currentStack.last())
            nav.back(); assertEquals(origin, nav.currentStack.last())
        }
        val privateStacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val private = PrototypeNavigation(mutableStateOf("HOME"), PrototypeWorld.NSFW, {}, privateStacks)
        private.openKomgaReader(source, "sample", "0_1"); assertEquals(1, private.currentStack.size)
    }


    @Test fun unifiedTagTargetsAndSourceSettingsPreserveTheirReturnStacks() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val nav = PrototypeNavigation(mutableStateOf("TAGS"), PrototypeWorld.SFW, {}, stacks)
        nav.openSettings(); nav.openSources(); nav.openKomgaConfiguration()
        assertEquals(KomgaConfiguration, nav.currentStack.last())
        assertEquals(nav.currentStack.toList(), restore("TAGS", MainTab.primary.map { stacks.getValue(it).toList() }).stacks[MainTab.TAGS])
        nav.back(); assertEquals(OnlineSources, nav.currentStack.last()); nav.back(); assertEquals(AppSettings, nav.currentStack.last()); nav.back()
        val targets = listOf(TagTarget("komga-test", "冒险"), TagTarget(com.flux.manga.data.CopyMangaEndpoint.SOURCE_ID, "adventure"))
        nav.openUnifiedTag("冒险", targets)
        val route = UnifiedTagResults(PrototypeWorld.SFW, "冒险", targets)
        assertEquals(route, nav.currentStack.last())
        assertEquals(nav.currentStack.toList(), restore("TAGS", MainTab.primary.map { stacks.getValue(it).toList() }).stacks[MainTab.TAGS])
        nav.openKomgaBook(targets[1].sourceId, "work"); nav.back(); assertEquals(route, nav.currentStack.last())
    }
    @Test fun baoziAccountEntryAndAccountOwnedReaderRestoreWithoutCredentials() {
        val source = com.flux.manga.data.baoziAccountId("fixture")
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val nav = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.SFW, {}, stacks)
        nav.openBaoziAccount(); assertEquals(BaoziAccountSettings, nav.currentStack.last())
        nav.openKomgaBook(source, "same"); nav.openKomgaReader(source, "same", "0_1")
        assertEquals(BaoziReaderRoute(source, "same", "0_1"), nav.currentStack.last())
        assertEquals(listOf(TabRoot(MainTab.LIBRARY), OnlineLibrary),
            restore("LIBRARY", MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[MainTab.LIBRARY])
        nav.back(); nav.back(); assertEquals(BaoziAccountSettings, nav.currentStack.last())
        assertEquals(nav.currentStack.toList(), restore("LIBRARY", MainTab.primary.map { stacks.getValue(it).toList() }).stacks[MainTab.LIBRARY])
        val privateStacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val private = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.NSFW, {}, privateStacks)
        private.openBaoziAccount(); private.openKomgaBook(source, "same")
        assertEquals(1, private.currentStack.size)
    }
    @Test fun komgaImportSettingsDetailAndReaderKeepReturnStack() {
        val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val nav = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.SFW, {}, stacks)
        nav.openKomgaImport(); nav.openKomgaConfiguration(); nav.back()
        assertEquals(KomgaAccountImport, nav.currentStack.last())
        nav.openKomgaBook("komga-test", "series"); nav.openKomgaReader("komga-test", "series", "book")
        nav.back(); nav.back(); assertEquals(KomgaAccountImport, nav.currentStack.last())
        assertEquals(nav.currentStack.toList(), restore("LIBRARY", MainTab.primary.map { stacks.getValue(it).toList() }).stacks[MainTab.LIBRARY])
        val privateStacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val private = PrototypeNavigation(mutableStateOf("LIBRARY"), PrototypeWorld.NSFW, {}, privateStacks)
        private.openKomgaImport(); private.openKomgaConfiguration(); assertEquals(1, private.currentStack.size)
    }
    @Test fun offlineDirectoryAndReaderRestoreAndNeverOpenInPrivateWorld() {
        val stacks=MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
        val nav=PrototypeNavigation(mutableStateOf("LIBRARY"),PrototypeWorld.SFW,{},stacks)
        nav.openDownloads();nav.openDownloadedBook("account-a","work");nav.openDownloadedReader("account-a","work","original-chapter")
        assertEquals(DownloadedReader("account-a","work","original-chapter"),nav.currentStack.last())
        assertEquals(nav.currentStack.toList(),restore("LIBRARY",MainTab.primary.map { stacks.getValue(it).toList() }).stacks[MainTab.LIBRARY])
        assertEquals(listOf(TabRoot(MainTab.LIBRARY),DownloadedBook("account-a","work")),
            restore("LIBRARY",MainTab.primary.map { stacks.getValue(it).toList() },process=true).stacks[MainTab.LIBRARY])
        nav.back();assertEquals(DownloadedBook("account-a","work"),nav.currentStack.last());nav.back();assertEquals(DownloadQueue,nav.currentStack.last())
        val private=PrototypeNavigation(mutableStateOf("LIBRARY"),PrototypeWorld.NSFW,{},MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) })
        private.openDownloadedBook("a","work");private.openDownloadedReader("a","work","chapter");assertEquals(1,private.currentStack.size)
    }

    @Test fun libraryGroupsAndStorageReturnToTheirOriginWithoutChangingOtherStacks() {
        MainTab.primary.forEach { origin ->
            val stacks = MainTab.primary.associateWith { NavBackStack<NavKey>(TabRoot(it)) }
            val nav = PrototypeNavigation(mutableStateOf(origin.name), PrototypeWorld.SFW, {}, stacks)
            nav.openSettings(); nav.openLibraryGroups(); nav.openLibraryGroups()
            assertEquals(listOf(TabRoot(origin), AppSettings, LibraryGroups()), nav.currentStack.toList())
            assertEquals(nav.currentStack.toList(), restore(origin.name, MainTab.primary.map { stacks.getValue(it).toList() }, process = true).stacks[origin])
            nav.back(); nav.openSpaceStorage(); nav.openSpaceStorage()
            assertEquals(SpaceStorageSettings, nav.currentStack.last())
            assertEquals(nav.currentStack.toList(), restore(origin.name, MainTab.primary.map { stacks.getValue(it).toList() }).stacks[origin])
            nav.back(); nav.back(); assertEquals(TabRoot(origin), nav.currentStack.last())
            nav.openLibraryGroups("SFW/local/3:cbz4:same")
            assertEquals(LibraryGroups("SFW/local/3:cbz4:same"), nav.currentStack.last())
            MainTab.primary.filter { it != origin }.forEach { assertEquals(listOf(TabRoot(it)), stacks.getValue(it).toList()) }
        }
    }
    @Test fun privateGroupAndCleanupRoutesRestoreOnlyWithinCurrentProcess() {
        val slots = roots().toMutableList().apply { this[1] = this[1] + listOf(AppSettings, LibraryGroups(), SpaceStorageSettings) }
        assertEquals(slots[1], restore("LIBRARY", slots, world = PrototypeWorld.NSFW).stacks[MainTab.LIBRARY])
        val recovered = restore("LIBRARY", slots, process = true, world = PrototypeWorld.NSFW)
        assertEquals(roots(), MainTab.primary.map { recovered.stacks.getValue(it) })
    }

}
