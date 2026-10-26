package com.flux.manga.data

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import com.flux.manga.MainActivity
import kotlinx.coroutines.*
import java.util.UUID

/** A started + locally bound foreground host. Queue and credentials remain owned by WorldSession. */
internal class ChapterDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private var owner: DownloadController? = null
    private var ended: ((String) -> Unit)? = null
    private var active: () -> Boolean = { false }
    private var ready = false
    private var finished = false
    private val token = UUID.randomUUID().toString()
    private val manager get() = getSystemService(NotificationManager::class.java)

    inner class LocalBinder : Binder() {
        fun attach(controller: DownloadController, valid: () -> Boolean, onEnd: (String) -> Unit): Boolean {
            if (!ready || finished || owner != null) return false
            owner = controller; active = valid; ended = onEnd
            updates = scope.launch {
                controller.state.collect { state ->
                    if (!active()) { finish("会话已结束"); return@collect }
                    if (!notificationsAllowed(this@ChapterDownloadService)) {
                        finish("下载通知已关闭，任务待核对后继续"); return@collect
                    }
                    val notice = downloadNotice(state)
                    if (!notice.working) { finish(notice.text, notice.text); return@collect }
                    try { manager.notify(PROGRESS_ID, notification(notice)) }
                    catch (_: RuntimeException) { finish("通知不可用，下载待继续"); return@collect }
                    delay(500) // StateFlow conflates page checkpoints; no per-byte notifications.
                }
            }
            return !finished
        }
        fun stop() = finish("后台下载已停止")
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            if (intent.data?.lastPathSegment == token) finish("下载已暂停，返回队列核对并继续", "下载已暂停，返回队列继续")
            else if (!ready) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START || finished) { stopSelf(startId); return START_NOT_STICKY }
        try {
            createChannel(this)
            manager.cancel(RESULT_ID)
            startForeground(PROGRESS_ID, notification(DownloadNotice("正在连接下载队列", working = true)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            ready = true
            // An interrupted UI handoff must never leave an unowned service running.
            scope.launch { delay(5000); if (owner == null) finish("后台交接未完成") }
        } catch (_: RuntimeException) { finish("系统未允许启动后台下载") }
        return START_NOT_STICKY
    }

    private fun notification(notice: DownloadNotice, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 501,
            Intent(this, MainActivity::class.java).setAction(OPEN_QUEUE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FluxManga 下载")
            .setContentText(notice.text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(notice.total, notice.saved, ongoing && notice.total == 0)
            .apply {
                if (ongoing) addAction(Notification.Action.Builder(null, "暂停下载",
                    PendingIntent.getService(this@ChapterDownloadService, 502,
                        Intent(this@ChapterDownloadService, ChapterDownloadService::class.java).setAction(PAUSE)
                            .setData(Uri.parse("flux-download://pause/$token")),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            }.build()
    }

    private fun finish(reason: String, result: String? = null) {
        if (finished) return
        finished = true; ready = false
        owner?.pauseAll(); owner = null
        updates?.cancel(); updates = null
        active = { false }
        val callback = ended; ended = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        callback?.invoke(reason)
        if (result != null && notificationsAllowed(this)) {
            try { manager.notify(RESULT_ID, notification(DownloadNotice(result), ongoing = false)) } catch (_: RuntimeException) { }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) = finish("系统后台时限已到，返回队列继续", "后台时限已到，任务保留")
    override fun onTaskRemoved(rootIntent: Intent?) = finish("应用任务已移除，下载待继续")
    override fun onDestroy() { finish("后台服务已结束，下载待继续"); scope.cancel(); super.onDestroy() }

    companion object {
        const val START = "com.flux.manga.download.START"
        private const val PAUSE = "com.flux.manga.download.PAUSE"
        const val OPEN_QUEUE = "com.flux.manga.download.OPEN_QUEUE"
        const val CHANNEL = "chapter-downloads"
        private const val PROGRESS_ID = 5101
        private const val RESULT_ID = 5102
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "章节下载", NotificationManager.IMPORTANCE_LOW))
        }
        fun notificationsAllowed(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun clearNotifications(context: Context) {
            context.getSystemService(NotificationManager::class.java).apply { cancel(PROGRESS_ID); cancel(RESULT_ID) }
        }
    }
}
