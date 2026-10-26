package com.flux.manga

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** The production online/offline reader UI and timing path with generated repository pixels.
 * No HTTP, source registration, actual download, private admission or business records. */
class ReadingStatisticsOnlineTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val namespace = "reading-online-${UUID.randomUUID()}"
    private val data by lazy { WorldDataStore(app, namespace) }
    private val owners = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private lateinit var model: OnlineReaderModel
    private lateinit var timer: ReadingTimeModel
    private lateinit var fixture: StatisticsPixels
    private fun total() = runBlocking { data.worldData(PrototypeWorld.SFW) { it.total("0000-01-01", null, null, null, null).durationMs } }
    private fun checkpoint(): Long { compose.runOnUiThread { timer.retry() }; SystemClock.sleep(300); return total() }
    private fun pixels() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("第 ${model.state.value.index + 1} 页", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun mount(offline: Boolean) {
        compose.runOnUiThread {
            owners.viewModelStore.clear()
            val session = compose.activity.worldSession
            fixture = StatisticsPixels(data, session, offline)
            model = OnlineReaderModel(app, session, "stats-generated", "same-book", "c1", SavedStateHandle(),
                offlineOnly = offline, readerRepository = fixture, data = data)
            timer = ReadingTimeModel(app, session, "online", "stats-generated", "same-book", data)
            owners.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${OnlineReaderModel::class.java.canonicalName}", model)
            owners.viewModelStore.put("reading-time:online:stats-generated:same-book", timer)
            val identity = session.identity.value
            val key = listOf(identity.processSession, identity.world.name, identity.generation.toString(), "stats-generated", "same-book").joinToString(":") { "${it.length}:$it" }
            owners.viewModelStore.put("chapter-reading:$key", ChapterReadingModel(ChapterReadings(data, session, "stats-generated", "same-book")))
            compose.activity.setContent { MaterialTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owners) {
                    OnlineReaderPage(session, "stats-generated", "same-book", "c1", {}, offlineOnly = offline)
                }
            } }
        }
        compose.waitUntil(10_000) { model.state.value.pages.isNotEmpty() && !model.state.value.loading }
        pixels()
    }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.viewModelStore.clear() } }

    @Test fun onlineOfflineFourModesChapterBoundaryAndImageFailure() {
        compose.waitUntil(10_000) { !compose.activity.isCovered && compose.activity.worldSession.state.value.foreground }
        val result = JSONObject().put("namespace", namespace)
        for (offline in listOf(false, true)) {
            mount(offline)
            for (mode in ReadingMode.entries) {
                compose.onNodeWithText("方式 · ${model.state.value.mode.label}").performScrollTo().performClick()
                compose.onNodeWithTag("online-reader-mode-${mode.name}").performScrollTo().performClick()
                pixels()
                val before = checkpoint(); val began = SystemClock.elapsedRealtime()
                SystemClock.sleep(1_100)
                compose.onNodeWithTag("online-reader-next").performClick()
                pixels()
                val after = checkpoint()
                assertTrue("$offline ${mode.name} missing time", after - before > 800)
                assertTrue("Multiple images counted", after - before < SystemClock.elapsedRealtime() - began + 500)
                result.put("${if (offline) "offline" else "online"}-${mode.name}", after - before)
                compose.runOnUiThread { model.position(0) }
                pixels()
            }
            compose.runOnUiThread { model.mode(ReadingMode.LTR); model.position(2) }
            pixels()
            compose.onNodeWithTag("online-reader-next").performClick()
            compose.waitUntil(10_000) { model.state.value.pages.firstOrNull()?.chapterId == "c2" }
            pixels()
            val beforeFailure = checkpoint()
            fixture.failImages = true
            compose.runOnUiThread { model.chapter("c3") }
            if (offline) {
                compose.waitUntil(10_000) { model.state.value.boundaryMessage != null || model.state.value.error != null }
                assertEquals("c2", model.state.value.pages.first().chapterId)
                result.put("offlineGapRetainedChapter", true)
            } else {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("合成图片失败").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                val failed = checkpoint()
                SystemClock.sleep(1_400)
                assertEquals("Failed images counted", failed, checkpoint())
                fixture.failImages = false
                compose.onAllNodesWithText("重试图片").onFirst().performClick()
                pixels(); SystemClock.sleep(1_000)
                assertTrue(checkpoint() > failed + 700)
                result.put("imageFailurePaused", true)
            }
            assertTrue(total() >= beforeFailure)
            assertFalse(timer.error.value)
        }
        val summary = runBlocking { data.worldData(PrototypeWorld.SFW) { it.total("0000-01-01", null, null, null, null) } }
        assertEquals("Online/offline must share work identity", 1, summary.books)
        result.put("totalMs", summary.durationMs)
        File(app.getExternalFilesDir(null), "$namespace-result.json").writeText(result.toString(2))
    }
}

private class StatisticsPixels(private val data: WorldDataStore, private val session: WorldSession, private val offline: Boolean) : ReaderRepository {
    private val identity = session.identity.value
    override val mangaKey = SourceMangaKey(SourceWorld.SFW, "stats-generated", "same-book")
    private val context = SourceContext(SourceWorld.SFW, "stats-generated", identity.processSession, identity.generation.toULong(), null)
    @Volatile var failImages = false
    override fun isActive() = session.accepts(identity)
    override fun active() { if (!isActive()) throw CancellationException("Expired generated session") }
    override suspend fun pages(chapterId: String): List<SourcePageResource> {
        active()
        if (offline && chapterId == "c3") throw java.io.IOException("合成离线缺章")
        return (1..3).map { SourcePageResource(mangaKey, chapterId, "$chapterId-$it", it.toUInt(), "generated", null, 480u, if(it==3) 3000u else 720u, context) }
    }
    override suspend fun chapters(request: SourcePageRequest) = SourceChapterPage(context, (1..3).map {
        SourceChapter(mangaKey, "c$it", "合成章节 $it", it.toUInt(), null)
    }, SourceContinuation.End, 3u)
    override suspend fun completeOfflineCatalog() = chapters(SourcePageRequest.Start).items.take(2)
    override suspend fun offlineIds() = if(offline) setOf("c1", "c2") else emptySet()
    override suspend fun progress() = data.worldData(PrototypeWorld.SFW, ::isActive) { it.onlineProgress(mangaKey.sourceId, mangaKey.mangaId) }
    override suspend fun chapterProgress(chapter: String) = data.worldData(PrototypeWorld.SFW, ::isActive) { it.chapterReading(mangaKey.sourceId, mangaKey.mangaId, chapter) }
    override suspend fun save(page: SourcePageResource, mode: ReadingMode, fraction: Float, modeExplicit: Boolean) {
        data.worldData(PrototypeWorld.SFW, ::isActive) { saveChapterProgress(it, OnlineReadingProgress(mangaKey.sourceId, mangaKey.mangaId,
            page.chapterId, page.id, page.ordinal.toInt(), fraction, mode.name, System.currentTimeMillis(), modeExplicit)) }
    }
    override suspend fun dimensions(page: SourcePageResource) = page.width!!.toInt() to page.height!!.toInt()
    override suspend fun image(page: SourcePageResource, width: Int, top: Int, bottom: Int?): Bitmap {
        active(); delay(70)
        if (failImages) throw java.io.IOException("合成图片失败")
        return Bitmap.createBitmap(480, (bottom ?: page.height!!.toInt()) - top, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff397da8.toInt()) }
    }
    override suspend fun clearImages() { }
}
