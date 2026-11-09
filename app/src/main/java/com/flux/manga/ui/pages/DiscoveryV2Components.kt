package com.flux.manga.ui.pages

import com.flux.manga.R
import com.flux.manga.ui.LucideIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DiscoverySearchGlyph() {
    LucideIcon(R.drawable.lucide_search, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun DiscoveryHeading(tag: String, settings: () -> Unit, title: String = "发现") {
    ReferencePageHeader(title) {
        ReferenceMoreButton("设置", tag, settings)
    }
}

@Composable
internal fun DiscoverySectionHeading(title: String, tag: String, actionLabel: String = "全部",
    actions: (@Composable () -> Unit)? = null, all: () -> Unit) {
    @Composable fun Actions() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions?.invoke()
            TextButton(onClick = all, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(actionLabel, color = ReadingSurfaceStyle.muted, style = MaterialTheme.typography.labelLarge)
                    SettingsChevron()
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(tag)) {
        if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f) {
            Column {
                Text(title, Modifier.clickable(role = Role.Button, onClick = all).heightIn(min = 48.dp)
                    .semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Box(Modifier.align(Alignment.End)) { Actions() }
            }
        } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.weight(1f).clickable(role = Role.Button, onClick = all).semantics { heading() },
                style = MaterialTheme.typography.titleLarge)
            Actions()
        }
    }
}

/** Bounded home previews; the page owns vertical scroll, no nested vertical lazy layout. */
@Composable
internal fun <T> DiscoveryPreviewGrid(items: List<T>, identity: (T) -> String, maxColumns: Int = 3, content: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = when {
            fontScale >= 2f -> 1
            fontScale >= 1.5f -> (maxColumns - 1).coerceAtLeast(1)
            maxWidth < 300.dp -> (maxColumns - 1).coerceAtLeast(1)
            else -> maxColumns
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry -> key(identity(entry)) { Column(Modifier.weight(1f)) { content(entry) } } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun TopicCard(name: String, open: () -> Unit) {
    Surface(shape = ReadingSurfaceStyle.cardShape, color = ReadingSurfaceStyle.card,
        border = ReadingSurfaceStyle.border,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).readingCardShadow().clickable(role = Role.Button,
            onClickLabel = "查看「$name」下的作品", onClick = open)) {
        Box(contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                SettingsChevron()
            }
        }
    }
}
