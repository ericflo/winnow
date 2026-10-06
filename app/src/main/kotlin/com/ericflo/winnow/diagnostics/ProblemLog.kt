package com.ericflo.winnow.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What went wrong on this phone, kept on it: crashes, caught as they happen, and "not
 * responding" and native crashes, which Android records itself and Winnow reads back at the next
 * start. Nothing is sent anywhere. Settings shows them, and the user can share a report (to email
 * it to themselves, say) or clear them. A report can include bits of whatever was being handled
 * when it went wrong, which is why it only leaves the phone when they share it.
 */
class ProblemLog(private val context: Context) {
    /** [version] is the app's when it happened ("0.1.19 (119)"); null when that wasn't recorded. */
    data class Problem(val at: Long, val kind: Kind, val detail: String, val version: String? = null)

    enum class Kind(val label: String) {
        CRASH("Crash"),
        NOT_RESPONDING("Not responding"),
        NATIVE_CRASH("Native crash"),
        OTHER("Stopped"),
        /** Not a crash: the inbox found texts on the phone it couldn't list (see EmptyInbox). */
        LISTING("Couldn't list conversations"),
    }

    private val dir get() = File(context.filesDir, "problems")
    private val prefs by lazy { context.getSharedPreferences("problems", Context.MODE_PRIVATE) }

    private val _problems = MutableStateFlow<List<Problem>>(emptyList())
    /** Newest first. Empty until [load] has read them. */
    val problems: StateFlow<List<Problem>> = _problems.asStateFlow()

    private val _unseen = MutableStateFlow(0)
    /** Problems since the user last looked at or dismissed them (see [markSeen]). */
    val unseen: StateFlow<Int> = _unseen.asStateFlow()

    private val _unseenEarlier = MutableStateFlow<String?>(null)
    /** When every unseen problem happened in an earlier version than this one: the newest such version. */
    val unseenEarlier: StateFlow<String?> = _unseenEarlier.asStateFlow()

