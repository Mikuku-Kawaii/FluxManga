package com.flux.manga.ui.navigation

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.flux.manga.data.SampleManga
import com.flux.manga.data.SampleScenario
import com.flux.manga.session.SessionIdentity
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.pages.SamplePageViewModel
import java.util.UUID

class DetailViewModel(
    savedState: SavedStateHandle,
    private val route: MangaDetail,
    session: WorldSession,
) : SamplePageViewModel<SampleManga>(savedState, session) {
    private val instance = UUID.randomUUID().toString()
    val marks = savedState.getStateFlow("marks", 0)

    init {
        Log.d("FluxNavVM", "created $instance $route")
        reload()
    }

    override suspend fun fetch(identity: SessionIdentity, scenario: SampleScenario) =
        repository.detail(identity, route.world, route.sourceId, route.mangaId, scenario)

    fun addMark() { savedState["marks"] = marks.value + 1 }

    override fun onCleared() {
        Log.d("FluxNavVM", "cleared $instance $route")
    }
}
