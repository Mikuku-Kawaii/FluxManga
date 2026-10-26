package com.flux.manga.data

import android.content.*
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DownloadBackgroundState(val starting: Boolean = false, val running: Boolean = false,
    val message: String? = null)

/** Owned by WorldSession, bound using Application Context; never creates another source session. */
internal class DownloadBackgroundConnection(context: Context, private val scope: CoroutineScope,
    private val controller: DownloadController, private val valid: () -> Boolean,
    private val foreground: () -> Boolean) {
    private val app = context.applicationContext
    private val lease = DownloadBackgroundLease()
    private val mutable = MutableStateFlow(DownloadBackgroundState())
    val state = mutable.asStateFlow()
    val running get() = lease.running
    private var connection: ServiceConnection? = null
    private var binder: ChapterDownloadService.LocalBinder? = null
    private var deadline: Job? = null

    fun enable() {
        if (!valid() || !foreground() || connection != null) return
        if (!ChapterDownloadService.notificationsAllowed(app)) {
            mutable.value = DownloadBackgroundState(message = "请先允许下载进度通知；前台下载仍可使用")
            return
        }
        if (!downloadNotice(controller.state.value).working) {
            mutable.value = DownloadBackgroundState(message = "请先加入或继续下载任务，再开启后台下载")
            return
        }
        val ticket = lease.begin()
        mutable.value = DownloadBackgroundState(starting = true)
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (connection !== this) return
                val local = service as? ChapterDownloadService.LocalBinder
                if (local == null || !lease.activate(ticket, valid() && foreground())) {
                    stop("未完成后台交接，任务保留，请在前台重试")
                    return
                }
                binder = local
                val attached = local.attach(controller, { valid() && lease.accepts(ticket) }) { reason ->
                    if (connection === this) release(reason)
                }
                if (!attached) { stop("后台服务未就绪，请重试"); return }
                if (connection !== this || !lease.accepts(ticket)) return
                deadline?.cancel()
                mutable.value = DownloadBackgroundState(running = true)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection === this) stop("后台服务已断开，任务待核对后继续")
            }
            override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
        }
        connection = next
        try {
            app.startForegroundService(Intent(app, ChapterDownloadService::class.java).setAction(ChapterDownloadService.START))
            if (!app.bindService(Intent(app, ChapterDownloadService::class.java), next, Context.BIND_AUTO_CREATE)) {
                stop("无法绑定后台服务，任务保留，可重试")
                return
            }
            deadline = scope.launch {
                delay(5000)
                if (connection === next && !running) stop("后台交接超时，任务保留，可重试")
            }
        } catch (_: RuntimeException) { stop("系统暂不允许后台下载，任务保留，请在前台重试") }
    }

    /** Stops work before dropping the background grant. Does not remove queue records or files. */
    fun stop(message: String? = "后台下载已停止，返回队列核对并继续") {
        controller.pauseAll()
        lease.revoke()
        val old = binder
        release(message)
        old?.stop()
        app.stopService(Intent(app, ChapterDownloadService::class.java))
        ChapterDownloadService.clearNotifications(app)
    }

    private fun release(message: String?) {
        lease.revoke()
        deadline?.cancel(); deadline = null
        binder = null
        val old = connection; connection = null
        if (old != null) try { app.unbindService(old) } catch (_: IllegalArgumentException) { }
        mutable.value = DownloadBackgroundState(message = message)
    }
}
