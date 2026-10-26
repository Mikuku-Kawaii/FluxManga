package com.flux.manga.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DiscoverySearchGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(Modifier.size(24.dp).clearAndSetSemantics { }) {
        val unit = size.width / 24
        drawCircle(color, 7 * unit, androidx.compose.ui.geometry.Offset(10 * unit, 10 * unit),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2 * unit))
        drawLine(color, androidx.compose.ui.geometry.Offset(15 * unit, 15 * unit),
            androidx.compose.ui.geometry.Offset(21 * unit, 21 * unit), 2 * unit, androidx.compose.ui.graphics.StrokeCap.Round)
    }
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
                Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                SettingsChevron()
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
    val base = MaterialTheme.colorScheme.primaryContainer
    Surface(shape = MaterialTheme.shapes.medium, color = base,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button,
            onClickLabel = "查看「$name」下的作品", onClick = open)) {
        Box(contentAlignment = Alignment.CenterStart) {
            Image(painterResource(topicArtwork(name).resource), contentDescription = null,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillHeight, alignment = Alignment.CenterEnd,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary.copy(alpha = .22f)))
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to base, .28f to base.copy(alpha = .95f), .74f to base.copy(alpha = .15f), 1f to base.copy(alpha = 0f))))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                SettingsChevron()
            }
        }
    }
}
