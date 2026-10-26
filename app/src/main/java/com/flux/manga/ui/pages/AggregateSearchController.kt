package com.flux.manga.ui.pages

import com.flux.manga.core.SourcePageRequest
import com.flux.manga.data.CopyMangaEndpoint
import com.flux.manga.data.SourceCardPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun aggregateSearchScope(sources: List<String>) = "aggregate-v1:" + sources.distinct().sorted().joinToString("|")

internal data class AggregateSearchState(
    val query: String = "",
    val results: Map<String, DiscoveryFeedState> = emptyMap(),
) {
    val busy get() = results.values.any { it.busy }
    val failures get() = results.values.count { it.error != null || it.appendError != null }
    val loaded get() = results.values.count { it.loaded }
}

/** Combines the existing per-source pagination state. No database writes or source identity merge.
 * Design reference: Mihon SearchViewModel, d001e6f (see verification record); no upstream code copied.
 * Owned and called by the entry's main-thread scope; each source has one independent request job. */
internal class AggregateSearchController(
    private val scope: CoroutineScope,
    val sources: List<String>,
    private val accepts: () -> Boolean,
    private val sourceIds: Map<String, String> = emptyMap(),
    private val restore: suspend (String, String) -> DiscoveryFeedState? = { _, _ -> null },
    private val save: suspend (String, String, DiscoveryFeedState) -> Unit = { _, _, _ -> },
    private val refreshRestored: Boolean = false,
    private val load: suspend (String, String, SourcePageRequest) -> SourceCardPage,
) {
    private val mutable = MutableStateFlow(AggregateSearchState())
    val state = mutable.asStateFlow()
    private var revision = 0L
    private val feeds = linkedMapOf<String, DiscoveryFeedController>()
    private val observers = mutableListOf<Job>()

    fun submit(input: String, allowEmpty: Boolean = false): Boolean {
        val query = input.trim().take(200)
        if ((!allowEmpty && query.isEmpty()) || !accepts() || sources.isEmpty()) return false
        if (query == state.value.query && state.value.busy) return false
        ++revision
        feeds.values.forEach { it.leave() }
        observers.forEach { it.cancel() }; observers.clear(); feeds.clear()
        val ticket = revision
        mutable.value = AggregateSearchState(query, sources.associateWith { DiscoveryFeedState() })
        sources.forEach { source ->
            val feed = DiscoveryFeedController(scope, accepts,
                { error ->
                    val id = sourceIds[source] ?: source
                    when {
                        id.startsWith("jmcomic-") -> com.flux.manga.data.jmFailure(error)
                        id.startsWith("picacg-") -> com.flux.manga.data.picaFailure(error)
                        id.startsWith("ehentai-") -> com.flux.manga.data.ehFailure(error, com.flux.manga.data.GallerySite.EH)
                        id.startsWith("exhentai-") -> com.flux.manga.data.ehFailure(error, com.flux.manga.data.GallerySite.EX)
                        id == CopyMangaEndpoint.SOURCE_ID -> copyMangaFailure(error)
                        id == com.flux.manga.data.BaoziEndpoint.SOURCE_ID -> baoziFailure(error)
                        else -> komgaFailure(error)
                    }
                }, restore = { restore(source, query) }, save = { save(source, query, it) }, refreshRestored = refreshRestored) { page ->
                load(source, query, page)
            }
            feeds[source] = feed
            observers += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                feed.state.collect { result ->
                    if (ticket == revision && accepts()) mutable.value = state.value.copy(results = state.value.results + (source to result))
                }
            }
            feed.start()
        }
        return true
    }

    fun more(source: String) { if (accepts()) feeds[source]?.more() }
    fun refresh(source: String) { if (accepts()) feeds[source]?.refresh() }
    fun cancel(source: String? = null) {
        if (source == null) feeds.values.forEach { it.cancel() } else feeds[source]?.cancel()
    }
    // Deliberately do not restart on re-entry; a cancelled request has an explicit retry action.
    fun leave() { cancel() }
    // App-background suspension differs from explicit user cancellation: unfinished first pages resume.
    fun pause() { feeds.values.forEach { it.leave() } }
    fun resume() { feeds.values.forEach { it.start() } }
}

/** Display-only interleave: source keys and each source's page order stay intact. */
internal fun mixedCards(sources: List<String>, results: Map<String, DiscoveryFeedState>): List<com.flux.manga.data.MangaCard> {
    val lists = sources.map { source -> results[source]?.let { if (it.error != null && !it.busy) emptyList() else it.cards }.orEmpty() }
    return buildList {
        repeat(lists.maxOfOrNull { it.size } ?: 0) { index -> lists.forEach { it.getOrNull(index)?.let(::add) } }
    }.distinctBy { it.key }
}
