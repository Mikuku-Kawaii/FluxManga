package com.flux.manga.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

internal object LiquidNavMotionSpec {
    const val DampingRatio = .72f
    const val Stiffness = 380f
    const val MaximumStretch = .22f
    const val MaximumSquash = .06f
    const val VelocityForFullStretch = 7f // tab widths / second
    const val PressScale = .92f
    const val SelectedIconScale = 1.15f
}

internal class LiquidNavMotion(initialIndex: Int) {
    val position = Animatable(initialIndex.coerceAtLeast(0).toFloat())
    // Read from the same spring in draw/layer callbacks, not from a second frame loop.
    val strain: Float get() = if (position.isRunning)
        (abs(position.velocity) / LiquidNavMotionSpec.VelocityForFullStretch).coerceIn(0f, 1f) else 0f
}

/** Rapid retargeting carries the current velocity; disabling motion snaps both position and strain. */
@Composable
internal fun rememberLiquidNavMotion(index: Int, motionEnabled: Boolean): LiquidNavMotion {
    val motion = remember { LiquidNavMotion(index) }
    val request by rememberUpdatedState(index to motionEnabled)
    LaunchedEffect(motion) {
        snapshotFlow { request }.collect { (index, enabled) ->
            // Let Animatable interrupt its predecessor itself, preserving velocity at retarget time.
            launch {
                val target = index.coerceAtLeast(0).toFloat()
                if (enabled) motion.position.animateTo(target, spring(
                    dampingRatio = LiquidNavMotionSpec.DampingRatio, stiffness = LiquidNavMotionSpec.Stiffness))
                else motion.position.snapTo(target)
            }
        }
    }
    return motion
}
