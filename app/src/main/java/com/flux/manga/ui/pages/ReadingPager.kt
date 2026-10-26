package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.flux.manga.data.ReadingMode

/** Both readers share snapping, page identity and zero beyond-viewport prefetch. */
@Composable
internal fun ReadingPager(state: PagerState, mode: ReadingMode, tag: String, pageKey: (Int) -> Any,
    enabled: Boolean = true, content: @Composable PagerScope.(Int) -> Unit) {
    if (mode == ReadingMode.VERTICAL) VerticalPager(state, beyondViewportPageCount = 0, userScrollEnabled = enabled,
        modifier = Modifier.fillMaxSize().testTag(tag), key = pageKey, pageContent = content)
    else HorizontalPager(state, reverseLayout = mode == ReadingMode.RTL, beyondViewportPageCount = 0, userScrollEnabled = enabled,
        modifier = Modifier.fillMaxSize().testTag(tag), key = pageKey, pageContent = content)
}
