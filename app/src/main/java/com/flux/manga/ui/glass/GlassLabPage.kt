package com.flux.manga.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.MainActivity
import com.flux.manga.R
import kotlin.math.ceil
import kotlin.math.sin

private data class Cover(val image: Int, val title: String, val note: String)
private val covers = listOf(
    Cover(R.drawable.cover_tide, "潮汐之间", "VOL. 01  /  海风与远方"),
    Cover(R.drawable.cover_night, "夜行邮差", "VOL. 02  /  城市未眠"),
    Cover(R.drawable.cover_day, "白昼航线", "VOL. 03  /  慢慢出发"),
    Cover(R.drawable.cover_mountain, "山间来信", "VOL. 04  /  一段小径"),
)

@Composable
fun GlassLabPage(back: () -> Unit) {
    var dark by rememberSaveable { mutableStateOf(false) }
    var scene by rememberSaveable { mutableIntStateOf(0) }
    var moving by rememberSaveable { mutableStateOf(false) }
    var sampleVisible by rememberSaveable { mutableStateOf(true) }
    var blur by rememberSaveable { mutableStateOf(false) }
    var tint by rememberSaveable { mutableStateOf(true) }
    var step by rememberSaveable { mutableFloatStateOf(0f) }
    var query by rememberSaveable { mutableStateOf("") }
    var editor by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    val palette = if (dark) GlassTokens.Dark else GlassTokens.Light
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val view = LocalView.current
    DisposableEffect(dark) {
        val activity = view.context as MainActivity
        val bars = WindowCompat.getInsetsController(activity.window, view)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
        onDispose {
            bars.isAppearanceLightStatusBars = true
            bars.isAppearanceLightNavigationBars = true
        }
    }
    MaterialTheme(colorScheme = WorldColors.scheme(
        if (dark) com.flux.manga.ui.navigation.PrototypeWorld.NSFW
        else com.flux.manga.ui.navigation.PrototypeWorld.SFW)) {
        // GraphicsLayer belongs to this active composition, never a ViewModel or saved state.
        // Theme changes discard its source rather than retaining the previous scene.
        key(active) {
            if (!active) Box(Modifier.fillMaxSize().background(palette.background))
            else {
                val density = LocalDensity.current
                val dockHeight = 60.dp + (24 * (density.fontScale - 1).coerceAtLeast(0f)).dp
                var headerPixels by remember { mutableIntStateOf(0) }
                val scroll = rememberLazyListState()
                var dockBounds by remember { mutableStateOf<Rect?>(null) }
                val phase: State<Float> = if (moving) {
                    rememberInfiniteTransition(label = "background-motion").animateFloat(
                        0f, 96f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "background-phase")
                } else rememberUpdatedState(step)
                val observeContentChanges: () -> Unit = {
                    phase.value
                    scroll.firstVisibleItemIndex
                    scroll.firstVisibleItemScrollOffset
                }
                GlassBackdropHost(
                    modifier = Modifier.fillMaxSize().testTag("glass-lab"),
                    sourceKey = dark,
                    observeContentChanges = observeContentChanges,
                    blur = blur && sampleVisible,
                    sampleBounds = dockBounds,
                    background = palette.background,
                    content = {
                        SceneContent(scene, palette, phase, scroll, with(density) { headerPixels.toDp() }, dockHeight)
                    },
                    overlay = { source, filtered, filteredOrigin, coordinates ->
                        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(palette.background.copy(alpha = .97f))
                            .onSizeChanged { headerPixels = it.height }
                            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = back, modifier = Modifier.testTag("glass-back")) { Text("‹ 返回") }
                                if (density.fontScale < 1.5f) Text("材质研究 / 04", Modifier.weight(1f), color = palette.muted, fontSize = 12.sp)
                                else Spacer(Modifier.weight(1f))
                                TextButton(onClick = { dark = !dark }, modifier = Modifier.testTag("glass-theme")) {
                                    Text(if (dark) "切换亮态" else "切换暗态")
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("样图", "棋盘", "图片").forEachIndexed { index, label ->
                                    FilterChip(selected = scene == index, onClick = { scene = index },
                                        label = { Text(label) }, modifier = Modifier.testTag("scene-$index"))
                                }
                                FilterChip(selected = moving, onClick = { moving = !moving }, label = { Text("移动") },
                                    modifier = Modifier.testTag("glass-motion"))
                                FilterChip(selected = false, onClick = { moving = false; step += 17f }, label = { Text("步进") },
                                    modifier = Modifier.testTag("glass-step"))
                                FilterChip(selected = sampleVisible, onClick = { sampleVisible = !sampleVisible }, label = { Text("采样") },
                                    modifier = Modifier.testTag("glass-sampling"))
                                FilterChip(selected = editor, onClick = { editor = !editor }, label = { Text("键盘") },
                                    modifier = Modifier.testTag("glass-editor"))
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = blur, onClick = { blur = !blur },
                                    label = { Text("模糊 16dp") }, modifier = Modifier.testTag("glass-blur"))
                                FilterChip(selected = tint, onClick = { tint = !tint },
                                    label = { Text("微染色") }, modifier = Modifier.testTag("glass-tint"))
                                Text("背景效果 · 前景保持清晰", color = palette.muted, fontSize = 10.sp,
                                    modifier = Modifier.align(Alignment.CenterVertically))
                            }
                            if (editor) OutlinedTextField(query, { query = it }, singleLine = true,
                                label = { Text("Insets 检查输入") },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("glass-input"))
                        }
                        Box(Modifier.align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .padding(bottom = 16.dp)) {
                            StudyDock(requireNotNull(source), coordinates, palette, dark, scene != 0, sampleVisible,
                                filtered, filteredOrigin, tint, dockHeight, selectedTab, { selectedTab = it }, { dark = !dark },
                                observeContentChanges = observeContentChanges,
                                positioned = { local ->
                                    if (coordinates?.isAttached == true) dockBounds = Rect(
                                        coordinates.localPositionOf(local, Offset.Zero),
                                        Size(local.size.width.toFloat(), local.size.height.toFloat()))
                                })
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SceneContent(scene: Int, palette: GlassPalette, phase: State<Float>, scroll: LazyListState, header: androidx.compose.ui.unit.Dp,
                         dockHeight: androidx.compose.ui.unit.Dp) {
    LazyColumn(
        state = scroll,
        modifier = Modifier.fillMaxSize().background(palette.background).testTag("glass-content"),
        contentPadding = PaddingValues(top = header + 20.dp, bottom = dockHeight + 64.dp),
        verticalArrangement = Arrangement.spacedBy(if (scene == 1) 0.dp else 22.dp),
    ) {
        if (scene == 0) item(key = "heading") {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (palette == GlassTokens.Dark) "NIGHT COLLECTION" else "DAYLIGHT COLLECTION",
                    color = palette.muted, fontSize = 11.sp, letterSpacing = 2.sp)
                Text(if (palette == GlassTokens.Dark) "夜色书库" else "日光书库",
                    color = palette.ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Text("留一点时间，给喜欢的故事。", color = palette.muted, fontSize = 14.sp)
                Text("视觉样本 · 原创几何封面 · 无真实内容", color = palette.muted, fontSize = 11.sp)
            }
        }
        if (scene == 1) {
            items((0..12).toList(), key = { "checker-$it" }) { row ->
                Canvas(Modifier.fillMaxWidth().height(192.dp)) {
                    val tile = 32.dp.toPx()
                    val shift = phase.value.dp.toPx()
                    for (y in -3..8) for (x in -4..ceil(size.width / tile).toInt() + 3) {
                        val color = if ((x + y + row) % 2 == 0) Color(0xFF183D57) else Color(0xFFD9ECE3)
                        drawRect(color, Offset(x * tile + shift % (tile * 2), y * tile), Size(tile, tile))
                    }
                    drawLine(Color(0xFFFFB45B), Offset(0f, 4.dp.toPx()), Offset(size.width, 4.dp.toPx()), 4.dp.toPx())
                }
            }
        } else {
            items((0..5).toList(), key = { "covers-$it" }) { row ->
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(2) { column ->
                        val cover = covers[(row * 2 + column) % covers.size]
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Image(painterResource(cover.image), contentDescription = "原创几何封面：${cover.title}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(12.dp))
                                    .drawWithContent {
                                        translate(left = sin(phase.value / 96 * 6.283f) * 8.dp.toPx()) {
                                            this@drawWithContent.drawContent()
                                        }
                                    })
                            Text(cover.title, color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(cover.note, color = palette.muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyDock(
    source: GraphicsLayer, host: LayoutCoordinates?, palette: GlassPalette, dark: Boolean,
    raw: Boolean, visible: Boolean, filtered: GraphicsLayer?, filteredOrigin: Offset, tint: Boolean,
    height: androidx.compose.ui.unit.Dp, selectedTab: Int,
    select: (Int) -> Unit, toggle: () -> Unit, observeContentChanges: () -> Unit,
    positioned: (LayoutCoordinates) -> Unit,
) {
    BoxWithConstraints(Modifier.widthIn(max = 350.dp).fillMaxWidth().padding(horizontal = 16.dp).height(height)
        .testTag("study-dock").onGloballyPositioned(positioned)) {
        val podWidth = maxWidth - height - 12.dp
        val circleX by animateDpAsState(if (dark) maxWidth - height else 0.dp,
            spring(GlassTokens.CircleDamping, GlassTokens.CircleStiffness), label = "circle-position")
        val podX by animateDpAsState(if (dark) 0.dp else height + 12.dp,
            spring(GlassTokens.PodDamping, GlassTokens.PodStiffness), label = "pod-position")
        Box(Modifier.offset { IntOffset(circleX.roundToPx(), 0) }.size(height)
            .testTag("glass-circle").glassSample(source, host, palette, raw, visible, filtered, filteredOrigin, tint, observeContentChanges)
            .semantics { contentDescription = "切换样图明暗"; role = Role.Button }
            .clickable(onClick = toggle), contentAlignment = Alignment.Center) {
            DockIcon(if (dark) 4 else 3, palette.foreground, Modifier.size(26.dp))
        }
        Row(Modifier.offset { IntOffset(podX.roundToPx(), 0) }.width(podWidth).height(height)
            .testTag("glass-pod").glassSample(source, host, palette, raw, visible, filtered, filteredOrigin, tint, observeContentChanges)
            .padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("搜索", "书库", "推荐").forEachIndexed { index, label ->
                Column(Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(50)).background(if (selectedTab == index) palette.selected else Color.Transparent)
                    .semantics { selected = selectedTab == index; role = Role.Tab }
                    .clickable { select(index) }.testTag("study-tab-$index"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    DockIcon(index, palette.foreground, Modifier.size(20.dp))
                    Text(label, color = palette.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp, letterSpacing = 0.sp, textAlign = TextAlign.Center,
                        maxLines = 1, modifier = Modifier.fillMaxWidth().testTag("study-label-$index"))
                }
            }
        }
    }
}

@Composable
private fun DockIcon(kind: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = 1.7.dp.toPx()
        when (kind) {
            0 -> {
                drawCircle(color, w * .25f, Offset(w * .4f, w * .4f), style = Stroke(stroke))
                drawLine(color, Offset(w * .6f, w * .6f), Offset(w * .88f, w * .88f), stroke, StrokeCap.Round)
            }
            1 -> {
                for (x in listOf(.18f, .42f, .7f)) drawLine(color, Offset(w * x, w * .18f), Offset(w * x, w * .82f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .1f, w * .9f), Offset(w * .9f, w * .9f), stroke, StrokeCap.Round)
            }
            2 -> {
                drawCircle(color, w * .34f, center, style = Stroke(stroke))
                drawLine(color, Offset(w * .35f, w * .65f), Offset(w * .65f, w * .35f), stroke, StrokeCap.Round)
            }
            3 -> {
                drawCircle(color, w * .2f, center, style = Stroke(stroke))
                for (i in 0..7) {
                    val angle = i * Math.PI / 4
                    val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                    drawLine(color, center + direction * (w * .33f), center + direction * (w * .45f), stroke, StrokeCap.Round)
                }
            }
            4 -> drawArc(color, 45f, 270f, false, Offset(w * .17f, w * .17f), Size(w * .66f, w * .66f), style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

