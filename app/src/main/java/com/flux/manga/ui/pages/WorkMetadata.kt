package com.flux.manga.ui.pages

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.semantics.*
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
    val heading = if (metadata.authors.any { !it.role.isNullOrBlank() }) "创作者" else "作者"
    key(identity) { DetailAuthors(metadata.authors.map { it.name to authorLabel(it) }, searchAuthor, heading) }
    metadata.status?.takeIf { it.isNotBlank() }?.let { DetailInfoText("状态：$it", color = MaterialTheme.colorScheme.primary) }
    metadataUpdateLabel(metadata)?.let { DetailInfoText(it, style = MaterialTheme.typography.bodySmall) }
    if (metadata.partial) DetailInfoText("部分作品信息无法识别，可刷新详情重试。", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
