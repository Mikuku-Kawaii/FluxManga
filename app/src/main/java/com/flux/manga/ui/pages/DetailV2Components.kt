package com.flux.manga.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clip

@Composable
internal fun DetailHeading(back: () -> Unit, modifier: Modifier = Modifier, backModifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null) {
    SettingsHeading("漫画详情", "", back, modifier = modifier, backModifier = backModifier, actions = actions)
}

@Composable
internal fun DetailHero(title: String, cover: @Composable () -> Unit, titleTag: String? = null,
    tags: @Composable () -> Unit = {},
    metadata: @Composable () -> Unit = {}) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    var titleOverflow by remember(title) { mutableStateOf(false) }
    if (expanded) DetailInformationDialog("完整书名", { expanded = false }) {
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverWidth = (maxWidth * .34f).coerceAtMost(126.dp)
        val coverHeight = coverWidth * 1.414f
        @Composable fun Information(modifier: Modifier = Modifier) {
            // Keep metadata visible now that there is no aggregate information action.
            // The information column can grow without changing the cover's aspect ratio.
            Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleOverflow = it.hasVisualOverflow },
                    modifier = Modifier.then(if (titleTag != null) Modifier.testTag(titleTag) else Modifier)
                        .clickable(enabled = titleOverflow, onClickLabel = "查看完整书名") { expanded = true }.semantics { heading() })
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    metadata()
                }
                CompositionLocalProvider(LocalDetailTagRows provides 3, LocalDetailHeroTags provides true) {
                    tags()
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Layout(content = cover, modifier = Modifier.width(coverWidth).height(coverHeight).clip(RoundedCornerShape(8.dp))) { children, frame ->
                val imageWidth = maxOf(frame.maxWidth, kotlin.math.ceil(frame.maxHeight * .7f).toInt())
                val images = children.map { it.measure(Constraints.fixedWidth(imageWidth)) }
                layout(frame.maxWidth, frame.maxHeight) {
                    images.forEach { it.placeRelative((frame.maxWidth - it.width) / 2, (frame.maxHeight - it.height) / 2) }
                }
            }
            Information(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun DetailActions(favorite: Boolean, saving: Boolean, modifier: Modifier = Modifier, favoriteEnabled: Boolean = true, toggleFavorite: () -> Unit,
    download: (() -> Unit)?, read: () -> Unit, canRead: Boolean, hasProgress: Boolean,
    readModifier: Modifier = Modifier, favoriteModifier: Modifier = Modifier,
    description: String? = null, descriptionIdentity: String = "", readingPosition: String? = null,
    belowActions: (@Composable () -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxWidth().testTag("detail-actions")) {
        val stack = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
        val actionHeight = referenceActionHeight()
        @Composable fun Favorite(modifier: Modifier = Modifier) {
            TextButton(onClick = toggleFavorite, enabled = favoriteEnabled && !saving,
                modifier = modifier.then(favoriteModifier).heightIn(min = actionHeight).semantics { selected = favorite }, shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (favorite) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (favorite) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)) {
                ReferenceIcon(ReferenceSymbol.BOOKMARK, filled = favorite)
                Spacer(Modifier.width(6.dp))
                Text(if (saving) "保存中…" else if (favorite) "已收藏" else "收藏")
            }
        }
        @Composable fun Download(modifier: Modifier = Modifier) {
            if (download != null) TextButton(onClick = download, modifier = modifier.heightIn(min = actionHeight), shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface)) {
                ReferenceIcon(ReferenceSymbol.DOWNLOAD); Spacer(Modifier.width(6.dp)); Text("下载")
            }
        }
        @Composable fun Read(modifier: Modifier = Modifier) {
            ReferenceButton(onClick = read, enabled = canRead, modifier = modifier.then(readModifier).heightIn(min = actionHeight)) {
                ReferenceIcon(ReferenceSymbol.PLAY)
                Spacer(Modifier.width(6.dp))
                Text(if (!hasProgress) "开始阅读" else readingPosition?.takeIf(String::isNotBlank)
                    ?.let { "继续阅读（$it）" } ?: "继续阅读",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Read(Modifier.fillMaxWidth())
            if (stack) {
                Favorite(Modifier.fillMaxWidth())
                if (download != null) Download(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Favorite(Modifier.weight(1f))
                if (download != null) Download(Modifier.weight(1f))
            }
            belowActions?.invoke()
            DetailDescription(description, descriptionIdentity, compact = true)
        }
    }
}

@Composable
internal fun DetailDescription(description: String?, identity: String, title: String = "简介", compact: Boolean = false) {
    if (description.isNullOrBlank()) return
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    var overflow by remember(description) { mutableStateOf(false) }
    if (expanded) DetailInformationDialog(title, { expanded = false }) { Text(description) }
    if (compact) {
        Row(Modifier.fillMaxWidth().referenceContentResize(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("$title：$description", Modifier.weight(1f).clickable(enabled = overflow, onClickLabel = "查看完整$title") { expanded = true }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflow = it.hasVisualOverflow })
            if (overflow) TextButton(onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.testTag("detail-description-toggle").semantics {
                    contentDescription = "查看完整$title"
                }) { Text("全部", maxLines = 1) }
        }
        return
    }
    Column(Modifier.referenceContentResize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge)
            if (overflow) TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("detail-description-toggle")) {
                Text("查看完整$title")
                Spacer(Modifier.width(4.dp))
                ReferenceDisclosureChevron(false)
            }
        }
        Text(description, Modifier.clickable(enabled = overflow, onClickLabel = "查看完整$title") { expanded = true }, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = LocalTextStyle.current,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflow = it.hasVisualOverflow })
    }
}

