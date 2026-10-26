package com.flux.manga.ui.pages

import android.app.Application
import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.SwitchPhase
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID

internal val LocalReadingTime = staticCompositionLocalOf<ReadingTimeModel?> { null }

/** Main-thread owner. No Activity, pixels, paths, credentials or serialized timer state. */
internal class ReadingTimeModel(app: Application, private val session: WorldSession,
    private val kind: String, private val source: String, private val manga: String,
    store: WorldDataStore = WorldDataStore(app)) : ViewModel() {
    private val identity = session.identity.value
    private val repository = ReadingStatistics(store, session)
    private val clock = ReadingClock()
    private val id = UUID.randomUUID().toString()
    private val images = mutableSetOf<Any>()
    private var gate = false
    private var title = "在线作品"
    private var saved = emptyMap<String, Long>()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    val error = MutableStateFlow(false)
    private val ticker = writer.launch {
        while (isActive) { delay(10_000); checkpoint(); save() }
    }

    private fun checkpoint() {
        val visible = gate && images.isNotEmpty() && session.accepts(identity) &&
            session.state.value.foreground && session.state.value.phase == SwitchPhase.Stable
        clock.update(visible, SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault())
    }
    fun gate(active: Boolean, name: String?) {
        name?.takeIf { it.isNotBlank() }?.let { title = it }
        if (gate == active) return
        gate = active
        checkpoint()
        if (!active) save()
    }
    fun image(token: Any, visible: Boolean) {
        val changed = if (visible) images.add(token) else images.remove(token)
        if (changed) checkpoint()
    }
    fun retry() { checkpoint(); save() }
    private fun save(): Job {
        val snapshot = clock.snapshot()
        val name = title
        return writer.launch {
            lock.withLock {
                if (snapshot.isEmpty() || snapshot.all { (day, time) -> (saved[day] ?: 0) >= time }) return@withLock
                try {
                    repository.save(snapshot.map { (day, time) -> ReadingTimeRow(id, day, kind, source, manga, name, time) })
                    saved = snapshot
                    error.value = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error.value = true }
            }
        }
    }
    override fun onCleared() {
        ticker.cancel()
        gate = false; checkpoint()
        save().invokeOnCompletion { writer.cancel() }
    }
}

@Composable
internal fun rememberReadingTime(session: WorldSession, kind: String, source: String, manga: String,
    ready: Boolean, title: String? = null): ReadingTimeModel {
    val app = LocalContext.current.applicationContext as Application
    val model: ReadingTimeModel = viewModel(key = "reading-time:$kind:$source:$manga", factory = viewModelFactory {
        initializer { ReadingTimeModel(app, session, kind, source, manga) }
    })
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val visibility by session.state.collectAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    val active = ready && focused && lifecycle == Lifecycle.State.RESUMED &&
        visibility.foreground && visibility.phase == SwitchPhase.Stable
    val eligible by rememberUpdatedState(ready && focused && visibility.foreground && visibility.phase == SwitchPhase.Stable)
    val currentTitle by rememberUpdatedState(title)
    // Pause synchronously: a background Activity need not draw another Compose frame.
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> model.gate(false, null)
                Lifecycle.Event.ON_RESUME -> model.gate(eligible, currentTitle)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.gate(false, null) }
    }
    DisposableEffect(model, active) {
        model.gate(active, title)
        onDispose { model.gate(false, null) }
    }
    return model
}

/** Register only images actually in the viewport, never offscreen pager/strip prefetch. */
@Composable
internal fun ReadingImageVisibility(visible: Boolean) {
    val owner = LocalReadingTime.current
    val token = remember(owner) { Any() }
    DisposableEffect(owner, token, visible) {
        owner?.image(token, visible)
        onDispose { owner?.image(token, false) }
    }
}

@Composable
internal fun ReadingTimeFeedback(model: ReadingTimeModel?) {
    if (model == null) return
    val failed by model.error.collectAsState()
    if (failed) TextButton(onClick = feedbackAttempt(model::retry)) { Text("阅读统计未保存 · 重试（不影响阅读进度）") }
}
