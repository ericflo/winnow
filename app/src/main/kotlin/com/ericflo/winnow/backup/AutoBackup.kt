package com.ericflo.winnow.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Weekly backups into a folder the user picked once, written while the phone charges. The folder
 * and its permission belong to this phone, so they're not part of a backup themselves.
 */
class AutoBackup(
    private val context: Context,
    private val settings: SettingsRepository,
    private val backups: BackupManager,
) {
    private val jobs get() = context.getSystemService(JobScheduler::class.java)

    // The weekly job and "Back up now" can overlap; one backup at a time.
    private val running = Mutex()

    /** Keeps the folder (with lasting permission to write there) and schedules the weekly job. */
    suspend fun enable(folder: Uri) {
        context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        settings.update { it.copy(autoBackupFolder = folder.toString(), autoBackupError = null) }
        schedule()
    }

    suspend fun disable() {
        jobs.cancel(JOB_ID)
        settings.current().autoBackupFolder?.let { folder ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        settings.update { it.copy(autoBackupFolder = null, autoBackupError = null) }
    }

    /** Called at startup: a job survives reboots and updates, but not a cleared app. */
    suspend fun ensureScheduled() {
        if (settings.current().autoBackupFolder != null && jobs.getPendingJob(JOB_ID) == null) schedule()
    }

    private fun schedule() {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJob::class.java))
            .setPeriodic(WEEK_MILLIS, DAY_MILLIS)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        jobs.schedule(job)
    }

    /** Writes a backup into the folder now and prunes old ones. Returns what happened, for the log or the user. */
    suspend fun runNow(): String = running.withLock { withContext(Dispatchers.IO) { backUp() } }

    private suspend fun backUp(): String {
        val folder = settings.current().autoBackupFolder?.let(Uri::parse) ?: return "Automatic backup is off"
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val name = "$PREFIX${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))}.zip"
            val file = DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", name)
                ?: error("The folder refused a new file")
            val summary = try {
                backups.exportQuietly(file)
            } catch (e: Throwable) {
                // Cancelled (JobScheduler stopped the job) or failed: a half-written zip mustn't
                // count as one of the backups kept, so it goes, even mid-cancellation.
                withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, file) } }
                throw e
            }
            prune(folder)
            settings.update { it.copy(autoBackupLast = System.currentTimeMillis(), autoBackupError = null) }
            summary
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Automatic backup failed", e)
            val reason = if (e is SecurityException) "Winnow can no longer write to that folder; choose it again" else e.message ?: "Backup failed"
            settings.update { it.copy(autoBackupError = reason) }
            throw e
        }
    }

    /** Keeps the newest [KEEP] automatic backups. Only files Winnow named are ever touched. */
    private fun prune(folder: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        data class Backup(val id: String, val name: String, val modified: Long)
        val ours = mutableListOf<Backup>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(".zip")) ours += Backup(c.getString(0), name, if (c.isNull(2)) 0 else c.getLong(2))
            }
        }
        // Newest first by the file's own time; the dated name breaks ties.
        ours.sortedWith(compareByDescending<Backup> { it.modified }.thenByDescending { it.name }).drop(KEEP).forEach { (id, _, _) ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(folder, id)) }
        }
    }

    /** The folder's name as the user's file manager shows it. */
    fun folderName(folder: String): String? = runCatching {
        val tree = Uri.parse(folder)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    companion object {
        const val JOB_ID = 4201
        const val PREFIX = "winnow-auto-backup-"
        const val KEEP = 4
        private const val DAY_MILLIS = 24 * 60 * 60_000L
        private const val WEEK_MILLIS = 7 * DAY_MILLIS
        private const val TAG = "WinnowAutoBackup"
    }
}

/** The weekly job; JobScheduler runs it while charging and reschedules a failure with backoff. */
class AutoBackupJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as WinnowApp).container
        work = container.appScope.launch {
            val ok = try {
                container.autoBackup.runNow()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            jobFinished(params, !ok)
        }
        return true
    }

    /** Unplugged, or out of time: stop writing (the partial file is deleted) and try again later. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}
