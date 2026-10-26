package com.flux.manga

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.ui.glass.GlassPalette
import com.flux.manga.ui.glass.GlassTokens
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassEffectsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private val evidence get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "glass-effects-evidence").apply { mkdirs() }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag.startsWith("scene-") || tag in listOf("glass-blur", "glass-tint", "glass-step", "glass-editor", "glass-sampling")) {
            node.performScrollTo()
        }
        node.performClick()
    }
    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("glass-lab").captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }
    private fun bounds(tag: String): Rect {
        val host = compose.onNodeWithTag("glass-lab").fetchSemanticsNode().boundsInRoot
        return compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.translate(-host.topLeft)
    }
    private fun regions() = listOf(bounds("glass-circle"), bounds("glass-pod"))
    private fun points(regions: List<Rect>, visit: (Int, Int) -> Unit) {
        for (r in regions) for (y in (r.top + r.height * .3f).roundToInt() until (r.bottom - r.height * .3f).roundToInt() step 2)
            for (x in (r.left + r.width * .3f).roundToInt() until (r.right - r.width * .3f).roundToInt() step 2) visit(x, y)
    }
    private fun error(a: Int, b: Int): Int = max(abs((a shr 16 and 255) - (b shr 16 and 255)),
        max(abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))))
    private fun delta(a: Bitmap, b: Bitmap, areas: List<Rect>): Double {
        var sum = 0.0; var count = 0
        points(areas) { x, y -> sum += error(a.getPixel(x, y), b.getPixel(x, y)); count++ }
        return sum / count
    }
    private fun tintError(source: Bitmap, result: Bitmap, palette: GlassPalette, areas: List<Rect>): Double {
        val tint = palette.tint.copy(alpha = palette.tintAlpha).toArgb()
        var sum = 0.0; var count = 0
        points(areas) { x, y ->
            sum += error(ColorUtils.compositeColors(tint, source.getPixel(x, y)), result.getPixel(x, y)); count++
        }
        return sum / count
    }
    private fun edgeEnergy(image: Bitmap, areas: List<Rect>): Double {
        var sum = 0.0; var count = 0
        points(areas) { x, y ->
            val red = image.getPixel(x, y) shr 16 and 255
            for ((dx, dy) in listOf(1 to 0, 0 to 1)) {
                val d = red - (image.getPixel(x + dx, y + dy) shr 16 and 255)
                sum += d * d; count++
            }
        }
        return sum / count
    }
    private fun assertOutsideUnchanged(raw: Bitmap, filtered: Bitmap, areas: List<Rect>) {
        var checked = 0
        var totalError = 0.0
        var overEight = 0
        // Includes the gap and rounded corners, while excluding the toolbar and outline AA.
        for (y in raw.height / 2 until raw.height step 5) for (x in 0 until raw.width step 5) {
            val outside = areas.all { r ->
                val radius = r.height / 2
                val dx = x - x.toFloat().coerceIn(r.left + radius, r.right - radius)
                val dy = y - r.center.y
                dx * dx + dy * dy > (radius + 4) * (radius + 4)
            }
            if (outside) {
                val e = error(raw.getPixel(x, y), filtered.getPixel(x, y))
                totalError += e
                if (e > 8) overEight++
                checked++
            }
        }
        assertTrue(checked > 1000)
        // Fractionally translated vector edges can differ by a few quantization levels
        // when replayed in another layer. A broad blur/tint leak cannot meet these limits.
        assertTrue("Outside mean error ${totalError / checked}", totalError / checked < .1)
        assertTrue("Outside changed pixels $overEight/$checked", overEight.toDouble() / checked < .001)
    }
    private fun report(name: String, values: JSONObject) {
        File(evidence, "$name.json").writeText(values.toString(2))
        Log.i("FluxGlassEffects", "$name $values")
    }

    @Test fun checkerFourCombinations_areIndependentAndReversible() {
        click("open-glass")
        click("scene-1")
        click("glass-tint") // Raw checker, no fill or foreground; isolate each effect.
        for ((name, palette) in listOf("light" to GlassTokens.Light, "dark" to GlassTokens.Dark)) {
            if (name == "dark") click("glass-theme")
            val areas = regions()
            val raw = capture("$name-checker-00")
            click("glass-tint")
            val tinted = capture("$name-checker-01-tint")
            click("glass-tint")
            click("glass-blur")
            val blurred = capture("$name-checker-10-blur")
            click("glass-tint")
            val combined = capture("$name-checker-11-both")
            val tintOnlyError = tintError(raw, tinted, palette, areas)
            val combinedError = tintError(blurred, combined, palette, areas)
            val reduction = edgeEnergy(blurred, areas) / edgeEnergy(raw, areas)
            val change = delta(raw, blurred, areas)
            report("$name-matrix", JSONObject().put("tintOnlyMeanError", tintOnlyError)
                .put("blurThenTintMeanError", combinedError).put("blurMeanChange", change)
                .put("blurToRawEdgeEnergyRatio", reduction))
            assertTrue("Tint-only composite error $tintOnlyError", tintOnlyError < 1.5)
            assertTrue("Blur then tint composite error $combinedError", combinedError < 1.5)
            assertTrue("Blur should smooth edges: $reduction", reduction < .35)
            assertTrue("Blur must change source", change > 5)
            assertOutsideUnchanged(raw, combined, areas)
            // Transparent black or the diagnostic magenta must not enter the sampled interior.
            points(areas) { x, y ->
                val p = blurred.getPixel(x, y)
                assertTrue("Black edge", (p shr 8 and 255) > 25)
                assertFalse("Sentinel leaked", (p shr 16 and 255) > 220 && (p and 255) > 180 && (p shr 8 and 255) < 70)
            }
            click("glass-tint")
            click("glass-blur")
            assertTrue("Off must restore raw pixels", delta(raw, capture("$name-restored"), areas) < .1)
            repeat(2) { click("glass-blur"); click("glass-blur") }
            assertTrue("Repeated toggles must restore raw", delta(raw, capture("$name-retoggled"), areas) < .1)
        }
    }

    /** Starts and ends with both effects enabled. Checks actual output after each invalidation. */
    private fun imageCase(name: String, palette: GlassPalette): Bitmap {
        compose.onNodeWithTag("glass-blur").assertIsSelected()
        compose.onNodeWithTag("glass-tint").assertIsSelected()
        val areas = regions()
        val combined = capture("$name-both")
        click("glass-tint")
        val blurred = capture("$name-blur")
        click("glass-blur")
        val raw = capture("$name-raw")
        val change = delta(raw, blurred, areas)
        val composite = tintError(blurred, combined, palette, areas)
        val sourceEdges = edgeEnergy(raw, areas)
        val metrics = JSONObject().put("blurMeanChange", change).put("blurThenTintMeanError", composite)
            .put("sourceEdgeEnergy", sourceEdges)
        report(name, metrics)
        // A uniform source remains uniform under blur (e.g. below the final list item).
        if (sourceEdges > 1 || name == "images-initial" || name == "images-moved") {
            assertTrue("$name textured blur output unchanged: $change", change > .5)
        }
        assertTrue("$name wrong tint: $composite", composite < 1.5)
        assertOutsideUnchanged(raw, combined, areas)
        click("glass-blur")
        click("glass-tint")
        click("glass-sampling")
        val disabled = capture("$name-sampling-off")
        assertTrue("$name sampling off must bypass both effects", delta(raw, disabled, areas) < .1)
        click("glass-sampling")
        val restoredDelta = delta(combined, capture("$name-reenabled"), areas)
        report(name, metrics.put("reenableMeanError", restoredDelta))
        assertTrue("$name re-enable stale pixels: $restoredDelta", restoredDelta < 1)
        return combined
    }

    @Test fun imageEffects_followMovementInsetsLifecycleAndKeepForegroundSharp() {
        click("open-glass")
        click("scene-2")
        click("glass-blur")
        val initial = imageCase("images-initial", GlassTokens.Light)
        click("glass-step")
        val moved = imageCase("images-moved", GlassTokens.Light)
        assertTrue("Moving source must refresh blur", delta(initial, moved, regions()) > .5)
        compose.onNodeWithTag("glass-content").performTouchInput {
            swipeUp(startY = height * .7f, endY = height * .5f, durationMillis = 1000)
        }
        imageCase("images-scrolled", GlassTokens.Light)
        click("glass-theme")
        imageCase("images-dark", GlassTokens.Dark)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        imageCase("images-resumed", GlassTokens.Dark)
        click("glass-editor")
        val normalBottom = bounds("study-dock").bottom
        compose.onNodeWithTag("glass-input").performClick().performTextInput("effects")
        compose.waitUntil(5_000) { bounds("study-dock").bottom < normalBottom - 100 }
        imageCase("images-ime", GlassTokens.Dark)
        Espresso.closeSoftKeyboard()
        click("glass-editor")
        val old = compose.activity
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity !== old }
            imageCase("images-landscape", GlassTokens.Dark)
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        click("scene-0")
        for (theme in listOf("dark", "light")) {
            if (theme == "light") click("glass-theme")
            val combined = capture("$theme-style-both")
            click("glass-blur")
            val clear = capture("$theme-style-blur-off")
            // The selected pill is opaque: its central icon/label pixels must be identical.
            val selected = bounds("study-tab-1")
            assertTrue("$theme foreground was blurred", delta(combined, clear, listOf(selected)) < .1)
            click("glass-blur")
        }
        click("study-tab-0")
        compose.onNodeWithTag("study-tab-0").assertIsSelected()
        click("glass-back")
        compose.onNodeWithTag("open-glass").assertIsDisplayed()
        Log.i("FluxGlassEffects", "PASS movement, scroll, theme, background, IME, rotation, sharp foreground and input")
    }
}
