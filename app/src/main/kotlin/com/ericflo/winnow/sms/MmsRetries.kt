package com.ericflo.winnow.sms

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.ericflo.winnow.WinnowApp

/**
 * Picture messages whose download failed (no signal, mobile data off, the carrier's server
 * busy) are fetched again by themselves, as Messages does, rather than waiting for a tap the
 * user may never make. Each is tried a few times with growing gaps; while airplane mode or
 * mobile data is in the way, it waits without using up a try, for up to two days. A download
 * the user put off (auto-download off, roaming) is never retried: that was their choice.
 */
class MmsRetries(private val context: Context, private val readiness: SendReadiness) {
    private val prefs = context.getSharedPreferences("mms_retries", Context.MODE_PRIVATE)

    /** A download of placeholder [mmsId] failed: try again later, unless it's had its tries. */
    @Synchronized
    fun failed(mmsId: Long) {
        val now = System.currentTimeMillis()
        val (tries, firstAt) = entry(mmsId) ?: (0 to now)
        if (tries >= BACKOFF_MILLIS.size) {
            Log.i(TAG, "MMS $mmsId: giving up after $tries tries; it can still be tapped to download")
            prefs.edit().remove(mmsId.toString()).apply()
            return
        }
        prefs.edit().putString(mmsId.toString(), "${tries + 1}:$firstAt:${now + BACKOFF_MILLIS[tries]}").apply()
        schedule()
    }

    /** As the app starts: a force stop cancels its jobs, so the waiting retries' job is set again. */
    @Synchronized
    fun rearm() {
        if (prefs.all.isNotEmpty()) schedule()
    }

    /** It downloaded (or is gone): nothing more to do for it. */
    @Synchronized
    fun done(mmsId: Long) {
        if (prefs.contains(mmsId.toString())) prefs.edit().remove(mmsId.toString()).apply()
    }

    /** The job's turn: download what's due, again, and wait for the rest. */
    @Synchronized
    fun runDue(retry: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        val state = readiness.now()
        val blocked = state.airplane || state.mobileDataOff
        for ((key, value) in prefs.all) {
            val mmsId = key.toLongOrNull() ?: continue
            val (tries, firstAt, dueAt) = (value as? String)?.split(':')?.mapNotNull(String::toLongOrNull)?.takeIf { it.size == 3 } ?: continue
            val placeholder = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
            // Downloaded by a tap since, deleted, or no longer failed (being fetched, or put off).
            val status = runCatching { MmsStore.statusOf(context, placeholder) }.getOrNull()
            if (status != MmsStore.STATUS_DOWNLOAD_FAILED) {
                prefs.edit().remove(key).apply()
                continue
            }
            when {
                dueAt > now -> Unit
                blocked && now - firstAt < WAIT_FOR_DATA_MILLIS ->
                    // Not a try: put back a little, for when data's on again.
                    prefs.edit().putString(key, "$tries:$firstAt:${now + BLOCKED_RECHECK_MILLIS}").apply()
                blocked -> prefs.edit().remove(key).apply()
                else -> {
                    // A failure comes back through failed(), which counts it and sets the next time.
                    prefs.edit().putString(key, "$tries:$firstAt:${now + BACKOFF_MILLIS.last()}").apply()
                    Log.i(TAG, "Retrying MMS $mmsId download (try ${tries + 1})")
                    retry(mmsId)
                }
            }
        }
        schedule()
    }

    private fun entry(mmsId: Long): Pair<Int, Long>? =
        prefs.getString(mmsId.toString(), null)?.split(':')?.mapNotNull(String::toLongOrNull)?.takeIf { it.size == 3 }?.let { it[0].toInt() to it[1] }

    /** One job, for the soonest of them, whenever there's a network. */
    private fun schedule() {
        val jobs = context.getSystemService(JobScheduler::class.java)
        val soonest = prefs.all.values.mapNotNull { (it as? String)?.split(':')?.getOrNull(2)?.toLongOrNull() }.minOrNull()
        if (soonest == null) {
            jobs.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, MmsRetryJob::class.java))
            .setMinimumLatency((soonest - System.currentTimeMillis()).coerceAtLeast(0))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { jobs.schedule(job) }.onFailure { Log.w(TAG, "Couldn't schedule MMS retries", it) }
    }

    companion object {
        const val JOB_ID = 4202
        /** The gaps before each try: soon (a blip), then longer. */
        private val BACKOFF_MILLIS = longArrayOf(60_000L, 5 * 60_000L, 30 * 60_000L, 2 * 3_600_000L, 6 * 3_600_000L)
        private const val BLOCKED_RECHECK_MILLIS = 15 * 60_000L
        private const val WAIT_FOR_DATA_MILLIS = 2 * 24 * 3_600_000L
        private const val TAG = "WinnowMms"
    }
}

/** Runs [MmsRetries.runDue]; the downloads themselves report back through MmsDownloadedReceiver. */
class MmsRetryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as WinnowApp).container
        runCatching { container.mmsRetries.runDue(container.mmsReceiver::retryDownload) }
            .onFailure { Log.w("WinnowMms", "MMS retries failed", it) }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
