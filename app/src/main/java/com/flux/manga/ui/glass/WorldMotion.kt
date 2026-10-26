package com.flux.manga.ui.glass

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import com.flux.manga.session.SwitchPhase
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** Composition-owned animation; only a collision event may request a business transition. */
class WorldMotion(private val scope: CoroutineScope, initial: PrototypeWorld) {
    val circle = Animatable(initial.fraction())
    val pod get() = circle
    val reveal = Animatable(1f)
    var pending by mutableStateOf(false); private set
    var moving by mutableStateOf(false); private set
    var revealing by mutableStateOf(false); private set
    var hasCommitted by mutableStateOf(false); private set
    var from by mutableStateOf(initial); private set
    var target by mutableStateOf(initial); private set
    var originInWindow by mutableStateOf<Offset?>(null); private set
    var impactDelivered by mutableStateOf(false); private set
    private var rollback by mutableStateOf(false)
    private var positions: Job? = null
    private var radial: Job? = null
    private var resolved = true
    private var revealFinished = true
    private var revealStarted by mutableStateOf(false)
    private var animate = true
    val animationsEnabled get() = animate
    private var onImpact: ((Offset?) -> Unit)? = null
    private var measuredImpact: Offset? = null
    private data class ImpactGeometry(val origin: Offset, val width: Float, val height: Float, val gap: Float)
    private var geometry: ImpactGeometry? = null
    var dockHeight by mutableStateOf<androidx.compose.ui.unit.Dp?>(null); private set
    fun updateDockHeight(height: androidx.compose.ui.unit.Dp) { dockHeight = height }
    fun plannedImpact() = geometry?.let { it.origin + momentumImpact(it.width, it.height, it.gap, from) } ?: measuredImpact

    val phase: MomentumPhase get() = when {
        !pending -> MomentumPhase.Idle
        rollback -> MomentumPhase.Rollback
        momentumProgress() < LiquidMomentumSpec.SplitStart -> MomentumPhase.Merging
        momentumProgress() < LiquidMomentumSpec.MergeEnd -> MomentumPhase.Transfer
        momentumProgress() < LiquidMomentumSpec.SpringStart -> MomentumPhase.Splitting
        else -> MomentumPhase.Settling
    }

    fun updateImpactGeometry(origin: Offset, width: Float, height: Float, gap: Float) {
        if (!pending || !impactDelivered) geometry = ImpactGeometry(origin, width, height, gap)
    }

