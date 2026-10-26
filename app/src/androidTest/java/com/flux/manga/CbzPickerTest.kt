package com.flux.manga

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import android.content.ContentValues
import android.provider.MediaStore

@RunWith(AndroidJUnit4::class)
class CbzPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun systemClick(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val found = automation.rootInActiveWindow?.let(::nodes).orEmpty().filter {
                it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label ||
                    it.contentDescription?.toString() == "文件,$label") // OPPO visually ellipsizes filenames.
            }
            for (node in found) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(100)
        }
        error("Missing system picker node: $label")
    }
    @Test fun systemPicker_importCoverNaturalPagesRecreationAndReadOnlyOriginal() {
        val resolver = compose.activity.contentResolver
        val oppo = InstrumentationRegistry.getArguments().getString("oppoPicker") == "true"
        val folder = "FluxManga-M2-${java.util.UUID.randomUUID()}"
        val fileName = if (oppo) "$folder.cbz" else "自然页序合成.cbz"
        val original = if (oppo) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.comicbook+zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
            })!!.also { uri -> resolver.openOutputStream(uri)!!.use { it.write(CbzFixtures.valid()) } }
        } else android.provider.DocumentsContract.buildDocumentUri("com.flux.manga.test.cbz", "fixture")
        // A unique synthetic download is retained on OEM pickers that do not expose test providers.
        val evidence = File(compose.activity.getExternalFilesDir(null), "m2-cbz-evidence").apply { mkdirs() }
        File(evidence, "picker-source.txt").writeText("uri=$original\nfolder=${if (oppo) "Download/$folder" else "read-only provider"}")
        compose.openLocalManagement()
        compose.awaitTag("local-import")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("local-import").performClick()
        if (oppo) {
            systemClick("收起标题")
            systemClick("文件")
            systemClick("下载")
        } else {
            systemClick("Show roots")
            systemClick("FluxManga 合成 CBZ")
        }
        systemClick(fileName)
        compose.waitUntil(15_000) {
            try {
                compose.onAllNodes(hasText("此 CBZ 已在书库中，未重复导入") or hasText("导入完成，原文件保持不变")).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty()
            } catch (e: IllegalStateException) {
                // The OEM picker can temporarily remove the app's Compose root while returning.
                if (e.message?.startsWith("No compose hierarchies found") == true) false else throw e
            }
        }
        // The library can now contain multiple books and a taller filter header.
        // Wait for the import outcome, then compose the result by scrolling to it.
        val sourceHash = MessageDigest.getInstance("SHA-256").digest(CbzFixtures.valid()).joinToString("") { "%02x".format(it) }
        val title = kotlinx.coroutines.runBlocking {
            val library = com.flux.manga.data.CbzLibrary(com.flux.manga.data.WorldDataStore(compose.activity))
            library.cards(com.flux.manga.ui.navigation.PrototypeWorld.SFW).first {
                library.detail(it.key)?.sha256 == sourceHash
            }.title
        }
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-book-$title"))
        compose.onNodeWithTag("local-book-$title").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-cover"))
        CbzFixtures.order.forEachIndexed { index, name ->
            compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-page-${index + 1}"))
            val node = compose.onNodeWithTag("local-page-${index + 1}").assertIsDisplayed().assertTextContains(name, substring = true)
            val safe = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.systemBars())
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Page obscured by system bars: $bounds / $safe", bounds.top >= safe.top && bounds.bottom <= screen.bottom - safe.bottom)
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("local-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("local-list").performScrollToNode(hasTestTag("local-book-title"))
        compose.onNodeWithTag("local-book-title").assertTextEquals(title)
        val actual = resolver.openInputStream(original)!!.use { it.readBytes() }
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(CbzFixtures.valid()), MessageDigest.getInstance("SHA-256").digest(actual))
        automation.takeScreenshot().let { bitmap ->
            val image = if (compose.activity.resources.configuration.fontScale > 1.5f) "local-detail-large-font.png" else "local-detail.png"
            File(evidence, image).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        File(evidence, "source-preserved.txt").writeText("PASS real ACTION_OPEN_DOCUMENT -> ${if (oppo) "OPPO picker / unique synthetic download" else "synthetic read-only provider"}; source SHA256=" + MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) })
    }
}
