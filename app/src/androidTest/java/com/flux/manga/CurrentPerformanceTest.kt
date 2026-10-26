package com.flux.manga

import android.content.pm.ApplicationInfo
import android.os.*
import android.view.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Real clock, current product routes, unique synthetic reader. No legacy emulator workload. */
class CurrentPerformanceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private lateinit var activity: MainActivity
    private val profile get() = args.getString("profile", "base")
    private val evidence get() = File(inst.targetContext.getExternalFilesDir(null), "oppo-20261023-performance").apply { mkdirs() }
    private fun await(action: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (!action()) {
            if (SystemClock.uptimeMillis() >= until) {
                inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(evidence, "$profile-timeout.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                error("Current product route did not settle")
            }
            SystemClock.sleep(25)
        }
    }
    private fun measure(name: String, action: () -> Unit) {
        val rows = Collections.synchronizedList(mutableListOf<String>())
        val memory = Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("current-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, drops ->
            rows += listOf(FrameMetrics.TOTAL_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
                FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION,
                FrameMetrics.SWAP_BUFFERS_DURATION, FrameMetrics.UNKNOWN_DELAY_DURATION,
                FrameMetrics.DEADLINE, FrameMetrics.FIRST_DRAW_FRAME).joinToString(",") { m.getMetric(it).toString() } + ",$drops"
        }
        inst.runOnMainSync { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                memory += File("/proc/self/status").useLines { lines -> lines.first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+"))[1].toLong() }
                SystemClock.sleep(100)
            }
        }.apply { start() }
        try { action() } finally {
            inst.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            running.set(false); sampler.join(); thread.quitSafely(); thread.join()
            File(evidence, "$profile-$name-frames.csv").writeText("total_ns,layout_ns,draw_ns,sync_ns,command_ns,swap_ns,unknown_ns,deadline_ns,first_draw,drops\n" + rows.joinToString("\n"))
            File(evidence, "$profile-$name-rss-kib.txt").writeText(memory.joinToString("\n"))
        }
        assertTrue("No frames for $name", rows.size > 10)
    }
    @Test fun currentSwitchAndSyntheticLongStrip() = runBlocking {
        // Opt-in diagnostic: OEM accessibility routing is not yet validated by this harness.
        org.junit.Assume.assumeTrue(args.getString("currentPerformance") == "true")
        assertEquals("Use benchmark APK", 0, inst.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        require(profile in listOf("base", "low"))
        val preferences = inst.targetContext.getSharedPreferences("display-preferences", 0)
        val keys = listOf("low-effects", "reduce-motion")
        val old = keys.associateWith { if (preferences.contains(it)) preferences.getBoolean(it, false) else null }
        val library = CbzLibrary(WorldDataStore(inst.targetContext))
        val book = ReaderFixtures.prepareUnique(library, evidence)
        library.saveProgress(book, "page3.png", 0f, ReadingMode.STRIP)
        preferences.edit().putBoolean("low-effects", profile == "low").putBoolean("reduce-motion", false).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity = it }
                await { !activity.isCovered && activity.worldSession.state.value.phase == SwitchPhase.Stable }
                SystemClock.sleep(750)
                measure("switch") {
                    repeat(8) { index ->
                        val target = if (index % 2 == 0) PrototypeWorld.NSFW else PrototypeWorld.SFW
                        scenario.onActivity { it.requestWorldSwitch() }
                        await { activity.worldSession.identity.value.world == target && activity.worldSession.state.value.phase == SwitchPhase.Stable && !activity.liquidMotion.pending && !activity.isCovered }
                        SystemClock.sleep(250)
                    }
                }
                scenario.onActivity { it.navigation.openLocalReader(book.mangaId) }
                await { inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("隐藏工具")?.isNotEmpty() == true }
                var tools = inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("隐藏工具").first()
                while (!tools.isClickable && tools.parent != null) tools = tools.parent
                assertTrue(tools.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(1000)
                fun capture(label: String) {
                    inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(evidence, "$profile-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                capture("reader-before")
                measure("strip") {
                    repeat(7) { index ->
                        val dm = activity.resources.displayMetrics
                        val start = if (index % 2 == 0) .82f else .42f
                        val end = if (index % 2 == 0) .42f else .82f
                        val down = SystemClock.uptimeMillis()
                        for (step in 0..24) {
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), when (step) { 0 -> MotionEvent.ACTION_DOWN; 24 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },
                                dm.widthPixels * .5f, dm.heightPixels * (start + (end - start) * step / 24f), 0)
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            inst.uiAutomation.injectInputEvent(event, true); event.recycle(); SystemClock.sleep(16)
                        }
                        SystemClock.sleep(250)
                    }
                }
                // Progress is debounced; persistence is checked outside the measured window.
                SystemClock.sleep(1000)
                capture("reader-after")
                val progress = library.detail(book)!!.card.progress!!
                assertEquals("STRIP", progress.mode)
                assertTrue("Real swipes must move the synthetic reader", progress.fraction > 0f || progress.pageId != "page3.png")
            }
        } finally {
            preferences.edit().apply { old.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
        }
    }
}
