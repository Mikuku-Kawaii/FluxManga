package com.flux.manga

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.espresso.Espresso
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.ui.navigation.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** User-requested, read-only page review. No fixtures, preference resets, imports or account writes. */
@RunWith(AndroidJUnit4::class)
class PageReviewCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val output get() = File(InstrumentationRegistry.getInstrumentation().targetContext
        .getExternalFilesDir(null), "page-review-20261025").apply { mkdirs() }
    private val index = JSONArray()
    private fun saveIndex() { File(output, "index.json").writeText(index.toString(2)) }
    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        // Popup/Dialog roots have their own window origin. PixelCopy of that root can
        // crop the Activity beneath it; capture the composed display for public dialogs.
        val image = if (roots.fetchSemanticsNodes().size > 1)
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        else roots[0].captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        index.put(JSONObject().put("name", name).put("file", "$name.png"))
        saveIndex()
    }
    private fun settle() {
        if (compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(750)
        Thread.sleep(900); compose.waitForIdle()
    }
    private fun root(tab: MainTab = MainTab.HOME) {
        compose.runOnIdle {
            val nav = compose.activity.navigation
            nav.select(tab)
            while (nav.currentStack.size > 1) nav.back()
        }
        settle()
    }
    private fun page(world: String, label: String, route: NavKey) {
        root()
        compose.runOnIdle { compose.activity.navigation.currentStack.add(route) }
        settle()
        capture("$world-$label")
        compose.runOnIdle { compose.activity.navigation.back() }
        settle()
    }
    private fun frames(name: String, action: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle(action)
            repeat(22) {
                compose.mainClock.advanceTimeBy(64)
                capture("$name-${it.toString().padStart(2, '0')}")
            }
        } finally { compose.mainClock.autoAdvance = true }
        settle()
    }
    @Test fun detailReaderDialogsAndFixedDock() {
        settle()
        check(compose.activity.navigation.world == PrototypeWorld.SFW)
        root(MainTab.LIBRARY); capture("SFW-root-LIBRARY")
        compose.onNodeWithTag("shelf-actions").performClick(); settle(); capture("SFW-library-filters")
        Espresso.pressBack(); settle()
        root(MainTab.TAGS)
        compose.onAllNodesWithText("奇幻")[0].performClick(); settle(); capture("SFW-tag-results")
        root()
        compose.runOnIdle { compose.activity.navigation.openLocalLibrary() }; settle()
        compose.onAllNodes(SemanticsMatcher("local book") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("local-book-") == true
        })[0].performClick(); settle()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("local-read").fetchSemanticsNodes().isNotEmpty() }
        capture("SFW-local-detail")
        compose.onNodeWithTag("local-actions").performClick(); settle(); capture("SFW-local-actions")
        compose.onNodeWithTag("local-edit-info").performClick(); settle(); capture("SFW-local-edit")
        Espresso.pressBack(); settle()
        compose.onNodeWithTag("local-read").performScrollTo().performClick(); settle()
        capture("SFW-reader-fullscreen")
        compose.onNodeWithTag("local-reader").performTouchInput { advanceEventTime(700); click(center) }
        compose.mainClock.advanceTimeBy(700); settle()
        capture("SFW-reader-tools")
        compose.onNodeWithTag("reader-tools").performClick(); settle(); capture("SFW-reader-modes")
        compose.onNodeWithText("跳转页码").performClick(); settle(); capture("SFW-reader-page-dialog")
        Espresso.pressBack(); settle()
        compose.onNodeWithTag("reader-tools").performClick()
        compose.onNodeWithText("阅读选项").performClick(); settle(); capture("SFW-reader-options")
        Espresso.pressBack(); settle()
        compose.onNodeWithTag("reader-tools").performClick()
        compose.onNodeWithText("书签与备注").performClick(); settle(); capture("SFW-reader-bookmarks")
        Espresso.pressBack(); settle()
        compose.onNodeWithTag("reader-back").performClick(); settle()
        root()
        MainTab.primary.forEach { root(it); capture("SFW-root-${it.name}") }
        root()
        frames("SFW-world-switch") { compose.activity.requestWorldSwitch() }
        compose.waitUntil(20_000) { !compose.activity.liquidMotion.pending }
        MainTab.primary.forEach { root(it); capture("NSFW-root-${it.name}") }
        root()
        frames("NSFW-world-switch") { compose.activity.requestWorldSwitch() }
        compose.waitUntil(20_000) { !compose.activity.liquidMotion.pending }
        root()
    }
    @Test fun allReachablePagesAndMotion() {
        settle()
        val originalWorld = compose.activity.worldSession.identity.value.world
        val snapshots = mutableMapOf<PrototypeWorld, Pair<MainTab, Map<MainTab, List<NavKey>>>>()
        fun rememberStacks() { compose.runOnIdle {
            val nav = compose.activity.navigation
            snapshots.putIfAbsent(nav.world, nav.selectedTab to nav.stacks.mapValues { it.value.toList() })
        } }
        fun restoreStacks() { compose.runOnIdle {
            val nav = compose.activity.navigation
            snapshots[nav.world]?.let { (tab, stacks) ->
                stacks.forEach { (key, values) -> nav.stacks.getValue(key).apply { clear(); addAll(values) } }
                nav.select(tab)
            }
        } }
        try {
            repeat(2) {
                rememberStacks()
                val world = compose.activity.navigation.world.name
                MainTab.primary.forEach { tab -> root(tab); capture("$world-root-${tab.name}") }
                root()
                frames("$world-tab") { compose.activity.navigation.select(MainTab.LIBRARY) }
                frames("$world-push") { compose.activity.navigation.openSettings() }
                frames("$world-pop") { compose.activity.navigation.back() }
                val common = listOf(
                    "search" to Search, "online-search" to OnlineSearch,
                    "recommendations" to DiscoveryList(DiscoverySection.RECOMMENDATIONS),
                    "updates" to DiscoveryList(DiscoverySection.UPDATES), "visits" to DiscoveryList(DiscoverySection.VISITS),
                    "shelf-all" to ShelfList(ShelfSection.ALL), "shelf-history" to ShelfList(ShelfSection.HISTORY),
                    "settings" to AppSettings, "library-settings" to LibrarySettings,
                    "privacy-maintenance" to PrivacyMaintenanceSettings, "protection" to DataProtection,
                    "privacy" to PrivacySettings, "appearance" to AppearanceSettings,
                    "reading-settings" to ReadingSettings, "statistics" to ReadingStatisticsRoute,
                    "export" to RecordExportRoute, "backup" to PortableBackupRoute,
                    "groups" to LibraryGroups(), "storage" to SpaceStorageSettings,
                    "app-updates" to AppUpdates, "about" to AboutSettings, "licenses" to OpenSourceLicenses,
                    "sources" to OnlineSources, "downloads" to DownloadQueue, "online-library" to OnlineLibrary)
                common.forEach { (label, route) -> page(world, label, route) }
                val sources = if (world == "SFW") listOf(
                    "local-import" to LocalLibrary, "komga-settings" to KomgaConfiguration,
                    "komga-import" to KomgaAccountImport, "copy-account" to CopyAccountSettings,
                    "copy-browse" to CopyMangaBrowse, "baozi-account" to BaoziAccountSettings)
                else listOf("jm-settings" to JmSettingsRoute, "jm-browse" to JmBrowseRoute(),
                    "jm-favorites" to JmBrowseRoute(favorites = true), "pica-settings" to PicaSettingsRoute,
                    "pica-browse" to PicaBrowseRoute(), "eh-settings" to EhSettingsRoute,
                    "eh-browse" to EhBrowseRoute(), "eh-favorites" to EhBrowseRoute(favorites = true),
                    "ex-settings" to ExSettingsRoute, "ex-browse" to ExBrowseRoute(),
                    "ex-favorites" to ExBrowseRoute(favorites = true), "eh-tags" to GalleryTagsRoute(),
                    "ex-tags" to GalleryTagsRoute(ex = true))
                sources.forEach { (label, route) -> page(world, label, route) }
                root()
                frames("$world-world-switch") { compose.activity.requestWorldSwitch() }
                compose.waitUntil(20_000) { !compose.activity.liquidMotion.pending }
                check(compose.activity.navigation.world.name != world) { "Space switch needs user authorization" }
            }
        } finally {
            if (compose.activity.navigation.world == originalWorld) restoreStacks()
            saveIndex()
        }
    }
}
