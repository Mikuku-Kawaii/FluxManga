package com.flux.manga.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Selection color and contour over a single shared glass material. */
@Composable
fun LiquidNavPillIndicator(modifier: Modifier = Modifier, tokens: LiquidGlassTokens? = null,
    lowEffects: Boolean = false, frostedLens: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    if (!frostedLens) {
        ClassicNavPillIndicator(modifier, tokens, lowEffects, content)
        return
    }
    val activeColor = tokens?.contentPrimaryActive ?: WorldColors.Blue
    Box(modifier.testTag("dock-selection").drawWithCache {
        val radius = size.height / 2f
        val shape = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
        }
        onDrawWithContent {
            drawContent()
            if (size.minDimension > 0f) clipPath(shape) {
                drawRect(activeColor.copy(alpha = if (lowEffects) .12f else .08f))
                drawPath(shape, activeColor.copy(alpha = if (lowEffects) .30f else .20f),
                    style = Stroke(1.5.dp.toPx()))
            }
        }
    }, content = content)
}

/** Quiet outer rim shared by the clear dock and its detached world switch. */
internal fun opticalRimBrush(size: Size) = Brush.linearGradient(
    0f to Color.White.copy(alpha = .28f), .30f to Color.White.copy(alpha = .20f),
    .50f to Color.White.copy(alpha = .06f), .80f to Color.White.copy(alpha = .14f),
    1f to Color.White.copy(alpha = .18f), start = Offset.Zero, end = Offset(size.width, size.height))
