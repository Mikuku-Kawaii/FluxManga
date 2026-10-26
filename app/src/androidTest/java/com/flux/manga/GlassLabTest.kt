package com.flux.manga

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.ui.glass.GlassTokens
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassLabTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private val evidence get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "glass-evidence").apply { mkdirs() }

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun open() { compose.onNodeWithTag("open-glass").performClick() }

    @Test fun checkerSampling_matchesAfterMovementScrollImeAndRotation() {
        open()
        click("scene-1")
        click("glass-tint")
        val first = compareSource("checker-initial")
        click("glass-step")
        val moved = compareSource("checker-moved")
        assertNotEquals("Moving background must change sampled pixels", first, moved)
        compose.onNodeWithTag("glass-content").performTouchInput { swipeUp() }
        compareSource("checker-scrolled")
        click("glass-editor")
        val normalBottom = compose.onNodeWithTag("study-dock").fetchSemanticsNode().boundsInRoot.bottom
        compose.onNodeWithTag("glass-input").performClick().performTextInput("insets")
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("study-dock").fetchSemanticsNode().boundsInRoot.bottom < normalBottom - 100
        }
        compareSource("checker-ime")
        Espresso.closeSoftKeyboard()
        click("glass-editor")
        val oldActivity = compose.activity
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity !== oldActivity }
            compose.waitForIdle()
            compareSource("checker-landscape")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        Log.i("FluxGlassTest", "PASS checker: movement, real scroll, IME displacement and real rotation match the underlying source")
    }

    @Test fun imageSampling_excludesOverlayAndRefreshesAfterThemeAndBackground() {
        open()
        click("scene-2")
        click("glass-tint")
        compareSource("images-initial")
        click("glass-step")
        compareSource("images-moved")
        compose.onNodeWithTag("glass-content").performTouchInput { swipeUp() }
        compareSource("images-scrolled")
        compose.onNodeWithTag("glass-theme").performClick()
        compareSource("images-dark")
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(5_000) { com.flux.manga.ui.glass.activeGlassHosts == 0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compareSource("images-resumed")
        Log.i("FluxGlassTest", "PASS images: raw replay over opaque sentinel equals source; pause removes sampling host; dark source replacement and background resume match")
    }

    @Test fun stylesTargetsContrastAndSpring() {
        open()
        if (InstrumentationRegistry.getArguments().getString("glassEffects") == "true") click("glass-blur")
        val config = compose.activity.resources.configuration
        val suffix = "${config.screenWidthDp}dp-${config.fontScale}x"
        save("light-$suffix")
        assertTargets()
        val initial = compose.onNodeWithTag("glass-circle").fetchSemanticsNode().boundsInRoot.left
        val circleWidth = compose.onNodeWithTag("glass-circle").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("glass-theme").performClick()
        val motion = JSONArray()
        repeat(60) { frame ->
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            assertEquals("Circle clipped during exchange", circleWidth,
                compose.onNodeWithTag("glass-circle").fetchSemanticsNode().boundsInRoot.width, 1f)
            motion.put(JSONObject().put("ms", (frame + 1) * 16)
                .put("circleX", compose.onNodeWithTag("glass-circle").fetchSemanticsNode().boundsInRoot.left)
                .put("podX", compose.onNodeWithTag("glass-pod").fetchSemanticsNode().boundsInRoot.left))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTargets()
        val final = compose.onNodeWithTag("glass-circle").fetchSemanticsNode().boundsInRoot.left
        assertTrue(final > initial + 100)
        assertTrue(abs(motion.getJSONObject(59).getDouble("circleX") - final) < 2)
        File(evidence, "spring-$suffix.json").writeText(motion.toString(2))
        save("dark-$suffix")
        val contrasts = JSONArray()
        for ((name, palette) in listOf("light" to GlassTokens.Light, "dark" to GlassTokens.Dark)) {
            for (tintEnabled in listOf(false, true)) for (base in listOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE)) {
                val filled = ColorUtils.compositeColors(palette.fill.toArgb(), base)
                val background = if (tintEnabled) ColorUtils.compositeColors(
                    palette.tint.copy(alpha = palette.tintAlpha).toArgb(), filled) else filled
                val ratio = ColorUtils.calculateContrast(palette.foreground.toArgb(), background)
                assertTrue("$name worst-case foreground contrast=$ratio", ratio >= 4.5)
                contrasts.put(JSONObject().put("theme", name).put("tint", tintEnabled).put("base", base).put("ratio", ratio))
            }
        }
        File(evidence, "contrast.json").writeText(contrasts.toString(2))
        Log.i("FluxGlassTest", "PASS styles $suffix: light/dark, >=48dp targets, unclipped labels, contrast >=4.5, spring settled at 960ms")
    }

    private fun assertTargets() {
        val density = compose.activity.resources.displayMetrics.density
        for (tag in listOf("glass-circle", "study-tab-0", "study-tab-1", "study-tab-2")) {
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width=${bounds.width / density}", bounds.width / density >= 48f)
            assertTrue("$tag height=${bounds.height / density}", bounds.height / density >= 48f)
        }
        repeat(3) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("study-label-$index", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            val layout = layouts.single()
            assertFalse("Label $index clipped: size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, lines=${layout.lineCount}, style=${layout.layoutInput.style}", layout.hasVisualOverflow)
        }
    }

    private fun compareSource(name: String): Long {
        compose.waitForIdle()
        val host = compose.onNodeWithTag("glass-lab").fetchSemanticsNode().boundsInRoot
        val regions = listOf("glass-circle", "glass-pod").map { tag ->
            compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.translate(-host.topLeft)
        }
        val sampled = compose.onNodeWithTag("glass-lab").captureToImage()
        saveImage(name, sampled.asAndroidBitmap())
        click("glass-sampling")
        val original = compose.onNodeWithTag("glass-lab").captureToImage()
        click("glass-sampling")
        val actual = sampled.toPixelMap()
        val expected = original.toPixelMap()
        var compared = 0
        var mismatched = 0
        var sum = 0.0
        var hash = 17L
        for (region in regions) {
            // Stay inside the rounded outline; compare grid edges and image details within it.
            val inner = Rect(region.left + region.width * .3f, region.top + region.height * .3f,
                region.right - region.width * .3f, region.bottom - region.height * .3f)
            for (y in inner.top.roundToInt() until inner.bottom.roundToInt() step 2) {
                for (x in inner.left.roundToInt() until inner.right.roundToInt() step 2) {
                    val a = actual[x, y]
                    val b = expected[x, y]
                    val error = max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue))) * 255
                    if (error > 8) mismatched++
                    sum += error
                    compared++
                    hash = hash * 31 + a.toArgb()
                }
            }
        }
        val mean = sum / compared
        val ratio = mismatched.toDouble() / compared
        val result = JSONObject().put("case", name).put("comparedPixels", compared)
            .put("meanMaxChannelError", mean).put("fractionErrorOver8", ratio)
            .put("sampleHash", hash)
        File(evidence, "$name.json").writeText(result.toString(2))
        Log.i("FluxGlassTest", result.toString())
        assertTrue("$name mean error $mean", mean < 1.0)
        assertTrue("$name mismatch ratio $ratio", ratio < .01)
        return hash
    }

    private fun save(name: String) = saveImage(name, compose.onNodeWithTag("glass-lab").captureToImage().asAndroidBitmap())
    private fun saveImage(name: String, bitmap: Bitmap) {
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
