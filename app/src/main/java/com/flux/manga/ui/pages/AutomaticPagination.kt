package com.flux.manga.ui.pages

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collect

/** A composed/prefetched footer is not necessarily visible. Only page at the actual viewport. */
@Composable
internal fun AutomaticPagination(scroll: LazyListState, footerKey: String, requestKey: Any?,
    enabled: Boolean, more: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val currentKey by rememberUpdatedState(requestKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val load by rememberUpdatedState(more)
    LaunchedEffect(scroll, footerKey, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        var requested: Any? = null
        snapshotFlow {
            val layout = scroll.layoutInfo
            val visible = layout.visibleItemsInfo.any {
                it.key == footerKey && it.offset < layout.viewportEndOffset &&
                    it.offset + it.size > layout.viewportStartOffset
            }
            Triple(visible, currentEnabled, currentKey)
        }.collect { (visible, ready, cursor) ->
            if (!visible) requested = null
            else if (ready && cursor != null && cursor != requested) {
                // A source that returns an unchanged cursor must not cause a tight request loop.
                requested = cursor
                load()
            }
        }
    }
}
