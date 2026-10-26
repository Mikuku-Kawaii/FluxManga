package com.flux.manga.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Only the live, authorized page is drawn. No previous-world capture or retained page layer.
 * Outgoing parallax finishes before revocation; incoming pixels wait for displayReady().
 */
@Composable
fun LiquidShockwaveScaffold(motion: WorldMotion, world: PrototypeWorld, currentTab: MainTab,
    modifier: Modifier = Modifier,
    pageContent: @Composable (LiquidThemePalette, MainTab) -> Unit) {
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { windowOrigin = it.localToWindow(Offset.Zero) }) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val aperture = Path()
            val ringWidth = LiquidMomentumSpec.ShockwaveWidthDp.dp.toPx()
            onDrawWithContent {
                if (!motion.pending) {
                    drawContent()
                } else {
                    val old = LiquidThemePalette.forWorld(motion.from)
                    val target = LiquidThemePalette.forWorld(motion.target)
                    drawRect(old.pageBackgroundPrimary)
                    val p = motion.revealProgress()
                    if (!motion.impactDelivered) {
                        // The current page is still authorized; never keep it after impact.
                        val depart = if (motion.animationsEnabled)
                            (motion.momentumProgress() / LiquidMomentumSpec.Impact).coerceIn(0f, 1f) else 0f
                        withTransform({ scale(1f - .06f * depart, 1f - .06f * depart, center) }) {
                            this@onDrawWithContent.drawContent()
                        }
                        drawRect(old.pageBackgroundPrimary.copy(alpha = depart * depart))
                    } else if (motion.hasCommitted && p > 0f) {
                        if (!motion.animationsEnabled) {
                            drawContent()
                        } else {
                            // Window -> local conversion stays valid after Insets/size changes.
                            val impact = motion.originInWindow?.minus(windowOrigin) ?: center
                            val radius = (revealRadius(size, impact) + ringWidth) * p
                            aperture.reset()
                            aperture.addOval(Rect(impact.x - radius, impact.y - radius,
                                impact.x + radius, impact.y + radius))
                            clipPath(aperture) {
                                drawRect(target.pageBackgroundPrimary)
                                val rebound = exp(-6f * p) * cos(10f * p) - exp(-6f) * cos(10f) * p
                                val zoom = 1f + .04f * rebound
                                withTransform({ scale(zoom, zoom, center) }) {
                                    this@onDrawWithContent.drawContent()
                                }
                            }
                            // Procedural optical ring, not a screen capture or full-page blur.
                            val energy = sin(PI.toFloat() * p).coerceAtLeast(0f)
                            val outer = radius + ringWidth / 2f
                            val innerStop = ((radius - ringWidth / 2f) / outer).coerceIn(0f, 1f)
                            val crestStop = (radius / outer).coerceIn(innerStop, 1f)
                            val glow = target.ambientShockwaveGlow
                            val clear = glow.copy(alpha = 0f)
                            val ring = Brush.radialGradient(0f to clear, innerStop to clear,
                                crestStop to glow.copy(alpha = glow.alpha * energy), 1f to clear,
                                center = impact, radius = outer)
                            withTransform({ scale(1f + .012f * energy, 1f - .012f * energy, impact) }) {
                                drawCircle(ring, outer, impact)
                                drawCircle(target.glassSpecularRimBright.copy(alpha =
                                    target.glassSpecularRimBright.alpha * .4f * energy), radius, impact,
                                    style = Stroke(1.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }) { pageContent(LiquidThemePalette.forWorld(world), currentTab) }
        if (motion.pending) {
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                }.testTag("liquid-theme-handoff"))
        }
    }
}
