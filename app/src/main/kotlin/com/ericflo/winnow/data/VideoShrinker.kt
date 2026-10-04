package com.ericflo.winnow.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Re-encodes a video small enough for an MMS, as phones' own texting apps do: H.264 at 240 or
 * 360 lines and 15 frames a second, mono AAC, at whatever bitrate the clip's length allows. A
 * clip too long for even the lowest bitrate is cut to the part that fits.
 */
@OptIn(UnstableApi::class)
class VideoShrinker(private val context: Context) {
    data class Result(val file: File, val trimmedToMillis: Long?)

    private val dir get() = File(context.cacheDir, "video").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    /** A copy of [uri] at most [budgetBytes], or null if it couldn't be made. [onProgress] gets 0–100. */
    suspend fun shrink(uri: Uri, budgetBytes: Long, onProgress: (Int) -> Unit = {}): Result? {
        val durationMs = withContext(Dispatchers.IO) { durationOf(uri) }?.takeIf { it > 0 } ?: return null
        // Bits to spend, leaving room for the container and a line of text.
        val usable = budgetBytes * 8 * 0.85
        var clipMs: Long? = null
        var videoBitrate = (usable / (durationMs / 1000.0) - AUDIO_BITRATE).toInt()
        if (videoBitrate < MIN_VIDEO_BITRATE) {
            clipMs = (usable / (MIN_VIDEO_BITRATE + AUDIO_BITRATE) * 1000).toLong()
            videoBitrate = MIN_VIDEO_BITRATE
        }
        videoBitrate = videoBitrate.coerceAtMost(MAX_VIDEO_BITRATE)
        // Encoders overshoot, and won't go below some rate however low the request (that floor
        // drops with the picture size). Each retry aims as far under as the last came out over:
        // a lower bitrate while there's room, then a shorter clip.
        var softwareDecoders = false
        repeat(ATTEMPTS) {
            val out = File(dir, "${UUID.randomUUID()}.mp4")
            var ok = transform(uri, out, videoBitrate, clipMs, softwareDecoders, onProgress)
            if (!ok && !softwareDecoders) {
                // Some hardware decoders choke on some videos (High profile on emulators, for one);
                // the slower software ones read nearly anything.
                softwareDecoders = true
                ok = transform(uri, out, videoBitrate, clipMs, softwareDecoders, onProgress)
            }
            if (!ok) {
                out.delete()
                return null
            }
            if (out.length() <= budgetBytes) return Result(out, clipMs)
            Log.i(TAG, "Shrunk video still ${out.length()} bytes at $videoBitrate bps, ${clipMs ?: durationMs} ms; trying again")
            val ratio = budgetBytes * 0.85 / out.length()
            out.delete()
            if (videoBitrate > MIN_VIDEO_BITRATE) {
                videoBitrate = (videoBitrate * ratio).toInt().coerceAtLeast(MIN_VIDEO_BITRATE)
            } else {
                clipMs = ((clipMs ?: durationMs) * ratio).toLong()
            }
        }
        return null
    }

    private fun durationOf(uri: Uri): Long? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()

    /** Transformer lives on the main thread; this waits for it there. */
    private suspend fun transform(uri: Uri, out: File, videoBitrate: Int, clipMs: Long?, softwareDecoders: Boolean, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val item = MediaItem.Builder().setUri(uri).apply {
                    if (clipMs != null) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipMs).build())
                }.build()
                val mono = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1))
                    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 1))
                }
                val edited = EditedMediaItem.Builder(item)
                    .setEffects(Effects(listOf(mono), listOf(Presentation.createForHeight(heightFor(videoBitrate)), FrameDropEffect.createDefaultFrameDropEffect(frameRateFor(videoBitrate)))))
                    .build()
                val encoders = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(AUDIO_BITRATE).build())
                    .build()
                val transformer = Transformer.Builder(context)
                    .apply {
                        if (softwareDecoders) {
                            val decoders = DefaultDecoderFactory.Builder(context)
                                .setMediaCodecSelector { mime, secure, tunneling ->
                                    MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { !it.softwareOnly }
                                }
                                .build()
                            setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoders, Clock.DEFAULT, null))
                        }
                    }
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            Log.w(TAG, "Couldn't shrink a video", exportException)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                    .build()
                transformer.start(edited, out.absolutePath)
                val progress = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress)
                        main.postDelayed(this, 250)
                    }
                }
                main.post(poll)
                continuation.invokeOnCancellation {
                    main.post {
                        main.removeCallbacks(poll)
                        transformer.cancel()
                        out.delete()
                    }
                }
            }
        }

    /** Fewer lines for fewer bits: a small sharp picture beats a big smeared one. */
    private fun heightFor(bitrate: Int) = when {
        bitrate >= 400_000 -> 360
        bitrate >= 200_000 -> 240
        else -> 144
    }

    private fun frameRateFor(bitrate: Int) = if (bitrate >= 200_000) 15f else 12f

    private companion object {
        const val TAG = "WinnowVideo"
        const val ATTEMPTS = 3
        const val AUDIO_BITRATE = 32_000
        const val MIN_VIDEO_BITRATE = 120_000
        const val MAX_VIDEO_BITRATE = 700_000
    }
}
