package com.flux.manga.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flux.manga.ui.glass.WorldColors

internal val ReaderBackground = WorldColors.DarkBackground

@Composable
internal fun ReaderLoadingScreen(error: String?, loading: Boolean, back: () -> Unit,
    retry: () -> Unit, cancel: (() -> Unit)? = null, extra: @Composable () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    MaterialTheme(colorScheme = darkColorScheme(background = ReaderBackground, surface = ReaderBackground,
        primary = accent, onBackground = Color.White, onSurface = Color.White)) {
        Box(Modifier.fillMaxSize().background(ReaderBackground).safeDrawingPadding()) {
            TextButton(back, Modifier.align(Alignment.TopStart).padding(horizontal = 12.dp, vertical = 4.dp)) { Text("返回") }
            Column(Modifier.align(Alignment.Center).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("正在加载漫画…", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
                    cancel?.let { TextButton(it) { Text("取消加载") } }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(retry, enabled = !loading) { Text("重试") }
                }
                extra()
            }
        }
    }
}
