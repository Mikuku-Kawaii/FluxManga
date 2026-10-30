package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

internal val LocalDetailTagRows = compositionLocalOf { 2 }

@Composable
internal fun KomgaDetailTags(sourceId: String, mangaId: String, tags: List<String>?, compact: Boolean = false, open: (String, String) -> Unit) {
    key(sourceId, mangaId) {
        WorkTags(tags?.map { it to it }, compact = compact, openInDialog = true) { id, _ -> open(sourceId, id) }
    }
}

/** Source identities and click actions stay attached to each visible tag. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkTags(tags: List<Pair<String, String>>?, canOpen: (String) -> Boolean = { true }, compact: Boolean = false,
    openInDialog: Boolean = false, maxRows: Int? = null, open: (String, String) -> Unit) {
    if (tags.isNullOrEmpty()) return
    var expanded by rememberSaveable(tags) { mutableStateOf(false) }
    val dialog = openInDialog || LocalDetailHeroTags.current
    val full = LocalDetailFullInformation.current
    val dense = (compact || LocalDetailHeroTags.current) && !full
    val dismissInformation = LocalDetailInformationDismiss.current
    val truncated = remember(tags) { mutableStateMapOf<String, Boolean>() }
    val rows = maxRows ?: LocalDetailTagRows.current
    @Composable fun Tag(name: String, id: String, dismiss: Boolean = false) {
        if (canOpen(id)) DetailTagButton(dense && !dismiss,
            onClick = { if (dismiss) expanded = false; dismissInformation?.invoke(); open(id, name) }) {
            Text(name, maxLines = if (dismiss || full) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!dismiss && !full) truncated[id] = it.hasVisualOverflow })
        } else Text(name, Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            style = if (dense && !dismiss) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            maxLines = if (dismiss || full) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!dismiss && !full) truncated[id] = it.hasVisualOverflow })
    }
    if (full) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tags.forEach { (name, id) -> Tag(name, id) }
        }
        return
    }
    Layout(content = {
        tags.forEach { (name, id) -> Tag(name, id) }
        DetailTagButton(dense, onClick = { expanded = !expanded }, modifier = Modifier.testTag("detail-tags-expand")) {
            Text(if (expanded && !dialog) "收起" else "展开", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }, modifier = Modifier.fillMaxWidth().testTag("detail-tags")) { measurables, constraints ->
        val children = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val chips = children.dropLast(1)
        val toggle = children.last()
        val gap = (if (dense) 4.dp else 8.dp).roundToPx()
        val rowGap = (if (dense) 4.dp else 2.dp).roundToPx()
        data class Positioned(val item: Placeable, val x: Int, val y: Int, val row: Int)
        fun arrange(items: List<Placeable>): List<Positioned> {
            var x = 0; var y = 0; var row = 0; var height = 0
            return items.map { item ->
                if (x > 0 && x + item.width > constraints.maxWidth) {
                    x = 0; y += height + rowGap; row++; height = 0
                }
                Positioned(item, x, y, row).also { x += item.width + gap; height = maxOf(height, item.height) }
            }
        }
        val natural = arrange(chips)
        val overflow = natural.any { it.row >= rows } || truncated.values.any { it }
        val placed = when {
            !overflow -> natural
            expanded && !dialog -> arrange(chips + toggle)
            else -> {
                var count = natural.count { it.row < rows }
                var result = arrange(chips.take(count) + toggle)
                // Reserve the toggle inside the final allowed row, never add an extra row.
                while (count > 0 && result.last().row >= rows) {
                    count--; result = arrange(chips.take(count) + toggle)
                }
                result
            }
        }
        val height = placed.maxOfOrNull { it.y + it.item.height } ?: 0
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            placed.forEach { it.item.placeRelative(it.x, it.y) }
        }
    }
    if (dialog && expanded) ReferenceDialog(onDismissRequest = { expanded = false }, title = { Text("作品标签") },
        text = {
            FlowRow(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { (name, id) -> Tag(name, id, dismiss = true) }
            }
        }, confirmButton = { TextButton({ expanded = false }) { Text("关闭") } })
}

/** Compact inline tags omit Material's extra layout reserve; the full popup keeps regular targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTagButton(compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    label: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compact) 0.dp else 48.dp) {
        OutlinedButton(onClick, modifier.heightIn(min = if (compact) 28.dp else 40.dp).widthIn(min = 1.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 6.dp)) {
            ProvideTextStyle(if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge) { label() }
        }
    }
}
