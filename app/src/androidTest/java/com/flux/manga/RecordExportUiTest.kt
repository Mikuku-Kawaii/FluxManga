package com.flux.manga

import android.os.SystemClock
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** CreateDocument lifecycle and synthetic output. Never exports existing business records. */
class RecordExportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun open() {
        compose.awaitTag("shelf-more")
        compose.onNodeWithTag("shelf-more").performClick()
        compose.awaitTag("settings-page")
        compose.onNodeWithTag("settings-management").performClick()
        compose.onNodeWithTag("settings-record-export").performScrollTo().performClick()
        compose.awaitTag("record-export")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("最近读取的记录").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("record-export").performScrollToNode(hasText("选择保存位置"))
        compose.onNodeWithText("选择保存位置").assertIsNotEnabled()
        compose.onNodeWithText("我了解导出文件是明文，仅包含当前空间记录").performClick()
        compose.onNodeWithText("选择保存位置").assertIsEnabled()
    }

    private fun awaitPicker() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val pkg = automation.rootInActiveWindow?.packageName?.toString()
            if (pkg in setOf("com.android.documentsui", "com.google.android.documentsui")) return
            SystemClock.sleep(100)
        }
        fail("System CreateDocument did not open")
    }

    private fun cancelPicker() {
        // The first Back can dismiss an automatically opened filename keyboard.
        repeat(3) {
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(250)
            if (automation.rootInActiveWindow?.packageName?.toString() == "com.flux.manga") return
        }
        fail("CreateDocument did not return")
    }

    private fun systemSave() {
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(nodes(it)) }
        }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && root.packageName?.toString() in setOf("com.android.documentsui", "com.google.android.documentsui")) {
                val save = nodes(root).firstOrNull { it.text?.toString() in setOf("保存", "Save", "SAVE") && it.isEnabled && it.isClickable }
                if (save?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(100)
        }
        fail("No enabled system Save control")
    }

    @Test fun systemCreatedDocumentAcceptsSyntheticEncryptedSpaceExport(): Unit = runBlocking {
        compose.awaitTag("shelf-more")
        val inst = InstrumentationRegistry.getInstrumentation()
        val data = WorldDataStore(inst.targetContext, "export-saf-${UUID.randomUUID()}")
        val world = PrototypeWorld.NSFW
        data.worldData(world) { dao ->
            dao.putOnlineBook(OnlineBook("synthetic-only", "saf", "合成保存与回读", false, 1))
            dao.putOnlineProgress(OnlineReadingProgress("synthetic-only", "saf", "chapter", "page", 2, .625f, "STRIP", 2))
        }
        val received = AtomicReference<Uri?>()
        lateinit var launcher: ActivityResultLauncher<String>
        compose.runOnUiThread {
            launcher = compose.activity.activityResultRegistry.register("synthetic-record-export", RecordCreateDocument()) { received.set(it) }
            launcher.launch("FluxManga-synthetic-saf-${UUID.randomUUID()}.json")
        }
        awaitPicker(); systemSave()
        compose.waitUntil(10_000) { received.get() != null }
        val uri = requireNotNull(received.get())
        val counts = exportRecordDocument(data, inst.targetContext.contentResolver, uri, world, { true }, {})
        val text = inst.targetContext.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        assertEquals(2L, counts.values.sum()); assertTrue(json.getBoolean("complete"))
        assertEquals("NSFW", json.getString("world"))
        assertEquals("合成保存与回读", json.getJSONArray("onlineBooks").getJSONObject(0).getString("title"))
        assertEquals(.625, json.getJSONArray("onlineProgress").getJSONObject(0).getDouble("fraction"), .00001)
        assertEquals(0, json.getJSONArray("localBooks").length())
        compose.runOnUiThread { launcher.unregister() }
    }

    @Test fun confirmationCancelAndConfigurationReturnRetainExportPage() {
        open()
        compose.onNodeWithText("选择保存位置").performClick()
        awaitPicker()
        cancelPicker()
        compose.awaitTag("record-export")
        compose.onNodeWithText("已取消选择，未导出记录。").assertExists()
        compose.activityRule.scenario.recreate()
        compose.awaitTag("record-export")
        compose.onNodeWithTag("record-export").performScrollToNode(hasText("选择保存位置"))
        compose.onNodeWithText("选择保存位置").assertIsEnabled().performClick()
        awaitPicker(); cancelPicker()
        compose.awaitTag("record-export")
        compose.onNodeWithText("已取消选择，未导出记录。").assertExists()
    }

    @Test fun pickerRotationKeepsCallbackRecipient() {
        open()
        val activity = compose.activity
        compose.onNodeWithText("选择保存位置").performClick()
        awaitPicker()
        // ActivityScenario.recreate waits for the covered Activity to resume and would deadlock
        // this driver before it can dismiss DocumentsUI. Request recreation without that wait.
        activity.runOnUiThread { activity.recreate() }
        systemSave()
        compose.awaitTag("record-export")
        compose.onNodeWithTag("record-export").performScrollToNode(hasText("开始导出当前空间记录"))
        compose.onNodeWithText("开始导出当前空间记录").assertIsEnabled()
        compose.onNodeWithText("放弃此文件").performClick()
        // Only an empty newly created target remains; no business records have been written.
        compose.onNodeWithText("选择保存位置").assertIsEnabled()
    }

    @Test fun pickerTimeoutCannotResumeOldExport() {
        open()
        val generation = compose.activity.worldSession.identity.value.generation
        compose.onNodeWithText("选择保存位置").performClick()
        awaitPicker()
        SystemClock.sleep(31_000)
        cancelPicker()
        compose.awaitTag("shelf-more")
        assertTrue(compose.activity.worldSession.identity.value.generation > generation)
        compose.onNodeWithTag("record-export").assertDoesNotExist()
        open()
        compose.onNodeWithText("选择保存位置").performClick()
        awaitPicker(); cancelPicker()
        compose.awaitTag("record-export")
        compose.onNodeWithText("已取消选择，未导出记录。").assertExists()
    }
}
