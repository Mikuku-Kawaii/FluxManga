package com.flux.manga.ui.pages

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class BlacklistSuggestion(val value: String, val label: String = value)

/** Read the current world's existing models only. Typing never starts source requests. */
@Composable
internal fun blacklistSuggestions(session: WorldSession, input: String, blocked: List<String>, presets: List<String>,
    limit: Int): List<BlacklistSuggestion> {
    val identity = session.identity.value
    val context = LocalContext.current.applicationContext
    val pages = preloadedPages(session)
    val revision by pages.revision.collectAsState()
    val configuration by remember(pages, revision) { pages.discovery() }.state.collectAsState()
    val directory = if (identity.world == PrototypeWorld.SFW) {
        remember(pages, revision, configuration.sourceId) { pages.tags(configuration.sourceId) }.states.collectAsState().value
    } else {
        remember(pages, revision) { pages.privateTags() }.states.collectAsState().value
    }
    val shelf by remember(pages) { pages.shelf() }.state.collectAsState()
    val selection = remember(identity, revision) { SourceSelection(context, identity.world) }
    val discovered = pages.loadedFeeds().flatMap { model -> key(model) {
        val state by model.feed.state.collectAsState()
        state.results.values.flatMap { result -> result.cards.flatMap { it.sourceTags.orEmpty() } } +
            model.featured.books.values.flatMap { it.info.tags }
    } }
    val dictionary = if (identity.world == PrototypeWorld.NSFW && (selection.enabled(SourceKind.EH) || selection.enabled(SourceKind.EX))) {
        remember(pages) { pages.galleryTags() }.state.collectAsState().value.dictionary
    } else null
    val result by produceState(emptyList<BlacklistSuggestion>(), identity, input, blocked, directory, shelf.books, discovered, dictionary, limit) {
        value = emptyList()
        val query = normalizedBlockedTag(input)
        if (query.isEmpty()) return@produceState
        val matches = withContext(Dispatchers.Default) {
            val excluded = blocked.map(::normalizedBlockedTag).toSet()
            val names = presets.asSequence() + discovered.asSequence() + directory.values.asSequence().flatMap { it.tags.asSequence() } +
                shelf.books.asSequence().filter { it.key.world == identity.world }.flatMap { it.information?.tags.orEmpty().asSequence() }
            val candidates = names.map { BlacklistSuggestion(it) } + dictionary?.entries.orEmpty().asSequence().map {
                // Save the original namespaced tag, not the translated label or a search expression.
                BlacklistSuggestion(it.key, if (it.name == it.original) it.key else "${it.name} · ${it.key}")
            }
            matchingSuggestions(candidates, query, excluded, limit)
        }
        if (session.accepts(identity) && session.state.value.foreground) value = matches
    }
    return result
}

private fun matchingSuggestions(candidates: Sequence<BlacklistSuggestion>, query: String, excluded: Set<String>,
    limit: Int): List<BlacklistSuggestion> =
    candidates.filter { it.value.isNotBlank() }.distinctBy { normalizedBlockedTag(it.value) }
        .filter { normalizedBlockedTag(it.value) !in excluded &&
            (query in normalizedBlockedTag(it.value) || query in normalizedBlockedTag(it.label)) }
        .sortedWith(compareBy<BlacklistSuggestion> { !normalizedBlockedTag(it.value).startsWith(query) }
            .thenBy { normalizedBlockedTag(it.value) })
        .take(limit + 1).toList()

@Composable
internal fun titleKeywordSuggestions(session: WorldSession, input: String, blocked: List<String>, presets: List<String>,
    limit: Int): List<BlacklistSuggestion> {
    val query = normalizedBlockedTag(input)
    if (query.isEmpty()) return emptyList()
    val identity = session.identity.value
    val pages = preloadedPages(session)
    val shelf by pages.shelf().state.collectAsState()
    val feeds = pages.loadedFeeds().map { model -> key(model) { model.feed.state.collectAsState().value } }
    val books = shelf.books
    val result by produceState(emptyList<BlacklistSuggestion>(), identity, query, blocked, feeds, books, limit) {
        value = emptyList()
        val matches = withContext(Dispatchers.Default) {
            val names = presets.asSequence() + books.asSequence().filter { it.key.world == identity.world }.map { it.title } +
                feeds.asSequence().flatMap { it.results.values.asSequence() }.flatMap { it.cards.asSequence() }.map { it.title }
            matchingSuggestions(names.map { BlacklistSuggestion(it) }, query, blocked.map(::normalizedBlockedTag).toSet(), limit)
        }
        if (session.accepts(identity) && session.state.value.foreground) value = matches
    }
    return result
}
