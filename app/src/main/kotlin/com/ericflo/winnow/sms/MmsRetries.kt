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
import kotlinx.coroutines.launch
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

    /** Persist a watchdog before asking the system service to download. A missing callback
     * must not leave an untracked "Downloading MMS" row forever. */
    @Synchronized
    fun started(mmsId: Long, attempt: String): Boolean {
        if (isDownloading(mmsId)) return false
        val now = System.currentTimeMillis()
        val (tries, firstAt) = entry(mmsId) ?: (0 to now)
        check(prefs.edit().putString(mmsId.toString(), "$tries:$firstAt:${now + DOWNLOAD_TIMEOUT_MILLIS}:downloading:$attempt").commit())
        schedule()
        return true
    }

    @Synchronized
    fun isDownloading(mmsId: Long): Boolean =
        prefs.getString(mmsId.toString(), null)?.split(':')?.let { it.getOrNull(3) == "downloading" && (it.getOrNull(2)?.toLongOrNull() ?: 0) > System.currentTimeMillis() } == true

    @Synchronized
    fun isCurrentAttempt(mmsId: Long, attempt: String): Boolean =
        prefs.getString(mmsId.toString(), null)?.split(':')?.getOrNull(4) == attempt

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
        if (!isDefaultSmsApp()) return
        // Also discover orphaned downloads from older releases or a process killed between
        // saving the notification and handing the request to Android.
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            "${Telephony.Mms.MESSAGE_TYPE} = ${MmsStore.MESSAGE_TYPE_NOTIFICATION_IND} AND ${Telephony.Mms.STATUS} IS NULL", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getLong(0).toString()
                if (!prefs.contains(key)) {
                    val receivedAt = c.getLong(1) * 1000
                    // A fresh push may still be entering download(); don't claim its attempt.
                    prefs.edit().putString(key, "0:$receivedAt:${receivedAt + DOWNLOAD_TIMEOUT_MILLIS}:orphan").apply()
                }
            }
        }
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
        if (!isDefaultSmsApp()) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            return
        }
        val now = System.currentTimeMillis()
        val state = readiness.now()
        val blocked = state.airplane || state.mobileDataOff
        for ((key, value) in prefs.all) {
            val mmsId = key.toLongOrNull() ?: continue
            val fields = (value as? String)?.split(':') ?: continue
            val (tries, firstAt, dueAt) = fields.take(3).mapNotNull(String::toLongOrNull).takeIf { it.size == 3 } ?: continue
            val placeholder = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
            // Distinguish a missing row from an active download (both used to return null).
            val row = context.contentResolver.query(placeholder, arrayOf(Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.STATUS), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getInt(0) to (if (c.isNull(1)) null else c.getInt(1)) else null }
            val status = row?.second
            if (row?.first == MmsStore.MESSAGE_TYPE_NOTIFICATION_IND && status == null) {
                if (dueAt <= now) {
                    MmsStore(context).markDownloadFailed(placeholder)
                    MmsFailures(context).record(mmsId, "Android didn't finish the download; Winnow will try again")
                    failed(mmsId)
                }
                continue
            }
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
        prefs.getString(mmsId.toString(), null)?.split(':')?.take(3)?.mapNotNull(String::toLongOrNull)?.takeIf { it.size == 3 }?.let { it[0].toInt() to it[1] }

    private fun isDefaultSmsApp() = context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)

    /** The system MMS service acquires its own network. The watchdog must also run offline. */
    private fun schedule() {
        val jobs = context.getSystemService(JobScheduler::class.java)
        val soonest = prefs.all.values.mapNotNull { (it as? String)?.split(':')?.getOrNull(2)?.toLongOrNull() }.minOrNull()
        if (soonest == null) {
            jobs.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, MmsRetryJob::class.java))
            .setMinimumLatency((soonest - System.currentTimeMillis()).coerceAtLeast(0))
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
        private const val DOWNLOAD_TIMEOUT_MILLIS = 10 * 60_000L
        private const val TAG = "WinnowMms"
    }
}

/**
 * Runs [MmsRetries.runDue]; the downloads themselves report back through MmsDownloadedReceiver.
 * Off the main thread (a job starts on it): each retry reads and writes the message store.
 */
class MmsRetryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as WinnowApp).container
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.mmsRetries.runDue(container.mmsReceiver::retryDownload) }
                .onFailure { Log.w("WinnowMms", "MMS retries failed", it) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
