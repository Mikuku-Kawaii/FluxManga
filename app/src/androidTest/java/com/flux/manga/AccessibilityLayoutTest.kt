package com.flux.manga

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.ui.glass.activeGlassHosts
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AccessibilityLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private val evidence get() = File(compose.activity.getExternalFilesDir(null), "m1-access-evidence").apply { mkdirs() }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()
    private fun effect(tag: String) {
        click("dock-settings")
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        click("dock-settings-close")
    }
    private fun capture(name: String) {
        val dialogs = compose.onAllNodes(isDialog())
        val target = if (dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs[0] else compose.onRoot()
        val b = target.captureToImage().asAndroidBitmap()
        File(evidence,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
        b.recycle()
    }
    private fun rootTop() {
        compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(0)
    }
    private fun assertTextFits(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse("Text overflow: $tag text=${layout.layoutInput.text} size=${layout.size} constraints=${layout.layoutInput.constraints} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}",layout.hasVisualOverflow)
    }
    private fun bars(world: PrototypeWorld) {
        compose.runOnIdle {
            val a = compose.activity
            val controller = WindowCompat.getInsetsController(a.window,a.window.decorView)
            assertEquals(world == PrototypeWorld.SFW, controller.isAppearanceLightStatusBars)
            assertEquals(world == PrototypeWorld.SFW, controller.isAppearanceLightNavigationBars)
        }
        val safe = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("dock-settings","switch-world","tab-HOME","tab-LIBRARY","tab-TAGS")) {
            val b = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag outside safe area: $b / $safe", b.left >= safe.left && b.right <= screen.right-safe.right && b.top >= safe.top && b.bottom <= screen.bottom-safe.bottom)
        }
    }

    @Test fun independentPreferences_surviveRecreation_andOverrideEffectsWithoutLosingChoices() {
        val preferences = compose.activity.getSharedPreferences("display-preferences", 0)
        val previous = listOf("low-effects", "reduce-motion").associateWith {
            if (preferences.contains(it)) preferences.getBoolean(it, false) else null
        }
        try {
            compose.awaitTag("root-title")
            if (compose.activity.lowEffects) effect("dock-low-effects")
            if (compose.activity.reduceMotion) effect("dock-reduce-motion")
            effect("dock-sampling"); effect("dock-blur")
            compose.waitUntil(5000) { activeGlassHosts == 1 }
            effect("dock-low-effects")
            assertTrue(compose.activity.lowEffects)
            assertFalse(compose.activity.reduceMotion)
            assertEquals(0, activeGlassHosts)
            click("dock-settings")
            compose.onNodeWithTag("dock-sampling").assertIsOn().assertIsNotEnabled()
            compose.onNodeWithTag("dock-blur").assertIsOn().assertIsNotEnabled()
            click("dock-settings-close")
            effect("dock-reduce-motion")
            compose.activityRule.scenario.recreate()
            compose.awaitTag("root-title")
            assertTrue(compose.activity.lowEffects && compose.activity.reduceMotion)
            effect("dock-low-effects")
            assertFalse(compose.activity.lowEffects)
            assertTrue(compose.activity.reduceMotion)
            compose.waitUntil(5000) { activeGlassHosts == 1 }
            val gate = CompletableDeferred<Unit>()
            compose.runOnIdle { compose.activity.fixtureStore = FixtureWorldStore(compose.activity) { gate.await() } }
            compose.mainClock.autoAdvance = false
            click("switch-world")
            compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
            assertEquals(1f,compose.activity.liquidMotion.circleValue(),0f)
            assertFalse(compose.activity.liquidMotion.moving)
            assertEquals(PrototypeWorld.SFW,compose.activity.worldSession.identity.value.world)
            gate.complete(Unit)
            compose.mainClock.autoAdvance = true
            compose.awaitTag("root-title")
            assertEquals(PrototypeWorld.NSFW,compose.activity.worldSession.identity.value.world)
            assertFalse(compose.activity.liquidMotion.pending)
            capture("reduced-motion-private")
        } finally {
            compose.mainClock.autoAdvance = true
            preferences.edit().apply {
                previous.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.commit()
        }
    }

    @Test fun wholePageLargeText_statesDetailsReaderDialog_andImeInsets() {
        compose.awaitTag("root-title")
        val profile = "${compose.activity.resources.configuration.screenWidthDp}dp-${compose.activity.resources.configuration.fontScale}x"
        bars(PrototypeWorld.SFW)
        assertTextFits("root-title")
        capture("$profile-root-top")
        for (scenario in listOf("EMPTY","ERROR","CONTENT")) {
            rootTop()
            val scenarioButton = compose.onNodeWithTag("root-scenario-$scenario").performScrollTo()
            // The dock floats over the list viewport. ScrollTo alone does not
            // account for that sibling overlay when the header is very tall.
            val dockTop = compose.onNodeWithTag("liquid-bottom-bar").fetchSemanticsNode().boundsInRoot.top
            val buttonBottom = scenarioButton.fetchSemanticsNode().boundsInRoot.bottom
            if (buttonBottom > dockTop) compose.onNodeWithTag("list-LIBRARY").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, buttonBottom - dockTop + 16f) }
            assertTrue(scenarioButton.fetchSemanticsNode().boundsInRoot.bottom <= dockTop)
            scenarioButton.performClick()
            if (scenario != "CONTENT") {
                val tag = if (scenario == "EMPTY") "root-empty" else "root-error"
                // At 2x font the library header can fill the viewport. Bring the
                // state item into composition before waiting for its async result.
                compose.waitUntil(5000) {
                    compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(1)
                    compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                capture("$profile-$scenario")
            }
        }
        compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(1)
        compose.awaitTag("sample-1")
        // CONTENT can arrive after the preceding scroll affected the loading list.
        compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(1)
        click("sample-1")
        compose.awaitTag("detail-content")
        compose.onNodeWithTag("add-mark").performScrollTo().performClick()
        compose.onNodeWithTag("open-reader").performScrollTo().assertIsDisplayed()
        capture("$profile-detail")
        click("open-reader")
        compose.awaitTag("reader-content")
        compose.onNodeWithTag("reader-next").performScrollTo().performClick()
        compose.onNodeWithTag("reader-page").performScrollTo().assertTextEquals("占位页 2 / 3")
        capture("$profile-reader")
        Espresso.pressBack(); Espresso.pressBack()
        click("dock-settings")
        compose.onNodeWithTag("dock-tint").performScrollTo().assertIsDisplayed()
        capture("$profile-settings")
        click("dock-settings-close")
        compose.openSampleSearch()
        compose.onNodeWithTag("search-query").performScrollTo().performTextInput("orbit")
        compose.waitUntil(5000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.isVisible(WindowInsetsCompat.Type.ime()) }
        compose.onNodeWithTag("liquid-bottom-bar").assertDoesNotExist()
        compose.onNodeWithTag("search-query").assertIsDisplayed()
        capture("$profile-ime")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("liquid-bottom-bar").assertDoesNotExist()
        Espresso.pressBack()
        compose.awaitTag("liquid-bottom-bar")
        click("switch-world")
        compose.awaitTag("root-title")
        bars(PrototypeWorld.NSFW)
        assertTextFits("root-title")
        capture("$profile-private")
        // Explicit dock traversal follows stable visual order in both spaces.
        assertEquals(3f,compose.onNodeWithTag("switch-world").fetchSemanticsNode().config[SemanticsProperties.TraversalIndex])
        val old = compose.activity
        compose.runOnUiThread { old.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10000) { compose.activity !== old }
        compose.awaitTag("root-title")
        bars(PrototypeWorld.NSFW)
        capture("$profile-landscape")
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun realEdgeBackGesture_returnsFromDetail_andRootIsUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val navigationMode = android.provider.Settings.Secure.getInt(compose.activity.contentResolver,"navigation_mode",0)
        assertEquals("Run with gestural navigation overlay enabled",2,navigationMode)
        compose.awaitTag("sample-1")
        click("sample-1"); compose.awaitTag("detail-content")
        val metrics = compose.activity.resources.displayMetrics
        val y = metrics.heightPixels * .5f
        val down = SystemClock.uptimeMillis()
        for (i in 0..12) {
            val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),when(i) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },2f + metrics.widthPixels * .4f * i/12,y,0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true)); event.recycle()
            SystemClock.sleep(16)
        }
        compose.awaitTag("root-title")
        bars(PrototypeWorld.SFW)
        capture("gesture-edge-back")
        File(evidence,"gesture-result.txt").writeText("PASS navigation_mode=2; injected real left-edge swipe; detail -> public library; dock safe insets\n")
    }
}
