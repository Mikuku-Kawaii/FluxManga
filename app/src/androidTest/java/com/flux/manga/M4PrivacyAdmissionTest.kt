package com.flux.manga

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.glass.activeGlassHosts
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Privacy-only checks: synthetic pages, no history/progress writes or persistent settings. */
@RunWith(AndroidJUnit4::class)
class M4PrivacyAdmissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun step(name: String, action: () -> Unit) {
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "m4-followup").apply { mkdirs() }
        val file = java.io.File(directory, "short-resume-stages.txt")
        file.appendText("${SystemClock.elapsedRealtime()} START $name\n")
        val watchdog = Thread {
            try {
                Thread.sleep(8_000)
                file.appendText("WAIT $name\n" + Thread.getAllStackTraces().entries.joinToString("\n") {
                    "${it.key.name} ${it.key.state}\n${it.value.joinToString("\n")}" } + "\n")
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        try { action(); file.appendText("${SystemClock.elapsedRealtime()} END $name\n") }
        catch (failure: Throwable) { file.appendText("FAIL $name: ${failure.javaClass.simpleName}\n"); throw failure }
        finally { watchdog.interrupt() }
    }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()
    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun awaitLayers(count: Int) {
        // The caller dispatches pending composition first; polling must not await a drawable root.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (activeGlassHosts != count && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertEquals(count, activeGlassHosts)
    }
    private fun awaitWorld(world: PrototypeWorld) {
        compose.waitUntil(10_000) {
            compose.activity.worldSession.let {
                it.identity.value.world == world && it.state.value.phase == SwitchPhase.Stable
            } && !compose.activity.isCovered
        }
        compose.awaitTag("main-tabs")
    }
    private fun sampleWithLayers() {
        compose.useSamplePages()
        click("dock-settings")
        compose.onNodeWithTag("dock-sampling").assertIsOff().performScrollTo().performClick()
        compose.onNodeWithTag("dock-blur").assertIsOff()
        compose.onNodeWithTag("dock-tint").assertIsOn()
        click("dock-settings-close")
        compose.waitUntil(5_000) { activeGlassHosts == 1 }
    }
    @Test fun privatePauseReleasesLayersAndShortResumeKeepsWorld() {
        step("sample and sampling") { sampleWithLayers() }
        step("switch private") { click("switch-world"); awaitWorld(PrototypeWorld.NSFW) }
        assertTrue(secure())
        val oldIdentity = compose.activity.worldSession.identity.value
        val activity = compose.activity
        try {
            step("move CREATED") { compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) }
            assertTrue(activity.isCovered)
            step("dispatch paused composition") { repeat(2) { compose.mainClock.advanceTimeByFrame() } }
            step("layers released") { awaitLayers(0) }
        } finally {
            // Wake the real Activity before ActivityScenario waits for an idle loop. On this
            // device the stopped test otherwise hides an assertion failure in rule cleanup.
            activity.startActivity(android.content.Intent(activity, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        step("move RESUMED") { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        step("private exposed") { awaitWorld(PrototypeWorld.NSFW) }
        assertEquals(oldIdentity, activity.worldSession.identity.value)
        compose.waitUntil(5_000) { activeGlassHosts == 1 }
        assertTrue(secure())
    }
    @Test fun privateSavedBundleDropsRoutesAndUncommittedSwitchFallsBack() {
        sampleWithLayers()
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle {
            compose.activity.fixtureStore = FixtureWorldStore(compose.activity) { gate.await() }
            compose.activity.requestWorldSwitch()
            assertTrue(compose.activity.isCovered)
            assertTrue(secure())
        }
        compose.waitUntil(5_000) { activeGlassHosts == 0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitWorld(PrototypeWorld.SFW)
        gate.complete(Unit)
        compose.runOnIdle { compose.activity.fixtureStore = FixtureWorldStore(compose.activity) }
        click("switch-world"); awaitWorld(PrototypeWorld.NSFW)
        assertTrue(secure())
        val saved = Bundle()
        compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, saved)
        }
        assertEquals(setOf("private-world"), saved.keySet())
        assertTrue(saved.getBoolean("private-world"))
        compose.activityRule.scenario.recreate()
        awaitWorld(PrototypeWorld.NSFW)
        assertTrue(secure())
        compose.awaitTag("reference-shelf")
        assertTrue(compose.activity.navigation.stacks.values.all { it.size == 1 })
        assertEquals(0, activeGlassHosts) // Saved sampling state was also discarded.
        click("switch-world"); awaitWorld(PrototypeWorld.SFW)
        assertFalse(secure())
    }
}