/** Chapters expand in the detail page; remote paging remains owned by the source controller. */
@Composable
internal fun DetailCatalog(entries: List<DetailCatalogEntry>, lastId: String?, open: (String) -> Unit,
    title: String = "章节目录", busy: Boolean = false, hasMore: Boolean = false, more: (() -> Unit)? = null,
    appendError: String? = null, enabled: Boolean = true,
    totalCount: Long? = null, completed: Set<String> = emptySet(), readingLabels: Map<String, String> = emptyMap(),
    recordStatus: @Composable () -> Unit = {}, chapterActions: ((DetailCatalogEntry) -> Unit)? = null, showSingle: Boolean = false) {
    // Public works keep their sole chapter reachable; private works retain the compact rule.
    if ((entries.isEmpty() || (entries.size == 1 && !showSingle)) && !hasMore && !busy && appendError == null) return
    var reverse by rememberSaveable { mutableStateOf(false) }
    var visibleCount by rememberSaveable { mutableIntStateOf(12) }
    val sorted = remember(entries, reverse) { orderedCatalog(entries, reverse) }
    val count = totalCount ?: entries.size.toLong().takeIf { !hasMore && entries.isNotEmpty() }
    val countLabel = count?.let { "目录 $it 项" } ?: "总数待确认"
    @Composable fun Feedback() {
        recordStatus()
        if (appendError != null) RequestFeedback(appendError, busy = busy, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (busy) RequestLoading()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReferenceActionRow(text = { modifier ->
            DetailCatalogHeading(title, countLabel, modifier)
        }, actions = {
            if (entries.size > 1) TextButton(onClick = { reverse = !reverse }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (reverse) "倒序" else "正序") }
        })
        DetailChapterGrid(sorted.take(visibleCount), lastId, enabled, completed, readingLabels, chapterActions, open)
        val hasHidden = sorted.size > visibleCount
        if (hasHidden || (hasMore && more != null)) TextButton(onClick = feedbackAttempt({
            if (hasHidden) visibleCount += 24
            else {
                // Reserve the next visible batch before the asynchronous page arrives.
                visibleCount = sorted.size + 24
                more?.invoke()
            }
        }), enabled = hasHidden || !busy,
            modifier = Modifier.fillMaxWidth().testTag("detail-more-chapters")) {
            Text(if (!hasHidden && appendError != null) "重试后续章节" else if (!hasHidden && busy) "正在加载…" else "更多")
        }
        Feedback()
    }
}

@Composable
private fun DetailCatalogHeading(title: String, count: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.alignByBaseline().semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(count, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailChapterGrid(entries: List<DetailCatalogEntry>, lastId: String?, enabled: Boolean,
    completed: Set<String>, readingLabels: Map<String, String>, actions: ((DetailCatalogEntry) -> Unit)?, open: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = chapterGridColumns(maxWidth, LocalDensity.current.fontScale)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.chunked(columns).forEach { row -> key(row.first().id) {
                DetailChapterGridRow(row, columns, lastId, enabled, completed, readingLabels, actions, open)
            } }
        }
    }
}

private fun chapterGridColumns(width: androidx.compose.ui.unit.Dp, fontScale: Float) = when {
    width < 240.dp || fontScale >= 2f -> 1
    width < 340.dp || fontScale >= 1.3f -> 2
    else -> 3
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DetailChapterGridRow(entries: List<DetailCatalogEntry>, columns: Int, lastId: String?, enabled: Boolean,
    completed: Set<String>, readingLabels: Map<String, String>, actions: ((DetailCatalogEntry) -> Unit)?, open: (String) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val readInk = if (dark) Color(0xFF81C784) else Color(0xFF256A2A)
    val readSurface = if (dark) Color(0xFF193B25) else Color(0xFFE8F5E9)
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry -> key(entry.id) {
            val read = entry.id in completed
            val last = entry.id == lastId
            val label = if (read) "已读" else readingLabels[entry.id] ?: entry.description
            Surface(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).clip(shape)
                .combinedClickable(enabled = enabled, role = Role.Button,
                    onClick = { open(entry.id) }, onClickLabel = "阅读${entry.title}",
                    onLongClick = actions?.let { action -> { action(entry) } }, onLongClickLabel = "章节信息与阅读标记")
                .semantics { selected = last; stateDescription = if (read) "已读" else if (last) "上次阅读" else "未标记已读" },
                shape = shape, color = if (read) readSurface else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (read) readInk else MaterialTheme.colorScheme.onSurface,
                border = if (last) BorderStroke(1.dp, if (read) readInk else MaterialTheme.colorScheme.primary) else null) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (read) readInk else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        repeat(columns - entries.size) { Spacer(Modifier.weight(1f)) }
    }
}
