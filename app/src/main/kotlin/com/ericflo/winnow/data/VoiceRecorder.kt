package com.ericflo.winnow.data

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Records a voice message as AAC in an .m4a, mono, small enough for MMS: it stops on its own at
 * [MAX_MILLIS] or [MAX_BYTES], whichever comes first, and [onLimit] says so.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds recorded so far; 0 when idle. */
    fun elapsed(): Long = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Starts recording; false if the microphone couldn't be opened (in use, or no permission). */
    fun start(onLimit: () -> Unit): Boolean {
        stopAndDiscard()
        val target = File(File(context.cacheDir, "voice").apply { mkdirs() }, "${UUID.randomUUID()}.m4a")
        val created = MediaRecorder(context)
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(22_050)
            created.setAudioEncodingBitRate(32_000)
            created.setMaxDuration(MAX_MILLIS)
            created.setMaxFileSize(MAX_BYTES)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()
            }
            created.setOutputFile(target)
            created.prepare()
            created.start()
            recorder = created
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start recording", e)
            created.release()
            target.delete()
            false
        }
    }

    /** Stops and returns the recording, or null if it was too short to keep or failed. */
    fun stop(): OutgoingAttachment? {
        val r = recorder ?: return null
        val f = file
        val length = elapsed()
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || f == null || length < MIN_MILLIS || !f.exists()) {
            f?.delete()
            return null
        }
        return OutgoingAttachment(Uri.fromFile(f).toString(), "audio/mp4", "Voice message.m4a")
    }

    fun stopAndDiscard() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        file = null
    }

    companion object {
        private const val TAG = "WinnowVoice"
        const val MAX_MILLIS = 5 * 60_000
        /** Leaves room in a ~900 KB MMS for a line of text. */
        const val MAX_BYTES = 800_000L
        private const val MIN_MILLIS = 600L
    }
}
