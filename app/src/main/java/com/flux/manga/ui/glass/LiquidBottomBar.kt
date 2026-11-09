package com.flux.manga.ui.glass

import com.flux.manga.R
import com.flux.manga.ui.LucideIcon
import android.animation.ValueAnimator
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.navigation.tabTitle
import com.flux.manga.traceMeasure

@Composable
fun liquidDockHeight(): Dp = LiquidNavLayoutTokens.MinimumItemHeight + LiquidNavLayoutTokens.SurfaceInset * 2

/** Foreground icon size; container geometry is measured by LiquidMetaballBar. */
private object DockGeometry {
    const val Icon = 20
}

/** Static surfaces and the shared merge/split contour keep foreground content separate. */
object LiquidShapeRenderer {
    fun outline(size: Size, scale: Offset = Offset(1f, 1f), path: Path = Path()) = path.apply {
        reset()
        val dx = size.width * (scale.x - 1f) / 2
        val dy = size.height * (scale.y - 1f) / 2
        addRoundRect(RoundRect(-dx, -dy, size.width + dx, size.height + dy, CornerRadius(size.height * scale.y / 2)))
    }
}

/** Surface draws only sampled background and material; content is always drawn afterward. */
@Composable
fun GlassSurface(
    modifier: Modifier,
    palette: GlassPalette,
    source: GraphicsLayer?,
    filtered: GraphicsLayer?,
    filteredOrigin: Offset,
    host: LayoutCoordinates?,
    tint: Boolean,
    observeContentChanges: () -> Unit,
    deformation: () -> Offset = { Offset(1f, 1f) },
    opaque: Boolean = false,
    frostedLens: Boolean = false,
    highlighted: Boolean = false,
    materialVisible: Boolean = true,
    outline: ((Size, Path) -> Path)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var position by remember { mutableStateOf(Offset.Zero) }
    var located by remember { mutableStateOf(false) }
    Box(modifier.onGloballyPositioned { local ->
        located = host?.isAttached == true
        if (located) position = host!!.localPositionOf(local, Offset.Zero)
    }.drawWithCache {
        val shape = Path()
        val stroke = Stroke(1.dp.toPx())
        val highlight = if (frostedLens) opticalRimBrush(size) else classicOpticalRimBrush(size)
        val bottomShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (frostedLens) .06f else .18f)),
            startY = size.height - 2.dp.toPx(), endY = size.height)
        val topLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (frostedLens) .12f else .4f), Color.Transparent),
            startY = 0f, endY = 1.dp.toPx())
        onDrawWithContent { com.flux.manga.traceWork("FluxDockDraw") {
            if (!materialVisible) { drawContent(); return@traceWork }
            // Only a sampled backdrop depends on content scrolling. Keep the static
            // base material's display list reusable while the list moves underneath.
            if (source != null) observeContentChanges()
            if (outline != null) outline(size, shape) else LiquidShapeRenderer.outline(size, deformation(), shape)
            clipPath(shape) {
                // Missing sampling stays transparent; only explicit low-effects mode is opaque.
                if (source != null && located && !opaque) {
                    if (filtered != null) translate(filteredOrigin.x - position.x, filteredOrigin.y - position.y) { drawLayer(filtered) }
                    else translate(-position.x, -position.y) { drawLayer(source) }
                    drawPath(shape, palette.fill)
                } else drawPath(shape, palette.fill.copy(alpha = if (opaque) 1f else palette.fill.alpha))
                if (tint) drawPath(shape, palette.tint.copy(alpha = palette.tintAlpha))
                if (!opaque) { drawRect(bottomShade); drawRect(topLight) }
            }
            if (opaque) drawPath(shape, palette.outline, style = stroke)
            else drawPath(shape, highlight, style = stroke)
            drawContent()
        } }
    }, content = content)
}

