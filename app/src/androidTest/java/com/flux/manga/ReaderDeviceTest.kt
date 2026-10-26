package com.flux.manga

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Choreographer/input; never installs a Compose test clock or changes accessibility services. */
@RunWith(AndroidJUnit4::class)
class ReaderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val evidence get() = File(context.getExternalFilesDir(null), "reader-accessibility").apply { mkdirs() }
    private val trace = JSONArray()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun all(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { node.getChild(it)?.let(::all).orEmpty() }
    private fun nodes() = automation.rootInActiveWindow?.let(::all).orEmpty()
        .filter { it.packageName == context.packageName && it.isVisibleToUser }
    private fun label(node: AccessibilityNodeInfo): String = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        ?: node.text?.toString().orEmpty().ifBlank { (0 until node.childCount).joinToString(" ") { node.getChild(it)?.let(::label).orEmpty() }.trim() }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(100) }
        File(evidence, "missing-node.txt").writeText("$message\n" + nodes().joinToString("\n") { "${it.className} ${label(it)} clickable=${it.isClickable} editable=${it.isEditable} scroll=${it.isScrollable}" })
        error(message)
    }
    private fun node(text: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitFor("Missing $text") {
            found = nodes().filter { !it.isEditable && label(it) == text }.maxByOrNull { if (it.isClickable) 1 else 0 }
            found != null
        }
        return found!!
    }
    private fun click(text: String) {
        var n = nodes().firstOrNull { !it.isEditable && it.isClickable && label(it) == text } ?: node(text)
        while (!n.isClickable && n.parent != null) n = n.parent
        assertTrue("Click $text", n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(250)
    }
    private fun scrollTo(text: String) {
        repeat(16) {
            if (nodes().any { !it.isEditable && label(it) == text }) return
            val scroll = nodes().firstOrNull { it.isScrollable } ?: error("No scroll container for $text")
            scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(180)
        }
        File(evidence, "missing-node.txt").writeText(nodes().joinToString("\n") { "${it.className} ${label(it)} clickable=${it.isClickable} scroll=${it.isScrollable}" })
        error("Cannot scroll to $text")
    }
    private fun launch() {
        automation
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        node("书库操作")
    }
    private fun openReader(): MangaKey = runBlocking {
        val library = CbzLibrary(WorldDataStore(context))
        val key = ReaderFixtures.prepareUnique(library, evidence)
        val title = library.detail(key)!!.card.title
        library.saveProgress(key, "page1.png", 0f, ReadingMode.LTR)
        launch(); click("书库操作"); scrollTo("本地管理 / 导入与页序"); click("本地管理 / 导入与页序")
        waitFor("Local query") { nodes().any { it.isEditable } }
        val edit = nodes().first { it.isEditable }
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
        }))
        // Use the sole filtered result's explicit read action, not text duplicated in the query.
        scrollTo("继续阅读 · 第 1 页"); click("继续阅读 · 第 1 页")
        node("第 1 / 5 页 · 单章节")
        key
    }
    private fun readPosition(key: MangaKey, page: Int) = runBlocking {
        val library = CbzLibrary(WorldDataStore(context))
        waitFor("Progress page $page") { runBlocking { library.detail(key)?.card?.progress?.pageId ==
            listOf("page1.png", "page2.jpg", "page3.png", "page4.webp", "page10.png")[page - 1] } }
    }
    @Test fun nativeLargeReaderSwipesAndReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeReader") == "true")
        assertFalse(context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled)
        val key = openReader()
        val pid = android.os.Process.myPid()
        repeat(6) { round ->
            for (page in listOf(2, 3, 2, 1)) {
                val bounds = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
                val forward = page == 3 || (page == 2 && runBlocking {
                    CbzLibrary(WorldDataStore(context)).detail(key)!!.card.progress!!.pageId == "page1.png"
                })
                val x1 = bounds.left + (bounds.width() * if (forward) .8f else .2f).toInt()
                val x2 = bounds.left + (bounds.width() * if (forward) .2f else .8f).toInt()
                val y = bounds.top + (bounds.height() * .7f).toInt()
                shell("input swipe $x1 $y $x2 $y 250")
                node("第 $page / 5 页 · 单章节"); readPosition(key, page)
                trace.put(JSONObject().put("round", round).put("page", page).put("pid", pid))
            }
            click("隐藏工具"); click("显示工具")
        }
        click("返回"); node("本地书库")
        File(evidence, "native-reader.json").writeText(trace.toString(2))
    }
}
