package com.flux.manga.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Slot-local feedback, read in draw so presses do not remeasure the navigation items. */
internal val LocalLiquidNavPressure = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Material only: replays the existing host's background, never records its children.
 * The host owns the single API 31+ blur/saturation pass and session-keyed layer lifetime.
 * API 33 refraction uses the existing licensed SDF shader; no bitmap readback or new sampler.
 */
@Composable
fun Modifier.liquidGlass(
    tokens: LiquidGlassTokens,
    active: Boolean = false,
    lowEffects: Boolean = false,
    pressure: () -> Float = LocalLiquidNavPressure.current,
    source: GraphicsLayer? = null,
    filtered: GraphicsLayer? = null,
    filteredOrigin: Offset = Offset.Zero,
    host: LayoutCoordinates? = null,
    effects: DockEffects = DockEffects(),
    observeContentChanges: () -> Unit = {},
    // The existing unified outer dock may already paint the pod's sampled background.
    paintSurface: Boolean = true,
    onEnhancementFailure: (String) -> Unit = {},
): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var samplePosition by remember { mutableStateOf(Offset.Zero) }
    val layer = rememberGraphicsLayer()
    val refraction = paintSurface && !lowEffects && effects.refractionEnabled(Build.VERSION.SDK_INT)
    val shader = if (Build.VERSION.SDK_INT >= 33 && refraction) rememberNavRefraction(onEnhancementFailure) else null
    return onGloballyPositioned {
        coordinates = it
        if (host?.isAttached == true) samplePosition = host.localPositionOf(it, Offset.Zero)
    }.drawWithCache {
        val stroke = LiquidNavLayoutTokens.RimWidth.toPx()
        val radius = size.height / 2f
        val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
        val pad = GlassTokens.MaterialPaddingDp.dp.toPx()
        val extent = IntSize(ceil(size.width + pad * 2).toInt(), ceil(size.height + pad * 2).toInt())
        val base = if (active) tokens.activePillSurface else tokens.glassSurfaceBase
        val clear = base.copy(alpha = 0f)
        val tint = Brush.linearGradient(listOf(base, base.copy(alpha = base.alpha * .72f), base),
            Offset.Zero, Offset(size.width, size.height))
        val bright = tokens.glassSpecularRimBright
        val edge = opticalRimBrush(size)
        val inner = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .18f)),
            startY = size.height - 2.dp.toPx(), endY = size.height)
        val topLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = .40f), Color.Transparent),
            startY = 0f, endY = 1.dp.toPx())
        val lensEffect = if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            navRefractionEffect(shader, extent, pad, size, radius,
                GlassTokens.RefractionEdgeDp.dp.toPx(), GlassTokens.RefractionAmountDp.dp.toPx() * (if (active) 1.7f else 1f),
                if (active) .08f else 0f, 2.dp.toPx())
        } else null
        // Stable, sparse grain: cached positions, no random generator or timer per frame.
        val grain = if (lowEffects) emptyList() else List(48) { index ->
            Offset(((index * 37 % 101) / 101f) * size.width, ((index * 61 % 103) / 103f) * size.height)
        }
        onDrawWithContent {
            if (size.minDimension > 0f) {
                val local = coordinates
                val sampled = !lowEffects && effects.backdropEnabled && source != null &&
                    host?.isAttached == true && local?.isAttached == true
                clipPath(shape) {
                    if (paintSurface) {
                        if (sampled) {
                            observeContentChanges()
                            // This state invalidates material drawing as the pill changes placement,
                            // even if the underlying page itself did not redraw.
                            val position = samplePosition
                            if (lensEffect != null) {
                                layer.renderEffect = lensEffect
                                layer.record(size = extent) {
                                    if (filtered != null) translate(pad + filteredOrigin.x - position.x,
                                        pad + filteredOrigin.y - position.y) { drawLayer(filtered) }
                                    else translate(pad - position.x, pad - position.y) { drawLayer(source!!) }
                                }
                                translate(-pad, -pad) { drawLayer(layer) }
                            } else scale(if (active && Build.VERSION.SDK_INT < 33) 1.06f else 1f) {
                                // API 31: magnified replay + host RenderEffect, without UV distortion.
                                if (filtered != null) translate(filteredOrigin.x - position.x,
                                    filteredOrigin.y - position.y) { drawLayer(filtered) }
                                else translate(-position.x, -position.y) { drawLayer(source!!) }
                            }
                            if (!active) drawRect(tint)
                        } else if (!active) {
                            drawRect(base.copy(alpha = if (lowEffects) 1f else base.alpha))
                        }
                        if (!active && effects.tint && !lowEffects) drawRect(tokens.activeAmbientGlow.copy(alpha = .025f))
                    }
                    // The selected indicator owns its one continuous tint and one crisp rim.
                    // Do not stack the outer glass's fill, grain or press ring beneath it.
                    if (!active) {
                        if (!lowEffects) {
                            drawRect(inner); drawRect(topLight)
                            grain.forEach { drawCircle(bright.copy(alpha = .012f), .35.dp.toPx(), it) }
                        }
                        drawPath(shape, if (lowEffects) Brush.linearGradient(listOf(tokens.glassSpecularRimDim,
                            tokens.glassSpecularRimDim)) else edge, style = Stroke(stroke * 2f))
                        val press = pressure().coerceIn(0f, 1f)
                        if (press > 0f) drawPath(shape, bright.copy(alpha = .9f * press), style = Stroke(stroke * 2f))
                    }
                }
            }
            // Foreground is never passed through the lens / blur effect.
            drawContent()
        }
    }
}

@RequiresApi(33)
@Composable
private fun rememberNavRefraction(onFailure: (String) -> Unit): RuntimeShader? {
    val shader = remember { runCatching { RuntimeShader(LiquidMaterialShader) }.getOrNull() }
    if (shader == null) LaunchedEffect(Unit) {
        onFailure("增强材质暂不可用，已使用基础外观。重开应用后可再次尝试。")
    }
    return shader
}

@RequiresApi(33)
private fun navRefractionEffect(shader: RuntimeShader, extent: IntSize, pad: Float,
    size: Size, radius: Float, edge: Float, amount: Float, magnification: Float, innerWidth: Float): androidx.compose.ui.graphics.RenderEffect {
    shader.setFloatUniform("resolution", extent.width.toFloat(), extent.height.toFloat())
    shader.setFloatUniform("circle", 0f, 0f, 0f, 0f)
    shader.setFloatUniform("bud", 0f, 0f, 0f, 0f)
    shader.setFloatUniform("pod", pad, pad, pad + size.width, pad + size.height)
    shader.setFloatUniform("cornerRadii", 0f, radius, 0f)
    shader.setFloatUniform("mergeRadius", 0f)
    shader.setFloatUniform("strokeWidth", 1f)
    shader.setFloatUniform("edgeWidth", edge)
    shader.setFloatUniform("refractionAmount", amount)
    shader.setFloatUniform("lensMagnification", magnification)
    shader.setFloatUniform("innerWidth", innerWidth)
    shader.setFloatUniform("sampled", 1f)
    shader.setFloatUniform("highlight", 0f) // Semantic rim colors are drawn above the lens.
    shader.setColorUniform("fillColor", android.graphics.Color.TRANSPARENT)
    shader.setColorUniform("tintColor", android.graphics.Color.TRANSPARENT)
    shader.setColorUniform("outlineColor", android.graphics.Color.TRANSPARENT)
    shader.setColorUniform("rimBright", android.graphics.Color.TRANSPARENT)
    shader.setColorUniform("rimDim", android.graphics.Color.TRANSPARENT)
    return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}
