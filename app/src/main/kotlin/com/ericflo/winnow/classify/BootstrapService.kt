package com.ericflo.winnow.classify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.ericflo.winnow.R
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a backlog run (see [Bootstrap]) going when the user leaves Winnow or the screen goes
 * off: thousands of requests take minutes, and a background app's network is cut and its
 * process killed long before then. A foreground service of the data-sync kind, started from
 * the user's own "Start", with its progress and a Stop button in a notification, and a
 * partial wake lock so the requests keep going with the screen off. It stops itself when the
 * run ends, and leaves a notification saying how it went.
 */
class BootstrapService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private val bootstrap get() = (application as WinnowApp).container.bootstrap

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            bootstrap.stop()
            return START_NOT_STICKY
        }
        createChannel(this)
        startForeground(NOTIFICATION_RUNNING, progress(0, 0, null, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "winnow:bootstrap").apply {
                setReferenceCounted(false)
                acquire(MAX_RUN_MILLIS)
            }
        }
        bootstrap.start()
        scope.launch {
            val provider = runCatching { (application as WinnowApp).container.settings.current().provider.label }.getOrDefault("The service")
            bootstrap.status.collectLatest { status ->
                when (status) {
                    is BootstrapStatus.Running -> {
                        // A new run: the last one's "how it went" is old news.
                        if (status.done == 0) clearFinished(this@BootstrapService)
                        notify(
                        NOTIFICATION_RUNNING,
                            progress(status.done, status.total, status.tally, status.pausedFor?.let { Bootstrap.pauseText(provider, status.trouble, it) }, status.runId),
                        )
                    }
                    is BootstrapStatus.Finished -> {
                        notify(NOTIFICATION_DONE, finished(status))
                        stopSelf()
                    }
                    BootstrapStatus.Idle -> Unit
                }
            }
        }
        // A run the system ends isn't restarted unasked: the next one is the user's to start.
        return START_NOT_STICKY
    }

    /** Android 15 ends a data-sync service after its daily allowance; the run stops cleanly and can resume. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        bootstrap.stop()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notify(id: Int, notification: Notification) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }

    /** The run's results (see RunScreen), or Train Winnow if it has no record. */
    private fun openRun(runId: Long): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setAction(if (runId != 0L) MainActivity.ACTION_OPEN_RUN else MainActivity.ACTION_OPEN_TRAIN)
            .putExtra(MainActivity.EXTRA_RUN_ID, runId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(done: Int, total: Int, tally: Bootstrap.Tally?, paused: String?, runId: Long = 0): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, BootstrapService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Labeling your backlog")
            .setContentText(paused ?: if (total == 0) "Starting…" else "$done of $total texts" + (tally?.let { " · ${it.labeled} labeled" } ?: ""))
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openRun(runId))
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun finished(status: BootstrapStatus.Finished): Notification {
        val t = status.tally
        val text = (if (status.stopped) "Stopped. " else "") + "${t.labeled} texts labeled" +
            (if (t.failed > 0) ", ${t.failed} to try again" else "") + (status.error?.let { ". $it" } ?: "")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (status.stopped) "Backlog labeling stopped" else "Backlog labeled")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text + "\nTap to see what it said."))
            .setAutoCancel(true)
            // Straight to what it said of each text, which stays in Train Winnow after this is gone.
            .setContentIntent(openRun(status.runId))
            .build()
    }

    companion object {
        // A run's progress and how it went are no reason for a badge on the app icon (see createChannel).
        private const val CHANNEL = "background_quiet"
        /** Where work going on in the background shows its progress, without a badge (WorkService's too). */
        const val QUIET_CHANNEL = CHANNEL
        private const val OLD_CHANNEL = "background"
        private const val NOTIFICATION_RUNNING = 7001
        private const val NOTIFICATION_DONE = 7002
        private const val ACTION_STOP = "com.ericflo.winnow.STOP_BOOTSTRAP"
        /** A wake lock is never held longer than this, whatever happens. */
        private const val MAX_RUN_MILLIS = 2 * 60 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BootstrapService::class.java))
        }

        /**
         * Takes down the notification saying how a run went, once the user has seen that in
         * Winnow itself (its results, or its card in Train Winnow), or a new run has started.
         */
        fun clearFinished(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_DONE) }
        }

        internal fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_background), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_background_description)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(channel)
                // The first channel badged, and a channel's badge can't be changed once made.
                deleteNotificationChannel(OLD_CHANNEL)
            }
        }
    }
}
