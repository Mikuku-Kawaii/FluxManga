package com.flux.manga

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the real TalkBack service; no Compose test rule (which would suppress services). */
@RunWith(AndroidJUnit4::class)
class TalkBackTraversalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val trace = JSONArray()
    private fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { node.getChild(it)?.let(::flatten).orEmpty() }
    private fun label(node: AccessibilityNodeInfo): String =
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString().orEmpty().ifBlank { (0 until node.childCount).joinToString(" ") { node.getChild(it)?.let(::label).orEmpty() }.trim() }
    // The new single switch/action node includes its visible explanation in the accessible name.
    private fun matchesLabel(actual: String, expected: String) = actual == expected ||
        (expected in setOf("低效果", "减少动画", "外观与动效") && actual.startsWith(expected) &&
            actual.getOrNull(expected.length)?.isWhitespace() == true)
    private fun awaitNode(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis()+8000
        while (SystemClock.uptimeMillis()<deadline) {
            automation.rootInActiveWindow?.let { root -> flatten(root).firstOrNull { matchesLabel(label(it), text) && it.isVisibleToUser }?.let { return it } }
            SystemClock.sleep(150)
        }
        error("Missing accessibility node: $text")
    }
    private fun shortcut(key: Int) {
        // API 31 AOSP AVD event1 is the hardware keyboard. Raw keys reach TalkBack's key filter.
        val commands = listOf(
            "sendevent /dev/input/event1 1 125 1", "sendevent /dev/input/event1 0 0 0",
            "sendevent /dev/input/event1 1 $key 1", "sendevent /dev/input/event1 0 0 0", "sleep 0.04",
            "sendevent /dev/input/event1 1 $key 0", "sendevent /dev/input/event1 0 0 0",
            "sendevent /dev/input/event1 1 125 0", "sendevent /dev/input/event1 0 0 0")
        val script = File(instrumentation.targetContext.cacheDir,"talkback-input.sh")
        script.writeText("set -e\n"+commands.joinToString("\n")+"\necho RAW_INPUT_OK\n")
        val descriptor = automation.executeShellCommand("su 0 sh ${script.absolutePath}")
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertEquals("Raw keyboard input failed", "RAW_INPUT_OK", output.trim())
        SystemClock.sleep(700)
    }
    private fun forward() = shortcut(106) // Meta+Right: TalkBack next item.
    private fun activate() = shortcut(57) // Meta+Space: activate accessibility focus.
    private fun focused(expected: String) {
        var node = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val deadline = SystemClock.uptimeMillis()+3000
        while (node == null && SystemClock.uptimeMillis()<deadline) {
            SystemClock.sleep(100)
            node = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }
        if (node == null) {
            val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"m1-access-evidence").apply { mkdirs() }
            File(dir,"talkback-missing-focus.txt").writeText(automation.rootInActiveWindow?.let { root -> flatten(root).joinToString("\n") { "${it.packageName} ${it.className} text=${it.text} desc=${it.contentDescription} a11yFocus=${it.isAccessibilityFocused}" } }.orEmpty())
        }
        assertNotNull("No TalkBack focus for $expected",node)
        val actual=label(node!!)
        trace.put(JSONObject().put("expected",expected).put("actual",actual).put("selected",node.isSelected)
            .put("class",node.className).put("checkable",node.isCheckable))
        assertTrue("Expected $expected, got $actual", matchesLabel(actual, expected))
    }
    private fun focus(text: String) {
        // Traverse through the service itself: external ACTION_ACCESSIBILITY_FOCUS does not
        // populate TalkBack's focus history and is unsuitable as a keyboard-navigation pivot.
        repeat(90) {
            val node = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (node != null && matchesLabel(label(node), text)) { focused(text); return }
            if (text == "切换到里世界样本" || text == "首页" || text == "更多标签操作" || text == "返回") shortcut(105) else forward()
        }
        error("TalkBack traversal could not reach $text")
    }
    @Test fun localContent_contextualActions_detailReaderAndReturn() {
        val context=instrumentation.targetContext
        automation
        val manager=context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        assertTrue(manager.isTouchExplorationEnabled)
        val nonce = java.util.UUID.randomUUID().toString()
        val title = "UI-R7 读屏 $nonce"
        val original = File(context.getExternalFilesDir(null), "$nonce.cbz").apply {
            writeBytes(CbzFixtures.archive(listOf("page1.png" to CbzFixtures.image(),
                "page2.png" to CbzFixtures.image(), "fixture.txt" to nonce.toByteArray())))
        }
        val library = com.flux.manga.data.CbzLibrary(com.flux.manga.data.WorldDataStore(context))
        val key = kotlinx.coroutines.runBlocking {
            library.import(com.flux.manga.ui.navigation.PrototypeWorld.SFW,
                com.flux.manga.data.CbzSource("$title.cbz") { original.inputStream() }).key
        }
        val originalHash = ReaderFixtures.hash(original)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitNode("切换到里世界样本"); SystemClock.sleep(1500); forward()
        focus("更多书库操作"); activate()
        focus("本地管理 / 导入与页序"); activate(); awaitNode("本地书库")
        focus(title); activate(); awaitNode("作品详情")
        focus("开始阅读"); activate(); awaitNode("第 1 / 2 页 · 单章节")
        focus("下一页"); activate(); awaitNode("第 2 / 2 页 · 单章节")
        focus("返回"); activate(); awaitNode("作品详情")
        focus("返回"); activate(); awaitNode("本地书库")
        focus("继续阅读 · 第 2 页")
        File(ContentFixtures.evidence,"talkback-local-focus.json").writeText(trace.toString(2))
        assertEquals(originalHash, ReaderFixtures.hash(original))
        kotlinx.coroutines.runBlocking { library.remove(key) } // Only this run's copy; original retained.
    }
    @Test fun talkBackKeyboard_followVisualOrder_activateTabsAndSwitch_andExposeOneToggle() {
        val context=instrumentation.targetContext
        val manager=context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        automation // Connect without suppressing the installed screen reader.
        assertTrue("TalkBack touch exploration must be enabled",manager.isTouchExplorationEnabled)
        assertTrue(manager.getEnabledAccessibilityServiceList(-1).any { it.resolveInfo.serviceInfo.packageName=="com.android.talkback" })
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitNode("切换到里世界样本")
        SystemClock.sleep(2000)
        forward() // Enter hardware keyboard mode before positioning the initial focus.
        focus("切换到里世界样本")
        for (expected in listOf("首页","书库","标签")) { forward(); focused(expected) }
        activate()
        awaitNode("查找标签")
        focus("切换到里世界样本"); activate()
        awaitNode("我的书库")
        focus("首页")
        for (expected in listOf("书库","标签","返回表世界")) { forward(); focused(expected) }
        activate(); awaitNode("查找标签")
        focus("设置"); activate(); awaitNode("设置")
        focus("外观与动效"); activate()
        val low=awaitNode("低效果")
        assertTrue("Toggle should be one labelled checkable node",low.isCheckable)
        val oldLow = low.isChecked
        focus("低效果"); activate()
        try {
            assertEquals(!oldLow, awaitNode("低效果").isChecked)
            forward(); focused("减少动画")
        } finally {
            if (awaitNode("低效果").isChecked != oldLow) { focus("低效果"); activate() }
        }
        focus("返回"); activate(); awaitNode("设置")
        focus("返回"); activate(); awaitNode("查找标签")
        val dir=File(context.getExternalFilesDir(null),"m1-access-evidence").apply { mkdirs() }
        File(dir,"talkback-focus.json").writeText(trace.toString(2))
        File(dir,"talkback-result.txt").writeText("PASS actual TalkBack service + touch exploration; raw Meta+Right keyboard traversal in both worlds; Meta+Space tab/world/settings activation; one labelled toggle per preference; no service suppression.\n")
    }
}



