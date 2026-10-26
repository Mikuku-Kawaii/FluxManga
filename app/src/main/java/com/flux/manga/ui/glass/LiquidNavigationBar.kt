package com.flux.manga.ui.glass

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

/** Minimums and spacing, never a fixed container size. Height is measured from the slots. */
object LiquidNavLayoutTokens {
    val FloatingMargin = 22.dp
    val FloatingGap = 12.dp
    val SurfaceInset = 4.dp // Vertical content breathing room only; slots span the full bar width.
    val MinimumItemHeight = 48.dp
    val ItemHorizontalPadding = 6.dp
    val ItemVerticalPadding = 6.dp
    val IconSize = 20.dp
    val ContentGap = 6.dp
    val RimWidth = 1.dp
    val Shape = GenericShape { size, _ ->
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.height / 2f)))
    }
    val LabelStyle = TextStyle(fontSize = 13.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
}

/**
 * A bounded-width, content-height navigation capsule. 3–5 items need no geometry changes.
 * No internal selected key or route stack: clicks only notify the caller.
 * Insets are owned here by default; an already-inset host must pass WindowInsets(0).
 * surfaceSlot / indicatorSlot are material seams; foreground slots never enter backdrop capture.
 * overlaySlot is the reserved badge seam. Long press, double tap and haptics are not enabled here.
 */
