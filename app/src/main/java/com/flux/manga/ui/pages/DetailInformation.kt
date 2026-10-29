package com.flux.manga.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val LocalDetailFullInformation = compositionLocalOf { false }
internal val LocalDetailHeroTags = compositionLocalOf { false }
internal val LocalDetailInformationDismiss = compositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun DetailInformationDialog(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ReferenceDialog(onDismissRequest = close, title = { Text(title) }, text = {
        CompositionLocalProvider(LocalDetailInformationDismiss provides close) {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}

/** A compact metadata line never expands the cover; its full value stays directly accessible. */
@Composable
internal fun DetailInfoText(text: String, label: String = "作品信息", modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var overflow by remember(text) { mutableStateOf(false) }
    var expanded by remember(text) { mutableStateOf(false) }
    val full = LocalDetailFullInformation.current
    Text(text, modifier.clickable(enabled = overflow && !full, onClickLabel = "查看完整$label") { expanded = true },
        style = style, color = color, maxLines = if (full) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { overflow = it.hasVisualOverflow })
    if (expanded) DetailInformationDialog(label, { expanded = false }) { Text(text) }
}

/** Keep every author and their search action in the popup, rather than squeezing links into one line. */
@Composable
internal fun DetailAuthors(names: List<Pair<String, String>>, search: ((String) -> Unit)?, heading: String = "作者") {
    val authors = names.filter { it.first.isNotBlank() }.distinct()
    if (authors.isEmpty()) return
    var expanded by remember(authors) { mutableStateOf(false) }
    val dismiss = LocalDetailInformationDismiss.current
    if (LocalDetailFullInformation.current) {
        Text(authorNames(authors, search?.let { action -> { name: String -> dismiss?.invoke(); action(name) } }, "$heading："),
            style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("$heading：${authors.joinToString(" / ") { it.second }}", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClickLabel = "查看$heading") { expanded = true })
    }
    if (expanded) DetailInformationDialog(heading, { expanded = false }) {
        authors.forEach { author ->
            Text(authorNames(listOf(author), search?.let { action -> { name: String -> expanded = false; action(name) } }))
        }
    }
}
