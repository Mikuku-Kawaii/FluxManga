package com.flux.manga.ui.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay

/** Session-local receipts survive recomposition and list recycling, never a world change. */
internal class RequestFeedbackState {
    private val seen = mutableSetOf<String>()
    private var delivered = false
    var notice by mutableStateOf<RequestNotice?>(null)
        private set
    fun show(message: String) {
        if (message.isBlank() || !seen.add(message)) return
        val current = notice
        if (current != null) current.messages.add(message)
        else if (!delivered) { delivered = true; notice = RequestNotice(message) }
    }
    // Explicit refresh/login/retry only. Clearing receipts does not replay old errors.
    fun beginAttempt() { seen.clear(); delivered = false; notice = null }
    fun reset() { seen.clear(); leaveRoute() }
    fun leaveRoute() { notice = null; delivered = false }
    fun dismiss(current: RequestNotice) { if (notice === current) notice = null }
}

internal class RequestNotice(message: String) { val messages = mutableStateListOf(message) }

internal val LocalRequestFeedback = staticCompositionLocalOf<RequestFeedbackState?> { null }
internal val LocalPersistentReadFeedback = staticCompositionLocalOf { false }

@Composable
internal fun feedbackAttempt(action: () -> Unit): () -> Unit {
    val feedback = LocalRequestFeedback.current
    return { feedback?.beginAttempt(); action() }
}

/** A read failure stays visible beside its retry control, including after a toast would expire. */
@Composable
internal fun RequestFeedback(text: String, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip,
    failure: Boolean = true, busy: Boolean = false, persistent: Boolean = LocalPersistentReadFeedback.current) {
    val feedback = LocalRequestFeedback.current
    LaunchedEffect(feedback, text, failure, busy, persistent) { if (failure && !busy && !persistent) feedback?.show(text) }
    if (!failure || (persistent && !busy)) Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = overflow)
}

@Composable
internal fun RequestFeedbackHost(state: RequestFeedbackState, active: Boolean, route: Any?,
    motionEnabled: Boolean, content: @Composable () -> Unit) {
    LaunchedEffect(active, route) { state.leaveRoute() }
    CompositionLocalProvider(LocalRequestFeedback provides state) {
        content()
        val notice = state.notice
        if (active && notice != null) key(notice) {
            val opacity = remember { Animatable(if (motionEnabled) 0f else 1f) }
            var closing by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (motionEnabled) opacity.animateTo(1f, tween(180))
                delay(3_000)
                closing = true
            }
            LaunchedEffect(closing) {
                if (closing) {
                    if (motionEnabled) opacity.animateTo(0f, tween(160))
                    state.dismiss(notice)
                }
            }
            val density = LocalDensity.current
            val top = WindowInsets.statusBars.getTop(density) + with(density) { 8.dp.roundToPx() }
            val position = remember(top) { object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                    IntOffset((windowSize.width - popupContentSize.width) / 2, top)
            } }
            Popup(popupPositionProvider = position, onDismissRequest = { closing = true },
                properties = PopupProperties(focusable = false, dismissOnBackPress = false,
                    dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.Inherit)) {
                Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 560.dp).graphicsLayer {
                    alpha = opacity.value
                    translationY = -12.dp.toPx() * (1f - opacity.value)
                }.semantics { liveRegion = LiveRegionMode.Polite },
                    shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                        Text(notice.messages.joinToString("\n"), Modifier.weight(1f).heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { closing = true }, modifier = Modifier.semantics { contentDescription = "关闭提示" }) {
                            Text("×", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

/** A small status replaces the removed horizontal bars without inventing a percentage. */
@Composable
internal fun RequestLoading() {
    Text("处理中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
