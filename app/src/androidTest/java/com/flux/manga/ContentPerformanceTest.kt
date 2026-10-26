package com.flux.manga

import android.content.pm.ApplicationInfo
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.ReadingMode
import com.flux.manga.data.MangaKey
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.session.SwitchPhase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Real clock / input / Window.FrameMetrics. No Compose test clock or screenshot in measured windows. */
class ContentPerformanceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val automation get() = inst.uiAutomation
    private lateinit var activity: MainActivity
    private val verifiedEffects = JSONObject()
    private val switchEvents = Collections.synchronizedList(mutableListOf<String>())
    private val args get() = InstrumentationRegistry.getArguments()
    private val evidence get() = File(inst.targetContext.getExternalFilesDir(null),
        args.getString("evidence", "m2-validation-evidence")).apply { mkdirs() }
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun click(text: String) {
        val deadline = SystemClock.uptimeMillis()+10000
        while (SystemClock.uptimeMillis()<deadline) {
            val node=automation.rootInActiveWindow?.let(::nodes)?.firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
            if(node!=null) {
                var target: AccessibilityNodeInfo?=node
                while(target!=null && !target.isClickable) target=target.parent
                if(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) return
            }
            SystemClock.sleep(100)
        }
        error("No clickable $text")
    }
    private fun checked(text: String): Boolean? {
        var node=automation.rootInActiveWindow?.let(::nodes)?.firstOrNull {
            it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text)
        }
        while(node!=null && !node.isCheckable) node=node.parent
        return node?.isChecked
    }
    private fun setToggle(text: String, value: Boolean) {
        val readyDeadline=SystemClock.uptimeMillis()+5000
        var initial=checked(text)
        while(initial==null && SystemClock.uptimeMillis()<readyDeadline) {
            SystemClock.sleep(50); initial=checked(text)
        }
        assertNotNull("Effect control not ready: $text",initial)
        if(initial!=value) click(text)
        val deadline=SystemClock.uptimeMillis()+5000
        while(checked(text)!=value && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(50)
        assertEquals("Effect state was not applied: $text",value,checked(text))
        verifiedEffects.put(text,value)
    }
    private fun swipe(horizontal: Boolean = false, reverse: Boolean = false) {
        val size=activity.resources.displayMetrics
        val x=size.widthPixels*.5f; val y=size.heightPixels*.6f
        val start=if(reverse) .3f else .78f; val end=if(reverse) .78f else .3f
        val down=SystemClock.uptimeMillis()
        for(step in 0..24) {
            val t=step/24f; val p=start+(end-start)*t
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(), when(step) {0->MotionEvent.ACTION_DOWN;24->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},
                if(horizontal) size.widthPixels*p else x,if(horizontal)y else size.heightPixels*p,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event,true); event.recycle(); SystemClock.sleep(16)
        }
        SystemClock.sleep(150)
    }
    private fun status(name: String): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("$name:") }.trim().split(Regex("\\s+"))[1].toLong()
    }
    private fun measure(name: String, action: () -> Unit) {
        val frames=Collections.synchronizedList(mutableListOf<String>())
        val memory=Collections.synchronizedList(mutableListOf<String>())
        val handlerThread=HandlerThread("frame-measurement").apply { start() }
        val start=SystemClock.elapsedRealtimeNanos()
        val listener=Window.OnFrameMetricsAvailableListener { _, metric, dropped ->
            frames.add("${SystemClock.elapsedRealtimeNanos()-start},${metric.getMetric(FrameMetrics.TOTAL_DURATION)},${metric.getMetric(FrameMetrics.DEADLINE)},${metric.getMetric(FrameMetrics.FIRST_DRAW_FRAME)},$dropped," +
                listOf(FrameMetrics.INTENDED_VSYNC_TIMESTAMP, FrameMetrics.LAYOUT_MEASURE_DURATION,
                    FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION,
                    FrameMetrics.SWAP_BUFFERS_DURATION, FrameMetrics.UNKNOWN_DELAY_DURATION).joinToString(",") { metric.getMetric(it).toString() })
        }
        inst.runOnMainSync { activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handlerThread.looper)) }
        val running=AtomicBoolean(true)
        val sampler=Thread {
            while(running.get()) {
                memory.add("${SystemClock.elapsedRealtimeNanos()-start},${status("VmRSS")},${status("VmHWM")}")
                SystemClock.sleep(100)
            }
        }.apply { start() }
        Trace.beginSection("FluxWorkload:$name")
        try { action() } finally {
            Trace.endSection()
            inst.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            running.set(false); sampler.join(); handlerThread.quitSafely(); handlerThread.join()
        }
        val prefix="${args.getString("profile","base")}-${args.getString("repeat","1") }-$name"
        File(evidence,"$prefix-frames.csv").writeText("elapsed_ns,total_ns,deadline_ns,first_draw,report_drops,vsync_ns,layout_ns,draw_ns,sync_ns,command_ns,swap_ns,unknown_ns\n"+frames.joinToString("\n"))
        File(evidence,"$prefix-memory.csv").writeText("elapsed_ns,rss_kib,process_hwm_kib\n"+memory.joinToString("\n"))
        assertTrue("No useful frames for $name",frames.size > if (name == "switch") 0 else 20)
    }

    /** Separate from the legacy 650ms workload: every transaction and target content is asserted. */
    private fun settledSwitches(scenario: ActivityScenario<MainActivity>) {
        repeat(8) { index ->
            val target = if(index % 2 == 0) PrototypeWorld.NSFW else PrototypeWorld.SFW
            val label = "FluxSwitch:$index:${target.name}"
            val started = System.nanoTime()
            var generation = -1L
            var last = ""
            var committed = false
            var stable = false
            var watching = true
            val watcher = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if(!watching) return
                    val session = activity.worldSession
                    val value = "${session.identity.value.world},${session.state.value.phase},${activity.liquidMotion.pending}"
                    if(value != last) {
                        switchEvents.add("$index,${target.name},${System.nanoTime()},state,$value")
                        Trace.beginSection("FluxSwitchState:$index:$value"); Trace.endSection()
                        last = value
                    }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Trace.beginAsyncSection(label,index)
            try {
                scenario.onActivity {
                    generation = it.worldSession.identity.value.generation
                    Choreographer.getInstance().postFrameCallback(watcher)
                    it.requestWorldSwitch()
                    assertEquals(generation+1,it.worldSession.identity.value.generation)
                }
                switchEvents.add("$index,${target.name},$started,request,,,")
                val deadline = SystemClock.uptimeMillis()+15000
                while(!stable && SystemClock.uptimeMillis()<deadline) {
                    scenario.onActivity {
                        committed = it.worldSession.identity.value.world == target
                        stable = committed && it.worldSession.state.value.phase == SwitchPhase.Stable && !it.liquidMotion.pending
                    }
                    if(!stable) SystemClock.sleep(25)
                }
                assertTrue("Switch $index did not settle in $target",stable)
                val content = if(target == PrototypeWorld.NSFW) "里空间测试样本 1" else "最近导入"
                var ready = false
                while(!ready && SystemClock.uptimeMillis()<deadline) {
                    ready = automation.rootInActiveWindow?.let(::nodes)?.any { it.isVisibleToUser && it.text?.toString()==content } == true
                    if(!ready) SystemClock.sleep(50)
                }
                assertTrue("Switch $index target content missing",ready)
                switchEvents.add("$index,${target.name},${System.nanoTime()},content,,,")
                // Same tail in both cohorts, including the first content draw / cover delivery.
                SystemClock.sleep(250)
            } finally {
                scenario.onActivity { watching=false; Choreographer.getInstance().removeFrameCallback(watcher) }
                Trace.endAsyncSection(label,index)
                switchEvents.add("$index,${target.name},${System.nanoTime()},end,,,")
            }
        }
        File(evidence,"${args.getString("profile","base")}-${args.getString("repeat","1")}-switch-events.csv").writeText(
            "index,target,monotonic_ns,event,world,phase,motion_pending\n"+switchEvents.joinToString("\n"))
    }
    @Test fun measureFixedWorkload(): Unit = runBlocking {
        assertEquals("Use non-debug benchmark APK",0,inst.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        ContentFixtures.verifyOriginals()
        val profile=args.getString("profile","base")
        val switchOnly=args.getString("switchOnly") == "true"
        // This mode is for an already-used phone: no preparation or reader progress writes.
        val preserved = if (switchOnly) {
            require(profile == "base")
            val allowed = JSONObject(File(evidence, "allowed-fixtures.json").readText()).getJSONArray("sha256")
            val hashes = (0 until allowed.length()).map { allowed.getString(it) }.toSet()
            ContentFixtures.library.cards(PrototypeWorld.SFW).associate { card ->
                val detail = ContentFixtures.library.detail(card.key)!!
                check(detail.sha256 in hashes) { "Non-fixture book present; stop before displaying library" }
                card.key to detail
            }
        } else null
        val preferences=inst.targetContext.getSharedPreferences("display-preferences",0)
        val saved=preferences.all
        preferences.edit().putBoolean("low-effects",false).putBoolean("reduce-motion",false).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity=it }
                SystemClock.sleep(2000)
                run {
                    click("显示效果")
                    setToggle("背景采样",profile !in listOf("base", "reader"))
                    setToggle("模糊 16dp",profile !in listOf("base", "reader", "sample"))
                    setToggle("微染色",profile!="no-tint")
                    setToggle("低效果",profile=="low")
                    setToggle("减少动画",profile=="reduce")
                    // Read the final combined state too: no lost update between adjacent toggles.
                    listOf("背景采样","模糊 16dp","微染色","低效果","减少动画").forEach {
                        assertEquals("Final preset mismatch: $it",verifiedEffects.getBoolean(it),checked(it))
                    }
                    click("完成"); SystemClock.sleep(500)
                }
                if(profile=="reader") {
                    val key=MangaKey(PrototypeWorld.SFW,"cbz",ContentFixtures.readerId())
                    ContentFixtures.library.saveProgress(key,"page1.png",0f,ReadingMode.LTR)
                    scenario.onActivity { it.navigation.openLocalReader(key.mangaId) }
                    SystemClock.sleep(1500)
                    swipe(true); swipe(true,true)
                    measure("paged") { repeat(12) { swipe(true,it%6>=3) } }
                    // Start explicitly at the long image, not wherever paged swipes ended.
                    val longPage = ContentFixtures.library.detail(key)!!.chapter.pages.maxBy { it.height }
                    val input = nodes(automation.rootInActiveWindow!!).first { it.isEditable }
                    assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, longPage.ordinal.toString())
                    }))
                    click("跳转"); SystemClock.sleep(300)
                    click("条漫")
                    SystemClock.sleep(1500)
                    measure("strip") { repeat(12) { swipe(reverse=it%8>=6) } }
                } else {
                    scenario.onActivity { it.navigation.select(com.flux.manga.ui.navigation.MainTab.DISCOVER) }
                    SystemClock.sleep(700)
                    if (!switchOnly) {
                        repeat(4) { swipe(reverse=it>=2) }
                        measure("home") { repeat(12) { swipe(reverse=it%6>=3) } }
                    }
                    // Both worlds contain only fixed fixtures. Wait after each switch for completion.
                    measure("switch") {
                        if(args.getString("settledSwitches") == "true") settledSwitches(scenario)
                        else repeat(8) { i ->
                            click(if(i%2==0) "切换到里世界样本" else "返回表世界")
                            SystemClock.sleep(650)
                        }
                    }
                }
                File(evidence,"$profile-${args.getString("repeat","1")}-metadata.json").writeText(JSONObject()
                    .put("debuggable",false).put("sdk",Build.VERSION.SDK_INT).put("profile",profile)
                    .put("displayRefreshRateAtEnd",activity.display?.refreshRate)
                    .put("densityDpi",activity.resources.displayMetrics.densityDpi)
                    .put("fontScale",activity.resources.configuration.fontScale)
                    .put("widthPixels",activity.resources.displayMetrics.widthPixels)
                    .put("heightPixels",activity.resources.displayMetrics.heightPixels)
                    .put("verifiedEffects",verifiedEffects)
                    .put("settledSwitches",args.getString("settledSwitches") == "true")
                    .put("switchOnly",switchOnly)
                    .put("animatorsEnabled",android.animation.ValueAnimator.areAnimatorsEnabled())
                    .put("pssEndKiB",Debug.getPss()).put("processHwmKiB",status("VmHWM"))
                    .put("pid",Process.myPid()).put("samplingMs",100).toString(2))
            }
        } finally {
            val edit=preferences.edit().clear()
            saved.forEach { (key,value) -> if(value is Boolean) edit.putBoolean(key,value) }
            edit.commit()
            ContentFixtures.verifyOriginals()
            preserved?.let { before ->
                val after = ContentFixtures.library.cards(PrototypeWorld.SFW).associate { it.key to ContentFixtures.library.detail(it.key) }
                assertEquals("Existing library/progress changed", before, after)
                File(evidence, "base-${args.getString("repeat","1")}-preservation.json").writeText(JSONObject()
                    .put("books",before.size).put("unchanged",true).put("originalsUnchanged",true).toString(2))
            }
        }
    }
}


