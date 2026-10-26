package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Only invokes the caller's read operation. Refresh never submits a form or repeats a write. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceRefreshBox(refreshing: Boolean, refresh: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    val canRefresh = enabled && !refreshing
    val requestRefresh = feedbackAttempt(refresh)
    Box(modifier.pullToRefresh(isRefreshing = refreshing, state = state, enabled = canRefresh,
        onRefresh = { if (canRefresh) requestRefresh() }).semantics {
        if (canRefresh) customActions = listOf(CustomAccessibilityAction("下拉刷新") { requestRefresh(); true })
    }) {
        CompositionLocalProvider(LocalPersistentReadFeedback provides true) { content() }
        PullToRefreshDefaults.Indicator(state, refreshing, Modifier.align(Alignment.TopCenter),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
internal fun ReferenceRefreshColumn(modifier: Modifier = Modifier, refreshing: Boolean, refresh: () -> Unit,
    refreshEnabled: Boolean = true, state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: LazyListScope.() -> Unit) {
    ReferenceRefreshBox(refreshing, refresh, enabled = refreshEnabled) {
        LazyColumn(modifier, state = state, contentPadding = contentPadding,
            verticalArrangement = verticalArrangement, content = content)
    }
}
