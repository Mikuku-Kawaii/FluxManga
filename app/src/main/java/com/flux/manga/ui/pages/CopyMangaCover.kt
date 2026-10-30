package com.flux.manga.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.core.SourceException
import com.flux.manga.data.OnlineSourceCovers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

@Composable
internal fun CopyMangaCover(covers: OnlineSourceCovers, id: String, fillWidth: Boolean = false) {
    val revision by covers.coverRevision.collectAsState()
    var bitmap by remember(covers, id, revision) { mutableStateOf(covers.cached(id)) }
    var missing by remember(covers, id) { mutableStateOf(false) }
    var failed by remember(covers, id) { mutableStateOf(false) }
    var attempt by remember(covers, id) { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visibility by covers.visibility.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && visibility.foreground &&
        visibility.phase == com.flux.manga.session.SwitchPhase.Stable
    LaunchedEffect(covers, id, active, visibility.visibilityEpoch, attempt, revision) {
        if (!active || bitmap != null) return@LaunchedEffect
        failed = false; missing = false
        if (active) try { bitmap = covers.load(id) }
        catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) failed = true
            throw cancelled
        }
        catch (_: SourceException.NotFound) { missing = true }
        catch (_: Exception) { failed = true }
    }
    CoverArtwork(bitmap, active && bitmap == null && !missing && !failed,
        if (missing) "暂无封面" else if (failed) "封面加载失败" else if (active) "正在加载封面" else "封面",
        Modifier.then(if (fillWidth) Modifier else Modifier.widthIn(max = 112.dp)).fillMaxWidth().aspectRatio(.7f),
        retry = if (failed && active) ({ covers.retry(id); attempt++ }) else null)
}