@Composable
fun LiquidBottomBar(
    world: PrototypeWorld,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
    switchEnabled: Boolean = true,
    effects: DockEffects = DockEffects(),
    source: GraphicsLayer? = null,
    filtered: GraphicsLayer? = null,
    filteredOrigin: Offset = Offset.Zero,
    host: LayoutCoordinates? = null,
    observeContentChanges: () -> Unit = {},
    positioned: (LayoutCoordinates) -> Unit = {},
    motion: WorldMotion? = null,
    onSwitchAt: ((Offset) -> Unit)? = null,
    onEnhancementFailure: (String) -> Unit = {},
    enhancementFailure: String? = null,
    longPressToEnter: Boolean = false,
    onHeightChanged: (Dp) -> Unit = {},
) {
    val requireHold = world == PrototypeWorld.SFW && longPressToEnter
    val context = LocalContext.current
    val viewConfiguration = LocalViewConfiguration.current
    val holdConfiguration = remember(viewConfiguration, requireHold) {
        object : ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long = if (requireHold) 1500L else viewConfiguration.longPressTimeoutMillis
        }
    }
    val momentumMaterial = motion?.pending == true && motion.animationsEnabled && !effects.lowEffects
    val materialEffects = if (momentumMaterial) effects.copy(fusion = true, highlight = true) else effects
    val unified = materialEffects.unifiedMaterial(android.os.Build.VERSION.SDK_INT) && enhancementFailure == null
    val themeProgress = if (motion?.pending == true) motion.themeProgress() else if (world == PrototypeWorld.SFW) 0f else 1f
    val theme = LiquidThemePalette.at(themeProgress, effects.frostedLens)
    val navTokens = LiquidGlassTokens.at(themeProgress, effects.frostedLens)
    val originalPalette = GlassTokens.paletteAt(themeProgress)
    val palette = if (effects.frostedLens) originalPalette.copy(fill = navTokens.glassSurfaceBase, tintAlpha = .004f) else originalPalette
    val items = MainTab.primary.map { LiquidNavItem(it.name, tabTitle(it)) }
    val reduceMotion = effects.reduceMotion || effects.lowEffects || !ValueAnimator.areAnimatorsEnabled()
    LiquidMetaballBar(world, motion,
        modifier.traceMeasure("Dock").fillMaxWidth().padding(horizontal = LiquidNavLayoutTokens.FloatingMargin)
            .onGloballyPositioned(positioned).testTag("liquid-bottom-bar")
            .semantics { isTraversalGroup = true; traversalIndex = 2f },
        onHeightChanged = onHeightChanged,
        detachedPodContent = { pod, placement ->
            GlassSurface(placement.testTag("dock-circle"), palette.copy(fill = theme.detachedPodSurface),
                source, filtered, filteredOrigin, host, effects.tint, observeContentChanges,
                opaque = effects.lowEffects, frostedLens = effects.frostedLens, highlighted = true, materialVisible = !unified,
                outline = { size, path -> path.apply {
                    reset(); addRoundRect(RoundRect(0f, 0f, size.width, size.height,
                        CornerRadius(size.height * LiquidMomentumSpec.PodCornerFraction)))
                } }) {
                val activate = { if (onSwitchAt != null && motion?.plannedImpact() != null)
                    onSwitchAt(motion.plannedImpact()!!) else onSwitch() }
                CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration) {
                    val hit = if (pod.enabled) Modifier.combinedClickable(enabled = switchEnabled, role = Role.Button,
                        onClickLabel = if (requireHold) "显示长按提示" else pod.label,
                        onLongClickLabel = if (requireHold) "长按 1.5 秒进入里世界" else null,
                        onLongClick = if (requireHold) activate else null,
                        onClick = {
                            if (requireHold) android.widget.Toast.makeText(context, "请长按切换键 1.5 秒进入", android.widget.Toast.LENGTH_SHORT).show()
                            else activate()
                        }).semantics {
                            contentDescription = if (requireHold) "长按 1.5 秒进入里世界" else pod.label
                            traversalIndex = if (pod.side == DetachedPodSide.Left) -1f else 3f
                        }.testTag("switch-world")
                    else Modifier.clearAndSetSemantics { }
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape((LiquidMomentumSpec.PodCornerFraction * 100).toInt()))
                        .then(hit), contentAlignment = Alignment.Center) {
                        SwitchGlyph(theme.contentPrimaryActive, Modifier.graphicsLayer { rotationZ = themeProgress * 180f })
                    }
                }
            }
        },
        navItemContent = { placement ->
            LiquidNavigationBar(items = items, selectedKey = selected.name,
                onItemSelected = { key -> onSelect(MainTab.primary.first { it.name == key }) },
                tokens = navTokens, enabled = motion?.pending != true, reduceMotion = reduceMotion,
                lowEffects = effects.lowEffects, frostedLens = effects.frostedLens,
                modifier = placement.testTag("dock-pod"),
                windowInsets = WindowInsets(0), contentPadding = PaddingValues(0.dp),
                iconSlot = { item, _ -> TabGlyph(MainTab.primary.first { it.name == item.key },
                    palette.copy(foreground = LocalContentColor.current)) },
                surfaceSlot = {
                    if (!unified) Box(Modifier.fillMaxSize().liquidGlass(navTokens, lowEffects = effects.lowEffects,
                        source = source, filtered = filtered, filteredOrigin = filteredOrigin, host = host,
                        effects = effects, observeContentChanges = observeContentChanges,
                        paintSurface = !unified, onEnhancementFailure = onEnhancementFailure))
                },
                indicatorSlot = {
                    // The clear style shares the outer material; selection is only a tinted outline.
                    if (!effects.frostedLens) Box(Modifier.fillMaxSize().liquidGlass(navTokens, active = true, lowEffects = effects.lowEffects,
                        source = source, filtered = filtered, filteredOrigin = filteredOrigin, host = host,
                        effects = effects, observeContentChanges = observeContentChanges,
                        onEnhancementFailure = onEnhancementFailure))
                })
        },
        materialContent = { frame, side ->
            // One material contour: no independent square/capsule rims across a connected neck.
            if (unified) LiquidDockMaterial(Modifier.matchParentSize(), palette, materialEffects, source, filtered, filteredOrigin, host,
                circleSize = side, gap = LiquidNavLayoutTokens.FloatingGap, frame = { frame },
                observeContentChanges = observeContentChanges, onFailure = onEnhancementFailure, theme = theme)
        })

}

