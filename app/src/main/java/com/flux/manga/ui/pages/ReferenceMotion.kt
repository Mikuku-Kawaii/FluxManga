package com.flux.manga.ui.pages

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Scoped to the live navigation host; never retained across a world/session change. */
internal val LocalReferenceMotionEnabled = staticCompositionLocalOf { false }

internal enum class ReferenceTransitionKind { Page, Tab, Reader }

/** One timing vocabulary for routes, dialogs and reader overlays. No business completion callbacks. */
private object ReferenceMotionTiming {
    const val Page = 240
    const val Tab = 200
    const val Reader = 180
    const val Overlay = 170
}

/** Apply to the visual content only; the enclosing control keeps its 48dp hit area. */
@Composable
internal fun Modifier.referencePressFeedback(interaction: InteractionSource, enabled: Boolean = true): Modifier {
    val motion = LocalReferenceMotionEnabled.current
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (motion && enabled && pressed) .96f else 1f,
        if (motion) spring(dampingRatio = .85f, stiffness = 700f) else snap(), label = "press")
    return graphicsLayer {
        val value = if (motion && enabled) scale.value else 1f
        scaleX = value; scaleY = value
    }
}

/** Content adopts its final layout immediately; motion must never interpolate measured size. */
@Composable
internal fun Modifier.referenceContentResize(): Modifier = this

/** Entrance only: closing tools removes them immediately and never delays reader input.
 * Transform the panel, not the viewport's height or the manga itself. */
@Composable
internal fun Modifier.referenceToolEntrance(fromTop: Boolean = false, dialog: Boolean = false): Modifier {
    val enabled = LocalReferenceMotionEnabled.current
    val progress = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(enabled) {
        if (enabled) progress.animateTo(1f, tween(ReferenceMotionTiming.Overlay, easing = LinearOutSlowInEasing)) else progress.snapTo(1f)
    }
    return graphicsLayer {
        val value = if (enabled) progress.value else 1f
        alpha = value
        translationY = (if (fromTop) -8.dp.toPx() else 8.dp.toPx()) * (1f - value)
        scaleX = if (dialog) .97f + .03f * value else 1f
        scaleY = scaleX
    }
}

@Composable
internal fun ReferenceDisclosureChevron(expanded: Boolean) {
    val enabled = LocalReferenceMotionEnabled.current
    val target = if (expanded) -90f else 90f
    val rotation by animateFloatAsState(target, if (enabled) tween(180, easing = FastOutSlowInEasing) else snap(), label = "disclosure")
    SettingsChevron(modifier = Modifier.rotate(if (enabled) rotation else target))
}

@Composable
internal fun referenceStateColor(target: Color): Color {
    val enabled = LocalReferenceMotionEnabled.current
    val color by animateColorAsState(target, if (enabled) tween(140) else snap(), label = "control-state")
    return if (enabled) color else target
}

/** Nav3 owns gesture progress and cancellation; no duplicate navigation state or timers. */
internal fun referencePageTransition(enabled: Boolean, back: Boolean = false,
    kind: ReferenceTransitionKind = ReferenceTransitionKind.Page, tabDirection: Int = 1): ContentTransform {
    if (!enabled) return EnterTransition.None togetherWith ExitTransition.None
    if (kind == ReferenceTransitionKind.Reader) {
        // Keep large manga pixels stable. The route fades; actual reading gestures keep their own motion.
        return fadeIn(tween(ReferenceMotionTiming.Reader, easing = LinearOutSlowInEasing)) togetherWith
            fadeOut(tween(100))
    }
    if (kind == ReferenceTransitionKind.Tab) {
        return (fadeIn(tween(150)) + slideInHorizontally(tween(ReferenceMotionTiming.Tab,
            easing = LinearOutSlowInEasing)) { tabDirection * it / 12 }) togetherWith
            (fadeOut(tween(100)) + slideOutHorizontally(tween(ReferenceMotionTiming.Tab,
                easing = LinearOutSlowInEasing)) { -tabDirection * it / 24 })
    }
    val direction = if (back) -1 else 1
    return ((fadeIn(tween(180, easing = LinearOutSlowInEasing)) +
        slideInHorizontally(tween(ReferenceMotionTiming.Page, easing = LinearOutSlowInEasing)) { direction * it / 10 } +
        scaleIn(tween(ReferenceMotionTiming.Page, easing = LinearOutSlowInEasing), initialScale = .985f)) togetherWith
        (fadeOut(tween(120)) + slideOutHorizontally(tween(ReferenceMotionTiming.Page,
            easing = LinearOutSlowInEasing)) { -direction * it / 30 }))
        .apply { targetContentZIndex = if (back) -1f else 1f }
}
