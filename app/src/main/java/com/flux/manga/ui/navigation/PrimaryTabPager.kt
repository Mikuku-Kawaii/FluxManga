package com.flux.manga.ui.navigation

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import com.flux.manga.ui.pages.LocalRequestFeedback

/** Live page movement belongs to the content host; the dock keeps its own selection animation. */
@Composable
internal fun PrimaryTabPager(
    navigation: PrototypeNavigation,
    swipeEnabled: Boolean,
    motionEnabled: Boolean,
    onSwipeSelection: (MainTab?) -> Unit,
    content: @Composable (MainTab, Boolean) -> Unit,
) {
    val tabs = MainTab.primary
    val selected = tabs.indexOf(navigation.selectedTab)
    val pager = rememberPagerState(initialPage = selected) { tabs.size }
    val parent = LocalLifecycleOwner.current
    val feedback = LocalRequestFeedback.current
    val updateSwipeSelection by rememberUpdatedState(onSwipeSelection)

    // Dock taps and restored navigation also use this same pager, without changing dock code.
    LaunchedEffect(selected, swipeEnabled, motionEnabled) {
        if (pager.currentPage != selected || pager.currentPageOffsetFraction != 0f ||
            (!swipeEnabled && pager.isScrollInProgress)) {
            if (swipeEnabled && motionEnabled) pager.animateScrollToPage(selected)
            else pager.scrollToPage(selected)
        }
    }
    LaunchedEffect(pager, navigation) {
        var wasScrolling = false
        snapshotFlow { Triple(pager.isScrollInProgress, pager.targetPage, pager.settledPage) }
            .collect { (scrolling, target, settled) ->
                if (scrolling) {
                    wasScrolling = true
                    updateSwipeSelection(tabs[target])
                } else {
                    // Commit first, then clear the visual preview, so the dock never flashes back.
                    if (wasScrolling && navigation.selectedTab != tabs[settled]) navigation.select(tabs[settled])
                    wasScrolling = false
                    updateSwipeSelection(null)
                }
            }
    }

    HorizontalPager(
        state = pager, modifier = Modifier.fillMaxSize(), key = { tabs[it] },
        beyondViewportPageCount = 0, userScrollEnabled = swipeEnabled,
        flingBehavior = if (motionEnabled) PagerDefaults.flingBehavior(pager)
            else PagerDefaults.flingBehavior(pager, snapAnimationSpec = snap()),
    ) { page ->
        val tab = tabs[page]
        val active = tab == navigation.selectedTab
        // Neighbours are root previews only; never start an offscreen reader or account page.
        if (active || navigation.isPrimaryRoot) {
            val owner = rememberLifecycleOwner(
                maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
                parent = parent,
            )
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalRequestFeedback provides if (active) feedback else null,
            ) { content(tab, active) }
        }
    }
}
