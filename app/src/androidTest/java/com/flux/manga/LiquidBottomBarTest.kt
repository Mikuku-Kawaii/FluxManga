package com.flux.manga

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.session.SwitchPhase
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.ui.glass.GlassTokens
import com.flux.manga.ui.glass.activeGlassHosts
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidBottomBarTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private val evidence get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "m1-dock-evidence").apply { mkdirs() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performClick() }
    private fun effect(tag: String) {
        click("dock-settings"); compose.onNodeWithTag(tag).performScrollTo().performClick(); click("dock-settings-close")
    }
    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun awaitWorld(world: PrototypeWorld) {
        compose.waitUntil(5_000) { compose.activity.worldSession.identity.value.world == world && compose.activity.worldSession.state.value.phase == SwitchPhase.Stable }
        compose.awaitTag("sample-1")
    }
    private fun targets() {
        val density = compose.activity.resources.displayMetrics.density
        for (tag in listOf("switch-world", "tab-HOME", "tab-LIBRARY", "tab-TAGS")) {
            val b = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width=${b.width / density}", b.width / density >= 48f)
            assertTrue("$tag height=${b.height / density}", b.height / density >= 48f)
        }
        listOf("HOME", "LIBRARY", "TAGS").forEach { tab ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("dock-label-$tab", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("$tab clipped: size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, constraints=${layout.layoutInput.constraints}", layout.hasVisualOverflow)
        }
    }

    @Test fun layoutsTargetsAndThemes() {
        compose.awaitTag("root-title")
        val config = compose.activity.resources.configuration
        val suffix = "${config.screenWidthDp}dp-${config.fontScale}x"
        targets()
        assertTrue(bounds("dock-circle").right < bounds("dock-pod").left)
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
        capture("$suffix-light-base").recycle()
        click("dock-settings")
        compose.onNodeWithTag("dock-sampling").assertIsOff()
        compose.onNodeWithTag("dock-blur").assertIsOff()
        compose.onNodeWithTag("dock-tint").assertIsOn()
        click("dock-settings-close")
        assertEquals(0, activeGlassHosts)
        click("switch-world")
        awaitWorld(PrototypeWorld.NSFW)
        targets()
        assertTrue(bounds("dock-pod").right < bounds("dock-circle").left)
        capture("$suffix-dark-base").recycle()
        Log.i("FluxDockTest", "PASS $suffix: opposite circle/pod layout, 48dp targets, selected semantics, unclipped labels, base defaults without recorded layers")
    }

    private fun delta(a: Bitmap, b: Bitmap, rect: Rect): Double {
        var sum = 0L; var count = 0
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(a.height) step 2) {
            for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(a.width) step 2) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) { sum += abs((p shr shift and 255) - (q shr shift and 255)); count++ }
            }
        }
        return sum.toDouble() / count.coerceAtLeast(1)
    }

    @Test fun effectsChangeOnlyBackground_andTintComposesAfterBlur() {
        compose.awaitTag("sample-1")
        effect("dock-sampling")
        effect("dock-tint")
        for (world in PrototypeWorld.entries) {
            if (world == PrototypeWorld.NSFW) { click("switch-world"); awaitWorld(world) }
            compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(10)
            val palette = if (world == PrototypeWorld.SFW) GlassTokens.Light else GlassTokens.Dark
            val dock = bounds("liquid-bottom-bar")
            val circle = bounds("dock-circle")
            val raw = capture("$world-raw")
            assertEquals(1, activeGlassHosts)
            effect("dock-blur")
            val blurred = capture("$world-blur")
            effect("dock-tint")
            val tinted = capture("$world-blur-tint")
            val change = delta(raw, blurred, dock)
            // Circle top interior contains no foreground or outline: verify premultiplied composition.
            var compositeError = 0L; var count = 0
            for (y in (circle.top + circle.height * .14f).toInt()..(circle.top + circle.height * .24f).toInt()) {
                for (x in (circle.center.x - circle.width * .10f).toInt()..(circle.center.x + circle.width * .10f).toInt()) {
                    val expected = ColorUtils.compositeColors(palette.tint.copy(alpha = palette.tintAlpha).toArgb(), blurred.getPixel(x,y))
                    val actual = tinted.getPixel(x,y)
                    for (shift in listOf(0,8,16)) { compositeError += abs((expected shr shift and 255) - (actual shr shift and 255)); count++ }
                    assertEquals(255, actual ushr 24)
                }
            }
            val tintError = compositeError.toDouble() / count
            // Compare solid glyph pixels, excluding the transparent corners of the capsule.
            val label = bounds("dock-label-LIBRARY")
            var inkError = 0L; var inkCount = 0
            for (y in label.top.toInt() until label.bottom.toInt()) {
                for (x in label.left.toInt() until label.right.toInt()) {
                    val pixel = raw.getPixel(x,y)
                    if (pixel == palette.foreground.toArgb()) {
                        for (shift in listOf(0,8,16)) {
                            inkError += abs((pixel shr shift and 255) - (tinted.getPixel(x,y) shr shift and 255)); inkCount++
                        }
                    }
                }
            }
            assertTrue("No foreground pixels measured", inkCount > 60)
            val foregroundDelta = inkError.toDouble() / inkCount
            assertTrue("Foreground blurred: $foregroundDelta", foregroundDelta < .1)
            val outside = Rect(30f, dock.top - 250f, raw.width - 30f, dock.top - 25f)
            assertTrue("Effects modified page", delta(raw, tinted, outside) < .1)
            assertTrue("Blur did not change background: $change", change > .05)
            assertTrue("Tint order/alpha error=$tintError", tintError < 1.5)
            File(evidence, "$world-pixels.json").writeText(JSONObject().put("blurMeanDelta",change).put("tintMeanError",tintError)
                .put("foregroundDelta",foregroundDelta).put("foregroundChannels",inkCount).put("outsideDelta",delta(raw,tinted,outside)).toString(2))
            effect("dock-tint"); effect("dock-blur")
            val restored = capture("$world-restored")
            assertTrue("Effect toggle retained stale pixels", delta(raw, restored, dock) < .1)
            compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(20)
            // Whole rows have identical text placement; offset vertically so the sampled
            // background changes outside the opaque selected-tab foreground as well.
            val offset = 24f * compose.activity.resources.displayMetrics.density
            compose.onNodeWithTag("list-LIBRARY").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, offset) }
            val moved = capture("$world-scrolled")
            assertTrue("Scroll did not update sampled background", delta(raw, moved, dock) > .05)
            listOf(raw,blurred,tinted,restored,moved).forEach { it.recycle() }
        }
        Log.i("FluxDockTest", "PASS integrated light/dark sampling, blur/tint order and alpha, sharp foreground, outside content unchanged, toggle restoration and scroll refresh")
    }

    @Test fun sessionBackgroundImeAndChildrenReleaseLayers() {
        compose.awaitTag("sample-1")
        effect("dock-sampling"); effect("dock-blur")
        assertEquals(1, activeGlassHosts)
        click("sample-1"); compose.awaitTag("detail-content")
        compose.onNodeWithTag("liquid-bottom-bar").assertDoesNotExist()
        assertEquals(0, activeGlassHosts)
        Espresso.pressBack(); compose.awaitTag("liquid-bottom-bar")
        assertEquals(1, activeGlassHosts)
        compose.openSampleSearch()
        compose.onNodeWithTag("search-query").performClick().performTextInput("orbit")
        compose.waitUntil(5_000) { activeGlassHosts == 0 }
        compose.onNodeWithTag("liquid-bottom-bar").assertDoesNotExist()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("liquid-bottom-bar").assertDoesNotExist()
        Espresso.pressBack(); compose.awaitTag("liquid-bottom-bar")
        val activity = compose.activity
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(5_000) { activeGlassHosts == 0 }
        assertTrue(activity.isCovered)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { activeGlassHosts == 1 }
        val preparation = CompletableDeferred<Unit>()
        compose.runOnIdle {
            compose.activity.fixtureStore = FixtureWorldStore(compose.activity) { preparation.await() }
            compose.activity.requestWorldSwitch()
        }
        compose.awaitTag("switch-phase")
        assertEquals(0, activeGlassHosts)
        compose.onNodeWithTag("switch-world").assertIsNotEnabled()
        compose.onNodeWithTag("tab-HOME").assertIsEnabled()
        preparation.complete(Unit)
        awaitWorld(PrototypeWorld.NSFW)
        assertEquals(1, activeGlassHosts)
        capture("private-fresh-source").recycle()
        click("switch-failure"); compose.awaitTag("switch-error")
        compose.waitUntil(5_000) { activeGlassHosts == 1 }
        assertEquals(PrototypeWorld.SFW, compose.activity.worldSession.identity.value.world)
        Log.i("FluxDockTest", "PASS layers released on child/IME/pause/preparing, rebuilt for committed world/failure fallback; window cover and switch debounce retained")
    }

    @Test fun lastItemSettingsAndRotationKeepPageState() {
        compose.awaitTag("sample-1")
        compose.onNodeWithTag("list-LIBRARY").performScrollToIndex(59)
        compose.onNodeWithTag("sample-60").assertIsDisplayed()
        assertTrue("Last item covered by dock", bounds("sample-60").bottom <= bounds("liquid-bottom-bar").top)
        effect("dock-sampling"); effect("dock-blur")
        compose.onNodeWithTag("sample-60").assertIsDisplayed()
        val beforeScroll = compose.onNodeWithTag("list-LIBRARY").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.activityRule.scenario.recreate()
        compose.awaitTag("sample-60")
        assertEquals(beforeScroll, compose.onNodeWithTag("list-LIBRARY").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
        click("dock-settings")
        compose.onNodeWithTag("dock-sampling").assertIsOn(); compose.onNodeWithTag("dock-blur").assertIsOn()
        click("dock-settings-close")
        val old = compose.activity
        try {
            compose.runOnIdle { old.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity !== old }
            compose.awaitTag("liquid-bottom-bar")
            capture("landscape-layout").recycle()
            targets()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        Log.i("FluxDockTest", "PASS last row clears measured dock, effects do not reset list, config retains preferences/scroll, real landscape layout")
    }
}
