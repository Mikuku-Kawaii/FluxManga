package com.flux.manga

import android.graphics.Bitmap

import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.data.SampleScenario
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.MangaReader
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.PageState
import com.flux.manga.ui.pages.RootPageViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldSessionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performClick() }
    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun awaitWorld(world: PrototypeWorld) {
        compose.waitUntil(5_000) {
            compose.activity.worldSession.let { it.identity.value.world == world && it.state.value.phase == SwitchPhase.Stable }
        }
        compose.awaitTag("root-title")
        compose.waitUntil(5_000) { !compose.activity.isCovered }
    }
    private fun capture(name: String, inProcess: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "m1-session-evidence").apply { mkdirs() }
        val bitmap = if (inProcess) compose.onRoot().captureToImage().asAndroidBitmap() else instrumentation.uiAutomation.takeScreenshot()
        if (bitmap == null) {
            assertTrue(secure())
            File(directory, "$name.png").delete()
            File(directory, "$name.txt").writeText("PASS: UiAutomation screenshot denied (null) with FLAG_SECURE enabled.")
            return
        }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun sampleRowsKeepButtonSemanticsSizeAndNavigation() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val variant = InstrumentationRegistry.getArguments().getString("rowVariant", "candidate")
        require(variant in setOf("baseline", "candidate"))
        val directory = File(inst.targetContext.getExternalFilesDir(null), "m2-oppo-switch-ui").apply { mkdirs() }
        for (world in listOf(PrototypeWorld.SFW, PrototypeWorld.NSFW)) {
            if (world == PrototypeWorld.NSFW) { click("switch-world"); awaitWorld(world) }
            compose.awaitTag("sample-1")
            compose.onNodeWithTag("sample-1")
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
                .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(if (variant == "baseline") 40f else 48f))
            File(directory, "$variant-${world.name}-bounds.txt").writeText((1..3).joinToString("\n") { index ->
                "sample-$index: ${compose.onNodeWithTag("sample-$index").fetchSemanticsNode().boundsInRoot}"
            })
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(directory, "$variant-${world.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            click("sample-1"); compose.awaitTag("detail-title")
            click("detail-back"); compose.awaitTag("sample-1")
            assertEquals(world == PrototypeWorld.NSFW, secure())
        }
    }

    @Test fun spacesKeepOnlyTabSelection_andProtectWindowAndRoutes() {
        compose.awaitTag("sample-1")
        capture("public-library")
        // Leave a dormant library detail, then switch from the home root after closing its search child.
        click("sample-1")
        compose.awaitTag("detail-content")
        compose.runOnIdle { compose.activity.navigation.select(MainTab.HOME) }
        compose.openSampleSearch()
        compose.onNodeWithTag("search-query").performTextReplacement("60")
        Espresso.closeSoftKeyboard()
        compose.awaitTag("sample-60")
        Espresso.pressBack()
        compose.awaitTag("main-tabs")
        val oldIdentity = compose.activity.worldSession.identity.value
        click("switch-world")
        awaitWorld(PrototypeWorld.NSFW)
        compose.awaitTag("sample-1")
        assertTrue(secure())
        assertFalse(compose.activity.worldSession.accepts(oldIdentity))
        compose.onNodeWithTag("root-title").assertTextContains("里世界", substring = true)
        capture("private-secure-window")
        capture("private-fixture-in-process", inProcess = true)
        click("tab-LIBRARY")
        compose.awaitTag("sample-1")
        click("sample-1")
        compose.awaitTag("detail-content")
        compose.onNodeWithTag("switch-world").assertDoesNotExist()
        compose.runOnIdle { compose.activity.requestWorldSwitch() }
        assertEquals(PrototypeWorld.NSFW, compose.activity.worldSession.identity.value.world)
        compose.onNodeWithTag("open-reader").performScrollTo().performClick()
        compose.awaitTag("reader-content")
        assertEquals(PrototypeWorld.NSFW, (compose.activity.navigation.currentStack.last() as MangaReader).world)
        Espresso.pressBack()
        compose.awaitTag("detail-content")
        Espresso.pressBack()
        click("switch-world")
        awaitWorld(PrototypeWorld.SFW)
        compose.awaitTag("sample-1")
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 首页")
        compose.onNodeWithTag("search-query").assertDoesNotExist()
        assertFalse(secure())
        assertTrue(compose.activity.navigation.stacks.values.all { it.size == 1 })
        click("switch-world")
        awaitWorld(PrototypeWorld.NSFW)
        compose.onNodeWithTag("root-title").assertTextContains("书库", substring = true)
        assertTrue(compose.activity.navigation.stacks.values.all { it.size == 1 })
        Log.i("FluxSessionTest", "PASS dual spaces: per-world tab only, old query/results/dormant detail removed, reader world identity, root-only switch, secure before target and cleared after public replacement")
    }

    @Test fun preparationRejectsRepeat_cancelsOldRequest_andFailureRetries() {
        compose.awaitTag("sample-1")
        val session = compose.activity.worldSession
        // Hold the fixture boundary explicitly: cold composition can outlast its 500ms demo delay.
        val preparation = CompletableDeferred<Unit>()
        lateinit var oldModel: RootPageViewModel
        compose.runOnIdle {
            compose.activity.fixtureStore = FixtureWorldStore(compose.activity) { preparation.await() }
            oldModel = RootPageViewModel(SavedStateHandle(), session)
            session.pageOwner.viewModelStore.put("pending-fixture", oldModel)
            oldModel.selectScenario(SampleScenario.ERROR)
            compose.activity.requestWorldSwitch(fail = true)
            val generation = session.identity.value.generation
            assertTrue(compose.activity.isCovered)
            assertTrue(secure())
            assertEquals(SwitchPhase.Preparing, session.state.value.phase)
            compose.activity.requestWorldSwitch()
            assertEquals(generation, session.identity.value.generation)
        }
        compose.awaitTag("switch-phase")
        compose.onNodeWithTag("switch-world").assertIsNotEnabled()
        compose.onNodeWithTag("sample-1").assertDoesNotExist()
        compose.onNodeWithTag("tab-HOME").assertIsEnabled().performClick()
        preparation.complete(Unit)
        compose.awaitTag("switch-error")
        awaitWorld(PrototypeWorld.SFW)
        assertEquals(PageState.Loading, oldModel.state.value)
        assertFalse(secure())
        capture("preparation-failure")
        click("switch-world")
        awaitWorld(PrototypeWorld.NSFW)
        compose.onNodeWithTag("root-title").assertTextContains("首页", substring = true)
        click("switch-failure")
        compose.awaitTag("switch-error")
        awaitWorld(PrototypeWorld.SFW)
        assertFalse(secure())
        Log.i("FluxSessionTest", "PASS preparing cover/secure, duplicate rejected, tabs usable, disposed request cannot publish late error, failure returns public with error, retry succeeds")
    }

    @Test fun rotationBeforeCommitFallsBack_andAfterCommitKeepsWorld() {
        compose.awaitTag("sample-1")
        compose.runOnIdle { compose.activity.requestWorldSwitch() }
        compose.activityRule.scenario.recreate()
        awaitWorld(PrototypeWorld.SFW)
        assertFalse(secure())
        click("switch-world")
        compose.waitUntil(5_000) { compose.activity.worldSession.state.value.phase == SwitchPhase.Revealing }
        compose.activityRule.scenario.recreate()
        awaitWorld(PrototypeWorld.NSFW)
        assertTrue(secure())
        assertEquals(SwitchPhase.Stable, compose.activity.worldSession.state.value.phase)
        // The private saved-state bundle is deliberately discarded, including sample-page mode.
        compose.awaitTag("reference-shelf")
        compose.awaitTag("main-tabs")
        Log.i("FluxSessionTest", "PASS configuration interruption: uncommitted -> SFW; committed reveal -> stable NSFW with FLAG_SECURE")
    }

    @Test fun backgroundCoversImmediately_shortResumeKeepsWorld_timeoutReturnsPublic() {
        compose.awaitTag("sample-1")
        click("switch-world")
        awaitWorld(PrototypeWorld.NSFW)
        val activity = compose.activity
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(activity.isCovered)
        assertFalse(activity.worldSession.state.value.foreground)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitWorld(PrototypeWorld.NSFW)
        assertTrue(secure())
        // Test the exact boundary on the monotonic clock without spending 30 seconds here.
        compose.runOnIdle {
            activity.worldSession.pause(now = 100)
            activity.worldSession.resume(now = 30_100)
            assertEquals(PrototypeWorld.NSFW, activity.worldSession.identity.value.world)
            activity.worldSession.pause(now = 100)
            activity.worldSession.resume(now = 30_101)
        }
        awaitWorld(PrototypeWorld.SFW)
        assertFalse(secure())
        capture("timeout-public")
        Log.i("FluxSessionTest", "PASS real Activity background cover and short resume; injected monotonic boundary: 30000 ms retains, 30001 ms resets SFW")
    }

    @Test fun namespaceAndForeignIdentityAreIsolated(): Unit = runBlocking {
        val store = FixtureWorldStore(compose.activity.applicationContext)
        store.prepare(PrototypeWorld.SFW)
        store.prepare(PrototypeWorld.NSFW)
        val publicPaths = store.paths(PrototypeWorld.SFW)
        val privatePaths = store.paths(PrototypeWorld.NSFW)
        assertNotEquals(publicPaths.databaseName, privatePaths.databaseName)
        assertNotEquals(publicPaths.privateFiles.canonicalPath, privatePaths.privateFiles.canonicalPath)
        assertNotEquals(publicPaths.cache.canonicalPath, privatePaths.cache.canonicalPath)
        assertTrue(publicPaths.privateFiles.isDirectory && privatePaths.privateFiles.isDirectory)
        val repository = com.flux.manga.data.SampleRepository(com.flux.manga.data.WorldDataStore(compose.activity))
        val publicIdentity = compose.activity.worldSession.identity.value
        val publicItem = repository.detail(publicIdentity, PrototypeWorld.SFW, "sample", "1", SampleScenario.CONTENT)!!
        val privateItem = repository.detail(publicIdentity.copy(world = PrototypeWorld.NSFW), PrototypeWorld.NSFW, "sample", "1", SampleScenario.CONTENT)!!
        assertNotEquals(publicItem, privateItem)
        assertNull(repository.detail(publicIdentity, PrototypeWorld.NSFW, "sample", "1", SampleScenario.CONTENT))
        assertNull(repository.chapter(publicIdentity, PrototypeWorld.SFW, "foreign-source", "1", "chapter-1", SampleScenario.CONTENT))
        Log.i("FluxSessionTest", "PASS fixed catalog from separate business DBs; namespace and foreign route rejection; no real content")
    }
}
