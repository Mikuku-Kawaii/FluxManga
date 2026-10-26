package com.flux.manga

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.PrototypeWorld
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Explicit acceptance capture only; private world contains no real content. */
class ReferenceAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun currentRootsAndBothPalettes() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("referenceCapture") == "true")
        val output = File(compose.activity.getExternalFilesDir(null), "oppo-20261023-reference").apply { mkdirs() }
        fun settled() {
            compose.waitUntil(10_000) { !compose.activity.isCovered && !compose.activity.liquidMotion.pending &&
                compose.activity.worldSession.state.value.phase == SwitchPhase.Stable }
            compose.waitForIdle()
        }
        fun capture(name: String) {
            settled()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        settled()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.awaitTag("reference-shelf")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-loading").fetchSemanticsNodes().isEmpty() }
        capture("public-library")
        for ((tab, name) in listOf("HOME" to "public-home", "TAGS" to "public-tags")) {
            compose.onNodeWithTag("tab-$tab").performClick(); capture(name)
        }
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        try {
            compose.onNodeWithTag("switch-world").performClick(); settled()
            check(compose.activity.worldSession.identity.value.world == PrototypeWorld.NSFW)
            capture("private-empty-library")
        } finally {
            if (compose.activity.worldSession.identity.value.world == PrototypeWorld.NSFW) {
                compose.runOnIdle { compose.activity.requestWorldSwitch() }; settled()
            }
        }
    }
}
