package com.flux.manga.ui.pages

import androidx.compose.runtime.Composable
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.TagTarget
import com.flux.manga.ui.navigation.UnifiedTagResults

/** Keep existing detail links and saved routes, but always open the mixed tag results. */
@Composable
internal fun SourceTagBooksPage(session: WorldSession, sourceId: String, tag: String, back: () -> Unit,
    sources: () -> Unit, open: (String, String) -> Unit, label: String = tag) {
    MixedTagBooksPage(session, UnifiedTagResults(session.identity.value.world, label,
        listOf(TagTarget(sourceId, tag))), back, sources, open)
}
