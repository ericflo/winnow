package com.ericflo.winnow.classify

import android.app.ActivityManager
import android.app.Notification
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps work the user started going when they leave Winnow or the screen goes off: training a
 * Lab model on their labels (see [ModelLab]), the examples test, asking the classifier service
 * about their labeled texts (see [ExamplesExperiment]), scoring models on their labels (see
 * Evaluations), and checking older conversations (see [HistoryReviewer]). Each can take minutes, and Android freezes an app in the background within about a minute, and may end it,
 * losing the run (and, for the test, requests already paid for). A foreground service of the
 * data-sync kind (local processing, and requests to the service), with the work's progress
 * and a Stop button in a notification, and a partial wake lock so it keeps going with the
 * screen off. It stops itself when the work ends; if Winnow isn't showing then, a notification
 * says how it went, and goes once the Model screen's tab for it is seen.
 */
class WorkService : Service() {
    /** Everything this keeps going, as it stands. */
    private data class Work(val lab: ModelLab.Status, val test: ExperimentStatus, val scoring: String?, val review: ReviewStatus)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    /** Watching the work: once, however many times the service is started (training and a test both). */
    private var watcher: kotlinx.coroutines.Job? = null
    private val container get() = (application as WinnowApp).container
    private val lab get() = container.modelLab
    private val experiment get() = container.examplesExperiment
    private val evaluations get() = container.evaluations
    private val reviewer get() = container.historyReviewer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lab.cancel()
            experiment.stop()
            evaluations.cancel()
            reviewer.stop()
            return START_NOT_STICKY
        }
        BootstrapService.createChannel(this)
        startForeground(NOTIFICATION_RUNNING, progress("Working on Winnow's model", "Starting…", 0f, LAB), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "winnow:model-work").apply {
                setReferenceCounted(false)
                acquire(MAX_RUN_MILLIS)
            }
        }
        if (watcher?.isActive == true) return START_NOT_STICKY
        watcher = scope.launch {
            var training: String? = null
            // When it was last trained before this: a training that ends with a new time finished.
            var trainedBefore: Long? = null
            var testing = false
            var scoring = false
            var scoringFinishedBefore: Long? = null
            var reviewing = false
            combine(lab.status, experiment.status, evaluations.progress, reviewer.status, ::Work).collectLatest { (l, e, scored, r) ->
                // Training: how it ended, when it has.
                if (l is ModelLab.Status.Running && training == null) {
                    clearFinished(this@WorkService, LAB)
                    training = l.id
                    trainedBefore = lab.entries.value.firstOrNull { it.id == l.id }?.trainedAt
                } else if (l !is ModelLab.Status.Running && training != null) {
                    val entry = lab.entries.value.firstOrNull { it.id == training }
                    if (!visible()) when {
                        l is ModelLab.Status.Failed -> notify(NOTIFICATION_LAB_DONE, finished("Training ${entry?.name ?: "a model"} stopped", l.why, LAB))
                        // Done, or stopped by the user (who saw it stop, and needs no word of it).
                        entry?.trainedAt != null && entry.trainedAt != trainedBefore -> {
                            val score = entry.accuracy?.let { "${(it * 100).roundToInt()}% on your labels" + if (entry.scoredOn > 0) " (${entry.scoredOn})" else "" }
                            notify(NOTIFICATION_LAB_DONE, finished("${entry.name} is trained", score ?: "Trained on everything, ready to use.", LAB))
                        }
                    }
                    training = null
                }
                // The examples test: how it ended, when it has.
                if (e is ExperimentStatus.Running && !testing) {
                    clearFinished(this@WorkService, EVALUATE)
                    testing = true
                } else if (e !is ExperimentStatus.Running && testing) {
                    if (e is ExperimentStatus.Finished && !e.stopped && !visible()) notify(NOTIFICATION_TEST_DONE, tested(e))
                    testing = false
                }
                // Scoring on the Evaluate tab: how it ended, when it has.
                if (scored != null && !scoring) {
                    clearFinished(this@WorkService, EVALUATE)
                    scoring = true
                    scoringFinishedBefore = evaluations.finishedAt.value
                } else if (scored == null && scoring) {
                    val error = evaluations.error.value
                    val finished = evaluations.finishedAt.value.let { it != null && it != scoringFinishedBefore }
                    if (!visible() && (error != null || finished)) {
                        notify(NOTIFICATION_TEST_DONE, finished(if (error != null) "Scoring stopped" else "Your models are scored", error ?: "On your labels: see how each did.", EVALUATE))
                    }
                    scoring = false
                }
                // Checking older conversations: how it ended, when it has.
                if (r is ReviewStatus.Running && !reviewing) {
                    clearFinished(this@WorkService, INBOX)
                    reviewing = true
                } else if (r !is ReviewStatus.Running && reviewing) {
                    if (r is ReviewStatus.Finished && !visible()) {
                        val text = "Checked ${r.reviewed}: ${r.filtered} filtered, ${r.silenced} silenced." +
                            if (r.unreached > 0) " ${r.unreached} couldn't be checked; they wait for next time." else ""
                        notify(NOTIFICATION_REVIEW_DONE, finished("Older conversations checked", text, INBOX))
                    }
                    reviewing = false
                }
                when {
                    l is ModelLab.Status.Running -> notify(NOTIFICATION_RUNNING, progress("Training ${nameOf(l.id) ?: "a model"}", l.what, l.progress, LAB))
                    e is ExperimentStatus.Running -> notify(
                        NOTIFICATION_RUNNING,
                        progress(
                            "Testing your labels as examples",
                            e.waiting ?: if (e.total == 0) "Starting…" else "${e.done} of ${e.total} texts asked",
                            if (e.total == 0) 0f else e.done / e.total.toFloat(),
                            EVALUATE,
                        ),
                    )
                    scored != null -> notify(NOTIFICATION_RUNNING, progress("Scoring on your labels", scored, 0f, EVALUATE))
                    r is ReviewStatus.Running -> notify(
                        NOTIFICATION_RUNNING,
                        progress(
                            "Checking older conversations",
                            if (r.total == 0) "Starting…" else "${r.done} of ${r.total}",
                            if (r.total == 0) 0f else r.done / r.total.toFloat(),
                            INBOX,
                        ),
                    )
                    else -> stop()
                }
            }
        }
        // Work the system ends isn't restarted unasked: the user starts it again.
        return START_NOT_STICKY
    }

    /** Android 15 ends a data-sync service after its daily allowance: the work stops. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        lab.cancel()
        experiment.stop()
        evaluations.cancel()
        reviewer.stop()
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun nameOf(id: String): String? = lab.entries.value.firstOrNull { it.id == id }?.name

    /** Winnow is on screen: it shows how the work went itself. */
    private fun visible(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun notify(id: Int, notification: Notification) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }

    /** The Model screen, on [tab]; or the inbox, where checking older conversations says how it went. */
    private fun open(tab: String): PendingIntent = PendingIntent.getActivity(
        this, when (tab) { LAB -> 2; INBOX -> 5; else -> 4 },
        Intent(this, MainActivity::class.java)
            .setAction(if (tab == INBOX) MainActivity.ACTION_OPEN_INBOX else MainActivity.ACTION_OPEN_MODEL)
            .putExtra(MainActivity.EXTRA_MODEL_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(title: String, what: String, progress: Float, tab: String): Notification {
        val stop = PendingIntent.getService(this, 3, Intent(this, WorkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, BootstrapService.QUIET_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(what)
            .setProgress(100, (progress * 100).roundToInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(open(tab))
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun finished(title: String, text: String, tab: String): Notification = Notification.Builder(this, BootstrapService.QUIET_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText("$text\nTap to see it${when (tab) { LAB -> " in the Lab"; INBOX -> " in your inbox"; else -> "" }}."))
        .setAutoCancel(true)
        .setContentIntent(open(tab))
        .build()

    private fun tested(e: ExperimentStatus.Finished): Notification {
        val s = e.summary
        val text = e.error ?: if (s.trials == 0) "No text was answered both ways." else
            "${s.trials} texts answered both ways: ${s.plainRight} right as asked plainly, ${s.withRight} with your labels as examples."
        return finished("The examples test is done", text, EVALUATE)
    }

    companion object {
        /** Model screen tabs the notifications open (see ModelRoute). */
        const val LAB = "lab"
        const val EVALUATE = "evaluate"
        /** Not a tab: the inbox, where checking older conversations shows how it went. */
        const val INBOX = "inbox"
        private const val NOTIFICATION_RUNNING = 7003
        private const val NOTIFICATION_LAB_DONE = 7004
        private const val NOTIFICATION_TEST_DONE = 7005
        private const val NOTIFICATION_REVIEW_DONE = 7006
        private const val ACTION_STOP = "com.ericflo.winnow.STOP_MODEL_WORK"
        /** A wake lock is never held longer than this, whatever happens. */
        private const val MAX_RUN_MILLIS = 60 * 60_000L

        /**
         * Keeps the work going in the background. Android may refuse when Winnow isn't on screen
         * (a retrain after a backlog run, say): it goes ahead anyway, as long as Android lets it.
         */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WorkService::class.java)) }
        }

        /** Takes down the notification saying how the work shown on [tab] went, once that tab has been seen. */
        fun clearFinished(context: Context, tab: String) {
            val id = when (tab) {
                LAB -> NOTIFICATION_LAB_DONE
                EVALUATE -> NOTIFICATION_TEST_DONE
                INBOX -> NOTIFICATION_REVIEW_DONE
                else -> return
            }
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(id) }
        }
    }
}
