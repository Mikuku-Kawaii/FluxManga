package com.flux.manga

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.manga.data.*
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.OnlineReaderModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Compile now; device execution requires separate authorization. All writes use a new UUID. */
@RunWith(AndroidJUnit4::class)
class PrivateReaderIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun syntheticRepositoryFeedsRealReaderModelAndEncryptedProgressAcrossReopen(): Unit = runBlocking {
        compose.useSamplePages()
        compose.onNodeWithTag("switch-world").performClick()
        val session = compose.activity.worldSession
        compose.waitUntil(10_000) { session.identity.value.world == PrototypeWorld.NSFW && session.state.value.phase == SwitchPhase.Stable }
        val namespace = "m4-private-reader-${UUID.randomUUID()}"
        val app = compose.activity.application as Application
        val owners = ViewModelStore()
        lateinit var model: OnlineReaderModel
        compose.runOnIdle {
            model = OnlineReaderModel(app, session, SyntheticPrivateReaderRepository.SOURCE,
                SyntheticPrivateReaderRepository.MANGA, null, SavedStateHandle(), syntheticPrivate = true, syntheticNamespace = namespace)
            owners.put("reader", model); model.load()
        }
        try {
            compose.waitUntil(10_000) { model.state.value.pages.size == 3 }
            val page = model.state.value.pages.last()
            assertEquals(480 to 6000, model.repository.dimensions(page))
            val pixels = model.repository.image(page, 480, 2048, 4096)
            try { assertEquals(480, pixels.width); assertEquals(2048, pixels.height) } finally { pixels.recycle() }
            compose.runOnIdle { model.mode(ReadingMode.STRIP); model.position(2); model.displayed(page.id); model.stripPosition(2, .4f, 2048) }
            // Only a successfully displayed region may advance durable progress.
            compose.runOnIdle { model.displayed(page.id, 2048) }
            lateinit var saved: Job
            compose.runOnIdle { saved = model.flush() }; saved.join()
            val reopened = SyntheticPrivateReaderRepository(app, session, namespace)
            val progress = requireNotNull(reopened.progress())
            assertEquals(page.id, progress.pageId); assertEquals(ReadingMode.STRIP.name, progress.mode)
            assertEquals(.4f, progress.fraction, .001f)
            val data = WorldDataStore(app, namespace)
            val file = data.cacheFile(ResourceKey(SampleKey(PrototypeWorld.NSFW, SyntheticPrivateReaderRepository.SOURCE,
                SyntheticPrivateReaderRepository.MANGA), "${page.chapterId}/${page.id}"))
            val encrypted = file.readBytes()
            assertNull(BitmapFactory.decodeByteArray(encrypted, 0, encrypted.size))
            val transformed = reopened.pages("chapter-3").first()
            assertEquals(480 to 723, reopened.dimensions(transformed))
            val restored = reopened.image(transformed, 480)
            try {
                for (row in listOf(0, 239, 240, 479, 480, 722)) {
                    val color = if ((row / 240 + 1) % 2 == 0) android.graphics.Color.rgb(31, 76, 102)
                        else android.graphics.Color.rgb(145, 66, 24)
                    assertEquals(color, restored.getPixel(0, row))
                }
            } finally { restored.recycle() }
            // Looking at another chapter must not rewrite durable reading progress.
            assertEquals(progress, reopened.progress())
            val largePage = reopened.pages("chapter-4").single()
            assertEquals(1536 to 1536, reopened.dimensions(largePage))
            val largeFile = data.cacheFile(ResourceKey(SampleKey(PrototypeWorld.NSFW,
                SyntheticPrivateReaderRepository.SOURCE, SyntheticPrivateReaderRepository.MANGA),
                "${largePage.chapterId}/${largePage.id}"))
            assertTrue(largeFile.length() > 4L * 1024 * 1024)
            val largePixels = reopened.image(largePage, 768, 256, 1280)
            try { assertEquals(768, largePixels.width); assertEquals(512, largePixels.height) }
            finally { largePixels.recycle() }
            assertEquals(progress, reopened.progress())
            compose.runOnIdle { session.pause(); model.background() }
            try { reopened.image(page, 480); fail("Background image published") } catch (_: CancellationException) { }
            compose.runOnIdle { session.resume() }
            reopened.image(page, 480, 2048, 4096).recycle()
            assertArrayEquals(encrypted, file.readBytes())
            compose.onNodeWithTag("switch-world").performClick()
            compose.waitUntil(10_000) { session.identity.value.world == PrototypeWorld.SFW && session.state.value.phase == SwitchPhase.Stable }
            try { reopened.image(page, 480); fail("Old private generation accepted") } catch (_: CancellationException) { }
        } finally { compose.runOnIdle { owners.clear() } }
    }
}
