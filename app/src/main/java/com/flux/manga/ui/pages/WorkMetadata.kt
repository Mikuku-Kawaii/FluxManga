package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flux.manga.core.SourceAuthor
import com.flux.manga.core.SourceUpdateKind
import com.flux.manga.core.SourceWorkMetadata

internal fun authorLabel(author: SourceAuthor): String = author.role?.takeIf { it.isNotBlank() }
    ?.let { "${author.name}（$it）" } ?: author.name

/** Link only the name: roles, separators and uploaders are not search terms. */
@Composable
internal fun authorNames(names: List<Pair<String, String>>, search: ((String) -> Unit)?, prefix: String = ""): AnnotatedString {
    val styles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary))
    return buildAnnotatedString {
        append(prefix)
        names.filter { it.first.isNotBlank() }.distinct().forEachIndexed { index, (name, label) ->
            if (index > 0) append(" / ")
            if (search == null) append(label)
            else withLink(LinkAnnotation.Clickable(name, styles) { search(name) }) { append(label) }
        }
    }
}

internal fun metadataUpdateLabel(metadata: SourceWorkMetadata): String? = metadata.updatedAt?.takeIf { it.isNotBlank() }?.let {
    when (metadata.updateKind) {
        SourceUpdateKind.METADATA -> "资料更新时间：$it"
        SourceUpdateKind.CONTENT -> "更新时间：$it"
        null -> null // A timestamp without a defined meaning must not become a chapter update.
    }
}

/** Uses the existing detail column and palette; source text is never replaced with sample metadata. */
@Composable
internal fun WorkMetadata(metadata: SourceWorkMetadata, identity: String, searchAuthor: ((String) -> Unit)? = null) {
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    var overflow by remember(metadata.authors) { mutableStateOf(false) }
    val heading = if (metadata.authors.any { !it.role.isNullOrBlank() }) "创作者" else "作者"
    if (metadata.authors.isNotEmpty()) {
        Text(authorNames(metadata.authors.take(2).map { it.name to authorLabel(it) }, searchAuthor, "$heading："),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflow = it.hasVisualOverflow })
        if (metadata.authors.size > 2 || overflow) TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("查看${heading}列表（${metadata.authors.size}）")
        }
    }
    metadata.status?.takeIf { it.isNotBlank() }?.let { Text("状态：$it", color = MaterialTheme.colorScheme.primary) }
    metadataUpdateLabel(metadata)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    if (metadata.partial) RequestFeedback("部分作品信息无法识别，可刷新详情重试。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (expanded) ReferenceDialog(onDismissRequest = { expanded = false }, title = { Text(heading) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(metadata.authors) { _, author -> Text(authorNames(listOf(author.name to authorLabel(author)),
                searchAuthor?.let { search -> { name: String -> expanded = false; search(name) } }), style = MaterialTheme.typography.bodyLarge) }
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}
