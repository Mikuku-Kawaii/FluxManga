package com.flux.manga.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Content-page finishes only. Dock palettes, typography, sampling and motion stay independent. */
internal object ReadingSurfaceStyle {
    const val bookCoverRatio = 3f / 4f
    const val detailArtworkRatio = .7f
    val stackedBookTitleGap = 2.dp
    val sideBookTitleGap = 6.dp
    val cardShape = RoundedCornerShape(16.dp)
    val coverShape = RoundedCornerShape(8.dp)
    val capsuleShape = RoundedCornerShape(50)
    private val lightTags = listOf(Color(0xFF8A5269), Color(0xFF426C91), Color(0xFF3F7564))
    private val darkTags = listOf(Color(0xFFD6A2B8), Color(0xFFA4C6E6), Color(0xFFA0CBB8))

    val dark: Boolean @Composable get() = MaterialTheme.colorScheme.background.luminance() < .5f
    // Outer world: visible cool paper gradient. Inner world: a slight warm lift over #181818.
    val pageTop: Color @Composable get() = if (dark) Color(0xFF1C1B1A) else Color(0xFFFAFBFD)
    val pageBottom: Color @Composable get() = if (dark) Color(0xFF181818) else Color(0xFFE5EDF7)
    val card: Color @Composable get() = if (dark) Color(0xFF2C2F35) else Color.White
    val muted: Color @Composable get() = if (dark) Color(0xFFA5ADB9) else Color(0xFF687180)
    val action: Color @Composable get() = if (dark) Color(0xFF33363D) else Color(0xFFF2F3F5)
    val outline: Color @Composable get() = if (dark) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .04f)
    val searchOutline: Color @Composable get() = if (dark) outline else Color.Black.copy(alpha = .05f)
    val coverOutline: Color @Composable get() = if (dark) outline else Color.Black.copy(alpha = .06f)
    val border: BorderStroke @Composable get() = BorderStroke(1.dp, outline)

    @Composable fun tagInk(tag: String): Color {
        val tones = if (dark) darkTags else lightTags
        return tones[(tag.hashCode() and Int.MAX_VALUE) % tones.size]
    }
}

/** A static brush: no sampling, animation, bitmap allocation or change to page measurements. */
@Composable
internal fun Modifier.readingPageBackground(): Modifier {
    val top = ReadingSurfaceStyle.pageTop
    val bottom = ReadingSurfaceStyle.pageBottom
    val brush = remember(top, bottom) { Brush.verticalGradient(listOf(top, bottom)) }
    return background(brush)
}

@Composable
internal fun Modifier.readingCardShadow(shape: Shape = ReadingSurfaceStyle.cardShape): Modifier {
    val shadowColor = if (ReadingSurfaceStyle.dark) Color.Black.copy(alpha = .30f) else Color(0xFF182432).copy(alpha = .14f)
    return shadow(6.dp, shape, clip = false,
        ambientColor = shadowColor, spotColor = shadowColor)
}

/** Opaque sticky content follows the page gradient instead of revealing scrolling book covers. */
@Composable
internal fun Modifier.readingPageBackdrop(): Modifier {
    val top = ReadingSurfaceStyle.pageTop
    val bottom = ReadingSurfaceStyle.pageBottom
    var topInRoot by remember { mutableFloatStateOf(0f) }
    var pageHeight by remember { mutableFloatStateOf(1f) }
    return onGloballyPositioned { coordinates ->
        topInRoot = coordinates.positionInRoot().y
        pageHeight = coordinates.findRootCoordinates().size.height.toFloat().coerceAtLeast(1f)
    }.drawBehind {
        drawRect(Brush.verticalGradient(listOf(top, bottom),
            startY = -topInRoot, endY = pageHeight - topInRoot))
    }
}

/** Replaces the old one-line slash summary; the detail page still owns all tag actions. */
@Composable
internal fun ReadingTagBadges(tags: List<String>, width: Dp) {
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val visible = remember(tags, width, style, measurer, density) {
        var remaining = with(density) { width.roundToPx() }
        val padding = with(density) { 14.dp.roundToPx() }
        val gap = with(density) { 4.dp.roundToPx() }
        buildList {
            for (tag in tags.take(3)) {
                val pixels = measurer.measure(tag, style = style, maxLines = 1).size.width + padding
                if (isNotEmpty() && pixels + gap > remaining) break
                remaining -= pixels + if (isEmpty()) 0 else gap
                add(tag)
            }
        }
    }
    Row(Modifier.width(width).clearAndSetSemantics { contentDescription = tags.joinToString("、") },
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.forEach { tag ->
            val ink = ReadingSurfaceStyle.tagInk(tag)
            Text(tag, Modifier.background(ink.copy(alpha = .12f), ReadingSurfaceStyle.capsuleShape)
                .padding(horizontal = 7.dp, vertical = 2.dp), color = ink, style = style,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