@Composable
private fun SwitchGlyph(ink: Color, modifier: Modifier = Modifier) {
    LucideIcon(R.drawable.lucide_arrow_right_left, modifier.size(26.dp).testTag("dock-switch-icon"), ink)
}

@Composable
private fun TabGlyph(tab: MainTab, palette: GlassPalette) {
    val glyph = when (tab) {
        MainTab.HOME -> R.drawable.lucide_house
        MainTab.LIBRARY -> R.drawable.lucide_book_open
        MainTab.TAGS -> R.drawable.lucide_tag
        MainTab.SEARCH -> R.drawable.lucide_search
        MainTab.DISCOVER -> R.drawable.lucide_compass
    }
    LucideIcon(glyph, Modifier.size(DockGeometry.Icon.dp).testTag("dock-icon-${tab.name}"), palette.foreground)
}

@Composable
fun DockEffectsDialog(effects: DockEffects, change: (DockEffects) -> Unit, failure: String? = null, dismiss: () -> Unit) {
    com.flux.manga.ui.pages.ReferenceDialog(onDismissRequest = dismiss, title = { Text("底栏显示效果") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            com.flux.manga.ui.pages.DockEffectControls(effects, change, failure)
        }
    }, confirmButton = { TextButton(onClick = dismiss, modifier = Modifier.testTag("dock-settings-close")) { Text("完成") } })
}
