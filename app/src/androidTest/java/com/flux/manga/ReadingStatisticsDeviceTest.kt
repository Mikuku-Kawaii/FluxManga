package com.flux.manga

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.*
import com.flux.manga.ui.ReferenceShapes
import com.flux.manga.ui.ReferenceTypography
import com.flux.manga.ui.glass.WorldColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.io.File
import java.util.UUID

/** Production timing hooks and statistics page, Android Room/SQLCipher, isolated synthetic
 * namespaces only. No business records, originals, settings or real source requests are changed. */
class ReadingStatisticsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val namespace = "reading-stats-${UUID.randomUUID()}"
    private val data by lazy { WorldDataStore(app, namespace) }
    private val owners = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val today get() = LocalDate.now().toString()
    private val yesterday get() = LocalDate.now().minusDays(1).toString()
    private val key = "reading-time:local:cbz:synthetic"
    private var ready by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private var dialog by mutableStateOf(false)
    private lateinit var timer: ReadingTimeModel

    private fun start() {
        compose.awaitTag("shelf-more")
        compose.waitUntil(10_000) { !compose.activity.isCovered }
    }
    private fun total(world: PrototypeWorld = PrototypeWorld.SFW): Long = runBlocking {
        data.worldData(world) { it.total("0000-01-01", null, null, null, null).durationMs }
    }
    private fun awaitTotal(predicate: (Long) -> Boolean): Long {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do { val value = total(); if (predicate(value)) return value; SystemClock.sleep(50) }
        while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Statistics checkpoint did not settle: ${total()}")
    }
    private fun newTimer() {
        compose.runOnUiThread {
            timer = ReadingTimeModel(app, compose.activity.worldSession, "local", "cbz", "synthetic", data)
            owners.viewModelStore.put(key, timer)
        }
    }
    private fun reader() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff397da8.toInt()) }.asImageBitmap()
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owners) {
                    val model = rememberReadingTime(compose.activity.worldSession, "local", "cbz", "synthetic", ready, "合成计时作品")
                    CompositionLocalProvider(LocalReadingTime provides model) {
                        Image(bitmap, "合成正文", Modifier.fillMaxSize())
                        // Two visible pages must still count one interval; offscreen prefetch adds none.
                        ReadingImageVisibility(visible)
                        ReadingImageVisibility(visible)
                        ReadingImageVisibility(false)
                    }
                    if (dialog) Dialog(onDismissRequest = { dialog = false }) { Text("合成阅读弹窗") }
                }
            } }
        }
        compose.onNodeWithContentDescription("合成正文").assertExists()
        compose.waitForIdle()
    }
    @After fun finish() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.viewModelStore.clear() }
    }

    @Test fun visibleTimeTickerDialogAndShortBackgroundResume() {
        start(); newTimer(); reader()
        val began = SystemClock.elapsedRealtime()
        // Observe the real ten-second periodic flush without calling retry or pausing.
        SystemClock.sleep(10_500)
        val periodic = awaitTotal { it > 5_000 }
        assertTrue("Two pages counted twice: $periodic", periodic < SystemClock.elapsedRealtime() - began + 2_000)
        compose.runOnUiThread { ready = false }
        compose.waitForIdle()
        val paused = awaitTotal { it >= periodic }
        SystemClock.sleep(1_500)
        compose.runOnUiThread { timer.retry() }
        SystemClock.sleep(300)
        val readyPaused = total()
        assertTrue("Loading time counted", readyPaused - paused < 400)
        compose.runOnUiThread { ready = true }
        compose.waitForIdle(); SystemClock.sleep(1_200)
        compose.runOnUiThread { dialog = true }
        compose.waitForIdle(); SystemClock.sleep(400)
        val beforeDialog = total()
        SystemClock.sleep(1_800)
        compose.runOnUiThread { timer.retry() }
        SystemClock.sleep(300)
        assertEquals("Unfocused dialog counted", beforeDialog, total())
        compose.runOnUiThread { dialog = false }
        compose.waitForIdle(); SystemClock.sleep(1_200)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(500)
        val background = total()
        SystemClock.sleep(2_500)
        compose.runOnUiThread { timer.retry() }
        SystemClock.sleep(300)
        assertEquals("Background time counted", background, total())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle(); SystemClock.sleep(1_500)
        compose.runOnUiThread { ready = false }
        compose.waitForIdle()
        val resumed = awaitTotal { it > background + 900 }
        assertFalse(timer.error.value)
        Log.i("FluxStatisticsTest", "ticker=$periodic readyPaused=$readyPaused dialog=$beforeDialog background=$background resumed=$resumed")
    }

    @Test fun noVisibleImagesRecreationAndNewReaderDoNotCountOfflineGap() {
        start(); newTimer(); reader(); SystemClock.sleep(1_200)
        compose.runOnUiThread { visible = false; timer.retry() }
        compose.waitForIdle(); SystemClock.sleep(300)
        compose.runOnUiThread { timer.retry() }
        SystemClock.sleep(300)
        val hidden = total()
        SystemClock.sleep(1_500)
        compose.runOnUiThread { timer.retry() }
        SystemClock.sleep(300)
        assertEquals(hidden, total())
        compose.activityRule.scenario.recreate()
        reader()
        compose.runOnUiThread { visible = true }
        compose.waitForIdle(); SystemClock.sleep(1_200)
        compose.runOnUiThread { ready = false }
        compose.waitForIdle()
        val recreated = awaitTotal { it > hidden + 700 }
        compose.runOnUiThread { owners.viewModelStore.clear() }
        SystemClock.sleep(1_500)
        assertEquals(recreated, total())
        newTimer(); reader()
        compose.runOnUiThread { ready = true }
        compose.waitForIdle(); SystemClock.sleep(1_200)
        compose.runOnUiThread { ready = false }
        compose.waitForIdle()
        val reopened = awaitTotal { it > recreated + 700 }
        val freshStore = WorldDataStore(app, namespace)
        assertEquals(reopened, runBlocking { freshStore.worldData(PrototypeWorld.SFW) { it.total("0000-01-01", null, null, null, null).durationMs } })
        assertTrue("New reader counted the closed interval", reopened - recreated < 3_000)
        Log.i("FluxStatisticsTest", "hidden=$hidden recreated=$recreated reopened=$reopened")
    }

    private fun seed() = runBlocking {
        data.worldData(PrototypeWorld.SFW) { dao ->
            dao.put(ReadingTimeRow("one", today, "local", "cbz", "a", "合成甲", 60_000))
            dao.put(ReadingTimeRow("one", yesterday, "local", "cbz", "a", "合成甲", 120_000))
            dao.put(ReadingTimeRow("two", today, "online", "source-a", "a", "合成乙", 180_000))
        }
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onNodeWithTag("reading-statistics").captureToImage().asAndroidBitmap()
        File(app.cacheDir, "reading-statistics-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun dailyWorksFiltersDrillBackAndSavedSelection() {
        start(); seed()
        val saved = SavedStateHandle()
        lateinit var model: ReadingStatisticsModel
        fun mount() {
            compose.runOnUiThread {
                model = ReadingStatisticsModel(app, compose.activity.worldSession, saved, data)
                owners.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${ReadingStatisticsModel::class.java.canonicalName}", model)
                compose.activity.setContent { MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.SFW),
                    typography = ReferenceTypography, shapes = ReferenceShapes) { Surface {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owners) {
                        ReadingStatisticsPage(compose.activity.worldSession) { }
                    }
                } } }
            }
            compose.awaitTag("reading-statistics")
            compose.waitUntil(10_000) { model.state.value.snapshot != null && !model.state.value.loading }
        }
        fun click(text: String) {
            compose.onNodeWithTag("reading-statistics").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).performClick()
            compose.waitUntil(10_000) { !model.state.value.loading }
        }
        mount()
        compose.onNodeWithText("2 个阅读日 · 2 部作品").assertExists()
        assertEquals(360_000L, model.state.value.snapshot!!.total.durationMs)
        screenshot("daily")
        click(today)
        assertEquals(240_000L, model.state.value.snapshot!!.total.durationMs)
        screenshot("works")
        click("合成甲")
        assertEquals(60_000L, model.state.value.snapshot!!.total.durationMs)
        assertEquals(today, model.state.value.filter.day)
        compose.activityRule.scenario.recreate(); mount()
        assertEquals("a", model.state.value.filter.manga)
        assertEquals(today, model.state.value.filter.day)
        click("返回上一级统计")
        assertEquals(today, model.state.value.filter.day)
        assertNull(model.state.value.filter.kind)
        click("返回上一级统计")
        assertNull(model.state.value.filter.day)
        click("作品统计"); click("合成甲")
        assertEquals(180_000L, model.state.value.snapshot!!.total.durationMs)
        click(yesterday)
        assertEquals(120_000L, model.state.value.snapshot!!.total.durationMs)
        click("返回上一级统计")
        assertNull(model.state.value.filter.day)
        assertEquals("a", model.state.value.filter.manga)
        click("全部")
        assertEquals("0000-01-01", model.state.value.filter.since)
        click("近 7 天")
        assertEquals(LocalDate.now().minusDays(6).toString(), model.state.value.filter.since)
        assertEquals(360_000L, model.state.value.snapshot!!.total.durationMs)
    }

    @Test fun roomCipherReopenSourceIdentityPaginationAndCheckpointRetry() = runBlocking {
        val session = WorldSession().apply { configure(app) }
        val repository = ReadingStatistics(data, session)
        val row = ReadingTimeRow("cumulative", today, "online", "a", "same", "同名", 20_000)
        repository.save(listOf(row)); repository.save(listOf(row)); repository.save(listOf(row.copy(durationMs = 10_000)))
        repository.save(listOf(row.copy(durationMs = 30_000)))
        data.worldData(PrototypeWorld.SFW) { dao ->
            // Online/offline sessions combine, while kind and source retain distinct identities.
            dao.put(row.copy(sessionId = "offline", durationMs = 5_000))
            dao.put(row.copy(sessionId = "other-source", sourceId = "b", durationMs = 7_000))
            dao.put(row.copy(sessionId = "local", kind = "local", durationMs = 8_000))
        }
        data.worldData(PrototypeWorld.NSFW) { it.put(row.copy(durationMs = 90_000, title = "私有合成")) }
        val reopened = WorldDataStore(app, namespace)
        for ((world, expected) in listOf(PrototypeWorld.SFW to 50_000L, PrototypeWorld.NSFW to 90_000L)) {
            reopened.worldData(world) { dao ->
                assertEquals(expected, dao.total("0000-01-01", null, null, null, null).durationMs)
                assertEquals(expected, dao.days("0000-01-01", null, null, null, null, 10, 0).single().durationMs)
            }
        }
        val all = repository.read(ReadingFilter(), 10)
        assertEquals(3, all.total.books)
        assertEquals(listOf(35_000L, 8_000L, 7_000L), all.works.map { it.durationMs })
        data.worldData(PrototypeWorld.SFW) { dao ->
            assertEquals(all.works.drop(1), dao.works("0000-01-01", null, null, null, null, 2, 1))
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { session.returnToPublicForPrivacy() }
        try { repository.save(listOf(row.copy(durationMs = 100_000))); fail("Expired generation wrote statistics") }
        catch (_: CancellationException) { }
        assertEquals(50_000L, total())
        assertEquals(90_000L, total(PrototypeWorld.NSFW))
    }
}