    fun begin(world: PrototypeWorld, destination: PrototypeWorld, origin: Offset?, reduceMotion: Boolean = false,
        onImpact: (Offset?) -> Unit = {}) {
        if (pending) return
        from = world; target = destination
        originInWindow = null
        measuredImpact = origin ?: measuredImpact
        this.onImpact = onImpact
        resolved = false; hasCommitted = false; rollback = false; impactDelivered = false
        revealFinished = false; revealStarted = false
        animate = !reduceMotion && ValueAnimator.areAnimatorsEnabled()
        pending = true; moving = true
        positions = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            circle.snapTo(world.fraction())
            if (animate) {
                val driven = world.fraction() + (destination.fraction() - world.fraction()) * LiquidMomentumSpec.SpringStart
                circle.animateTo(driven, tween(LiquidMomentumSpec.DriveMillis,
                    easing = androidx.compose.animation.core.LinearEasing)) {
                    if (momentumProgress() >= LiquidMomentumSpec.Impact) deliverImpact()
                }
                circle.animateTo(destination.fraction(), spring(LiquidMomentumSpec.Damping, LiquidMomentumSpec.Stiffness),
                    initialVelocity = (destination.fraction() - world.fraction()) * LiquidMomentumSpec.SpringStart /
                        (LiquidMomentumSpec.DriveMillis / 1000f))
            } else { deliverImpact(); circle.snapTo(destination.fraction()) }
            moving = false
            finishIfReady()
        }
    }

    private fun deliverImpact() {
        if (!pending || impactDelivered || rollback) return
        impactDelivered = true
        originInWindow = geometry?.let { it.origin + momentumImpact(it.width, it.height, it.gap, from,
            if (animate) momentumProgress() else LiquidMomentumSpec.Impact) } ?: measuredImpact
        val callback = onImpact
        onImpact = null // No delayed callback can survive cancellation or fire twice.
        callback?.invoke(originInWindow)
    }

    /** WorldSession remains the sole commit authority. Failed preparation returns the current shapes. */
    fun resolve(world: PrototypeWorld, phase: SwitchPhase) {
        if (!pending) { interrupt(world); return }
        if (!impactDelivered || resolved || phase == SwitchPhase.Preparing) return
        resolved = true
        if (world != target) {
            radial?.cancel(); positions?.cancel(); onImpact = null
            revealing = false; revealFinished = true; rollback = true; moving = true
            positions = scope.launch {
                if (animate) circle.animateTo(world.fraction(), spring(LiquidMomentumSpec.Damping, LiquidMomentumSpec.Stiffness))
                interrupt(world)
            }
        } else { hasCommitted = true; revealing = true }
    }

    /** No target theme exposure until the committed page and native privacy cover are ready. */
    fun displayReady() {
        if (!pending || !hasCommitted || revealStarted) return
        radial = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            reveal.snapTo(if (animate) 0f else 1f)
            revealStarted = true
            if (animate) reveal.animateTo(1f, tween(GlassTokens.WorldRevealMillis, easing = FastOutSlowInEasing))
            revealing = false; revealFinished = true
            finishIfReady()
        }
    }

    fun momentumProgress(): Float {
        if (!pending) return 0f
        val direction = target.fraction() - from.fraction()
        return if (direction == 0f) 0f else (circle.value - from.fraction()) / direction
    }
    fun revealProgress() = if (!hasCommitted || !revealStarted) 0f else if (revealing) reveal.value else 1f
    fun veilAlpha() = 1f - ((revealProgress() - .35f) / .65f).coerceIn(0f, 1f)
    fun themeProgress(): Float = if (pending) from.fraction() + (target.fraction() - from.fraction()) * revealProgress() else target.fraction()
    private fun finishIfReady() { if (!moving && revealFinished) pending = false }

    fun interrupt(world: PrototypeWorld) {
        positions?.cancel(); radial?.cancel(); onImpact = null
        target = world; from = world
        moving = false; revealing = false; pending = false; rollback = false
        resolved = true; revealFinished = true; originInWindow = null; measuredImpact = null; geometry = null
        revealStarted = false; hasCommitted = false; impactDelivered = false
    }

    fun circleValue() = if (moving) circle.value else target.fraction()
    fun podValue() = circleValue()
    fun circleSpeed(): Float { circle.value; return if (moving) (abs(circle.velocity) / 6f).coerceIn(0f, 1f) else 0f }
    fun podSpeed() = circleSpeed()
}

private fun PrototypeWorld.fraction() = if (this == PrototypeWorld.SFW) 0f else 1f

internal fun revealRadius(size: Size, center: Offset): Float = maxOf(
    hypot(center.x, center.y), hypot(size.width - center.x, center.y),
    hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y),
)

/** Only two solid theme colors. It cannot record, clip or reveal a page/image layer. */
@Composable
fun CircularRevealBox(motion: WorldMotion, modifier: Modifier = Modifier) {
    var center by remember { mutableStateOf<Offset?>(null) }
    Canvas(modifier.testTag("safe-radial-reveal").onGloballyPositioned { coordinates ->
        center = motion.originInWindow?.let { coordinates.windowToLocal(it) }
    }) {
        val origin = center ?: Offset(size.width / 2, size.height / 2)
        val oldColor = if (motion.from == PrototypeWorld.SFW) GlassTokens.Light.background else GlassTokens.Dark.background
        val newColor = if (motion.target == PrototypeWorld.SFW) GlassTokens.Light.background else GlassTokens.Dark.background
        drawRect(oldColor)
        if (motion.hasCommitted) drawCircle(newColor,
            revealRadius(size, origin) * (motion.revealProgress() / .7f).coerceIn(0f, 1f), origin)
    }
}
