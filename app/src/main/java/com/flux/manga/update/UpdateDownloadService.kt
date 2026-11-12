package com.flux.manga.update

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import com.flux.manga.MainActivity
import com.flux.manga.R
import kotlinx.coroutines.*

/** Holds only the public APK transfer; it never owns a world, credentials or reading data. */
internal class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: AppUpdateModel? = null
    private var ended: (() -> Unit)? = null
    private var finished = false
    private var observer: Job? = null
    private val manager get() = getSystemService(NotificationManager::class.java)
    inner class LocalBinder : Binder() {
        fun attach(model: AppUpdateModel, onEnd: () -> Unit): Boolean {
            if (finished || owner != null) return false
            owner = model; ended = onEnd
            observer = scope.launch {
                model.state.collect { state ->
                    val busy = state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.APPLYING, UpdatePhase.VERIFYING)
                    if (!allowed(this@UpdateDownloadService)) { finish(true); return@collect }
                    try { manager.notify(ID, notification(state, busy)) }
                    catch (_: RuntimeException) { finish(true); return@collect }
                    if (!busy) { finish(false, keepNotice = true); return@collect }
                    delay(300)
                }
            }
            return !finished
        }
        fun stop(state: AppUpdateState) {
            val finalState = if (state.busy) state.copy(phase = UpdatePhase.PAUSED, message = "更新已暂停，返回应用继续") else state
            if (!finished && allowed(this@UpdateDownloadService)) {
                try { manager.notify(ID, notification(finalState, false)) } catch (_: RuntimeException) { }
            }
            finish(false, keepNotice = true)
        }
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { finish(true); return START_NOT_STICKY }
        if (finished) { stopSelf(startId); return START_NOT_STICKY }
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "软件更新", NotificationManager.IMPORTANCE_LOW))
            startForeground(ID, notification(AppUpdateState(phase = UpdatePhase.DOWNLOADING), true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            scope.launch { delay(5000); if (owner == null) finish(false) }
        } catch (_: RuntimeException) { finish(true) }
        return START_NOT_STICKY
    }
    private fun notification(state: AppUpdateState, working: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 5201, Intent(this, MainActivity::class.java).setAction(OPEN_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = when (state.phase) {
            UpdatePhase.DOWNLOADING -> "正在下载更新"
            UpdatePhase.APPLYING -> "正在合成更新包"
            UpdatePhase.VERIFYING -> "正在校验更新包"
            UpdatePhase.READY -> "更新包已就绪，点击返回应用安装"
            else -> state.message ?: "更新已暂停，返回应用继续"
        }
        val total = if (state.phase == UpdatePhase.APPLYING) state.artifact?.size ?: 0 else state.transferSize
        val done = if (state.phase == UpdatePhase.APPLYING) state.assembledBytes else state.bytes
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.lucide_download)
            .setContentTitle("FluxManga 软件更新").setContentText(label).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(working).setAutoCancel(!working)
            .setProgress(if (working && total > 0) 100 else 0, if (total > 0) ((done * 100) / total).toInt().coerceIn(0,100) else 0,
                working && (total <= 0 || state.phase == UpdatePhase.VERIFYING))
            .apply { if (working) addAction(Notification.Action.Builder(null, "暂停更新", PendingIntent.getService(
                this@UpdateDownloadService, 5202, Intent(this@UpdateDownloadService, UpdateDownloadService::class.java).setAction(PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()) }.build()
    }
    private fun finish(pause: Boolean, keepNotice: Boolean = false) {
        if (finished) return
        finished = true
        val model = owner; owner = null
        if (pause) model?.pause()
        observer?.cancel(); observer = null
        stopForeground(if (keepNotice) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        val callback = ended; ended = null; callback?.invoke()
        stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) = finish(true)
    override fun onTaskRemoved(rootIntent: Intent?) = finish(true)
    override fun onDestroy() { finish(true); scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "application-updates"
        private const val ID = 5201
        private const val PAUSE = "com.flux.manga.update.PAUSE"
        const val OPEN_UPDATES = "com.flux.manga.update.OPEN"
        fun allowed(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}

internal class UpdateBackgroundConnection(private val app: Context, private val model: AppUpdateModel) {
    @Volatile var running = false; private set
    private var connection: ServiceConnection? = null
    private var binder: UpdateDownloadService.LocalBinder? = null
    suspend fun start(): Boolean {
        if (running) return true
        if (!UpdateDownloadService.allowed(app)) return false
        val ready = CompletableDeferred<Boolean>()
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (connection !== this) return
                val local = service as? UpdateDownloadService.LocalBinder
                binder = local
                running = local?.attach(model) { release() } == true
                ready.complete(running)
            }
            override fun onServiceDisconnected(name: ComponentName?) { model.pause(); release(); ready.complete(false) }
            override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
        }
        connection = next
        try {
            app.startForegroundService(Intent(app, UpdateDownloadService::class.java))
            if (!app.bindService(Intent(app, UpdateDownloadService::class.java), next, Context.BIND_AUTO_CREATE)) { stop(); return false }
            val success = withTimeoutOrNull(5000) { ready.await() } == true
            if (!success) stop()
            return success
        } catch (cancelled: CancellationException) { stop(); throw cancelled }
        catch (_: RuntimeException) { stop(); return false }
    }
    private fun release() {
        running = false; binder = null
        val old = connection; connection = null
        if (old != null) try { app.unbindService(old) } catch (_: IllegalArgumentException) { }
    }
    fun stop() {
        val old = binder
        release(); old?.stop(model.state.value)
        app.stopService(Intent(app, UpdateDownloadService::class.java))
    }
}
