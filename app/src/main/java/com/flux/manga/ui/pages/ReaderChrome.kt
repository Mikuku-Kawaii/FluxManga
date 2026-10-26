package com.flux.manga.ui.pages

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.data.ReadingMode
import kotlin.math.roundToInt

internal val LocalReaderTap = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalReaderChromeVisible = staticCompositionLocalOf { true }

@Composable
internal fun ReaderImmersive(showChrome: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(showChrome)
    val controller = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    fun apply() {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (latest) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(window, owner) {
        val oldBehavior = controller.systemBarsBehavior
        val before = ViewCompat.getRootWindowInsets(window.decorView)
        val statusVisible = before?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationVisible = before?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        fun restore() {
            controller.systemBarsBehavior = oldBehavior
            if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply()
            if (event == Lifecycle.Event.ON_PAUSE) restore()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); restore() }
    }
    LaunchedEffect(window, owner, showChrome) {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) apply()
    }
}

@Composable
internal fun ReaderCanvas(modifier: Modifier = Modifier, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    val tap by rememberUpdatedState(LocalReaderTap.current)
    BoxWithConstraints(modifier.pointerInput(Unit) { detectTapGestures(onTap = { tap?.invoke() }) }, content = content)
}

@Composable
internal fun ReaderTopBar(title: String, mode: ReadingMode, enabled: Boolean, leaving: Boolean,
    back: () -> Unit, selectMode: (ReadingMode) -> Unit, showTool: (ReaderTool) -> Unit,
    hide: () -> Unit, local: Boolean = false, bookmarks: (() -> Unit)? = null, chapters: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var fullTitle by remember { mutableStateOf(false) }
    if (fullTitle) ReferenceDialog(onDismissRequest = { fullTitle = false }, title = { Text("当前作品") },
        text = { Text(title, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton({ fullTitle = false }) { Text("关闭") } })
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(back, enabled = !leaving, modifier = Modifier.testTag(if (local) "reader-back" else "online-reader-back")
                .semantics { contentDescription = "返回" }) { SettingsChevron(back = true) }
            TextButton({ fullTitle = true }, Modifier.weight(1f), shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                Text(title, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            }
            Box {
                ReferenceActionsButton("阅读设置与阅读方式", { menu = true },
                    Modifier.testTag(if (local) "reader-tools" else "online-reader-tools"), enabled = !leaving)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("阅读方式 · ${mode.label}") }, enabled = enabled,
                        onClick = { menu = false; showTool(ReaderTool.MODE) })
                    DropdownMenuItem(text = { Text("跳转页码") }, enabled = enabled, onClick = { menu = false; showTool(ReaderTool.PAGE) })
                    chapters?.let { DropdownMenuItem(text = { Text("章节目录") }, enabled = enabled, onClick = { menu = false; it() }) }
                    bookmarks?.let { DropdownMenuItem(text = { Text("书签与备注") }, enabled = enabled,
                        modifier = Modifier.testTag("reader-bookmarks"), onClick = { menu = false; it() }) }
                    DropdownMenuItem(text = { Text("阅读选项") }, enabled = enabled, onClick = { menu = false; showTool(ReaderTool.OPTIONS) })
                    DropdownMenuItem(text = { Text("隐藏工具") }, onClick = { menu = false; hide() })
                }
            }
        }
    }
}

/** Preview while dragging; issue one existing position command when released. */
@Composable
internal fun ReaderSeekBar(identity: Any, page: Int, total: Int, enabled: Boolean, jump: (Int) -> Unit,
    previous: () -> Unit, next: () -> Unit, canPrevious: Boolean, canNext: Boolean,
    showPage: () -> Unit, local: Boolean = false, feedback: @Composable ColumnScope.() -> Unit = {}) {
    var preview by remember(identity, total) { mutableStateOf<Float?>(null) }
    val last = (total - 1).coerceAtLeast(0)
    val current = (preview ?: page.toFloat()).coerceIn(0f, last.toFloat())
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 4.dp)) {
            TextButton(showPage, enabled = enabled, modifier = Modifier.align(Alignment.CenterHorizontally)
                .testTag(if (local) "reader-position" else "online-reader-position")) { Text("${current.roundToInt() + 1} / $total") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(previous, enabled = enabled && canPrevious, modifier = Modifier.testTag(if (local) "reader-prev" else "online-reader-prev")
                    .semantics { contentDescription = "上一页" }) { ReferenceIcon(ReferenceSymbol.PREVIOUS) }
                Slider(current, { preview = it }, Modifier.weight(1f).testTag(if (local) "reader-page-slider" else "online-reader-page-slider")
                    .semantics { contentDescription = "拖动跳转页码" }, enabled = enabled && total > 1,
                    valueRange = 0f..last.coerceAtLeast(1).toFloat(), onValueChangeFinished = {
                        preview?.let { if (enabled) jump(it.roundToInt().coerceIn(0, last)) }; preview = null
                    })
                IconButton(next, enabled = enabled && canNext, modifier = Modifier.testTag(if (local) "reader-next" else "online-reader-next")
                    .semantics { contentDescription = "下一页" }) { ReferenceIcon(ReferenceSymbol.NEXT) }
            }
            Column(Modifier.heightIn(max = 128.dp).verticalScroll(rememberScrollState())) { feedback() }
        }
    }
}
