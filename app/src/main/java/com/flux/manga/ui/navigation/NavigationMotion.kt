package com.flux.manga.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation3.runtime.NavKey
import com.flux.manga.ui.pages.ReferenceTransitionKind

internal fun NavKey.isReaderRoute(): Boolean = when (this) {
    is LocalBookReader, is KomgaReader, is CopyMangaReader, is BaoziReaderRoute,
    is JmReaderRoute, is PicaReaderRoute, is EhReaderRoute, is ExReaderRoute,
    is DownloadedReader, is PrivateOfflineReaderRoute, is MangaReader -> true
    else -> false
}

internal data class NavigationMotion(val kind: ReferenceTransitionKind, val tabDirection: Int = 1)

/** Remembers only route identifiers within this host, never pages or cross-world snapshots. */
@Composable
internal fun rememberNavigationMotion(route: NavKey, tab: MainTab): NavigationMotion {
    var previous by remember { mutableStateOf(route to tab) }
    val motion = remember(route, tab) {
        when {
            previous.second != tab -> NavigationMotion(ReferenceTransitionKind.Tab,
                if (MainTab.primary.indexOf(tab) > MainTab.primary.indexOf(previous.second)) 1 else -1)
            route.isReaderRoute() || previous.first.isReaderRoute() -> NavigationMotion(ReferenceTransitionKind.Reader)
            else -> NavigationMotion(ReferenceTransitionKind.Page)
        }
    }
    SideEffect { previous = route to tab }
    return motion
}
