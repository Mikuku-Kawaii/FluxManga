package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.flux.manga.data.MangaCard
import com.flux.manga.data.isCopySource
import com.flux.manga.data.isBaoziSource
import com.flux.manga.data.isPrivateComicSource
import com.flux.manga.data.isEhSource
import com.flux.manga.data.isExSource
import com.flux.manga.ui.navigation.DiscoverySection
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class HomeUpdateInfo(val date: String = "", val chapter: String = "")

private fun updateDate(raw: String?, suffix: String): String {
    val text = raw.orEmpty().trim()
    val match = Regex("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日|(?=T|\\s|$))").find(text)
    return try {
        val date = when {
            match != null -> LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            text.matches(Regex("[0-9]{10}|[0-9]{13}")) ->
                (if (text.length == 10) Instant.ofEpochSecond(text.toLong()) else Instant.ofEpochMilli(text.toLong()))
                    .atZone(ZoneId.systemDefault()).toLocalDate()
            else -> return "日期未提供"
        }
        date.format(DateTimeFormatter.ofPattern("MM月dd日")) + suffix
    } catch (_: java.time.DateTimeException) { "日期未提供" }
}

@Composable
internal fun HomeDiscoveryRail(model: MixedDiscoveryModel, cards: List<MangaCard>, section: DiscoverySection,
    open: (String, String) -> Unit) {
    if (cards.isEmpty()) return
    val scroll = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A partial next cover reveals the horizontal gesture; large fonts get wider cards.
        val width = (maxWidth / 2.65f * scale.coerceAtMost(1.5f)).coerceIn(108.dp, 200.dp).coerceAtMost(maxWidth)
        LazyRow(state = scroll, modifier = Modifier.fillMaxWidth().testTag("home-rail-${section.name}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(cards, key = { "${it.key.sourceId}:${it.key.mangaId}" }, contentType = { "home-book" }) { card ->
                Column(Modifier.width(width).clickable(role = Role.Button,
                    onClickLabel = "查看《${card.title}》详情", onClick = { open(card.key.sourceId, card.key.mangaId) }),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OnlineCover(model.library, card.key.sourceId, card.key.mangaId, fillWidth = true)
                    Text(card.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (section == DiscoverySection.UPDATES) {
                        val version = model.updateVersions[card.key.sourceId] ?: 0
                        LaunchedEffect(card.key, version, lifecycle) {
                            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.loadUpdate(card, version) }
                        }
                        val extra = model.updateInfo[card.key]
                        val source = card.key.sourceId
                        val dateSuffix = if (isCopySource(source) || isBaoziSource(source) || isPrivateComicSource(source)) "更新" else "资料更新"
                        Text(updateDate(card.updatedAt ?: extra?.date, dateSuffix), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val chapter = card.latestChapter?.takeIf(String::isNotBlank) ?: extra?.chapter?.takeIf(String::isNotBlank)
                        Text(chapter?.let { if (it.startsWith("更新")) it else "更新至 $it" }
                            ?: if (isEhSource(source) || isExSource(source)) "单篇画廊" else "章节未提供",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else if (card.author.isNotBlank()) {
                        Text(card.author, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
