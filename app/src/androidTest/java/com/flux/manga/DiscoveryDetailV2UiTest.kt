package com.flux.manga

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled only: pure UI fixtures, no source calls or library writes. */
class DiscoveryDetailV2UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Let the production first-frame privacy cover finish before replacing content with fixtures.
    @org.junit.Before fun awaitExposedWindow() { compose.waitUntil(10_000) { !compose.activity.isCovered } }
    @Test fun workMetadataKeepsFullCreatorListAndDistinctUpdateLabelAtLargeText() {
        var world by mutableStateOf(PrototypeWorld.SFW)
        val metadata = com.flux.manga.core.SourceWorkMetadata((1..4).map {
            com.flux.manga.core.SourceAuthor("合成创作者 $it", if (it == 4) "custom-role" else "writer")
        }, "休刊中", "2026-10-01T12:30:00", com.flux.manga.core.SourceUpdateKind.METADATA, true)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        DetailHero("合成作品", { Surface(Modifier.size(100.dp,140.dp)) {} }) { WorkMetadata(metadata, "synthetic") }
                    }
                }
            }
        } }
        for (palette in PrototypeWorld.entries) {
            compose.runOnIdle { world = palette }
            compose.onNodeWithText("资料更新时间：2026-10-01T12:30:00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("部分作品信息无法识别，可刷新详情重试。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("查看创作者列表（4）").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("合成创作者 4（custom-role）").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText("状态：休刊中").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun largeTextActionsKeepSingleCallbacksAndOptionalDownload() {
        var world by mutableStateOf(PrototypeWorld.SFW)
        var favorites = 0; var downloads = 0; var reads = 0
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        DetailHero("很长的合成标题，用来检查大字换行", { Surface(Modifier.size(100.dp,140.dp)) {} })
                        DetailActions(false, false, toggleFavorite = { favorites++ }, download = { downloads++ },
                            read = { reads++ }, canRead = true, hasProgress = true)
                    }
                }
            }
        } }
        for (palette in PrototypeWorld.entries) {
            compose.runOnIdle { world = palette }
            compose.onNodeWithText("收藏").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("下载").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("继续阅读").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.runOnIdle { assertEquals(2, favorites); assertEquals(2, downloads); assertEquals(2, reads) }
    }
    @Test fun fullCatalogLocatesSavedChapterAndUsesStableId() {
        var opened: String? = null
        val entries = (1..80).map { DetailCatalogEntry("chapter-$it", "合成章节 $it") }
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme(colorScheme = WorldColors.scheme(PrototypeWorld.SFW)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                    DetailCatalog(entries, "chapter-42", { opened = it })
                }
            }
        } }
        compose.onNodeWithTag("detail-locate").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("detail-full-directory").assertExists()
        compose.onNodeWithText("合成章节 42").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("chapter-42", opened) }
        compose.onNodeWithTag("detail-full-directory").assertDoesNotExist()
    }
    @Test fun chapterMarkIsSeparateFromOpeningAndCanBeUndoneAtLargeText() {
        var world by mutableStateOf(PrototypeWorld.SFW)
        var record by mutableStateOf(com.flux.manga.data.ChapterReading("synthetic", "book", "ch", "p", 3, .5f, 1L, false))
        var opens = 0
        var marks = 0
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = WorldColors.scheme(world)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        DetailCatalog(listOf(DetailCatalogEntry("ch", "合成章节")), "ch", { opens++ },
                            chapterState = { ChapterReadingAction(it.title, record, true) { completed -> marks++; record = record.copy(completed = completed) } })
                    }
                }
            }
        } }
        for (palette in PrototypeWorld.entries) {
            compose.runOnIdle { world = palette }
            compose.onNodeWithText("标记已读").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.runOnIdle { assertEquals(0, opens); assertTrue(record.completed) }
            compose.onNodeWithText("取消已读标记").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(record.completed); assertEquals(3, record.ordinal); assertEquals(.5f, record.fraction) }
        }
        compose.runOnIdle { assertEquals(4, marks) }
        compose.onNodeWithText("合成章节").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
