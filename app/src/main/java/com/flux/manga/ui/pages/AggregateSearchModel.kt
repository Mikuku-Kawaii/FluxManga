package com.flux.manga.ui.pages

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.data.AggregateSearchRepository
import com.flux.manga.data.OnlineLibrary
import com.flux.manga.session.WorldSession

internal class AggregateSearchModel(app: Application, session: WorldSession, komgaId: String?) : ViewModel() {
    private val repository = AggregateSearchRepository(app, session, komgaId)
    val library = OnlineLibrary(app, session)
    private val blacklist = TagBlacklistFilter(app, session)
    private val snapshots = repository.sources.associateWith { CatalogPageCache(app, session, "search:$it") }
    val search = AggregateSearchController(viewModelScope, repository.sources, repository::isActive,
        restore = { source, query -> snapshots.getValue(source).feed(query) },
        save = { source, query, state -> snapshots.getValue(source).saveFeed(query, state) }) { source, query, page ->
            blacklist.apply(repository.search(source, query, page))
        }
    val historyScope = aggregateSearchScope(repository.sources)
}
