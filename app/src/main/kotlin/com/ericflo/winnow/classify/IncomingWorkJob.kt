package com.ericflo.winnow.classify

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.ericflo.winnow.WinnowApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps Winnow running while a text it has stored is still being classified and announced.
 *
 * A receiver finishes as soon as the text is stored, so the next one can come (see
 * IncomingMessageHandler.storeSms); the rest (classifying, maybe asking a service, the
 * notification) runs after, and nothing else keeps the process alive meanwhile. In the
 * background, Android can freeze it, or end it for memory, and the text would be announced
 * late, at the next start. A job running keeps it alive, with no notification of its own.
 *
 * If the process is ended anyway, the job Android brings back for it can wait a long while
 * (seen on Android 16), so a second one, a watchdog with a deadline, is set beside it: it runs
 * within [WATCHDOG_DEADLINE_MILLIS] if nothing cancels it first, Winnow starts, and its start
 * finishes what was left (IncomingMessageHandler.recoverUnfinished). Once everything stored has
 * been said, it's cancelled ([settled]).
 */
class IncomingWorkJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as WinnowApp).container
        container.appScope.launch {
            val incoming = container.incoming
            // Until every text stored is said, and anything left from before is finished; never for long.
            val said = withTimeoutOrNull(MAX_MILLIS) { incoming.idle.first { it } } != null
            // Stuck past that (something failed on its way): left to the next start, not kept up for.
            if (!said) incoming.forgetStale(MAX_MILLIS)
            incoming.settleIfIdle()
            jobFinished(params, false)
            // A text stored just as it ended keeps the next one going.
            if (!incoming.isIdle) schedule(this@IncomingWorkJob)
        }
        return true
    }

    /** Stopped by Android: run again if there's still work. */
    override fun onStopJob(params: JobParameters): Boolean = !(application as WinnowApp).container.incoming.isIdle

    companion object {
        const val JOB_ID = 4203
        const val WATCHDOG_ID = 4204
        private const val WATCHDOG_DEADLINE_MILLIS = 90_000L
        /** A burst of slow answers is still well under this; the job isn't for anything longer. */
        private const val MAX_MILLIS = 3 * 60_000L

        /** Asks for the job, unless it's already waiting or running: expedited, else plain. */
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, IncomingWorkJob::class.java)
            if (jobs.getPendingJob(WATCHDOG_ID) == null) {
                runCatching {
                    jobs.schedule(JobInfo.Builder(WATCHDOG_ID, component).setMinimumLatency(WATCHDOG_DEADLINE_MILLIS / 2).setOverrideDeadline(WATCHDOG_DEADLINE_MILLIS).build())
                }.onFailure { Log.w("WinnowIncoming", "Couldn't set the watchdog for a text", it) }
            }
            if (jobs.getPendingJob(JOB_ID) != null) return
            val expedited = runCatching { jobs.schedule(JobInfo.Builder(JOB_ID, component).setExpedited(true).build()) }.getOrNull()
            if (expedited != JobScheduler.RESULT_SUCCESS) {
                // Out of expedited time: a plain job still brings Winnow back if it's ended.
                runCatching { jobs.schedule(JobInfo.Builder(JOB_ID, component).setOverrideDeadline(0).build()) }
                    .onFailure { Log.w("WinnowIncoming", "Couldn't keep Winnow going for a text", it) }
            }
        }

        /** Everything stored has been said: the watchdog isn't needed. */
        fun settled(context: Context) {
            runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(WATCHDOG_ID) }
        }
    }
}
