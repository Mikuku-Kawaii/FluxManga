package com.flux.manga.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Only material pixels enter these layers. Foreground, hit areas and selection are siblings. */
@Composable
internal fun LiquidDockMaterial(
    modifier: Modifier, palette: GlassPalette, effects: DockEffects,
    source: GraphicsLayer?, filtered: GraphicsLayer?, filteredOrigin: Offset, host: LayoutCoordinates?,
    circleSize: Dp, gap: Dp, frame: () -> DockMaterialFrame,
    observeContentChanges: () -> Unit, onFailure: (String) -> Unit,
    theme: LiquidThemePalette,
) {
    // No sensitive pixels are retained here: all graphics layers belong to this composition.
    var position by remember { mutableStateOf(Offset.Zero) }
    var located by remember { mutableStateOf(false) }
    val aligned = modifier.onGloballyPositioned {
        located = host?.isAttached == true
        if (located) position = host!!.localPositionOf(it, Offset.Zero)
    }
    val density = LocalDensity.current
    val margin = with(density) { GlassTokens.MaterialPaddingDp.dp.toPx() }
    val origin = Offset(-margin, -margin)
    val sampling = source != null && located && effects.backdropEnabled
    val background: DrawScope.() -> Unit = {
        if (sampling) {
            observeContentChanges()
            drawRect(palette.background)
            if (filtered != null) translate(filteredOrigin.x - position.x - origin.x,
                filteredOrigin.y - position.y - origin.y) { drawLayer(filtered) }
            else translate(-position.x - origin.x, -position.y - origin.y) { drawLayer(source!!) }
        }
    }
    if (Build.VERSION.SDK_INT >= 33) {
        SdfDockMaterial(aligned, palette, effects, origin, frame, sampling, background, onFailure, theme)
    } else {
        ThresholdDockMaterial(aligned, palette, effects, origin, frame, sampling, background, theme)
    }
}

@RequiresApi(33)
@Composable
private fun SdfDockMaterial(
    modifier: Modifier, palette: GlassPalette, effects: DockEffects, origin: Offset,
    frame: () -> DockMaterialFrame, sampling: Boolean, background: DrawScope.() -> Unit,
    onFailure: (String) -> Unit,
    theme: LiquidThemePalette,
) {
    // A malformed / unsupported driver shader cannot remove navigation or change preferences.
    val shader = remember { runCatching { RuntimeShader(LiquidMaterialShader) }.getOrNull() }
    if (shader == null) {
        LaunchedEffect(Unit) { onFailure("增强材质暂不可用，已使用基础外观。重开应用后可再次尝试。") }
        return
    }
    val layer = rememberGraphicsLayer()
    Box(modifier.drawWithCache {
        val extent = IntSize(ceil(size.width - origin.x * 2).toInt(), ceil(size.height - origin.y - origin.x).toInt())
        val radius = GlassTokens.FusionRadiusDp.dp.toPx()
        val stroke = GlassTokens.ContourWidthDp.dp.toPx()
        val edge = GlassTokens.RefractionEdgeDp.dp.toPx()
        val amount = GlassTokens.RefractionAmountDp.dp.toPx()
        var lastGeometry: DockMaterialFrame? = null
        var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null
        onDrawBehind {
            val geometry = frame()
            // RenderEffect snapshots uniforms. Rewrap only when shape/material/size changes;
            // scrolling the shared source does not rebuild the effect or shader program.
            if (geometry != lastGeometry) {
            fun bounds(name: String, b: MaterialBounds) = shader.setFloatUniform(name,
                b.left - origin.x, b.top - origin.y, b.right - origin.x, b.bottom - origin.y)
            bounds("circle", geometry.circle); bounds("pod", geometry.pod); bounds("bud", geometry.bud)
            shader.setFloatUniform("cornerRadii", geometry.circle.radius, geometry.pod.radius, geometry.bud.radius)
            shader.setFloatUniform("resolution", extent.width.toFloat(), extent.height.toFloat())
            shader.setFloatUniform("mergeRadius", if (effects.fusionEnabled) radius * geometry.fusionScale else 0f)
            shader.setFloatUniform("strokeWidth", stroke)
            shader.setFloatUniform("edgeWidth", edge)
            shader.setFloatUniform("refractionAmount", if (sampling && effects.refractionEnabled(33)) amount else 0f)
            shader.setFloatUniform("lensMagnification", 0f)
            shader.setFloatUniform("innerWidth", 2.dp.toPx())
            shader.setFloatUniform("highlight", if (effects.highlightEnabled(33)) 1f else 0f)
            shader.setFloatUniform("sampled", if (sampling) 1f else 0f)
            shader.setColorUniform("fillColor", palette.fill.toArgb())
            shader.setColorUniform("tintColor", palette.tint.copy(alpha = if (effects.tint) palette.tintAlpha else 0f).toArgb())
            shader.setColorUniform("outlineColor", if (effects.highlightEnabled(33)) android.graphics.Color.TRANSPARENT else palette.outline.toArgb())
            shader.setColorUniform("rimBright", Color.White.toArgb())
            shader.setColorUniform("rimDim", Color.Black.copy(alpha = .18f).toArgb())
            cachedEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
            lastGeometry = geometry
            }
            layer.renderEffect = cachedEffect
            layer.record(size = extent) {
                // A nonempty input is required even when only the procedural silhouette is drawn.
                drawRect(palette.background)
                background()
            }
            translate(origin.x, origin.y) { drawLayer(layer) }
        }
    })
}

