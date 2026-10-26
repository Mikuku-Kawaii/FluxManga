package com.flux.manga.ui.glass

import android.graphics.RenderEffect
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** Optical surfaces only. Placement, spring strain and pressure still belong to LiquidNavMotion. */
@Composable
fun LiquidNavPillIndicator(modifier: Modifier = Modifier, tokens: LiquidGlassTokens? = null,
    lowEffects: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val activeColor = tokens?.contentPrimaryActive ?: WorldColors.Blue
    val selectedSurface = tokens?.activePillSurface ?: WorldColors.LightSelected.copy(alpha = .32f)
    val rimColor = tokens?.glassSpecularRimBright ?: Color.White.copy(alpha = .70f)
    val glowLayer = if (lowEffects) null else rememberGraphicsLayer()
    val density = LocalDensity.current
    val blurRadius = with(density) { 10.dp.toPx() }
    val glowEffect = remember(blurRadius, lowEffects) {
        if (lowEffects) null else RenderEffect.createBlurEffect(
            blurRadius, blurRadius, Shader.TileMode.DECAL).asComposeRenderEffect()
    }
    // The measured slot is the visible capsule: no independent padding or overflow compensation.
    Box(modifier.testTag("dock-selection").drawWithCache {
        val radius = size.height / 2f
        val capsule = RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))
        val shape = Path().apply { addRoundRect(capsule) }
        val glowPad = blurRadius * 2f
        val glowSize = IntSize(ceil(size.width + glowPad * 2f).toInt(), ceil(size.height + glowPad * 2f).toInt())
        val tint = Brush.linearGradient(
            0f to rimColor.copy(alpha = rimColor.alpha * .40f).compositeOver(selectedSurface),
            .45f to selectedSurface,
            1f to activeColor.copy(alpha = .08f).compositeOver(selectedSurface),
            start = Offset.Zero, end = Offset(size.width, size.height))
        val meniscusInset = 1.dp.toPx().coerceAtMost(size.minDimension / 2f)
        val meniscus = Path().apply { addRoundRect(RoundRect(
            meniscusInset, meniscusInset, size.width - meniscusInset, size.height - meniscusInset,
            CornerRadius((radius - meniscusInset).coerceAtLeast(0f)))) }
        val sheen = Brush.verticalGradient(listOf(rimColor.copy(alpha = rimColor.alpha * .65f), Color.White.copy(alpha = 0f)),
            startY = meniscusInset, endY = size.height * .45f)
        val caustic = Brush.verticalGradient(listOf(activeColor.copy(alpha = 0f), activeColor.copy(alpha = .18f)),
            startY = size.height * .80f, endY = size.height)
        val rimStroke = 1.2.dp.toPx()
        val specular = Brush.linearGradient(
            0f to rimColor,
            .30f to rimColor,
            .55f to activeColor.copy(alpha = .24f),
            1f to rimColor.copy(alpha = rimColor.alpha * .65f),
            start = Offset.Zero, end = Offset(size.width, size.height))
        onDrawWithContent {
            if (size.minDimension > 0f && glowLayer != null) {
                glowLayer.renderEffect = glowEffect
                val capsuleSize = size
                glowLayer.record(size = glowSize) {
                    drawRoundRect(activeColor.copy(alpha = .10f), topLeft = Offset(glowPad, glowPad),
                        size = capsuleSize, cornerRadius = CornerRadius(radius))
                }
                translate(-glowPad, -glowPad + 2.dp.toPx()) { drawLayer(glowLayer) }
            }
            drawContent() // Only the real backdrop / refraction comes from indicatorSlot.
            if (size.minDimension > 0f) clipPath(shape) {
                if (lowEffects) drawRect(selectedSurface.copy(alpha = 1f)) else drawRect(tint)
                if (!lowEffects) {
                    clipPath(meniscus) { drawRect(sheen) }
                    // A narrow inner arc, rather than another full concentric capsule.
                    clipRect(top = size.height * .80f) {
                        drawPath(shape, caustic, style = Stroke(2.dp.toPx()))
                    }
                }
                // Clip the outer half of the stroke: same RoundRect for the mask and rim.
                if (lowEffects) drawPath(shape, activeColor.copy(alpha = .34f), style = Stroke(2.dp.toPx()))
                else drawPath(shape, specular, style = Stroke(rimStroke * 2f))
            }
        }
    }, content = content)
}

internal fun opticalRimBrush(size: Size) = Brush.linearGradient(
    0f to Color.White.copy(alpha = .75f), .30f to Color.White.copy(alpha = .75f),
    .50f to Color.White.copy(alpha = .15f), .80f to Color.White.copy(alpha = .45f),
    1f to Color.White.copy(alpha = .45f), start = Offset.Zero, end = Offset(size.width, size.height))

/** Theme-colored light under icon + text; never a black contour around the selected lens. */
@Composable
internal fun Modifier.lensContentShadow(enabled: Boolean): Modifier {
    if (!enabled) return this
    val shadow = rememberGraphicsLayer()
    val density = LocalDensity.current
    val radius = with(density) { 1.dp.toPx() }
    val glowColor = LocalContentColor.current.copy(alpha = .16f).toArgb()
    val effect = remember(radius, glowColor) {
        RenderEffect.createColorFilterEffect(PorterDuffColorFilter(glowColor, PorterDuff.Mode.SRC_IN),
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)).asComposeRenderEffect()
    }
    return drawWithCache {
        onDrawWithContent {
            shadow.renderEffect = effect
            shadow.record { this@onDrawWithContent.drawContent() }
            translate(0f, 1.dp.toPx()) { drawLayer(shadow) }
            drawContent()
        }
    }
}
