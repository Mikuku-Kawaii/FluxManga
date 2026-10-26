package com.flux.manga

import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.SyntheticPrivateReaderRepository
import com.flux.manga.data.WorldDataStore
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.MangaReader
import com.flux.manga.ui.navigation.PrototypeWorld
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Repeatable real UI fixture: all reader writes go to a fresh UUID, never old sample progress. */
class PrivateReaderIsolationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun isolatedDeepReaderDisplaysPixelsAndSavesOnlyPrivacyMarker() {
        val namespace = "m4-private-reader-${UUID.randomUUID()}"
        assertFalse(WorldDataStore(compose.activity, namespace).directory(PrototypeWorld.NSFW).exists())
        SyntheticPrivateReaderRepository.admissionNamespace = namespace
        try {
            compose.useSamplePages()
            compose.onNodeWithTag("switch-world").performClick()
            compose.waitUntil(10_000) {
                compose.activity.worldSession.let { it.identity.value.world == PrototypeWorld.NSFW &&
                    it.state.value.phase == SwitchPhase.Stable } && !compose.activity.isCovered
            }
            compose.runOnIdle { compose.activity.navigation.openDetail("1") }
            compose.awaitTag("detail-content")
            compose.onNodeWithTag("open-reader").performScrollTo().performClick()
            compose.onNodeWithTag("open-private-synthetic-reader").performScrollTo().performClick()
            compose.awaitTag("private-synthetic-reader")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
            compose.runOnIdle {
                val activity = compose.activity
                assertTrue(activity.navigation.currentStack.last() is MangaReader)
                assertEquals(3, activity.navigation.currentStack.size)
                assertFalse(activity.isCovered)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val saved = Bundle()
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, saved)
                assertEquals(setOf("private-world"), saved.keySet())
                Log.i("FluxM4Stage", "UI_ASSERTIONS pid=${Process.myPid()} task=${activity.taskId} namespace=$namespace depth=3 pixels=page1 secure=true saved=private-world")
            }
        } finally {
            SyntheticPrivateReaderRepository.admissionNamespace = null
        }
    }
}