@Composable
fun LiquidNavigationBar(
    items: List<LiquidNavItem>,
    selectedKey: String,
    onItemSelected: (String) -> Unit,
    tokens: LiquidGlassTokens,
    iconSlot: @Composable (LiquidNavItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reduceMotion: Boolean = false,
    lowEffects: Boolean = false,
    windowInsets: WindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    contentPadding: PaddingValues = PaddingValues(horizontal = LiquidNavLayoutTokens.FloatingMargin,
        vertical = LiquidNavLayoutTokens.FloatingGap),
    labelSlot: @Composable (LiquidNavItem, Boolean) -> Unit = { item, _ -> Text(item.label) },
    overlaySlot: @Composable BoxScope.(LiquidNavItem, Boolean) -> Unit = { _, _ -> },
    surfaceSlot: @Composable BoxScope.() -> Unit = {
        Box(Modifier.matchParentSize().liquidGlass(tokens, lowEffects = lowEffects))
    },
    indicatorSlot: @Composable BoxScope.() -> Unit = {
        Box(Modifier.matchParentSize().liquidGlass(tokens, active = true, lowEffects = lowEffects))
    },
) {
    if (items.isEmpty()) return
    val keys = items.map { it.key }
    require(keys.distinct().size == keys.size) { "LiquidNavItem keys must be unique" }
    val selectedIndex = keys.indexOf(selectedKey)
    val motionEnabled = !reduceMotion && !lowEffects && ValueAnimator.areAnimatorsEnabled()
    // Reordering / replacing items starts in the new geometry, never animates across unrelated keys.
    val motion = key(keys) { rememberLiquidNavMotion(selectedIndex, motionEnabled) }
    val interactions = remember(keys) { keys.map { MutableInteractionSource() } }
    val pressures = interactions.mapIndexed { index, interaction -> key(keys[index]) {
        val pressed by interaction.collectIsPressedAsState()
        val focused by interaction.collectIsFocusedAsState()
        animateFloatAsState(if (enabled && items[index].enabled && (pressed || focused)) 1f else 0f,
            if (motionEnabled) spring(dampingRatio = .72f, stiffness = 550f) else snap(), label = "nav-pressure")
    } }
    val pressScales = interactions.mapIndexed { index, interaction -> key(keys[index]) {
        val pressed by interaction.collectIsPressedAsState()
        animateFloatAsState(if (pressed && enabled && items[index].enabled && motionEnabled)
            LiquidNavMotionSpec.PressScale else 1f,
            if (motionEnabled) spring(dampingRatio = .72f, stiffness = 550f) else snap(), label = "nav-press-scale")
    } }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val widestLabel = items.maxOf { textMeasurer.measure(it.label, LiquidNavLayoutTokens.LabelStyle).size.width }
    val horizontalContentWidth = widestLabel + with(density) {
        (LiquidNavLayoutTokens.IconSize + LiquidNavLayoutTokens.ContentGap +
            LiquidNavLayoutTokens.ItemHorizontalPadding * 2).toPx()
    }
    Layout(modifier = modifier.windowInsetsPadding(windowInsets).padding(contentPadding).fillMaxWidth()
        .selectableGroup().testTag("main-tabs"), content = {
        CompositionLocalProvider(LocalLiquidNavPressure provides { pressures.maxOfOrNull { it.value } ?: 0f }) {
            Box(content = surfaceSlot)
        }
        CompositionLocalProvider(LocalLiquidNavPressure provides { pressures.getOrNull(selectedIndex)?.value ?: 0f }) {
            LiquidNavPillIndicator(tokens = tokens, lowEffects = lowEffects,
                modifier = Modifier.graphicsLayer {
                    clip = false
                    // Subscribing to position invalidates the layer at each spring frame.
                    motion.position.value
                    val strain = if (motionEnabled) motion.strain else 0f
                    val compression = if (motionEnabled) pressScales.getOrNull(selectedIndex)?.value ?: 1f else 1f
                    scaleX = (1f + LiquidNavMotionSpec.MaximumStretch * strain) * compression
                    scaleY = (1f - LiquidNavMotionSpec.MaximumSquash * strain) * compression
                }, content = indicatorSlot)
        }
        items.forEachIndexed { index, item -> key(item.key) {
            val selected = item.key == selectedKey
            val ink by animateColorAsState(if (!enabled || !item.enabled) tokens.contentSubtle
                else if (selected) tokens.contentPrimaryActive else tokens.contentSecondaryMuted,
                if (motionEnabled) spring(stiffness = 550f) else snap(), label = "nav-ink")
            val iconScale by animateFloatAsState(if (selected && motionEnabled) LiquidNavMotionSpec.SelectedIconScale else 1f,
                if (motionEnabled) spring(dampingRatio = .72f, stiffness = 380f) else snap(), label = "nav-icon")
            Box(Modifier.clip(LiquidNavLayoutTokens.Shape)
                .selectable(selected = selected, enabled = enabled && item.enabled, role = Role.Tab,
                    interactionSource = interactions[index], indication = null,
                    onClick = { onItemSelected(item.key) })
                .semantics { contentDescription = item.label; traversalIndex = index.toFloat() }
                .testTag("tab-${item.key}").drawWithContent {
                    drawContent()
                    val strength = pressures[index].value.coerceIn(0f, 1f)
                    if (strength > 0f) drawRoundRect(tokens.activePillBorderGlow.copy(alpha = .7f * strength),
                        cornerRadius = CornerRadius(size.height / 2f), style = Stroke(1.dp.toPx()))
                }) {
                CompositionLocalProvider(LocalContentColor provides ink) {
                    ProvideTextStyle(LiquidNavLayoutTokens.LabelStyle) {
                        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = LiquidNavLayoutTokens.MinimumItemHeight)
                            .lensContentShadow(selected && !lowEffects)
                            .drawWithContent { scale(if (motionEnabled) pressScales[index].value else 1f) {
                                this@drawWithContent.drawContent()
                            } },
                            contentAlignment = Alignment.Center) {
                            val horizontal = with(density) { maxWidth.toPx() } >= horizontalContentWidth
                            val padding = Modifier.fillMaxWidth().padding(horizontal = LiquidNavLayoutTokens.ItemHorizontalPadding,
                                vertical = LiquidNavLayoutTokens.ItemVerticalPadding)
                            @Composable fun Icon() { Box(Modifier.sizeIn(minWidth = LiquidNavLayoutTokens.IconSize,
                                minHeight = LiquidNavLayoutTokens.IconSize).graphicsLayer {
                                    scaleX = iconScale; scaleY = iconScale
                                }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                                iconSlot(item, selected)
                            } }
                            @Composable fun Label() { Box(Modifier.testTag("dock-label-${item.key}").clearAndSetSemantics { }) {
                                labelSlot(item, selected)
                            } }
                            if (horizontal) Row(padding, horizontalArrangement = Arrangement.spacedBy(
                                LiquidNavLayoutTokens.ContentGap, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                Icon(); Label()
                            } else Column(padding, horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(LiquidNavLayoutTokens.ContentGap)) {
                                Icon(); Label()
                            }
                        }
                    }
                }
                Box(Modifier.matchParentSize()) { overlaySlot(item, selected) }
            }
        } }
    }) { measurables, constraints ->
        require(constraints.hasBoundedWidth) { "LiquidNavigationBar requires a bounded parent width" }
        val width = constraints.maxWidth
        val inset = LiquidNavLayoutTokens.SurfaceInset.roundToPx()
        // Integer boundaries partition every pixel exactly once, including the first/last edge.
        val edges = (0..items.size).map { width * it / items.size }
        val itemConstraints = constraints.copy(minHeight = 0,
            maxHeight = (constraints.maxHeight - inset * 2).coerceAtLeast(0))
        val entries = measurables.drop(2).mapIndexed { index, measurable ->
            measurable.measure(itemConstraints.copy(minWidth = edges[index + 1] - edges[index],
                maxWidth = edges[index + 1] - edges[index]))
        }
        val itemHeight = entries.maxOf { it.height }
        val height = constraints.constrainHeight(itemHeight + inset * 2)
        val surface = measurables[0].measure(Constraints.fixed(width, height))
        val pillWidth = if (selectedIndex >= 0) edges[selectedIndex + 1] - edges[selectedIndex] else 0
        val indicator = measurables[1].measure(Constraints.fixed(pillWidth, height))
        layout(width, height) {
            surface.placeRelative(0, 0)
            if (selectedIndex >= 0) {
                val index = if (motionEnabled) motion.position.value.coerceIn(0f, items.lastIndex.toFloat()) else selectedIndex.toFloat()
                val slot = index.toInt()
                val left = edges[slot] + (edges[slot + 1] - edges[slot]) * (index - slot)
                indicator.placeRelative(left.roundToInt(), 0)
            }
            entries.forEachIndexed { index, entry ->
                entry.placeRelative(edges[index], (height - entry.height) / 2)
            }
        }
    }
}
