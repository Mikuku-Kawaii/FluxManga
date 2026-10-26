package com.flux.manga.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import com.flux.manga.ui.glass.WorldColors

/** Transforms displayed pixels only; never decodes a larger bitmap just to zoom. */
@Composable
internal fun ReaderZoomBox(identity: Any, doubleTap: Boolean, modifier: Modifier = Modifier,
    active: Boolean = true, imageWidth: Int? = null, imageHeight: Int? = null,
    buttonTag: String = "reader-zoom", changed: (Boolean) -> Unit = {}, content: @Composable (Boolean) -> Unit) {
    val tap by rememberUpdatedState(LocalReaderTap.current)
    val chromeVisible = LocalReaderChromeVisible.current
    var transform by remember(identity) { mutableStateOf(ReaderTransform()) }
    var size by remember(identity) { mutableStateOf(IntSize.Zero) }
    fun clamp(scale: Float, x: Float, y: Float) = readerTransform(scale, x, y, size.width.toFloat(), size.height.toFloat(),
        imageWidth?.toFloat() ?: size.width.toFloat(), imageHeight?.toFloat() ?: size.height.toFloat())
    fun toggle(x: Float = size.width / 2f, y: Float = size.height / 2f) {
        transform = if (transform.scale > 1f) ReaderTransform() else clamp(2f, size.width / 2f - x, size.height / 2f - y)
    }
    LaunchedEffect(active, size, doubleTap) { if (!active) transform = ReaderTransform() else transform = clamp(transform.scale, transform.x, transform.y) }
    val latestChanged by rememberUpdatedState(changed)
    LaunchedEffect(transform.scale > 1f, active) { if (active) latestChanged(transform.scale > 1f) }
    val gestures = rememberTransformableState { scale, pan, _ ->
        transform = clamp(transform.scale * scale, transform.x + pan.x, transform.y + pan.y)
    }
    Box(modifier.clipToBounds().onSizeChanged { size = it }) {
        Box(Modifier.fillMaxSize().transformable(gestures, canPan = { transform.scale > 1f }, enabled = active)
            .pointerInput(identity, active, doubleTap) {
                if (active) detectTapGestures(onTap = { tap?.invoke() }, onDoubleTap = if (doubleTap) ({ toggle(it.x, it.y) }) else null)
            }.semantics {
                stateDescription = if (transform.scale > 1f) "已放大" else "原始比例"
                customActions = listOf(CustomAccessibilityAction(if (transform.scale > 1f) "还原" else "放大") { toggle(); true })
            }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = transform.scale; scaleY = transform.scale; translationX = transform.x; translationY = transform.y
            }) { content(transform.scale > 1f) }
        }
        if (chromeVisible) TextButton(onClick = { toggle() }, enabled = active, modifier = Modifier.align(Alignment.CenterEnd)
            .background(WorldColors.DarkBackground.copy(alpha = .87f), MaterialTheme.shapes.small).testTag(buttonTag)) {
            Text(if (transform.scale > 1f) "还原" else "放大", color = WorldColors.DarkInk)
        }
    }
}
