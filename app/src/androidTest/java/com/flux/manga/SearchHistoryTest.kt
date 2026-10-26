package com.flux.manga

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.WorldDataStore
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }
    private fun clear() = runBlocking {
        val store = WorldDataStore(InstrumentationRegistry.getInstrumentation().targetContext)
        PrototypeWorld.entries.forEach { store.clearHistory(it, "sample") }
    }
    private var ownsHistory = false
    @Before fun resetHistory() {
        val store = WorldDataStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val empty = runBlocking { PrototypeWorld.entries.all { store.history(it, "sample").isEmpty() } }
        org.junit.Assume.assumeTrue("Existing sample history preserved; run this case in an isolated empty history namespace", empty)
        ownsHistory = true
        compose.activityRule.scenario.recreate()
    }
    @After fun cleanHistory() { if (ownsHistory) clear() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun save(query: String) {
        compose.onNodeWithTag("search-query").performScrollTo().performTextReplacement(query)
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("root-loading").fetchSemanticsNodes().isEmpty() }
        click("history-save")
        compose.awaitTag("history-0")
        compose.onNodeWithTag("history-0").assertTextEquals(query)
    }
    private fun switch(world: PrototypeWorld) {
        Espresso.closeSoftKeyboard()
        Espresso.pressBack() // Search is a child; switching is root-only.
        compose.awaitTag("switch-world")
        compose.onNodeWithTag("switch-world").performClick()
        compose.waitUntil(10_000) { compose.activity.worldSession.let { it.identity.value.world == world && it.state.value.phase == SwitchPhase.Stable } }
        compose.awaitTag("root-title")
    }
    private fun capture(name: String) {
        val dir = File(compose.activity.getExternalFilesDir(null), "m1-storage-evidence").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun explicitHistory_recreatesAndSwitchesWithoutCrossWorldLeak() {
        compose.awaitTag("sample-1")
        compose.openSampleSearch()
        compose.awaitTag("search-query")
        save("orbit")
        capture("public-history")
        compose.activityRule.scenario.recreate()
        compose.awaitTag("history-0")
        compose.onNodeWithTag("history-0").assertTextEquals("orbit")
        switch(PrototypeWorld.NSFW)
        compose.openSampleSearch()
        compose.awaitTag("sample-1")
        compose.onNodeWithTag("history-0").assertDoesNotExist()
        save("60")
        capture("private-synthetic-history-in-process")
        switch(PrototypeWorld.SFW)
        compose.openSampleSearch()
        compose.awaitTag("history-0")
        compose.onNodeWithTag("history-0").assertTextEquals("orbit")
        compose.onNodeWithTag("search-query").assertTextEquals("", "查询（状态恢复验证）")
        click("history-0")
        compose.onNodeWithTag("search-query").assertTextContains("orbit")
        click("history-clear")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("history-0").fetchSemanticsNodes().isEmpty() }
        switch(PrototypeWorld.NSFW)
        compose.openSampleSearch()
        compose.awaitTag("history-0")
        compose.onNodeWithTag("history-0").assertTextEquals("60")
    }
}