    /** This app's version, "0.1.26 (126)": asked once, and recorded with each problem. */
    val version: String by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("?")
    }

    /**
     * Records a crash on any thread before the process dies, then hands it on to Android's own
     * handler, which shows "Winnow keeps stopping" and ends the process as before.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(Problem(System.currentTimeMillis(), Kind.CRASH, "Thread: ${thread.name}\n${error.stackTraceToString()}", runCatching { version }.getOrNull())) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Reads what's recorded, and Android's records of this app's exits since the last look. Off the main thread. */
    fun load() {
        // Android keeps this with the record of how this process ends, so a freeze read back
        // after an update still says which version froze.
        runCatching { context.getSystemService(ActivityManager::class.java).setProcessStateSummary(version.toByteArray()) }
        val recorded = read()
        val since = prefs.getLong(KEY_EXITS_READ, 0)
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        }.getOrDefault(emptyList())
        var added = false
        for (exit in exits.filter { it.timestamp > since }) {
            val kind = kindOf(exit.reason) ?: continue
            // A crash the handler above caught is recorded already, with its stack.
            if (kind == Kind.CRASH && recorded.any { it.kind == Kind.CRASH && kotlin.math.abs(it.at - exit.timestamp) < SAME_CRASH_MILLIS }) continue
            val trace = if (kind == Kind.NOT_RESPONDING) runCatching { exit.traceInputStream?.use { readCapped(it, MAX_TRACE_BYTES) } }.getOrNull() else null
            val ranAs = runCatching { exit.processStateSummary?.decodeToString()?.takeIf { it.isNotBlank() } }.getOrNull()
            write(Problem(exit.timestamp, kind, listOfNotNull(exit.description?.let { "Android says: $it" }, trace).joinToString("\n\n").ifBlank { "No details recorded." }, ranAs))
            added = true
        }
        exits.maxOfOrNull { it.timestamp }?.let { newest -> if (newest > since) prefs.edit().putLong(KEY_EXITS_READ, newest).apply() }
        publish(if (added) read() else recorded)
    }

    /** Records something that went wrong without a crash, with [detail] for the report. */
    fun note(kind: Kind, detail: String) {
        runCatching {
            write(Problem(System.currentTimeMillis(), kind, detail, version))
            publish(read())
        }
    }

    /** The user has seen them (opened the report, or said "Not now"). */
    fun markSeen() {
        prefs.edit().putLong(KEY_SEEN, _problems.value.maxOfOrNull { it.at } ?: System.currentTimeMillis()).apply()
        _unseen.value = 0
        _unseenEarlier.value = null
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        markSeen()
        _problems.value = emptyList()
    }

    /** The report for everything recorded, and an intent that offers it to share. */
    fun shareIntent(): android.content.Intent {
        val device = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Winnow problem report")
            .putExtra(android.content.Intent.EXTRA_TEXT, report(_problems.value, version, device, System.currentTimeMillis()))
        return android.content.Intent.createChooser(send, "Share the problem report")
    }

    private fun publish(problems: List<Problem>) {
        _problems.value = problems
        val seen = prefs.getLong(KEY_SEEN, 0)
        val unseen = problems.filter { it.at > seen }
        _unseen.value = unseen.size
        _unseenEarlier.value = earlierVersion(unseen, version)
    }

    private fun write(problem: Problem) {
        dir.mkdirs()
        File(dir, "${problem.at}-${problem.kind.name}.txt").writeText(encode(problem))
        // The newest are the useful ones.
        dir.listFiles()?.sortedByDescending { it.name.substringBefore('-').toLongOrNull() ?: 0 }?.drop(MAX_KEPT)?.forEach { it.delete() }
    }

    private fun read(): List<Problem> = dir.listFiles().orEmpty().mapNotNull { file ->
        val at = file.name.substringBefore('-').toLongOrNull() ?: return@mapNotNull null
        val kind = runCatching { Kind.valueOf(file.name.substringAfter('-').substringBefore('.')) }.getOrDefault(Kind.OTHER)
        decode(at, kind, runCatching { file.readText() }.getOrDefault(""))
    }.sortedByDescending { it.at }

    companion object {
        private const val KEY_EXITS_READ = "exits_read"
        private const val KEY_SEEN = "seen"
        private const val MAX_EXITS = 16
        private const val MAX_KEPT = 20
        private const val MAX_TRACE_BYTES = 48 * 1024
        private const val SAME_CRASH_MILLIS = 10_000L
        /** Shared as text; kept well under what an intent can carry. */
        const val MAX_REPORT_CHARS = 150_000

        private const val VERSION_LINE = "#version "

        /** A problem as kept in its file: the version on a first line of its own, then the details. */
        fun encode(problem: Problem): String = problem.version?.let { "$VERSION_LINE$it\n${problem.detail}" } ?: problem.detail

        /** Reads [encode]'s form back; files from before versions were recorded are details alone. */
        fun decode(at: Long, kind: Kind, text: String): Problem =
            if (text.startsWith(VERSION_LINE)) {
                Problem(at, kind, text.substringAfter('\n', ""), text.substringBefore('\n').removePrefix(VERSION_LINE).trim().ifEmpty { null })
            } else {
                Problem(at, kind, text)
            }

        /** The newest version [problems] came from, when every one of them is known to be from a version other than [current]. */
        fun earlierVersion(problems: List<Problem>, current: String): String? =
            if (problems.isNotEmpty() && problems.all { it.version != null && it.version != current }) problems.maxBy { it.at }.version else null

        /** Which exits are problems worth a report. A low-memory kill or a force stop isn't. */
        fun kindOf(reason: Int): Kind? = when (reason) {
            ApplicationExitInfo.REASON_CRASH -> Kind.CRASH
            ApplicationExitInfo.REASON_ANR -> Kind.NOT_RESPONDING
            ApplicationExitInfo.REASON_CRASH_NATIVE -> Kind.NATIVE_CRASH
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Kind.OTHER
            else -> null
        }

        /** The report to share: what the app and phone are, then each problem, newest first. Pure, so it's unit-tested. */
        fun report(problems: List<Problem>, appVersion: String, device: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(zone)
            val text = buildString {
                appendLine("Winnow problem report")
                appendLine("App: $appVersion · $device")
                appendLine("Made: ${time.format(Instant.ofEpochMilli(now))}")
                problems.forEach { p ->
                    appendLine()
                    val ranAs = when (p.version) {
                        null -> ""
                        appVersion -> " · this version"
                        else -> " · in ${p.version}"
                    }
                    appendLine("== ${p.kind.label} · ${time.format(Instant.ofEpochMilli(p.at))}$ranAs ==")
                    appendLine(p.detail.trimEnd())
                }
            }
            return if (text.length <= MAX_REPORT_CHARS) text else text.take(MAX_REPORT_CHARS) + "\n[cut off: the rest didn't fit]"
        }

        private fun readCapped(input: InputStream, max: Int): String {
            val buffer = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = input.read(buffer, total, max - total)
                if (n < 0) break
                total += n
            }
            return String(buffer, 0, total)
        }
    }
}
