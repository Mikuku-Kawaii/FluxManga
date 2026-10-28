package com.flux.manga.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.flux.manga.data.MangaCard
import com.flux.manga.data.OnlineLibrary

/** Keep the page's existing lazy scroll/state owner; only visible rows compose their covers. */
@Composable
internal fun BookResultsLayout(enabled: Boolean = true, content: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = LocalDensity.current.fontScale
        val minimum = when {
            scale >= 2f -> 200f
            scale >= 1.5f -> 148f
            else -> 92f
        }
        val maximum = if (maxWidth < 840.dp) 3 else 6
        val columns = if (enabled) ((maxWidth.value - 44f + 12f) / (minimum + 12f)).toInt().coerceIn(1, maximum) else 1
        content(columns)
    }
}

internal fun <T> LazyListScope.bookGridItems(books: List<T>, columns: Int, identity: (T) -> String,
    content: @Composable (T) -> Unit) {
    items(books.chunked(columns), key = { "book-row:${identity(it.first())}" }, contentType = { "book-row" }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { book -> key(identity(book)) {
                Column(Modifier.weight(1f)) { content(book) }
            } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun BookResultCard(title: String, open: () -> Unit, grid: Boolean = true,
    titleModifier: Modifier = Modifier, metadata: String = "", enabled: Boolean = true, cover: @Composable () -> Unit) {
    if (!grid) {
        Column(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button,
            onClickLabel = "查看《$title》详情", onClick = open)) {
            ReferenceBookSummary(cover = cover) {
                Text(title, titleModifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else Column(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button,
        onClickLabel = "查看《$title》详情", onClick = open), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cover()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, titleModifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (metadata.isNotBlank()) Text(metadata, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun DiscoveryGridBook(library: OnlineLibrary, card: MangaCard, open: (String, String) -> Unit) {
    BookResultCard(card.title, { open(card.key.sourceId, card.key.mangaId) }, metadata = card.author, cover = {
        OnlineCover(library, card.key.sourceId, card.key.mangaId, fillWidth = true)
    })
}
