package com.flux.manga

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.SyntheticPrivateReaderRepository
import com.flux.manga.data.WorldDataStore
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.PrototypeWorld
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** One-time real debug route: skip if its fixed synthetic namespace already holds user progress. */
class PrivateReaderUiAdmissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun awaitWorld(world: PrototypeWorld) = compose.waitUntil(10_000) {
        compose.activity.worldSession.let { it.identity.value.world == world && it.state.value.phase == SwitchPhase.Stable } && !compose.activity.isCovered
    }
    private fun awaitPixels() = compose.waitUntil(10_000) {
        compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun realPrivatePixelsHideInBackgroundAndDeepRouteIsDroppedOnRecreation() {
        val data = WorldDataStore(compose.activity, SyntheticPrivateReaderRepository.NAMESPACE)
        assumeTrue("Preserve preexisting synthetic progress", !data.directory(PrototypeWorld.NSFW).exists())
        compose.useSamplePages()
        compose.onNodeWithTag("switch-world").performClick()
        awaitWorld(PrototypeWorld.NSFW)
        compose.runOnIdle { compose.activity.navigation.openDetail("1") }
        compose.awaitTag("detail-content")
        compose.onNodeWithTag("open-reader").performScrollTo().performClick()
        compose.onNodeWithTag("open-private-synthetic-reader").performScrollTo().performClick()
        compose.awaitTag("private-synthetic-reader"); awaitPixels()
        compose.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
        assertTrue(secure())
        val activity = compose.activity
        val identity = activity.worldSession.identity.value
        val saved = Bundle()
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, saved) }
        assertEquals(setOf("private-world"), saved.keySet())
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(activity.isCovered)
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithContentDescription("第 1 页").assertDoesNotExist()
        } finally {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitWorld(PrototypeWorld.NSFW); awaitPixels()
        assertEquals(identity, activity.worldSession.identity.value); assertTrue(secure())
        compose.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        awaitWorld(PrototypeWorld.NSFW)
        compose.onNodeWithTag("private-synthetic-reader").assertDoesNotExist()
        assertTrue(compose.activity.navigation.stacks.values.all { it.size == 1 })
        assertTrue(secure())
        compose.runOnIdle { compose.activity.requestWorldSwitch() }
        awaitWorld(PrototypeWorld.SFW)
    }
}
