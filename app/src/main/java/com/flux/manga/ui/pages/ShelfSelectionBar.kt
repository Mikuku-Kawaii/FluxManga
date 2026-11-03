package com.flux.manga.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Actions use the exact same compact text button as the shelf reading filters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShelfSelectionBar(
    canEdit: Boolean, hasBooks: Boolean, hasSelection: Boolean,
    selectAll: () -> Unit, clear: () -> Unit, unfavorite: () -> Unit, cancel: () -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        ShelfTextButton("全选", selectAll, enabled = canEdit && hasBooks)
        ShelfTextButton("清空", clear, enabled = canEdit && hasSelection)
        ShelfTextButton("取消收藏", unfavorite, enabled = canEdit && hasSelection)
        ShelfTextButton("取消", cancel, enabled = true)
    }
}

@Composable
internal fun ShelfTextButton(label: String, action: () -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, selected: Boolean? = null, drawSelection: Boolean = true) {
    val background = referenceStateColor(if (selected == true && drawSelection) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    val ink = referenceStateColor(if (selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(action, enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = background,
            contentColor = ink),
        modifier = modifier.then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)) {
        Text(label)
    }
}

/** One continuously moving selection surface; result cards never flash or change opacity. */
@Composable
internal fun ShelfFilterIndicator(target: Rect, modifier: Modifier = Modifier) {
    val enabled = LocalReferenceMotionEnabled.current
    val spec = if (enabled) tween<Float>(220, easing = LinearOutSlowInEasing) else snap<Float>()
    val left by animateFloatAsState(target.left, spec, label = "filter-left")
    val top by animateFloatAsState(target.top, spec, label = "filter-top")
    val width by animateFloatAsState(target.width, spec, label = "filter-width")
    val height by animateFloatAsState(target.height, spec, label = "filter-height")
    val color = MaterialTheme.colorScheme.secondaryContainer
    Canvas(modifier.clearAndSetSemantics { }) {
        val inset = 4.dp.toPx()
        drawRoundRect(color, topLeft = Offset(left, top + inset),
            size = Size(width, (height - inset * 2).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(14.dp.toPx()))
    }
}

/** Results slide in the filter's navigation direction without fading or changing layout. */
@Composable
internal fun shelfResultSlide(filter: ShelfFilter): Modifier {
    val enabled = LocalReferenceMotionEnabled.current
    val offset = remember { Animatable(0f) }
    var previous by remember { mutableStateOf(filter) }
    LaunchedEffect(filter, enabled) {
        val changed = previous != filter
        val direction = if (filter.ordinal > previous.ordinal) 1f else -1f
        previous = filter
        if (enabled && changed) {
            // Rapid selections continue from the current drawn position rather than jumping again.
            if (offset.value == 0f) offset.snapTo(direction)
            offset.animateTo(0f, tween(220, easing = LinearOutSlowInEasing))
        } else offset.snapTo(0f)
    }
    return Modifier.graphicsLayer {
        translationX = if (enabled) offset.value * 64.dp.toPx() else 0f
    }
}

/** The tile owns the touch target and checkbox semantics; this badge is decorative. */
@Composable
internal fun ShelfSelectionBadge(checked: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.size(26.dp).clearAndSetSemantics { }, shape = CircleShape,
        color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        if (checked) {
            val color = LocalContentColor.current
            Canvas(Modifier.padding(6.dp)) {
                drawLine(color, Offset(0f, size.height * .5f), Offset(size.width * .36f, size.height * .85f),
                    2.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(size.width * .36f, size.height * .85f), Offset(size.width, size.height * .15f),
                    2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
