package com.flux.manga

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.ui.pages.HomeBook
import com.flux.manga.ui.pages.LocalLibraryViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ContentAdaptationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualCard_lightDarkTheme_brightDarkCover_wrappingLabelsAndTargets() {
        val profile=InstrumentationRegistry.getArguments().getString("layout","normal")
        if (profile.contains("landscape")) {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        }
        val cards=ContentFixtures.cards().map { it.copy(progress=null) }
        val covers=listOf(cards.first { it.title.endsWith("亮封面") },cards.first { it.title.endsWith("暗封面") })
        val dark=mutableStateOf(false); val index=mutableIntStateOf(0)
        lateinit var model: LocalLibraryViewModel
        compose.runOnIdle {
            model=LocalLibraryViewModel(compose.activity,compose.activity.worldSession,null,SavedStateHandle())
            compose.activity.viewModelStore.put("content-adaptation",model)
            compose.activity.setContent {
                MaterialTheme(colorScheme=if(dark.value) darkColorScheme(primary=Color(0xFFD0BCFF),background=Color(0xFF181322))
                    else lightColorScheme(primary=Color(0xFF2266A8),background=Color(0xFFF5FAFF))) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp).testTag("card-harness")) {
                            HomeBook(covers[index.intValue],model,0,"matrix",{}, {})
                        }
                    }
                }
            }
        }
        for(theme in listOf(false,true)) for(i in 0..1) {
            compose.runOnIdle {
                dark.value=theme; index.intValue=i
                androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !theme; isAppearanceLightNavigationBars = !theme
                }
            }
            val card=covers[i]
            compose.waitUntil(10000) { compose.onAllNodesWithTag("cover-${card.key.mangaId}").fetchSemanticsNodes().isNotEmpty() }
            val detail=compose.onNodeWithTag("home-detail-matrix-${card.key.mangaId}")
            val read=compose.onNodeWithTag("home-read-matrix-${card.key.mangaId}")
            detail.performScrollTo().assertContentDescriptionEquals("查看《${card.title}》详情").assertHasClickAction()
            read.performScrollTo().assertContentDescriptionEquals("开始阅读《${card.title}》").assertHasClickAction()
            read.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            detail.assertHeightIsAtLeast(48.dp)
            compose.onAllNodes(hasText("查看详情") or hasText("开始阅读") or hasText(card.title),useUnmergedTree=true).fetchSemanticsNodes().forEach { node ->
                val results=mutableListOf<TextLayoutResult>()
                if (node.config.contains(SemanticsActions.GetTextLayoutResult)) node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                results.forEach { assertFalse("Clipped text: ${it.layoutInput.text}; size=${it.size}; constraints=${it.layoutInput.constraints}; width=${it.didOverflowWidth}; height=${it.didOverflowHeight}",(it.didOverflowHeight || (0 until it.lineCount).any { line -> it.isLineEllipsized(line) || it.getLineRight(line) > it.size.width + 1f || it.getLineLeft(line) < -1f })) }
            }
            compose.onNodeWithTag("cover-${card.key.mangaId}").performScrollTo()
            compose.waitForIdle()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(ContentFixtures.evidence,"v06-$profile-${if(theme) "dark" else "light"}-${if(i==0) "bright" else "dim"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        ContentFixtures.verifyOriginals()
    }
}






