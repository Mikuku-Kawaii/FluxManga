package com.flux.manga.ui.glass

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.PI

/** One geometry/neck timeline for foreground, SDF and the API 31 threshold material. */
internal fun momentumFrame(width: Float, height: Float, gap: Float, progress: Float, from: PrototypeWorld): DockMaterialFrame {
    val t = progress.coerceIn(0f, 1.08f)
    val merge = (t / LiquidMomentumSpec.MergeEnd).coerceIn(0f, 1f)
    val split = ((t - LiquidMomentumSpec.SplitStart) / (1f - LiquidMomentumSpec.SplitStart)).coerceIn(0f, 1.13f)
    val shift = split.coerceAtMost(1f)
    val navX = (height + gap) * (1f - shift)
    val navWidth = (width - height - gap).coerceAtLeast(0f)
    fun square(centerX: Float, amount: Float, strain: Float = 0f): MaterialBounds {
        val side = height * amount
        val w = side * (1f + strain); val h = side / (1f + strain)
        return MaterialBounds(centerX - w / 2, (height - h) / 2,
            centerX + w / 2, (height + h) / 2, h * LiquidMomentumSpec.PodCornerFraction)
    }
    fun smooth(start: Float, end: Float): Float {
        val u = ((t - start) / (end - start)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }
    val neck = if (t <= 0f || t >= 1f) 0f else 1.8f * smooth(0f, .24f) * (1f - smooth(.78f, .96f))
    fun pulse(start: Float, duration: Float): Float {
        val phase = ((t - start) / duration).coerceIn(0f, 1f)
        return if (phase == 0f || phase == 1f) 0f else sin(PI.toFloat() * phase)
    }
    val impact = pulse(.28f, .25f)
    val stretch = .16f * pulse(.65f, .35f)
    val outgoing = square(height / 2 + (height + gap) * merge * merge,
        1f - smooth(.18f, LiquidMomentumSpec.MergeEnd), .12f * impact)
    val incoming = square(navX + navWidth - height / 2 + (height + gap) * split,
        (split * 1.35f).coerceAtMost(1f), stretch)
    val dx = navWidth * .015f * impact; val dy = height * .025f * impact
    val capsule = MaterialBounds(navX - dx, dy, navX + navWidth + dx, height - dy)
    fun mirror(rect: MaterialBounds) = MaterialBounds(width - rect.right, rect.top, width - rect.left, rect.bottom, rect.cornerRadius)
    return if (from == PrototypeWorld.SFW) DockMaterialFrame(outgoing, capsule, incoming, neck)
        else DockMaterialFrame(mirror(incoming), mirror(capsule), mirror(outgoing), neck)
}

internal fun momentumImpact(width: Float, height: Float, gap: Float, from: PrototypeWorld,
    progress: Float = LiquidMomentumSpec.Impact): Offset {
    val frame = momentumFrame(width, height, gap, progress, from)
    val merging = if (from == PrototypeWorld.SFW) frame.circle else frame.bud
    val edge = if (from == PrototypeWorld.SFW) frame.pod.left else frame.pod.right
    return Offset((merging.cx + edge) / 2f, height / 2f)
}

/** Content stays clear; only the material slot unions and shades the shared silhouette. */
@Composable
internal fun LiquidMetaballBar(
    world: PrototypeWorld,
    motion: WorldMotion?,
    modifier: Modifier = Modifier,
    onHeightChanged: (Dp) -> Unit,
    detachedPodContent: @Composable (DetachedPodState, Modifier) -> Unit,
    navItemContent: @Composable (Modifier) -> Unit,
    materialContent: @Composable BoxScope.(DockMaterialFrame, Dp) -> Unit,
) {
    val initialHeight = liquidDockHeight()
    var measuredHeight by remember { mutableStateOf(motion?.dockHeight ?: initialHeight) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = LiquidNavLayoutTokens.FloatingGap
        val side = measuredHeight
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = with(density) { side.roundToPx().toFloat() }
        val gapPx = with(density) { gap.roundToPx().toFloat() }
        val navWidthPx = (widthPx - heightPx - gapPx).coerceAtLeast(1f)
        val from = if (motion?.pending == true) motion.from else world
        val progress = if (motion?.pending == true) motion.momentumProgress() else 0f
        val geometry = momentumFrame(widthPx, heightPx, gapPx, progress, from)
        val navLeft = (geometry.pod.cx - navWidthPx / 2f).roundToInt()
        // Match the foreground's integer placement and its center-based layer scaling exactly.
        val pixelShift = navLeft + navWidthPx / 2f - geometry.pod.cx
        val frame = geometry.copy(pod = geometry.pod.copy(
            left = geometry.pod.left + pixelShift, right = geometry.pod.right + pixelShift))
        Box(Modifier.fillMaxWidth().heightIn(min = side).onGloballyPositioned {
            motion?.updateImpactGeometry(it.localToWindow(Offset.Zero), widthPx, heightPx, gapPx)
        }) {
            materialContent(frame, side)
            @Composable fun Detached(bounds: MaterialBounds, podSide: DetachedPodSide) {
                if (bounds.width <= .1f) return
                val state = DetachedPodState(podSide, motion?.pending != true,
                    if (world == PrototypeWorld.SFW) "切换到里世界" else "返回表世界")
                detachedPodContent(state, Modifier.absoluteOffset {
                    IntOffset((bounds.cx - heightPx / 2).roundToInt(), 0)
                }.size(side).graphicsLayer {
                    scaleX = bounds.width / heightPx; scaleY = bounds.height / heightPx
                    alpha = (bounds.width / heightPx * 1.6f).coerceIn(0f, 1f)
                })
            }
            Detached(frame.circle, DetachedPodSide.Left)
            Detached(frame.bud, DetachedPodSide.Right)
            navItemContent(Modifier.absoluteOffset { IntOffset(navLeft, 0) }
                .width(with(density) { navWidthPx.toDp() }).graphicsLayer {
                    scaleX = frame.pod.width / navWidthPx
                    scaleY = frame.pod.height / heightPx
                }.onSizeChanged {
                    val height = with(density) { it.height.toDp() }
                    if (height != measuredHeight) measuredHeight = height
                    motion?.updateDockHeight(height)
                    onHeightChanged(height)
                })
        }
    }
}
