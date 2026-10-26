package com.flux.manga

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flux.manga.ui.pages.KomgaDetailTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated component fixture; never reads source configuration, library or user files. */
class KomgaDetailTagsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewAndFullListPreserveRawTagAndSource() {
        val tags = (1..9).map { " 合成 / &? $it " }
        var selected: Pair<String, String>? = null
        compose.setContent { MaterialTheme { KomgaDetailTags("source-a", "book", tags) { source, tag -> selected = source to tag } } }
        compose.onNodeWithText(tags.first()).performClick()
        compose.runOnIdle { assertEquals("source-a" to tags.first(), selected) }
        compose.onNodeWithText(tags.last()).assertDoesNotExist()
        compose.onNodeWithTag("komga-all-tags").performClick()
        compose.onNodeWithTag("komga-all-tags-list").performScrollToNode(hasText(tags.last()))
        compose.onNodeWithText(tags.last()).performClick()
        compose.runOnIdle { assertEquals("source-a" to tags.last(), selected) }
        compose.onNodeWithTag("komga-all-tags-list").assertDoesNotExist()
    }

    @Test fun missingTagsAreNotReportedAsAnEmptyList() {
        compose.setContent { MaterialTheme { KomgaDetailTags("source-a", "book", null) { _, _ -> error("No tag action") } } }
        compose.onNodeWithText("图源未提供此作品的标签信息").assertIsDisplayed()
        compose.onNodeWithText("此作品暂无图源标签").assertDoesNotExist()
    }
}
