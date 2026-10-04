package com.ericflo.winnow.ui.components

import android.content.Context
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import coil3.compose.AsyncImage
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.ericflo.winnow.data.VCard
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.text.style.TextAlign

/**
 * Plays audio attachments, one at a time: starting one stops whatever was playing. Owned by
 * the conversation screen, which releases it when it goes away.
 */
class AudioPlayer(private val context: Context) {
    data class State(val uri: String? = null, val playing: Boolean = false, val positionMillis: Int = 0, val durationMillis: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var player: MediaPlayer? = null

    fun toggle(uri: String) {
        val current = player
        if (current != null && _state.value.uri == uri && _state.value.durationMillis > 0) {
            if (_state.value.playing) current.pause() else current.start()
            _state.value = _state.value.copy(playing = !_state.value.playing)
            return
        }
        release()
        val created = MediaPlayer()
        player = created
        _state.value = State(uri, playing = false)
        runCatching {
            created.setDataSource(context, Uri.parse(uri))
            created.setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMillis = 0); it.seekTo(0) }
            created.setOnErrorListener { _, _, _ -> release(); true }
            // Prepared off the main thread; playback starts once it's ready.
            created.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                it.start()
                _state.value = State(uri, playing = true, positionMillis = 0, durationMillis = it.duration)
            }
            created.prepareAsync()
        }.onFailure { release() }
    }

    /** Called when the screen stops: a voice message shouldn't keep playing behind other apps or the lock. */
    fun pause() {
        val p = player ?: return
        if (_state.value.playing && runCatching { p.isPlaying }.getOrDefault(false)) p.pause()
        _state.value = _state.value.copy(playing = false)
    }

    /** Called while playing, to move the progress bar. */
    fun tick() {
        val p = player ?: return
        if (_state.value.playing) runCatching { _state.value = _state.value.copy(positionMillis = p.currentPosition) }
    }

    fun release() {
        player?.release()
        player = null
        _state.value = State()
    }
}

/** A voice memo or other audio attachment: play/pause, progress, and its length. */
@Composable
fun AudioAttachment(uri: String, player: AudioPlayer, outgoing: Boolean) {
    val context = LocalContext.current
    val state by player.state.collectAsStateWithLifecycle()
    val mine = state.uri == uri
    val known by produceState(0, uri) { value = withContext(Dispatchers.IO) { durationOf(context, uri) } }
    val duration = if (mine && state.durationMillis > 0) state.durationMillis else known
    LaunchedEffect(mine, state.playing) {
        while (mine && state.playing) {
            player.tick()
            delay(200)
        }
    }
    val colors = MaterialTheme.colorScheme
    val (container, content) = if (outgoing) colors.primaryContainer to colors.onPrimaryContainer else colors.surfaceContainerHigh to colors.onSurface
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(22.dp), modifier = Modifier.widthIn(min = 220.dp, max = 280.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            IconButton(onClick = { player.toggle(uri) }) {
                if (mine && state.playing) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play voice message")
                }
            }
            LinearProgressIndicator(
                progress = { if (mine && duration > 0) state.positionMillis.toFloat() / duration else 0f },
                modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = content,
                trackColor = content.copy(alpha = 0.25f),
            )
            Spacer(Modifier.width(12.dp))
            Text(clock(if (mine && state.playing) state.positionMillis else duration), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A video attachment's first frame with a play button; tapping opens [VideoViewer]. */
@Composable
fun VideoAttachment(uri: String, name: String?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val frame by produceState<Pair<Bitmap?, Int>?>(null, uri) { value = withContext(Dispatchers.IO) { frameOf(context, uri) } }
    Box(
        Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "Video${name?.let { ", $it" } ?: ""}. Play" },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = frame?.first
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(260.dp).height((260f * bitmap.height / bitmap.width).dp))
        } else {
            Box(Modifier.size(260.dp, 180.dp))
        }
        Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        frame?.second?.takeIf { it > 0 }?.let { length ->
            Text(
                clock(length),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * A pending attachment in the composer: the photo itself, a video's first frame, or a tile
 * naming what it is, so a voice note or PDF doesn't show as an empty square.
 */
@Composable
fun AttachmentThumbnail(uri: String, contentType: String, name: String?, modifier: Modifier = Modifier) {
    if (contentType.startsWith("image/")) {
        AsyncImage(model = uri, contentDescription = "Attachment", contentScale = ContentScale.Crop, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val isVideo = contentType.startsWith("video/")
    val isAudio = contentType.startsWith("audio/")
    val frame by produceState<Bitmap?>(null, uri) { if (isVideo) value = withContext(Dispatchers.IO) { frameOf(context, uri).first } }
    val isContact = VCard.isVCard(contentType)
    val label = when {
        isVideo -> "Video"
        isAudio -> "Audio"
        isContact -> name?.removeSuffix(".vcf")?.takeIf { it.isNotBlank() } ?: "Contact"
        else -> name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }?.uppercase() ?: "File"
    }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = "$label attachment${name?.let { ", $it" } ?: ""}" },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
            if (isContact) Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            if (isVideo || isAudio) {
                Box(Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            if (frame == null) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (isContact) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Full-screen playback with the platform's own controls. */
@Composable
fun VideoViewer(uri: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        setVideoURI(Uri.parse(uri))
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxWidth(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private fun durationOf(context: Context, uri: String): Int = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, Uri.parse(uri))
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
    }
}.getOrDefault(0)

private fun frameOf(context: Context, uri: String): Pair<Bitmap?, Int> = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, Uri.parse(uri))
        r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 520, 520) to
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0)
    }
}.getOrDefault(null to 0)

private fun clock(millis: Int): String {
    val seconds = (millis + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
