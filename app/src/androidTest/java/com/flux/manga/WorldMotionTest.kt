package com.flux.manga

import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.glass.GlassTokens
import com.flux.manga.ui.glass.activeGlassHosts
import com.flux.manga.ui.glass.revealRadius
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private lateinit var profile: String
    @Before fun recordProfile() {
        val config = compose.activity.resources.configuration
        profile = "${config.screenWidthDp}dp-${config.fontScale}x"
    }
    private val evidence get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "m1-motion-evidence/$profile").apply { mkdirs() }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun step(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun capture(name: String): Bitmap {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
    private fun gate() = CompletableDeferred<Unit>().also { gate ->
        compose.runOnIdle { compose.activity.fixtureStore = FixtureWorldStore(compose.activity) { gate.await() } }
    }
    private fun root(world: PrototypeWorld) {
        compose.waitUntil(5_000) { compose.activity.worldSession.identity.value.world == world && !compose.activity.liquidMotion.pending }
        compose.awaitTag("sample-1")
    }

    @Test fun twoDirections_measuredMotionAndRadialPixels_commitDoesNotWaitForAnimation() {
        compose.awaitTag("sample-1")
        for (destination in listOf(PrototypeWorld.NSFW, PrototypeWorld.SFW)) {
            val ready = gate()
            val initialLabel = bounds("dock-label-LIBRARY")
            val button = bounds("switch-world")
            val expectedOrigin = button.topLeft + Offset(button.width * .3f, button.height * .6f)
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("switch-world").performTouchInput { click(Offset(width * .3f, height * .6f)) }
                step(16)
                val motion = compose.activity.liquidMotion
                assertEquals(expectedOrigin.x, motion.originInWindow!!.x, 2f)
                assertEquals(expectedOrigin.y, motion.originInWindow!!.y, 2f)
                val generation = compose.activity.worldSession.identity.value.generation
                compose.runOnUiThread { compose.activity.requestWorldSwitch() }
                assertEquals(generation, compose.activity.worldSession.identity.value.generation)
                compose.onNodeWithTag("switch-world").assertIsNotEnabled()
                compose.onNodeWithTag("tab-HOME").assertIsEnabled()
                val frames = JSONArray()
                var peak = 0f
                repeat(60) { frame ->
                    step(16)
                    val circle = bounds("dock-circle")
                    val pod = bounds("dock-pod")
                    val label = bounds("dock-label-LIBRARY")
                    val scale = 1f + .22f * motion.circleSpeed()
                    peak = maxOf(peak, scale)
                    val screen = bounds("safe-radial-reveal")
                    val expansion = circle.width * (scale - 1) / 2
                    assertTrue("circle clipped", circle.left - expansion >= 0 && circle.right + expansion <= screen.right && circle.top >= 0)
                    assertEquals(initialLabel.width, label.width, 1f)
                    assertEquals(initialLabel.height, label.height, 1f)
                    assertTrue(1f + .08f * motion.podSpeed() <= 1.08f)
                    assertTrue(1f - .12f * motion.circleSpeed() >= .88f)
                    frames.put(JSONObject().put("ms",(frame+1)*16).put("circleX",circle.left).put("circleY",circle.top)
                        .put("podX",pod.left).put("circleScaleX",scale).put("circleVelocity",motion.circle.velocity).put("podVelocity",motion.pod.velocity))
                    if (frame == 5) {
                        val image = capture("$destination-spring")
                        val background = if (destination == PrototypeWorld.NSFW) GlassTokens.Light.background.toArgb() else GlassTokens.Dark.background.toArgb()
                        val row = circle.center.y.toInt()
                        val materialPixels = ((circle.left-circle.width*.3f).toInt()..(circle.right+circle.width*.3f).toInt())
                            .filter { x -> image.getPixel(x,row) != background }
                        val measuredWidth = materialPixels.last() - materialPixels.first() + 1
                        assertEquals("Velocity deformation not rendered", circle.width * scale, measuredWidth.toFloat(), 6f)
                        val stretchedFill = image.getPixel((circle.left - expansion / 2).toInt(), row)
                        val innerFill = image.getPixel((circle.left + circle.width * .15f).toInt(), row)
                        assertNotEquals("Stretched surface has no fill", background, stretchedFill)
                        assertEquals("Fill/tint did not follow the stretched outline", innerFill, stretchedFill)
                        File(evidence, "$destination-shape.json").writeText(JSONObject().put("scaleX",scale)
                            .put("layoutWidth",circle.width).put("paintedWidth",measuredWidth)
                            .put("stretchedFill",stretchedFill).put("innerFill",innerFill).toString(2))
                        image.recycle()
                    }
                    if (frame % 4 == 0) capture("$destination-motion-${(frame/4).toString().padStart(3,'0')}").recycle()
                }
                assertTrue("No deformation", peak > 1.05f)
                assertFalse("spring did not settle", motion.moving)
                assertEquals(0f, motion.circleSpeed(), 0f)
                File(evidence, "$destination-frames.json").writeText(frames.toString(2))
                compose.onNodeWithTag("sample-1").assertDoesNotExist()
                assertEquals(0, activeGlassHosts)
                ready.complete(Unit)
                // Real coroutine time commits and reaches Stable while the UI clock is paused.
                compose.waitUntil(5_000) { compose.activity.worldSession.state.value.phase == SwitchPhase.Stable }
                assertEquals(destination, compose.activity.worldSession.identity.value.world)
                assertTrue(motion.pending)
                step(32)
                assertTrue(motion.revealing)
                capture("$destination-reveal-000").recycle()
                repeat(3) { frame ->
                    step(64)
                    capture("$destination-reveal-${(frame+1).toString().padStart(3,'0')}").recycle()
                }
                val bitmap = capture("$destination-radial")
                val center = motion.originInWindow!!
                val radius = revealRadius(Size(bitmap.width.toFloat(), bitmap.height.toFloat()), center) * motion.reveal.value
                val oldColor = if (destination == PrototypeWorld.NSFW) GlassTokens.Light.background.toArgb() else GlassTokens.Dark.background.toArgb()
                val newColor = if (destination == PrototypeWorld.NSFW) GlassTokens.Dark.background.toArgb() else GlassTokens.Light.background.toArgb()
                var oldPoints = 0; var newPoints = 0; var errors = 0
                val sampleTop = bounds("switch-phase").bottom.toInt() + 30
                val sampleBottom = bounds("liquid-bottom-bar").top.toInt() - 30
                for (y in sampleTop until sampleBottom step 25) for (x in 30 until bitmap.width - 30 step 25) {
                    val distance = hypot(x - center.x, y - center.y)
                    if (abs(distance-radius) < 4) continue
                    val expected = if (distance < radius) { newPoints++; newColor } else { oldPoints++; oldColor }
                    if (bitmap.getPixel(x,y) != expected) errors++
                }
                assertTrue("No inner pixels", newPoints > 50)
                assertTrue("No outer pixels", oldPoints > 50)
                assertEquals("Radial layer contains unexpected content", 0, errors)
                File(evidence, "$destination-radial.json").writeText(JSONObject().put("progress",motion.reveal.value)
                    .put("centerX",center.x).put("centerY",center.y).put("radius",radius)
                    .put("innerPoints",newPoints).put("outerPoints",oldPoints).put("errors",errors).toString(2))
                bitmap.recycle()
                compose.onNodeWithTag("sample-1").assertDoesNotExist()
                repeat(5) { frame ->
                    step(64)
                    capture("$destination-reveal-${(frame+4).toString().padStart(3,'0')}").recycle()
                }
            } finally { compose.mainClock.autoAdvance = true }
            root(destination)
            capture("$destination-settled").recycle()
        }
        Log.i("FluxMotionTest", "PASS both directions: real touch origin, measured spring/shape bounds, unchanged label sizes, solid-color radial pixels, commit while animation clock paused")
    }

    @Test fun failedPreparation_retargetsCurrentPosition_andTabsRemainUsable() {
        compose.awaitTag("sample-1")
        val ready = gate()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { compose.activity.requestWorldSwitch(fail = true) }
            step(96)
            val motion = compose.activity.liquidMotion
            compose.onNodeWithTag("tab-HOME").assertIsEnabled().performClick()
            val before = motion.circleValue()
            val beforeTime = compose.mainClock.currentTime
            assertTrue(before > 0f && before < 1f)
            ready.complete(Unit)
            compose.waitUntil(5_000) { compose.activity.worldSession.state.value.error != null }
            step(16)
            val after = motion.circleValue()
            assertTrue("Rollback jumped: $before -> $after in ${compose.mainClock.currentTime-beforeTime}ms", abs(after-before) < .2f)
            assertEquals(PrototypeWorld.SFW, motion.target)
            capture("rollback-in-flight").recycle()
            step(1200)
            assertEquals(0f, motion.circleValue(), 0f)
            assertEquals(0f, motion.podValue(), 0f)
            assertFalse(motion.pending)
            File(evidence, "rollback.json").writeText(JSONObject().put("before",before).put("afterRetarget",after).toString(2))
        } finally { compose.mainClock.autoAdvance = true }
        compose.awaitTag("switch-error")
        root(PrototypeWorld.SFW)
        Log.i("FluxMotionTest", "PASS failure redirects current position/velocity, tabs remain enabled, settles to public without old page")
    }

    @Test fun pauseBeforeCommit_andRotationDuringReveal_snapToPermittedWorld() {
        compose.awaitTag("sample-1")
        gate()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { compose.activity.requestWorldSwitch() }
            step(80)
            val activity = compose.activity
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            assertTrue(activity.isCovered)
            assertFalse(activity.liquidMotion.pending)
            assertEquals(PrototypeWorld.SFW, activity.worldSession.identity.value.world)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            step(32)
        } finally { compose.mainClock.autoAdvance = true }
        root(PrototypeWorld.SFW)
        val ready = gate()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { compose.activity.requestWorldSwitch() }
            step(32)
            ready.complete(Unit)
            compose.waitUntil(5_000) { compose.activity.worldSession.identity.value.world == PrototypeWorld.NSFW }
            step(64)
            assertTrue(compose.activity.liquidMotion.revealing)
            val old = compose.activity
            compose.runOnUiThread { old.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity !== old }
            step(64)
            assertFalse(compose.activity.liquidMotion.pending)
            assertEquals(1f, compose.activity.liquidMotion.circleValue(), 0f)
        } finally { compose.mainClock.autoAdvance = true }
        try {
            compose.waitUntil(5_000) { compose.activity.worldSession.identity.value.world == PrototypeWorld.NSFW && !compose.activity.liquidMotion.pending }
            // Private saved state (including the debug sample-page choice) is not restored.
            compose.awaitTag("reference-shelf")
            capture("rotation-committed-private").recycle()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        Log.i("FluxMotionTest", "PASS pause before commit -> SFW; real rotation during committed reveal -> NSFW endpoint, no replayed motion or saved visual snapshots")
    }
}

@RunWith(AndroidJUnit4::class)
class MotionDisabledTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    @Test fun platformAnimationOff_usesEndpointsWithoutWaitingForVisualClock() {
        compose.awaitTag("sample-1")
        assertFalse("Run with animator_duration_scale=0", ValueAnimator.areAnimatorsEnabled())
        compose.onNodeWithTag("switch-world").performClick()
        compose.awaitTag("sample-1")
        compose.waitUntil(5_000) { compose.activity.worldSession.identity.value.world == PrototypeWorld.NSFW && !compose.activity.liquidMotion.pending }
        assertEquals(1f, compose.activity.liquidMotion.circleValue(), 0f)
        assertEquals(1f, compose.activity.liquidMotion.podValue(), 0f)
        assertFalse(compose.activity.liquidMotion.revealing)
        Log.i("FluxMotionTest", "PASS platform animation disabled: direct endpoints, valid committed world, no visual wait")
    }
}