/** API 31–32: union three silhouettes, threshold once, shade only the resulting closed contour. */
@Composable
private fun ThresholdDockMaterial(
    modifier: Modifier, palette: GlassPalette, effects: DockEffects, origin: Offset,
    frame: () -> DockMaterialFrame, sampling: Boolean, background: DrawScope.() -> Unit,
    theme: LiquidThemePalette,
) {
    val mask = rememberGraphicsLayer()
    val contour = rememberGraphicsLayer()
    val material = rememberGraphicsLayer()
    val rimMaterial = rememberGraphicsLayer()
    val innerContour = rememberGraphicsLayer()
    val thicknessMaterial = rememberGraphicsLayer()
    Box(modifier.drawWithCache {
        val extent = IntSize(ceil(size.width - origin.x * 2).toInt(), ceil(size.height - origin.y - origin.x).toInt())
        val radius = GlassTokens.ThresholdBlurDp.dp.toPx()
        val stroke = GlassTokens.ContourWidthDp.dp.toPx()
        val rim = if (effects.highlight) opticalRimBrush(androidx.compose.ui.geometry.Size(extent.width.toFloat(), extent.height.toFloat()))
            else Brush.linearGradient(listOf(palette.outline, palette.outline))
        var lastRadius = -1f
        var threshold: androidx.compose.ui.graphics.RenderEffect? = null
        var ring: androidx.compose.ui.graphics.RenderEffect? = null
        var innerRing: androidx.compose.ui.graphics.RenderEffect? = null
        val thickness = Brush.verticalGradient(0f to Color.White.copy(alpha = .40f),
            .15f to Color.Transparent, .85f to Color.Transparent, 1f to Color.Black.copy(alpha = .18f),
            startY = -origin.y, endY = size.height - origin.y)
        onDrawBehind {
            val geometry = frame()
            val fusionRadius = if (effects.fusionEnabled) radius * geometry.fusionScale else 0f
            val blurRadius = if (fusionRadius > 0f) fusionRadius.coerceAtLeast(.6f) else 0f
            if (blurRadius != lastRadius) {
                if (blurRadius == 0f) {
                    // At rest, render exact independent stadiums: no residual blur/threshold pull.
                    threshold = null
                    ring = null
                    innerRing = null
                } else {
                    val blur = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.DECAL)
                    threshold = alphaThreshold(blur, .5f).asComposeRenderEffect()
                    // Keep the rim near one dp even while the neck's blur radius changes.
                    val band = (stroke / (blurRadius * 2.5f)).coerceIn(.02f, .45f) / 2f
                    ring = RenderEffect.createBlendModeEffect(alphaThreshold(blur, .5f - band),
                        alphaThreshold(blur, .5f + band), android.graphics.BlendMode.DST_OUT).asComposeRenderEffect()
                    val innerBand = (2.dp.toPx() / (blurRadius * 2.5f)).coerceIn(.02f, .45f)
                    innerRing = RenderEffect.createBlendModeEffect(alphaThreshold(blur, .5f),
                        alphaThreshold(blur, .5f + innerBand), android.graphics.BlendMode.DST_OUT).asComposeRenderEffect()
                }
                lastRadius = blurRadius
            }
            mask.renderEffect = threshold
            mask.blendMode = BlendMode.DstIn
            mask.record(size = extent) { silhouettes(geometry, origin) }
            contour.renderEffect = ring
            contour.blendMode = BlendMode.DstIn
            contour.record(size = extent) {
                silhouettes(geometry, origin, innerStroke = if (blurRadius == 0f) stroke else null)
            }
            innerContour.renderEffect = innerRing
            innerContour.blendMode = BlendMode.DstIn
            innerContour.record(size = extent) {
                silhouettes(geometry, origin, innerStroke = if (blurRadius == 0f) 2.dp.toPx() else null)
            }
            material.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
            material.record(size = extent) {
                background()
                drawRect(palette.fill)
                if (effects.tint) drawRect(palette.tint.copy(alpha = palette.tintAlpha))
                drawLayer(mask)
            }
            rimMaterial.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
            rimMaterial.record(size = extent) { drawRect(rim); drawLayer(contour) }
            thicknessMaterial.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
            thicknessMaterial.record(size = extent) { drawRect(thickness); drawLayer(innerContour) }
            translate(origin.x, origin.y) { drawLayer(material); drawLayer(thicknessMaterial); drawLayer(rimMaterial) }
        }
    })
}

private fun alphaThreshold(input: RenderEffect, threshold: Float): RenderEffect {
    // Android ColorMatrix translations use 0..255 alpha units. Keep RGB white, not overbright.
    val sharpness = 24f
    val matrix = ColorMatrix(floatArrayOf(
        0f,0f,0f,0f,255f, 0f,0f,0f,0f,255f, 0f,0f,0f,0f,255f,
        0f,0f,0f,sharpness,255f * (.5f - sharpness * threshold),
    ))
    return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), input)
}

private fun DrawScope.silhouettes(frame: DockMaterialFrame, origin: Offset, innerStroke: Float? = null) {
    fun draw(b: MaterialBounds) {
        val shape = Path().apply { addRoundRect(RoundRect(b.left - origin.x, b.top - origin.y,
            b.right - origin.x, b.bottom - origin.y, CornerRadius(b.radius))) }
        if (innerStroke == null) drawPath(shape, Color.White)
        else clipPath(shape) { drawPath(shape, Color.White, style = Stroke(innerStroke * 2f)) }
    }
    if (frame.circle.radius > 0f) draw(frame.circle)
    draw(frame.pod)
    if (frame.bud.radius > 0f) draw(frame.bud)
}
