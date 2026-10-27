package com.flux.manga.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.core.SourceException
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Presentation only. The caller owns size, source identity, loading and bitmap lifetime. */
@Composable
internal fun CoverArtwork(bitmap: Bitmap?, loading: Boolean, message: String, modifier: Modifier,
    retry: (() -> Unit)? = null, description: String? = null, contentScale: ContentScale = ContentScale.Crop,
    imageModifier: Modifier = Modifier, retryModifier: Modifier = Modifier) {
    if (!loading && bitmap == null && retry != null) RequestFeedback(listOfNotNull(description, message).joinToString(" · "))
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), description,
                modifier = Modifier.fillMaxSize().then(imageModifier), contentScale = contentScale)
            else {
                val compact = maxWidth < 96.dp || maxHeight < 120.dp || LocalDensity.current.fontScale >= 1.5f
                val label = listOfNotNull(description, message.takeIf(String::isNotBlank)).joinToString(" · ")
                if (loading) CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = label })
                else if (compact) {
                    if (retry != null) IconButton(feedbackAttempt(retry), Modifier.then(retryModifier)
                        .semantics { contentDescription = "$label，重试封面" }) { ReferenceIcon(ReferenceSymbol.UPDATE) }
                    else Box(Modifier.semantics { contentDescription = label }) { ReferenceIcon(ReferenceSymbol.BOOK) }
                } else Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (retry != null) "封面暂不可用" else message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (retry != null) TextButton(feedbackAttempt(retry), retryModifier.semantics { contentDescription = "$label，重试封面" }) { Text("重试") }
                }
            }
        }
    }
}

/** Private list and detail covers use the same visibility contract and encrypted source loader. */
@Composable
internal fun PrivateSourceCover(session: WorldSession, source: String, id: String, title: String,
    load: suspend () -> Bitmap) {
    val identity by session.identity.collectAsState()
    val visibility by session.state.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = visibility.foreground && lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
        visibility.phase == com.flux.manga.session.SwitchPhase.Stable
    val cache = remember(session, identity) { session.pageCovers() }
    val app = LocalContext.current.applicationContext
    val savedCovers = remember(session, identity, app) {
        com.flux.manga.data.OnlineBookInformationStore(com.flux.manga.data.WorldDataStore(app), session)
    }
    val revision by cache.revision.collectAsState()
    var bitmap by remember(identity, source, id, visibility.visibilityEpoch, revision) { mutableStateOf(cache.peek(source, id)) }
    var error by remember(identity, source, id) { mutableStateOf<String?>(null) }
    var missing by remember(identity, source, id) { mutableStateOf(false) }
    var attempt by remember(identity, source, id) { mutableIntStateOf(0) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(identity, source, id, active, visibility.visibilityEpoch, attempt, revision) {
        if (!active) { bitmap = null; return@LaunchedEffect }
        if (bitmap != null) return@LaunchedEffect
        error = null; missing = false
        try { bitmap = cache.get(source, id, readCached = { savedCovers.cover(source, id) }) { loader() } }
        catch (cancelled: CancellationException) {
            // A source's Cancel action closes its client while this visible cell can remain mounted.
            if (currentCoroutineContext().isActive) error = "封面请求已取消"
            throw cancelled
        }
        catch (_: SourceException.NotFound) { missing = true }
        catch (_: SourceException.Timeout) { error = "封面加载超时" }
        catch (_: Exception) { error = "封面加载失败" }
    }
    CoverArtwork(bitmap?.takeIf { active }, active && bitmap == null && error == null && !missing,
        if (!active) "封面" else if (missing) "暂无封面" else error ?: "正在加载封面",
        Modifier.fillMaxWidth().aspectRatio(.7f), retry = if (active && error != null) ({ attempt++ }) else null,
        description = "《$title》封面")
}
