package com.flux.manga.ui.probe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.core.ProbeException
import com.flux.manga.core.ProbeRequest
import com.flux.manga.core.ProbeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FfiProbeState(val running: Boolean = false, val message: String = "等待验证")

class FfiProbeViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(FfiProbeState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var generation = 0

    fun start(delayMs: UInt, fail: Boolean) {
        task?.cancel()
        val request = ++generation
        mutableState.value = FfiProbeState(true, "Rust 异步处理中…")
        task = viewModelScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    // Destroy the native session inside this context even on cancellation.
                    // A session never crosses a cancellable withContext return boundary.
                    val session = ProbeSession()
                    try { session.run(ProbeRequest("ui-$request", delayMs, fail)) }
                    finally { session.destroy() }
                }
                ensureActive()
                if (request == generation) {
                    mutableState.value = FfiProbeState(message = "成功：${reply.message} · ${reply.requestId}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProbeException) {
                if (request == generation) {
                    mutableState.value = FfiProbeState(message = "失败：${failure.message}")
                }
            }
        }
    }

    fun cancel() {
        generation++
        task?.cancel()
        mutableState.value = FfiProbeState(message = "已取消")
    }
}
