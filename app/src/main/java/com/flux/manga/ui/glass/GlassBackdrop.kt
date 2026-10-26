package com.flux.manga.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

// Instrumentation observes actual host disposal without querying a paused UI tree.
@Volatile
internal var activeGlassHosts = 0
    private set

/** Source records ONLY content. Overlay is a sibling and can never record itself. */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    sourceKey: Any,
    observeContentChanges: () -> Unit,
    blur: Boolean,
    sampleBounds: Rect?,
    background: Color,
    enabled: Boolean = true,
    saturation: Boolean = false,
    motionPadding: Float = 0f,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.(GraphicsLayer?, GraphicsLayer?, Offset, LayoutCoordinates?) -> Unit,
) {
    DisposableEffect(enabled, sourceKey) {
        if (enabled) activeGlassHosts++
        onDispose { if (enabled) activeGlassHosts-- }
    }
    val backgroundGraphicsLayer = key(sourceKey, enabled) { if (enabled) rememberGraphicsLayer() else null }
    val filtered = key(sourceKey, enabled, blur, saturation) { if (enabled && (blur || saturation)) rememberGraphicsLayer() else null }
    val radius = with(LocalDensity.current) { GlassTokens.BlurRadiusDp.dp.toPx() }
    val padding = ceil((if (blur) radius * 3 else 0f) + with(LocalDensity.current) {
        (GlassTokens.MaterialPaddingDp + GlassTokens.RefractionAmountDp).dp.toPx()
    }).toInt()
    val effect = remember(radius, blur, saturation) {
        val blurred = if (blur) android.graphics.RenderEffect.createBlurEffect(radius, radius,
            android.graphics.Shader.TileMode.CLAMP) else null
        if (saturation) {
            val filter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
                setSaturation(GlassTokens.Saturation)
                postConcat(android.graphics.ColorMatrix().apply {
                    setScale(GlassTokens.Brightness, GlassTokens.Brightness, GlassTokens.Brightness, 1f)
                })
            })
            (if (blurred != null) android.graphics.RenderEffect.createColorFilterEffect(filter, blurred)
            else android.graphics.RenderEffect.createColorFilterEffect(filter)).asComposeRenderEffect()
        } else blurred?.asComposeRenderEffect()
    }
    val origin = Offset(-padding.toFloat(), (sampleBounds?.top ?: 0f) - padding - motionPadding)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(modifier.onGloballyPositioned { coordinates = it }) {
        Box(Modifier.fillMaxSize().drawWithContent {
            if (backgroundGraphicsLayer == null) {
                drawContent()
                return@drawWithContent
            }
            observeContentChanges()
            backgroundGraphicsLayer.record { this@drawWithContent.drawContent() }
            if (filtered != null && sampleBounds != null) {
                filtered.renderEffect = effect
                filtered.record(size = IntSize(ceil(size.width).toInt() + padding * 2,
                    ceil(sampleBounds.height + motionPadding).toInt() + padding * 2)) {
                    drawRect(background)
                    translate(-origin.x, -origin.y) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) drawLayer(backgroundGraphicsLayer)
                        // Keep the existing API 31 direct-record workaround for stale nested layers.
                        else this@drawWithContent.drawContent()
                    }
                }
            }
            drawLayer(backgroundGraphicsLayer)
        }, content = content)
        overlay(backgroundGraphicsLayer, if (sampleBounds != null) filtered else null, origin, coordinates)
    }
}

/** Replay in host coordinates, clip locally, then draw the unfiltered foreground. */
@Composable
fun Modifier.glassSample(
    source: GraphicsLayer, host: LayoutCoordinates?, palette: GlassPalette,
    raw: Boolean, visible: Boolean, filtered: GraphicsLayer?, filteredOrigin: Offset, tint: Boolean,
    observeContentChanges: () -> Unit,
): Modifier {
    var position by remember { mutableStateOf(Offset.Zero) }
    var located by remember { mutableStateOf(false) }
    return onGloballyPositioned { local ->
        if (host?.isAttached == true) {
            position = host.localPositionOf(local, Offset.Zero)
            located = true
        }
    }.drawWithCache {
        val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height,
            CornerRadius(size.height / 2))) }
        val outline = Stroke(1.dp.toPx())
        onDrawWithContent {
            // Child graphicsLayer translations can change without redrawing the host.
            // Subscribe in draw so the filtered offscreen result is refreshed as well.
            observeContentChanges()
            if (visible && located) {
                clipPath(path) {
                    // In raw mode this opaque sentinel proves we replay the source, not transparency.
                    if (raw) drawRect(Color.Magenta)
                    if (filtered != null) {
                        translate(filteredOrigin.x - position.x, filteredOrigin.y - position.y) { drawLayer(filtered) }
                    } else translate(-position.x, -position.y) { drawLayer(source) }
                    if (!raw) {
                        drawRect(palette.fill)
                    }
                    if (tint) drawRect(palette.tint.copy(alpha = palette.tintAlpha))
                }
                drawPath(path, if (raw) Color(0xFF00BD92) else palette.outline, style = outline)
            }
            if (!raw) drawContent()
        }
    }
}
