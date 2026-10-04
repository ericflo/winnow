package com.ericflo.winnow.ui.thread

import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import com.ericflo.winnow.data.SimCard
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Check
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.telephony.SmsMessage
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import com.ericflo.winnow.ui.components.VideoViewer
import com.ericflo.winnow.ui.components.VideoAttachment
import com.ericflo.winnow.ui.components.AudioPlayer
import com.ericflo.winnow.ui.components.AttachmentThumbnail
import com.ericflo.winnow.ui.components.AudioAttachment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ericflo.winnow.R
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.VerificationCodes
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.StoredVerdict
import com.ericflo.winnow.data.Tapback
import com.ericflo.winnow.data.db.ScheduledMessageEntity
import com.ericflo.winnow.ui.components.Avatar
import com.ericflo.winnow.ui.components.GroupAvatar
import com.ericflo.winnow.ui.components.ImageViewer
import com.ericflo.winnow.ui.components.headerLabel
import com.ericflo.winnow.ui.components.isEmojiOnly
import com.ericflo.winnow.ui.components.isSingleEmoji
import com.ericflo.winnow.ui.components.allWebLinks
import com.ericflo.winnow.ui.components.showOrCreateContact
import com.ericflo.winnow.ui.components.linkify
import com.ericflo.winnow.ui.components.timeOfDay
import com.ericflo.winnow.ui.theme.avatarColors
import com.ericflo.winnow.ui.theme.categoryColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import com.ericflo.winnow.data.TextScale
import com.ericflo.winnow.ui.SharedAttachments
import com.ericflo.winnow.ui.components.scaled
import com.ericflo.winnow.data.attachmentSummary
import com.ericflo.winnow.data.VCard
import com.ericflo.winnow.classify.deferredPreview
import com.ericflo.winnow.ui.components.ContactCardAttachment
import androidx.compose.material.icons.filled.Person
import com.ericflo.winnow.data.Attachment
import androidx.compose.material.icons.filled.Share
import android.widget.Toast
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.semantics.selected
import com.ericflo.winnow.ui.components.MuteDialog
import com.ericflo.winnow.ui.components.mutedLabel
import androidx.compose.material3.HorizontalDivider
import com.ericflo.winnow.data.LinkPreview
import com.ericflo.winnow.ui.components.LinkPreviewCard
import com.ericflo.winnow.ui.components.firstWebLink
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.data.SmartAction
import com.ericflo.winnow.data.SmartLink
import com.ericflo.winnow.data.subjectAndText
import com.ericflo.winnow.data.isEmailAddress
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Edit
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.InputChip
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: ThreadViewModel,
    onBack: () -> Unit,
    /** Forward a message: its text, and its attachments as [SharedAttachments] encoded them. */
    onForward: (text: String, attachments: String) -> Unit,
    /** Opens a conversation with the carrier's spam-reporting short code, pre-filled. */
    onReportSpam: (String) -> Unit,
    onOpenDetails: (threadId: Long) -> Unit,
    /** Opens a conversation with a number, from a shared contact card. */
    onMessageNumber: (String) -> Unit = {},
    /** False in the two-pane layout, where the conversation list stays beside it. */
    showBack: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sims by viewModel.sims.collectAsStateWithLifecycle()
    val selectedSim by viewModel.selectedSim.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val scheduled by viewModel.scheduled.collectAsStateWithLifecycle()
    val textScale by viewModel.textScale.collectAsStateWithLifecycle()
    val unreadOnOpen by viewModel.unreadOnOpen.collectAsStateWithLifecycle()
    val enterToSend by viewModel.enterToSend.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val sendSeparately by viewModel.sendSeparately.collectAsStateWithLifecycle()
    val subjectShown by viewModel.subjectShown.collectAsStateWithLifecycle()
    val subject = viewModel.subjectField.takeIf { subjectShown }
    // Only whether it's blank: reading the text itself here would redraw the screen per keystroke.
    val subjectBlank by remember(subject) { derivedStateOf { subject?.text.isNullOrBlank() } }
    // A permission was refused, maybe for good (then asking again shows nothing): say what it's for.
    var refused by remember { mutableStateOf<String?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else refused = "Voice messages need the microphone"
    }
    // Without this phone's own number, a group text lists the user among its people.
    val permissionContext = LocalContext.current
    fun hasPhoneNumbers() = permissionContext.checkSelfPermission(android.Manifest.permission.READ_PHONE_NUMBERS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var phoneNumbersAllowed by remember { mutableStateOf(hasPhoneNumbers()) }
    LifecycleResumeEffect(Unit) {
        phoneNumbersAllowed = hasPhoneNumbers()
        onPauseOrDispose {}
    }
    val phoneNumbersPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phoneNumbersAllowed = granted
        if (!granted) refused = "Leaving you out of group texts needs your number (the Phone numbers permission)"
    }
    val locating by viewModel.locating.collectAsStateWithLifecycle()
    val shrinking by viewModel.shrinking.collectAsStateWithLifecycle()
    val quickReplies by viewModel.quickReplies.collectAsStateWithLifecycle()
    // Precise or approximate, whichever the user allows; either makes a usable map link.
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) viewModel.shareLocation() else refused = "Sharing your location needs location access"
    }
    val linkPreviewSenders by viewModel.linkPreviewSenders.collectAsStateWithLifecycle()
    var confirmBlock by remember { mutableStateOf(false) }
    val scheduledJustNow = remember { mutableStateOf(false) }
    var confirmReport by remember { mutableStateOf(false) }
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(it) } }
    // Leaving the conversation, or the app, stops the microphone; the clip waits in the composer.
    LifecycleStartEffect(viewModel) { onStopOrDispose { viewModel.finishRecording() } }
    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var choosingMute by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var remindingFor by remember { mutableStateOf<ChatMessage?>(null) }
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    var detailsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var watching by rememberSaveable { mutableStateOf<String?>(null) }
    // One player for the whole conversation, so starting a voice message stops the last one.
    val appContext = LocalContext.current.applicationContext
    val audio = remember { AudioPlayer(appContext) }
    DisposableEffect(audio) { onDispose { audio.release() } }
    LifecycleStartEffect(audio) { onStopOrDispose { audio.pause() } }
    val context = LocalContext.current
    LaunchedEffect(refused) {
        val message = refused ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = "Settings", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            val appSettings = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(appSettings) }
        }
        refused = null
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Results show as a toast from the full-screen viewers, which cover the snackbar.
    fun tell(message: String, overViewer: Boolean) {
        if (overViewer) Toast.makeText(context, message, Toast.LENGTH_SHORT).show() else scope.launch { snackbar.showSnackbar(message) }
    }
    fun share(attachments: List<Attachment>, overViewer: Boolean = false) {
        scope.launch {
            val intent = viewModel.shareIntent(attachments)
            if (intent == null || runCatching { context.startActivity(intent) }.isFailure) tell("Couldn't share that", overViewer)
        }
    }
    fun save(attachments: List<Attachment>, overViewer: Boolean = false) {
        scope.launch { tell(viewModel.save(attachments), overViewer) }
    }
    fun copy(text: String, notice: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text)))
            snackbar.showSnackbar(notice)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addAttachment(OutgoingAttachment(uri.toString(), context.contentResolver.getType(uri) ?: "image/jpeg", null))
    }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) viewModel.attachContact(uri)
    }
    // Without the contacts permission only a picked phone number's own row is readable.
    val phonePicker = rememberLauncherForActivityResult(PickPhoneNumber) { uri ->
        if (uri != null) viewModel.attachPhone(uri)
    }
    // The camera app writes into a file of ours, so nothing depends on its permissions later.
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val target = cameraTarget
        cameraTarget = null
        if (taken && target != null) viewModel.addAttachment(OutgoingAttachment(target, "image/jpeg", "photo.jpg"))
    }
    var videoTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val videoCamera = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { recorded ->
        val target = videoTarget
        videoTarget = null
        // Too big for an MMS (most are), it's shrunk to fit once it's attached.
        if (recorded && target != null) viewModel.addAttachment(OutgoingAttachment(target, "video/mp4", "video.mp4"))
    }
    val single = state.recipients.singleOrNull()

    // Search within the conversation: matches newest first, and which one is in view.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query, state.messages) {
        if (query.trim().length < 2) emptyList()
        else state.messages.filter { Tapback.parse(it.body) == null && it.body.contains(query.trim(), ignoreCase = true) }.map { it.key }.reversed()
    }
    var matchIndex by rememberSaveable(query) { mutableIntStateOf(0) }
    val focusKey = matches.getOrNull(matchIndex)
    BackHandler(enabled = searching) { searching = false; query = "" }
    // Opened from a search result or a starred message: show that message.
    val searchRequest by viewModel.searchRequest.collectAsStateWithLifecycle()
    var pendingMatch by remember { mutableStateOf<String?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searchRequest) {
        val request = searchRequest ?: return@LaunchedEffect
        viewModel.searchRequestHandled()
        if (request.query != null) {
            searching = true
            query = request.query
            pendingMatch = request.focusKey
        } else {
            jumpTo = request.focusKey
        }
    }
    // Once the conversation has loaded: the tapped result among the matches (else the newest stays).
    LaunchedEffect(matches, pendingMatch) {
        val key = pendingMatch ?: return@LaunchedEffect
        if (state.messages.isEmpty()) return@LaunchedEffect
        matches.indexOf(key).takeIf { it >= 0 }?.let { matchIndex = it }
        pendingMatch = null
    }
    // A starred message is highlighted for a moment, then the conversation is just a conversation.
    LaunchedEffect(jumpTo, state.messages.isNotEmpty()) {
        if (jumpTo != null && state.messages.isNotEmpty()) {
            delay(2_500)
            jumpTo = null
        }
    }

    // Several messages at once: "Select" in a message's sheet starts it, taps add and remove.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selectedMessages = remember(selected, state.messages) { state.messages.filter { it.key in selected } }
    LaunchedEffect(state.messages) { selected = selected.filterTo(HashSet()) { key -> state.messages.any { it.key == key } } }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var confirmDeleteOne by remember { mutableStateOf<ChatMessage?>(null) }
    var reactingWithOther by remember { mutableStateOf<ChatMessage?>(null) }
    // A place, date or flight tapped in a message: its text, and where in it.
    var smartTapped by remember { mutableStateOf<Triple<String, SmartLink, Long>?>(null) }
    // A phone number tapped in a message: what to do with it, rather than straight to the dialer.
    var numberTapped by remember { mutableStateOf<String?>(null) }
    val systemUris = LocalUriHandler.current
    val uris = remember(systemUris) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("tel:")) numberTapped = uri.removePrefix("tel:") else systemUris.openUri(uri)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    allStarred = selectedMessages.isNotEmpty() && selectedMessages.all { it.starred },
                    canCopy = selectedMessages.any { it.body.isNotBlank() },
                    onClose = { selected = emptySet() },
                    onCopy = {
                        val text = selectedMessages.sortedBy { it.timestamp }.map { it.body }.filter { it.isNotBlank() }.joinToString("\n")
                        copy(text, if (selectedMessages.size == 1) "Message copied" else "${selectedMessages.size} messages copied")
                        selected = emptySet()
                    },
                    onStar = {
                        viewModel.setStarred(selectedMessages, starred = !selectedMessages.all { it.starred })
                        selected = emptySet()
                    },
                    onDelete = { confirmDeleteSelected = true },
                )
            } else if (searching) {
                ThreadSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    position = if (matches.isEmpty()) null else matchIndex + 1 to matches.size,
                    onOlder = { if (matchIndex < matches.lastIndex) matchIndex++ },
                    onNewer = { if (matchIndex > 0) matchIndex-- },
                    onClose = { searching = false; query = "" },
                )
            } else TopAppBar(
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    // Tapping the title opens the conversation's details, as in Messages.
                    val openDetails = Modifier.clickable {
                        viewModel.currentThreadId().takeIf { it >= 0 }?.let(onOpenDetails)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = openDetails) {
                        if (state.isGroup) GroupAvatar(state.members, 36.dp) else Avatar(state.title, seed = single.orEmpty(), size = 36.dp, photoUri = state.photos[single])
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            state.subtitle?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                actions = {
                    // An email address has no phone to call.
                    if (single != null && !isEmailAddress(single)) {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", single, null))) }) {
                            Icon(Icons.Filled.Call, contentDescription = "Call")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Search") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                onClick = { menuOpen = false; searching = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Details") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.currentThreadId().takeIf { it >= 0 }?.let(onOpenDetails)
                                },
                            )
                            state.addableContact?.let { number ->
                                DropdownMenuItem(
                                    text = { Text("Add contact") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) },
                                    onClick = { menuOpen = false; showOrCreateContact(context, number) },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    if (state.muted) {
                                        Column {
                                            Text("Unmute notifications")
                                            Text(mutedLabel(state.mutedUntil), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    } else {
                                        Text("Mute notifications")
                                    }
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_muted), contentDescription = null) },
                                onClick = { menuOpen = false; if (state.muted) viewModel.setMuted(false) else choosingMute = true },
                            )
                            if (viewModel.currentThreadId() >= 0) {
                                DropdownMenuItem(
                                    text = { Text("Add to home screen") },
                                    leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.addToHomeScreen() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (state.archived) "Unarchive" else "Archive") },
                                leadingIcon = { Icon(painterResource(if (state.archived) R.drawable.ic_unarchive else R.drawable.ic_archive), contentDescription = null) },
                                onClick = { menuOpen = false; viewModel.setArchived(!state.archived) },
                            )
                            if (single != null) {
                                DropdownMenuItem(
                                    text = { Text("Always allow this sender") },
                                    leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.allow() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Always filter this sender") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_block), contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.filter() },
                                )
                            }
                            if (viewModel.canBlock) {
                                DropdownMenuItem(
                                    // Android's block list takes email addresses too.
                                    text = {
                                        val what = if (single?.let(::isEmailAddress) == true) "address" else "number"
                                        Text(if (blocked) "Unblock $what" else "Block $what")
                                    },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_block), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        if (blocked) viewModel.setBlocked(false) else confirmBlock = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete conversation") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val pending by viewModel.pending.collectAsStateWithLifecycle()
            Column {
            pending?.let { UndoBar(it, onUndo = viewModel::undoSend) }
            Composer(
                sims = sims,
                selectedSim = sims.firstOrNull { it.subscriptionId == selectedSim },
                onSelectSim = viewModel::selectSim,
                field = viewModel.draftField,
                onKeyboardContent = viewModel::addKeyboardContent,
                attachments = attachments,
                // Videos too: one too big for an MMS is shrunk to fit.
                onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onCamera = {
                    val (file, uri) = viewModel.newCameraPhoto()
                    cameraTarget = android.net.Uri.fromFile(file).toString()
                    runCatching { camera.launch(uri) }.onFailure { cameraTarget = null }
                },
                onVideo = {
                    val (file, uri) = viewModel.newCameraVideo()
                    videoTarget = android.net.Uri.fromFile(file).toString()
                    runCatching { videoCamera.launch(uri) }.onFailure { videoTarget = null }
                },
                onVoice = {
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording()
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onLocation = {
                    val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        .any { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
                    if (granted) viewModel.shareLocation()
                    else locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                locating = locating,
                shrinking = shrinking,
                quickReplies = quickReplies,
                onQuickReply = viewModel::insertQuickReply,
                suggestions = viewModel.suggestedReplies.collectAsStateWithLifecycle().value,
                recording = recording,
                recordingElapsed = viewModel::recordingElapsed,
                onStopRecording = viewModel::stopRecording,
                onCancelRecording = viewModel::cancelRecording,
                onContact = {
                    val canReadContacts = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    runCatching { if (canReadContacts) contactPicker.launch(null) else phonePicker.launch(null) }
                },
                onRemoveAttachment = viewModel::removeAttachment,
                onRotateAttachment = viewModel::rotateAttachment,
                subject = subject,
                onAddSubject = viewModel::addSubject,
                onRemoveSubject = viewModel::removeSubject,
                // Sent separately, each person gets a plain text; a subject makes it an MMS (an empty one is left off).
                // An email address takes only an MMS.
                isSms = (single != null || sendSeparately) && state.recipients.none(::isEmailAddress) && attachments.isEmpty() && subjectBlank,
                sendsAsMms = viewModel.sendsAsMms.collectAsStateWithLifecycle().value,
                onSend = viewModel::send,
                enterToSend = enterToSend,
                onSendSeparately = if (state.isGroup && !sendSeparately) ({ viewModel.send(separately = true) }) else null,
                sendSeparately = sendSeparately,
                onClearSendSeparately = viewModel::clearSendSeparately,
                onSchedule = { at, label ->
                    if (viewModel.schedule(at, label)) scheduledJustNow.value = true
                },
            )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val verdictShown = state.verdict?.let { it.effectiveAction != Action.ALLOW || it.userAction != null } == true
            state.verdict?.takeIf { verdictShown }?.let { verdict ->
                VerdictBanner(verdict, onAllow = viewModel::allow, onFilter = viewModel::filter, onReport = { confirmReport = true })
            }
            val ownNumberDismissed by viewModel.ownNumberCardDismissed.collectAsStateWithLifecycle()
            if (state.isGroup && !phoneNumbersAllowed && !ownNumberDismissed) {
                OwnNumberBanner(
                    onAllow = { phoneNumbersPermission.launch(android.Manifest.permission.READ_PHONE_NUMBERS) },
                    onDismiss = viewModel::dismissOwnNumberCard,
                )
            }
            // Someone new, not yet answered: who is this? (Gone once they're added, or replied to.)
            var unknownDismissed by rememberSaveable(state.recipients) { mutableStateOf(false) }
            val unknown = state.addableContact
            if (unknown != null && !verdictShown && !unknownDismissed && state.messages.isNotEmpty() && state.messages.none { it.outgoing }) {
                UnknownSenderBanner(
                    onAddContact = { showOrCreateContact(context, unknown) },
                    onFilter = viewModel::filter,
                    onDismiss = { unknownDismissed = true },
                )
            }
            CompositionLocalProvider(LocalUriHandler provides uris) {
            MessageList(
                state = state,
                scheduled = scheduled,
                reminders = reminders,
                onScheduledSendNow = viewModel::sendScheduledNow,
                onScheduledEdit = viewModel::editScheduled,
                onScheduledDelete = viewModel::cancelScheduled,
                onScheduledReschedule = viewModel::rescheduleScheduled,
                onViewImage = { viewing = it },
                onViewVideo = { audio.release(); watching = it },
                audio = audio,
                onActions = { actionsFor = it },
                onRetry = viewModel::retry,
                onCopyCode = { copy(it, "Code copied") },
                highlight = query.trim().takeIf { searching && it.length >= 2 },
                focusKey = if (searching) focusKey else jumpTo,
                scheduledJustNow = scheduledJustNow,
                onMessageNumber = onMessageNumber,
                unreadOnOpen = unreadOnOpen,
                linkPreviewSenders = linkPreviewSenders,
                loadPreview = viewModel::preview,
                loadSmartLinks = viewModel::smartLinks,
                onSmartLink = { text, link, sentAt -> smartTapped = Triple(text, link, sentAt) },
                selected = selected,
                onToggleSelected = { m -> selected = if (m.key in selected) selected - m.key else selected + m.key },
                textScale = textScale,
                onTextScale = viewModel::setTextScale,
                modifier = Modifier.weight(1f),
            )
            }
        }
    }

    smartTapped?.let { (text, link, sentAt) ->
        SmartLinkSheet(
            text = text,
            link = link,
            loadActions = { viewModel.smartActions(text, link, sentAt) },
            onRun = { it.run(context) },
            onCopy = { copy(text.substring(link.start, link.end), "Copied") },
            onDismiss = { smartTapped = null },
        )
    }
    numberTapped?.let { number ->
        val name by produceState<String?>(null, number) { value = withContext(Dispatchers.IO) { viewModel.contactName(number) } }
        NumberSheet(
            number = number,
            name = name,
            canReadContacts = viewModel.canReadContacts(),
            // This conversation's own number needs no "Send message".
            isThisConversation = single?.let { normalizeAddress(it) == normalizeAddress(number) } == true,
            onDismiss = { numberTapped = null },
            onCall = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) } },
            onMessage = { onMessageNumber(number) },
            onContact = { showOrCreateContact(context, number) },
            onCopy = { copy(number, "Number copied") },
        )
    }

    actionsFor?.let { message ->
        // An MMS's subject goes along with its words: copied, forwarded and shared.
        val words = subjectAndText(message.subject, message.body)
        MessageActionsSheet(
            message = message,
            onDismiss = { actionsFor = null },
            onCopy = { copy(words, "Message copied") },
            onForward = {
                scope.launch {
                    val files = if (message.attachments.isEmpty()) emptyList() else viewModel.forwardable(message)
                    if (message.attachments.isNotEmpty() && files.isEmpty() && words.isBlank()) {
                        snackbar.showSnackbar("Couldn't copy that to forward it")
                    } else {
                        onForward(words, SharedAttachments.encode(files))
                    }
                }
            },
            onDelete = { confirmDeleteOne = message },
            onDetails = { detailsFor = message },
            onStar = { viewModel.toggleStar(message) },
            reminderAt = reminders[message.key],
            onRemind = { remindingFor = message },
            onReact = { emoji -> viewModel.react(message, emoji) },
            onSave = { save(message.attachments) },
            onShare = { share(message.attachments) },
            onSelect = { selected = setOf(message.key) },
            replyPrivately = message.sender?.takeIf { state.isGroup && !message.outgoing }?.let { sender ->
                (state.senderNames[sender] ?: sender) to { onMessageNumber(sender) }
            },
            onSelectText = { selectingText = words },
            onReactOther = { reactingWithOther = message },
            links = if (state.linksOff(message)) emptyList() else allWebLinks(message.body),
            onCopyLink = { copy(it, "Link copied") },
            onShareText = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, words)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            },
        )
    }
    selectingText?.let { text -> SelectTextDialog(text, onDismiss = { selectingText = null }) }
    reactingWithOther?.let { message ->
        OtherReactionDialog(
            onReact = { emoji -> viewModel.react(message, emoji); reactingWithOther = null },
            onDismiss = { reactingWithOther = null },
        )
    }
    if (choosingMute) {
        MuteDialog(
            onMute = { until -> viewModel.setMuted(true, until); choosingMute = false },
            onDismiss = { choosingMute = false },
        )
    }
    confirmDeleteOne?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDeleteOne = null },
            title = { Text("Delete this message?") },
            text = { Text("It's removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(message)
                    confirmDeleteOne = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteOne = null }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteSelected) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text(if (n == 1) "Delete this message?" else "Delete $n messages?") },
            text = { Text("${if (n == 1) "It's" else "They're"} removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessages(selectedMessages)
                    selected = emptySet()
                    confirmDeleteSelected = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } },
        )
    }
    remindingFor?.let { message ->
        ReminderDialog(
            current = reminders[message.key],
            onDismiss = { remindingFor = null },
            onPick = { at, label ->
                remindingFor = null
                viewModel.remind(message, at, label)
            },
            onRemove = {
                remindingFor = null
                viewModel.cancelReminder(message)
            },
        )
    }
    detailsFor?.let { message -> MessageDetailsDialog(message, state, sims, onDismiss = { detailsFor = null }) }
    viewing?.let { uri ->
        val images = remember(state.messages) { state.messages.flatMap { m -> m.attachments.filter { it.isImage } } }
        ImageViewer(
            images = images,
            start = images.indexOfFirst { it.uri == uri },
            onDismiss = { viewing = null },
            onShare = { share(listOf(it), overViewer = true) },
            onSave = { save(listOf(it), overViewer = true) },
        )
    }
    watching?.let { uri ->
        val video = remember(state.messages, uri) { state.messages.firstNotNullOfOrNull { m -> m.attachments.firstOrNull { it.uri == uri } } }
        VideoViewer(
            uri,
            onDismiss = { watching = null },
            onShare = video?.let { { share(listOf(it), overViewer = true) } },
            onSave = video?.let { { save(listOf(it), overViewer = true) } },
        )
    }
    if (confirmReport) {
        val spam = state.messages.lastOrNull { !it.outgoing }?.body.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text("Report to your carrier?") },
            text = {
                Text(
                    "US carriers collect spam at 7726 (\"SPAM\"). Winnow will open a message to 7726 with this text " +
                        "filled in for you to send. Your carrier usually replies asking for the sender's number.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReport = false
                    onReportSpam(spam)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmReport = false }) { Text("Cancel") } },
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Block ${state.title}?") },
            text = {
                Text(
                    "Android will drop texts and calls from this number before any app sees them. " +
                        "To only keep them out of your inbox, use \"Always filter this sender\" instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    viewModel.setBlocked(true)
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("It goes to Recently deleted, where it can be restored for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteConversation(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** A place, date or flight from a message, and what Android offers to do with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartLinkSheet(
    text: String,
    link: SmartLink,
    loadActions: suspend () -> List<SmartAction>,
    onRun: (SmartAction) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions by produceState<List<SmartAction>?>(null, text, link) { value = loadActions() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        val icon = when {
            link.isPlace -> Icons.Filled.LocationOn
            link.isDate -> Icons.Filled.DateRange
            else -> Icons.Filled.Info
        }
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                text.substring(link.start, link.end),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            when (val loaded = actions) {
                null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
                else -> loaded.forEach { action ->
                    ListItem(
                        headlineContent = { Text(action.title) },
                        leadingContent = { Icon(icon, contentDescription = null) },
                        colors = colors,
                        modifier = Modifier.clickable { onDismiss(); onRun(action) },
                    )
                }
            }
            ListItem(
                headlineContent = { Text("Copy") },
                leadingContent = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable { onDismiss(); onCopy() },
            )
        }
    }
}

/** Any emoji as a reaction, typed from the keyboard's emoji panel. */
@Composable
private fun OtherReactionDialog(onReact: (String) -> Unit, onDismiss: () -> Unit) {
    var emoji by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = isSingleEmoji(emoji)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("React with an emoji") },
        text = {
            OutlinedTextField(
                value = emoji,
                // One emoji, however many code points it takes (a family is eleven).
                onValueChange = { emoji = it.trim().take(32) },
                placeholder = { Text("🎉") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                supportingText = { Text(if (emoji.isNotEmpty() && !valid) "Just one emoji, please" else "Pick one from the keyboard's emoji panel") },
                isError = emoji.isNotEmpty() && !valid,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onReact(emoji) }, enabled = valid) { Text("React") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A number from a message: call it, text it, add it to contacts, or copy it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberSheet(
    number: String,
    name: String?,
    /** Without it, there's no telling whether the number is a contact. */
    canReadContacts: Boolean,
    isThisConversation: Boolean,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onContact: () -> Unit,
    onCopy: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        fun act(action: () -> Unit) = { onDismiss(); action() }
        val formatted = ContactLookup.formatAddress(number)
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(name ?: formatted, style = MaterialTheme.typography.titleMedium)
                if (name != null) Text(formatted, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ListItem(
                headlineContent = { Text("Call") },
                leadingContent = { Icon(Icons.Filled.Call, contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onCall)),
            )
            if (!isThisConversation) {
                ListItem(
                    headlineContent = { Text("Send message") },
                    leadingContent = { Icon(painterResource(R.drawable.ic_chat), contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onMessage)),
                )
            }
            ListItem(
                headlineContent = { Text(if (name != null) "View contact" else if (canReadContacts) "Add contact" else "Open in Contacts") },
                leadingContent = { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onContact)),
            )
            ListItem(
                headlineContent = { Text("Copy number") },
                leadingContent = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onCopy)),
            )
        }
    }
}

/** In a group, without the user's own number: ask for it, so they stop being listed as one of the people. */
@Composable
private fun OwnNumberBanner(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Which number is yours?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
            }
            Text(
                "Without the Phone numbers permission Winnow can't tell, so group texts may list you as one of the people. " +
                    "Allowing it fixes new group texts; your number stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

/** A first text from a number that isn't in contacts: add them, or filter them. */
@Composable
private fun UnknownSenderBanner(onAddContact: () -> Unit, onFilter: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Not in your contacts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
            }
            Text(
                "Know them? Add them. If not, Winnow can keep their texts out of your inbox.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onFilter) { Text("Filter sender") }
                TextButton(onClick = onAddContact) { Text("Add contact") }
            }
        }
    }
}

/** Why Winnow handled this conversation the way it did, and the one-tap correction. */
@Composable
private fun VerdictBanner(verdict: StoredVerdict, onAllow: () -> Unit, onFilter: () -> Unit, onReport: () -> Unit) {
    val (container, content) = categoryColors(if (verdict.userAction == Action.ALLOW) null else verdict.category)
    val label = verdict.category?.label ?: "This sender"
    val percent = if (verdict.confidence < 1.0) " · ${(verdict.confidence * 100).toInt()}%" else ""
    val (title, detail) = when {
        verdict.userAction == Action.ALLOW -> "You allowed this sender" to "Their messages will always reach your inbox."
        verdict.userAction != null -> "You filtered this sender" to "Their messages will skip your inbox without a notification."
        // A rule, not a category: its reason says it all ("Has “toll”, which you filter").
        verdict.category == null && verdict.action == Action.FILTER -> "Filtered" to "${verdict.source}. Kept out of your inbox, no notification."
        verdict.action == Action.FILTER -> "Filtered as $label$percent" to "${verdict.source}. Kept out of your inbox, no notification."
        else -> "Silenced: $label$percent" to "${verdict.source}. Delivered without a notification."
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (verdict.effectiveAction) {
                        Action.FILTER -> Icons.Filled.Warning
                        Action.SILENCE -> Icons.Filled.Info
                        Action.ALLOW -> Icons.Filled.CheckCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            // In the banner's own ink: the app's accent reads poorly on a red or amber banner.
            val buttons = ButtonDefaults.textButtonColors(contentColor = content)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (verdict.userAction == null && verdict.category in REPORTABLE) TextButton(onClick = onReport, colors = buttons) { Text("Report") }
                if (verdict.effectiveAction != Action.ALLOW) TextButton(onClick = onAllow, colors = buttons) { Text("Not spam", fontWeight = FontWeight.SemiBold) }
                if (verdict.effectiveAction != Action.FILTER) TextButton(onClick = onFilter, colors = buttons) { Text("Filter sender") }
            }
        }
    }
}

private val REPORTABLE = setOf(Category.SPAM, Category.SCAM, Category.PHISHING)

/** Camera photos: the ones a rotation re-encodes without losing anything that matters. */
private val ROTATABLE = setOf("image/jpeg", "image/jpg", "image/heic", "image/heif")

/** More than this many links, and the rest are copied with the text. */
private const val MAX_COPY_LINKS = 3

private sealed interface ListItem {
    val key: String

    /** "Texting with … (SMS/MMS)": which transport this conversation uses. */
    data class Transport(val text: String) : ListItem {
        override val key get() = "transport"
    }

    data class Header(val label: String, override val key: String) : ListItem

    /** "3 new messages": where the unread part began when the conversation was opened. */
    data class NewMessages(val count: Int) : ListItem {
        override val key get() = "new-messages"
    }
    data class Bubble(
        val message: ChatMessage,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean,
        /** Tapback emojis drawn on this bubble, e.g. "❤️" or "😂 2". */
        val reactions: List<String> = emptyList(),
    ) : ListItem {
        override val key get() = message.key
    }
}

/**
 * Folds tapback texts into the messages they quote. Returns the messages still worth showing
 * and the reactions per message key. A tapback whose original isn't found stays a message.
 */
private fun foldTapbacks(messages: List<ChatMessage>): Pair<List<ChatMessage>, Map<String, List<String>>> {
    val reactions = HashMap<String, MutableList<String>>()
    val shown = mutableListOf<ChatMessage>()
    for (m in messages) {
        val tapback = Tapback.parse(m.body)
        val target = tapback?.let { t ->
            shown.lastOrNull { prior ->
                t.matchesAttachments(prior.attachments.map { it.contentType }) ?: t.matches(prior.body)
            }
        }
        if (tapback == null || target == null) {
            shown += m
            continue
        }
        val list = reactions.getOrPut(target.key) { mutableListOf() }
        if (tapback.removal) list.remove(tapback.emoji) else list += tapback.emoji
    }
    val labels = reactions.mapValues { (_, emojis) ->
        emojis.groupingBy { it }.eachCount().map { (emoji, n) -> if (n > 1) "$emoji $n" else emoji }
    }.filterValues { it.isNotEmpty() }
    return shown to labels
}

/** The system's phone-number picker; the result is one readable Phone row. */
private object PickPhoneNumber : androidx.activity.result.contract.ActivityResultContract<Unit?, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: Unit?) =
        android.content.Intent(android.content.Intent.ACTION_PICK).setType(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? =
        intent?.data.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

/** Photo shapes already measured, so a bubble scrolled back into view doesn't jump from 4:3. */
private val photoRatios = android.util.LruCache<String, Float>(512)

/** A header opens every block of messages more than an hour after the previous one, like Messages. */
private const val BLOCK_GAP_MILLIS = 60 * 60_000L

/** Same-sender messages within a block group into one visual stack. */
private const val GROUP_GAP_MILLIS = 5 * 60_000L

/** Transport line, time headers and grouped bubbles. Newest first, for a reversed list. */
private fun buildItems(transport: String, messages: List<ChatMessage>, unreadOnOpen: List<String> = emptyList()): List<ListItem> {
    fun day(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    fun newBlock(prev: ChatMessage?, m: ChatMessage) =
        prev == null || m.timestamp - prev.timestamp > BLOCK_GAP_MILLIS || day(prev.timestamp) != day(m.timestamp)
    fun grouped(a: ChatMessage?, b: ChatMessage?) =
        a != null && b != null && a.outgoing == b.outgoing && a.sender == b.sender &&
            !newBlock(a, b) && b.timestamp - a.timestamp < GROUP_GAP_MILLIS

    val (shown, reactions) = foldTapbacks(messages)
    // The first unread message still shown: a reaction folded onto an older bubble isn't one.
    val unread = unreadOnOpen.toHashSet()
    val firstNew = shown.firstOrNull { it.key in unread }?.key
    val items = mutableListOf<ListItem>(ListItem.Transport(transport))
    shown.forEachIndexed { i, m ->
        val prev = shown.getOrNull(i - 1)
        val next = shown.getOrNull(i + 1)
        // Above the first message that was unread on opening, and above its time header if it has one.
        if (m.key == firstNew) items += ListItem.NewMessages(unreadOnOpen.size)
        if (newBlock(prev, m)) items += ListItem.Header(headerLabel(m.timestamp), "h-${m.key}")
        items += ListItem.Bubble(m, firstInGroup = !grouped(prev, m), lastInGroup = !grouped(m, next), reactions = reactions[m.key].orEmpty())
    }
    return items.asReversed()
}

@Composable
private fun MessageList(
    state: ThreadUiState,
    scheduled: List<ScheduledMessageEntity>,
    /** When messages are to come back ("Remind me"), by key. */
    reminders: Map<String, Long> = emptyMap(),
    onScheduledSendNow: (Long) -> Unit,
    onScheduledEdit: (ScheduledMessageEntity) -> Unit,
    onScheduledDelete: (Long) -> Unit,
    onScheduledReschedule: (id: Long, at: Long) -> Unit = { _, _ -> },
    onViewImage: (String) -> Unit,
    onViewVideo: (String) -> Unit,
    audio: AudioPlayer,
    onActions: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onCopyCode: (String) -> Unit,
    highlight: String? = null,
    focusKey: String? = null,
    /** Set when the user schedules a text, so the list shows it once it's in. */
    scheduledJustNow: MutableState<Boolean> = remember { mutableStateOf(false) },
    onMessageNumber: (String) -> Unit = {},
    unreadOnOpen: List<String> = emptyList(),
    linkPreviewSenders: Set<String>? = null,
    loadPreview: suspend (String) -> LinkPreview? = { null },
    /** Places, dates and flights in a message's text, from Android's text classifier. */
    loadSmartLinks: suspend (String) -> List<SmartLink> = { emptyList() },
    onSmartLink: (String, SmartLink, Long) -> Unit = { _, _, _ -> },
    selected: Set<String> = emptySet(),
    onToggleSelected: (ChatMessage) -> Unit = {},
    textScale: Float = 1f,
    onTextScale: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Follows the pinch live; saved when the fingers lift.
    var liveScale by remember { mutableFloatStateOf(textScale) }
    LaunchedEffect(textScale) { liveScale = textScale }
    val saveScale by rememberUpdatedState(onTextScale)
    val other = state.recipients.firstOrNull().orEmpty()
    val transport = when {
        state.isGroup -> "Group texting with ${state.recipients.size} people (MMS)"
        // An email address only ever gets an MMS.
        isEmailAddress(other) -> "Texting with $other (MMS)"
        else -> "Texting with ${ContactLookup.formatAddress(other)} (SMS/MMS)"
    }
    val items = remember(transport, state.messages, unreadOnOpen) { buildItems(transport, state.messages, unreadOnOpen) }
    val latestOutgoing = state.messages.lastOrNull { it.outgoing }?.key
    var revealed by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // A message arriving (or sent) while the newest ones are in view scrolls up into view. A
    // lazy list otherwise keeps the item that was at the bottom in place, leaving the new one
    // just below the edge. Someone reading further up is left where they are.
    // "In view" means the previous newest message is still on screen; a new message can bring a
    // time header with it, so its index alone doesn't say.
    // A message, never the "Texting with…" line that's all the list holds before messages load.
    val newestKey = items.firstOrNull()?.takeIf { it is ListItem.Bubble }?.key
    var shownNewest by remember { mutableStateOf<String?>(null) }
    // Texts that came in while the user was reading further up, for the jump-to-newest button.
    var missed by remember { mutableIntStateOf(0) }
    LaunchedEffect(newestKey) {
        val previous = shownNewest
        shownNewest = newestKey
        if (newestKey == null || focusKey != null) return@LaunchedEffect
        if (previous == null || listState.layoutInfo.visibleItemsInfo.any { it.key == previous }) {
            listState.animateScrollToItem(0)
        } else if (state.messages.lastOrNull()?.outgoing == false && listState.firstVisibleItemIndex > 2) {
            missed++
        }
    }
    val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    LaunchedEffect(scrolledUp) { if (!scrolledUp) missed = 0 }
    val jumpScope = rememberCoroutineScope()
    // A text the user just scheduled sits below the newest message: brought into view. (Not
    // when the list of scheduled texts first loads, which would undo a jump to a message.)
    LaunchedEffect(scheduled.size) {
        if (scheduledJustNow.value) {
            scheduledJustNow.value = false
            listState.animateScrollToItem(0)
        }
    }
    // Opening onto more new messages than fit on screen starts at the first of them, not the last.
    var jumpedToNew by remember { mutableStateOf(false) }
    LaunchedEffect(unreadOnOpen, items) {
        // Opening onto a particular message wins.
        if (focusKey != null) jumpedToNew = true
        if (jumpedToNew || unreadOnOpen.size < NEW_MESSAGES_JUMP) return@LaunchedEffect
        val index = items.indexOfFirst { it is ListItem.NewMessages }
        if (index < 0) return@LaunchedEffect
        jumpedToNew = true
        // Reversed list: the divider lands near the top, the new messages below it.
        listState.scrollToItem(scheduled.size + index, -listState.layoutInfo.viewportSize.height * 2 / 3)
    }
    val focusIndex = remember(items, focusKey) { items.indexOfFirst { it.key == focusKey } }
    // Again once it's there: a conversation opened onto a message may still be loading.
    LaunchedEffect(focusKey, focusIndex >= 0) {
        // Reversed list: a negative offset lifts the match off the composer, a third of the way up.
        if (focusKey != null && focusIndex >= 0) listState.animateScrollToItem(scheduled.size + focusIndex, -listState.layoutInfo.viewportSize.height / 3)
    }
    Box(modifier.fillMaxWidth()) {
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize().pinchToZoom(
            onZoom = { liveScale = TextScale.clamp(liveScale * it) },
            onEnd = {
                liveScale = TextScale.settle(liveScale)
                saveScale(liveScale)
            },
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Reversed list: scheduled messages sit below everything already sent, latest last.
        items(scheduled.asReversed(), key = { "scheduled-${it.id}" }) { message ->
            ScheduledBubble(
                message,
                onSendNow = { onScheduledSendNow(message.id) },
                onEdit = { onScheduledEdit(message) },
                onDelete = { onScheduledDelete(message.id) },
                onReschedule = { at -> onScheduledReschedule(message.id, at) },
            )
        }
        items(items, key = { it.key }) { item ->
            when (item) {
                is ListItem.Transport -> CenteredNote(item.text, Modifier.padding(vertical = 4.dp))
                is ListItem.Header -> CenteredNote(item.label, Modifier.padding(top = 20.dp, bottom = 8.dp))
                is ListItem.NewMessages -> NewMessagesDivider(item.count)
                is ListItem.Bubble -> {
                    // While selecting, every tap on a message selects or deselects it.
                    val selecting = selected.isNotEmpty()
                    val toggle = { onToggleSelected(item.message) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(if (item.key in selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                            .semantics { if (selecting) this.selected = item.key in selected },
                    ) {
                        MessageBubble(
                            item = item,
                            senderName = item.message.sender?.let { state.senderNames[it] }?.takeIf { state.isGroup },
                            speaker = if (item.message.outgoing) "You" else item.message.sender?.let { state.senderNames[it] } ?: state.title,
                            senderPhoto = item.message.sender?.let { state.photos[it] },
                            showTime = revealed == item.key,
                            isLatestOutgoing = item.key == latestOutgoing,
                            onClick = { if (selecting) toggle() else revealed = if (revealed == item.key) null else item.key },
                            onLongClick = { if (selecting) toggle() else onActions(item.message) },
                            onViewImage = { if (selecting) toggle() else onViewImage(it) },
                            onRetry = { if (selecting) toggle() else onRetry(item.message) },
                            onCopyCode = onCopyCode,
                            audio = audio,
                            onViewVideo = { if (selecting) toggle() else onViewVideo(it) },
                            highlight = highlight,
                            focused = item.key == focusKey,
                            onMessageNumber = onMessageNumber,
                            textScale = liveScale,
                            // Whether this message may load a preview, decided here where the sender is known.
                            linkPreviews = linkPreviewSenders != null && previewAllowed(item.message, state.recipients, linkPreviewSenders),
                            loadPreview = loadPreview,
                            loadSmartLinks = loadSmartLinks,
                            onSmartLink = onSmartLink,
                            onPreviewClick = if (selecting) toggle else null,
                            fraud = state.linksOff(item.message),
                            reminderAt = reminders[item.key],
                        )
                    }
                }
            }
        }
    }
    // Scrolled up: a way back to the newest, saying how many came in meanwhile.
    androidx.compose.animation.AnimatedVisibility(
        visible = scrolledUp,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
        SmallFloatingActionButton(
            onClick = {
                jumpScope.launch {
                    // A long way back would animate through every message on the way; just go.
                    if (listState.firstVisibleItemIndex > 40) listState.scrollToItem(0) else listState.animateScrollToItem(0)
                }
            },
            modifier = Modifier.semantics { contentDescription = if (missed > 0) "Jump to newest, $missed new" else "Jump to newest" },
        ) {
            BadgedBox(badge = { if (missed > 0) Badge { Text(if (missed > 99) "99+" else "$missed") } }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        }
    }
    }
}

/**
 * Reports two-finger pinches as zoom factors. One finger passes straight through, so the list
 * underneath still scrolls and bubbles still take taps and long presses.
 */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onZoom(zoom)
                    zoomed = true
                }
                event.changes.forEach { it.consume() }
            }
        }
        if (zoomed) onEnd()
    }
}

@Composable
private fun NewMessagesDivider(count: Int) {
    val color = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.5f))
        Text(
            if (count == 1) "1 new message" else "$count new messages",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.5f))
    }
}

/**
 * A message may load a link preview when the user sent it, or when its sender is trusted (see
 * ThreadViewModel.linkPreviewSenders) and its verdict is a plain "allow". No verdict yet (still
 * being classified, or classification failed) counts as no: an unjudged text never makes
 * Winnow fetch anything.
 */
private fun previewAllowed(m: ChatMessage, recipients: List<String>, trusted: Set<String>): Boolean {
    if (m.outgoing) return true
    val verdict = m.verdict ?: return false
    if (verdict.effectiveAction != Action.ALLOW || verdict.isFraud) return false
    val sender = m.sender ?: recipients.singleOrNull() ?: return false
    return normalizeAddress(sender) in trusted
}

/** At least this many new messages on opening, and the conversation starts at the first of them. */
private const val NEW_MESSAGES_JUMP = 6

@Composable
private fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: ListItem.Bubble,
    senderName: String?,
    senderPhoto: String?,
    showTime: Boolean,
    isLatestOutgoing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onViewImage: (String) -> Unit,
    onRetry: () -> Unit,
    onCopyCode: (String) -> Unit,
    audio: AudioPlayer,
    onViewVideo: (String) -> Unit,
    highlight: String? = null,
    focused: Boolean = false,
    onMessageNumber: (String) -> Unit = {},
    textScale: Float = 1f,
    /** Who said it, for screen readers, which can't see which side a bubble is on. */
    speaker: String = "",
    linkPreviews: Boolean = false,
    loadPreview: suspend (String) -> LinkPreview? = { null },
    /** Places, dates and flights in a message's text, from Android's text classifier. */
    loadSmartLinks: suspend (String) -> List<SmartLink> = { emptyList() },
    onSmartLink: (String, SmartLink, Long) -> Unit = { _, _, _ -> },
    onPreviewClick: (() -> Unit)? = null,
    /** Its links are off (see ThreadUiState.linksOff). */
    fraud: Boolean = item.message.verdict?.isFraud == true,
    /** When it's to come back, if the user asked to be reminded. */
    reminderAt: Long? = null,
) {
    val m = item.message
    val spoken = buildString {
        append(speaker.ifEmpty { if (m.outgoing) "You" else "Them" }).append(": ")
        m.subject?.let { append("Subject: ").append(it).append(". ") }
        append(m.body).append(", ").append(timeOfDay(m.timestamp))
        if (m.outgoing) when (m.status) {
            ChatMessage.Status.SENDING -> append(", sending")
            ChatMessage.Status.FAILED -> append(", not sent")
            ChatMessage.Status.DELIVERED -> append(", delivered")
            else -> Unit
        }
    }
    val colors = MaterialTheme.colorScheme
    val big = 22.dp
    val small = 6.dp
    val shape = if (m.outgoing) {
        RoundedCornerShape(big, if (item.firstInGroup) big else small, if (item.lastInGroup) big else small, big)
    } else {
        RoundedCornerShape(if (item.firstInGroup) big else small, big, big, if (item.lastInGroup) big else small)
    }
    // Found off the main thread, after the text is showing; never for fraud, whose links are off.
    val smart by produceState(emptyList<SmartLink>(), m.body, fraud) { value = if (fraud) emptyList() else loadSmartLinks(m.body) }
    val showAvatarColumn = senderName != null

    Column(
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(top = if (item.firstInGroup) 8.dp else 2.dp),
    ) {
        if (senderName != null && item.firstInGroup) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelMedium,
                color = avatarColors(m.sender.orEmpty()).second,
                modifier = Modifier.padding(start = 48.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (showAvatarColumn) {
                if (item.lastInGroup) Avatar(senderName.orEmpty(), seed = m.sender.orEmpty(), size = 36.dp, photoUri = senderPhoto) else Spacer(Modifier.width(36.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(
                horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Most of a phone's width, but not a tablet pane's: long lines get hard to read.
                // (wrapContentWidth first, or fillMaxWidth's fixed width would override the cap.)
                modifier = Modifier.fillMaxWidth(if (showAvatarColumn) 0.85f else 0.8f)
                    .wrapContentWidth(if (m.outgoing) Alignment.End else Alignment.Start)
                    .widthIn(max = 560.dp),
            ) {
                m.attachments.forEach { attachment ->
                    if (VCard.isVCard(attachment.contentType)) {
                        ContactCardAttachment(attachment.uri, outgoing = m.outgoing, onMessage = onMessageNumber, onLongClick = onLongClick)
                    } else if (attachment.isAudio) {
                        AudioAttachment(attachment.uri, audio, outgoing = m.outgoing)
                    } else if (attachment.isVideo) {
                        VideoAttachment(attachment.uri, attachment.name, onOpen = { onViewVideo(attachment.uri) })
                    } else if (attachment.isImage) {
                        // Drawn in the photo's own shape, up to 260 x 320 dp; 4:3 until it has loaded once.
                        var ratio by remember(attachment.uri) { mutableFloatStateOf(photoRatios[attachment.uri] ?: (4f / 3f)) }
                        AsyncImage(
                            model = attachment.uri,
                            contentDescription = if (m.outgoing) "Photo you sent" else "Photo from ${speaker.ifEmpty { "them" }}",
                            contentScale = ContentScale.Crop,
                            onSuccess = { loaded ->
                                val image = loaded.result.image
                                if (image.width > 0 && image.height > 0) {
                                    ratio = (image.width.toFloat() / image.height).coerceIn(0.5f, 2.5f)
                                    photoRatios.put(attachment.uri, ratio)
                                }
                            },
                            onError = { Log.w("WinnowImage", "Couldn't load ${attachment.uri}", it.result.throwable) },
                            modifier = Modifier
                                .width(minOf(260f, 320f * ratio).dp)
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(18.dp))
                                .combinedClickable(onClick = { onViewImage(attachment.uri) }, onLongClick = onLongClick),
                        )
                    } else {
                        Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(14.dp)) {
                            Text(
                                attachment.name ?: attachment.contentType,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                when {
                    m.status == ChatMessage.Status.DOWNLOAD_FAILED -> Text(
                        "Couldn't download this MMS · Tap to retry",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.error,
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.surfaceContainerHigh)
                            .combinedClickable(onClickLabel = "Retry", onClick = onRetry, onLongClick = onLongClick)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    m.status == ChatMessage.Status.NOT_DOWNLOADED -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.surfaceContainerHigh)
                            .combinedClickable(onClickLabel = "Download", onClick = onRetry, onLongClick = onLongClick)
                            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = colors.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(deferredPreview(m.downloadSize).replace("tap", "Tap"), style = MaterialTheme.typography.bodyLarge)
                    }
                    m.status == ChatMessage.Status.DOWNLOADING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.surfaceContainerHigh)
                            .combinedClickable(onClick = {}, onLongClick = onLongClick)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Downloading MMS…", style = MaterialTheme.typography.bodyLarge)
                    }
                    m.body.isBlank() && m.subject == null -> Unit
                    m.subject == null && isEmojiOnly(m.body) -> Text(
                        m.body,
                        fontSize = 44.sp * textScale,
                        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).semantics { contentDescription = spoken },
                    )
                    else -> Text(
                        buildAnnotatedString {
                            // An MMS subject heads the bubble, in bold, as in Messages.
                            m.subject?.let { subject ->
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(subject) }
                                if (m.body.isNotBlank()) append("\n")
                            }
                            append(
                                linkify(
                                    m.body, links = !fraud, linkColor = if (m.outgoing) colors.onPrimaryContainer else colors.primary,
                                    smart = smart, onSmart = { onSmartLink(m.body, it, m.timestamp) },
                                ),
                            )
                        }
                            .highlighted(highlight, if (focused) colors.tertiary.copy(alpha = 0.7f) else colors.tertiary.copy(alpha = 0.35f)),
                        style = MaterialTheme.typography.bodyLarge.scaled(textScale),
                        color = if (m.outgoing) colors.onPrimaryContainer else colors.onSurface,
                        modifier = Modifier
                            .clip(shape)
                            .background(if (m.outgoing) colors.primaryContainer else colors.surfaceContainerHigh)
                            .combinedClickable(onClickLabel = "Show time", onClick = onClick, onLongClickLabel = "More options", onLongClick = onLongClick)
                            .semantics { contentDescription = spoken }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                if (linkPreviews && !m.isPlaceholder) {
                    firstWebLink(m.body)?.let { url -> LinkPreviewCard(url, m.outgoing, loadPreview, onClick = onPreviewClick, onLongClick = onLongClick) }
                }
                if (item.reactions.isNotEmpty()) {
                    // Tucked under the bubble's corner, as Messages draws reactions.
                    Surface(
                        color = colors.surfaceContainerHighest,
                        shape = CircleShape,
                        border = BorderStroke(2.dp, colors.surface),
                        modifier = Modifier
                            .offset(x = if (m.outgoing) (-8).dp else 8.dp, y = (-10).dp)
                            .clearAndSetSemantics { contentDescription = "Reactions: ${item.reactions.joinToString(", ")}" },
                    ) {
                        Text(
                            item.reactions.joinToString(" "),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                reminderAt?.let { at ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reminder · ${scheduleLabel(at)}", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    }
                }
                if (fraud && m.body.contains('.')) {
                    // Its own verdict says why; a text that's off because of its sender's says that.
                    val why = m.verdict?.takeIf { it.isFraud }?.category?.label?.lowercase()?.let { "this looks like $it" }
                        ?: "this sender has sent phishing or scams"
                    Text("Links turned off: $why", style = MaterialTheme.typography.labelSmall, color = colors.error)
                }
                if (!m.outgoing) {
                    VerificationCodes.find(m.body)?.let { code ->
                        AssistChip(
                            onClick = { onCopyCode(code) },
                            label = { Text("Copy $code") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
        val status = when (m.status) {
            ChatMessage.Status.SENDING -> "Sending…"
            ChatMessage.Status.FAILED -> "Not sent · Tap to retry"
            ChatMessage.Status.DELIVERED -> when {
                showTime -> "Delivered · ${timeOfDay(m.timestamp)}"
                isLatestOutgoing -> "Delivered"
                else -> null
            }
            else -> if (showTime) timeOfDay(m.timestamp) else null
        }
        if (status != null || m.starred) {
            val failed = m.status == ChatMessage.Status.FAILED
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(if (failed) Modifier.clickable(onClick = onRetry) else Modifier)
                    .padding(start = if (showAvatarColumn) 56.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            ) {
                if (m.starred) {
                    Icon(Icons.Filled.Star, contentDescription = "Starred", tint = colors.tertiary, modifier = Modifier.size(14.dp))
                    if (status != null) Spacer(Modifier.width(4.dp))
                }
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = if (failed) colors.error else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onStar: () -> Unit,
    /** When it's to come back, if a reminder is set; [onRemind] sets or changes it. */
    reminderAt: Long? = null,
    onRemind: () -> Unit = {},
    onReact: (String) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onSelect: () -> Unit,
    onSelectText: () -> Unit,
    onShareText: () -> Unit,
    /** In a group, someone else's message: a one-to-one conversation with them, named. */
    replyPrivately: Pair<String, () -> Unit>? = null,
    /** React with an emoji that isn't one of the six. */
    onReactOther: () -> Unit = {},
    /** The message's links, to copy on their own; none for fraud, whose links can't be tapped either. */
    links: List<String> = emptyList(),
    onCopyLink: (String) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        fun act(action: () -> Unit) = { onDismiss(); action() }
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            // Reactions go out as text ("Loved “…”"), so they only make sense on real messages.
            if (!message.isPlaceholder) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Tapback.CHOICES.forEach { emoji ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(onClickLabel = "React $emoji") { onDismiss(); onReact(emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 22.sp) }
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(onClickLabel = "React with another emoji") { onDismiss(); onReactOther() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, contentDescription = "Another emoji") }
                }
            }
            if (message.body.isNotBlank() || message.subject != null) {
                ListItem(
                    headlineContent = { Text("Copy text") },
                    leadingContent = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onCopy)),
                )
                // A link on its own, without the rest of the message around it.
                links.take(MAX_COPY_LINKS).forEach { url ->
                    ListItem(
                        headlineContent = { Text("Copy link") },
                        // The real host, cut from the front: "paypal.com-verify….evil.example" ends in what matters.
                        supportingContent = {
                            Text(url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                        colors = colors,
                        modifier = Modifier.clickable(onClick = act { onCopyLink(url) }),
                    )
                }
                ListItem(
                    headlineContent = { Text("Select text") },
                    supportingContent = { Text("Copy just part of it") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onSelectText)),
                )
                ListItem(
                    headlineContent = { Text("Forward") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onForward)),
                )
                // Attachments have their own Share below; a text-only message shares its text.
                if (message.attachments.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("Share") },
                        leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                        colors = colors,
                        modifier = Modifier.clickable(onClick = act(onShareText)),
                    )
                }
            }
            // Downloaded attachments only; a placeholder has nothing to save yet.
            if (message.attachments.isNotEmpty() && !message.isPlaceholder) {
                ListItem(
                    headlineContent = { Text(if (message.attachments.size == 1) "Save to phone" else "Save ${message.attachments.size} attachments") },
                    leadingContent = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onSave)),
                )
                ListItem(
                    headlineContent = { Text("Share") },
                    leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onShare)),
                )
            }
            ListItem(
                headlineContent = { Text(if (message.starred) "Unstar" else "Star") },
                leadingContent = { Icon(if (message.starred) Icons.Outlined.Star else Icons.Filled.Star, contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onStar)),
            )
            if (!message.isPlaceholder) {
                ListItem(
                    headlineContent = { Text(if (reminderAt == null) "Remind me" else "Change reminder") },
                    supportingContent = reminderAt?.let { { Text(scheduleLabel(it)) } },
                    leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(onRemind)),
                )
            }
            ListItem(
                headlineContent = { Text("Select") },
                supportingContent = { Text("Then tap more messages to copy, star or delete them together") },
                leadingContent = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onSelect)),
            )
            replyPrivately?.let { (name, open) ->
                ListItem(
                    headlineContent = { Text("Reply privately") },
                    supportingContent = { Text("A conversation with just $name") },
                    leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = act(open)),
                )
            }
            ListItem(
                headlineContent = { Text("View details") },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onDetails)),
            )
            ListItem(
                headlineContent = { Text("Delete") },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                colors = colors,
                modifier = Modifier.clickable(onClick = act(onDelete)),
            )
        }
    }
}

@Composable
private fun MessageDetailsDialog(message: ChatMessage, state: ThreadUiState, sims: List<SimCard>, onDismiss: () -> Unit) {
    val at = Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
    val rows = buildList {
        add("Type" to if (message.kind == ChatMessage.Kind.MMS) "Multimedia message (MMS)" else "Text message (SMS)")
        if (message.outgoing) {
            add("To" to state.recipients.joinToString(", ") { ContactLookup.formatAddress(it) })
            add("Sent" to at)
        } else {
            val from = message.sender ?: state.recipients.firstOrNull().orEmpty()
            val number = ContactLookup.formatAddress(from)
            val name = state.senderNames[from] ?: state.title.takeIf { !state.isGroup }
            add("From" to if (name != null && name != number) "$name · $number" else number)
            add("Received" to at)
        }
        val status = when (message.status) {
            ChatMessage.Status.SENDING -> "Sending"
            ChatMessage.Status.SENT -> "Sent" + if (message.kind == ChatMessage.Kind.SMS) " (no delivery report)" else ""
            ChatMessage.Status.DELIVERED -> "Delivered"
            ChatMessage.Status.FAILED -> "Not sent"
            ChatMessage.Status.DOWNLOADING -> "Downloading"
            ChatMessage.Status.DOWNLOAD_FAILED -> "Couldn't download"
            ChatMessage.Status.NOT_DOWNLOADED -> "Not downloaded yet${message.downloadSize.takeIf { it > 0 }?.let { " (${it / 1000} KB)" }.orEmpty()}"
            ChatMessage.Status.RECEIVED -> null
        }
        status?.let { add("Status" to it) }
        // A long text goes out in parts, each counted (and maybe billed) as a text.
        if (message.kind == ChatMessage.Kind.SMS && message.body.isNotEmpty()) {
            val parts = android.telephony.SmsMessage.calculateLength(message.body, false)[0]
            if (parts > 1) add("Length" to "${message.body.length} characters, sent as $parts texts")
        }
        // Which SIM only matters on a phone with more than one.
        sims.firstOrNull { it.subscriptionId == message.subscriptionId }?.let { add("SIM" to "${it.slotName} · ${it.label}") }
        message.subject?.let { add("Subject" to it) }
        if (message.attachments.isNotEmpty()) add("Attachments" to message.attachments.joinToString { it.contentType })
        message.verdict?.let { v ->
            val percent = if (v.confidence < 1.0) " (${(v.confidence * 100).toInt()}%)" else ""
            add("Winnow" to "${v.label}$percent → ${v.effectiveAction.name.lowercase()}")
            add("Decided by" to v.source)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalFoundationApi::class) // contentReceiver
@Composable
private fun Composer(
    sims: List<SimCard>,
    selectedSim: SimCard?,
    onSelectSim: (Int) -> Unit,
    field: TextFieldState,
    /** A GIF, sticker or picture sent from the keyboard: its URI and type. */
    onKeyboardContent: (android.net.Uri, String?) -> Unit,
    attachments: List<OutgoingAttachment>,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onContact: () -> Unit,
    onRemoveAttachment: (OutgoingAttachment) -> Unit,
    /** A photo turned a quarter-turn: camera photos only, not stickers or GIFs, which would lose motion or transparency. */
    onRotateAttachment: (OutgoingAttachment) -> Unit = {},
    onVoice: () -> Unit = {},
    onVideo: () -> Unit = {},
    onLocation: () -> Unit = {},
    quickReplies: List<String> = emptyList(),
    onQuickReply: (String) -> Unit = {},
    /** Reply ideas for the newest message (see ThreadViewModel.suggestedReplies); a tap puts one in the draft. */
    suggestions: List<String> = emptyList(),
    locating: Boolean = false,
    /** A video being made small enough to send, 0–100; null when none is. */
    shrinking: Int? = null,
    recording: Boolean = false,
    recordingElapsed: () -> Long = { 0 },
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    /** The MMS subject line: null for no subject field. */
    subject: TextFieldState? = null,
    onAddSubject: () -> Unit = {},
    onRemoveSubject: () -> Unit = {},
    isSms: Boolean,
    /** The draft is long enough that the carrier has it sent as an MMS. */
    sendsAsMms: Boolean = false,
    onSend: () -> Unit,
    onSchedule: (at: Long, label: String) -> Unit,
    enterToSend: Boolean = false,
    onSendSeparately: (() -> Unit)? = null,
    /** Send goes to each person separately: a message that came back from "Send separately". */
    sendSeparately: Boolean = false,
    onClearSendSeparately: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val draft = field.text
    val subjectFocus = remember { FocusRequester() }
    // Focused when it's added from the menu, not when it comes back with a saved draft.
    var focusSubject by remember { mutableStateOf(false) }
    LaunchedEffect(focusSubject, subject != null) {
        if (focusSubject && subject != null) {
            runCatching { subjectFocus.requestFocus() }
            focusSubject = false
        }
    }
    Column(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().imePadding()) {
        if (recording) {
            RecordingBar(recordingElapsed, onCancel = onCancelRecording, onDone = onStopRecording)
            return@Column
        }
        if (locating) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Finding your location…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        if (shrinking != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Making the video small enough to send… $shrinking%", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                LinearProgressIndicator(progress = { shrinking / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (sendSeparately) {
            InputChip(
                selected = true,
                onClick = onClearSendSeparately,
                label = { Text("Sending to each person separately") },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Send to the group instead", Modifier.size(InputChipDefaults.IconSize)) },
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
        // Until the user starts writing: then they're in the way.
        if (suggestions.isNotEmpty() && draft.isEmpty() && attachments.isEmpty() && subject == null && !sendSeparately) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(top = 6.dp).semantics { contentDescription = "Suggested replies" },
            ) {
                items(suggestions) { reply ->
                    SuggestionChip(
                        onClick = { onQuickReply(reply) },
                        label = { Text(reply, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = RoundedCornerShape(18.dp),
                    )
                }
            }
        }
        if (attachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(attachments, key = { it.uri }) { attachment ->
                    Box {
                        AttachmentThumbnail(
                            attachment.uri,
                            attachment.contentType,
                            attachment.name,
                            Modifier.size(88.dp).clip(RoundedCornerShape(16.dp)),
                        )
                        // A plain circle, not an IconButton, which would grow itself to a 48dp target and cover the photo.
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                                .background(colors.surface.copy(alpha = 0.85f))
                                .clickable(onClickLabel = "Remove attachment") { onRemoveAttachment(attachment) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Close, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp)) }
                        if (attachment.contentType.lowercase() in ROTATABLE) {
                            Box(
                                Modifier.align(Alignment.BottomStart).padding(4.dp).size(26.dp).clip(CircleShape)
                                    .background(colors.surface.copy(alpha = 0.85f))
                                    .clickable(onClickLabel = "Rotate photo") { onRotateAttachment(attachment) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(painterResource(R.drawable.ic_rotate), contentDescription = "Rotate photo", modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Column {
                if (subject != null) {
                    SubjectField(subject, onRemoveSubject, Modifier.focusRequester(subjectFocus))
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp)) {
                    AttachMenu(
                        onGallery = onAttach, onCamera = onCamera, onVideo = onVideo, onContact = onContact, onVoice = onVoice, onLocation = onLocation,
                        quickReplies = quickReplies, onQuickReply = onQuickReply,
                        onSubject = if (subject == null) ({ focusSubject = true; onAddSubject() }) else null,
                    )
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        if (draft.isEmpty()) {
                            val kind = if (isSms) "Text message" else "MMS message"
                            Text(
                                selectedSim?.let { "$kind · ${it.label}" } ?: kind,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            state = field,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                            // With "Enter sends": the on-screen keyboard shows a Send key, and a
                            // hardware Enter sends while Shift+Enter still starts a new line.
                            keyboardOptions = if (enterToSend) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                            onKeyboardAction = { onSend() },
                            modifier = Modifier.fillMaxWidth()
                                // GIFs and stickers from the keyboard become attachments; anything else (text) is typed as usual.
                                .contentReceiver { content ->
                                    if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
                                    val description = content.clipMetadata.clipDescription
                                    content.consume { item ->
                                        val uri = item.uri ?: return@consume false
                                        onKeyboardContent(uri, (0 until description.mimeTypeCount).map(description::getMimeType).firstOrNull { it.startsWith("image/") })
                                        true
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                if (enterToSend && event.key == Key.Enter && !event.isShiftPressed) {
                                    if (event.type == KeyEventType.KeyDown) onSend()
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                    }
                    if (isSms && draft.length >= 100) {
                        if (sendsAsMms) {
                            Text(
                                "MMS",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Long enough that your carrier has it sent as an MMS" },
                            )
                        } else {
                            SegmentCounter(draft.toString())
                        }
                    }
                    if (sims.size >= 2 && selectedSim != null) SimPicker(sims, selectedSim, onSelectSim)
                }
                }
            }
            Spacer(Modifier.width(8.dp))
            SendButton(
                enabled = draft.isNotBlank() || attachments.isNotEmpty() || subject?.text?.isNotBlank() == true,
                onSend = onSend, onSchedule = onSchedule, onSendSeparately = onSendSeparately,
            )
        }
    }
}

/**
 * A subject is one short line: line breaks become spaces, and what's typed or pasted stops at
 * MAX_SUBJECT characters, cut from its own end (never the subject's, and never through an
 * emoji). One already longer (two joined when a message came back) can shrink, not grow.
 */
@OptIn(ExperimentalFoundationApi::class) // TextFieldBuffer.changes
private object SubjectInput : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        // Backwards, so a "\r\n" becoming one space doesn't move what's still to look at.
        var i = length - 1
        while (i >= 0) {
            val c = asCharSequence()[i]
            if (c == '\n' && i > 0 && asCharSequence()[i - 1] == '\r') {
                replace(i - 1, i + 1, " ")
                i -= 2
                continue
            }
            if (c == '\n' || c == '\r') replace(i, i + 1, " ")
            i--
        }
        val over = length - maxOf(ThreadViewModel.MAX_SUBJECT, originalText.length)
        if (over <= 0) return
        val inserted = if (changes.changeCount > 0) changes.getRange(changes.changeCount - 1) else null
        if (inserted == null || inserted.length < over) {
            revertAllChanges()
            return
        }
        val characters = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(asCharSequence().toString()) }
        val wanted = inserted.end - over
        val cut = if (characters.isBoundary(wanted)) wanted else characters.preceding(wanted)
        if (cut < inserted.start) revertAllChanges() else replace(cut, inserted.end, "")
    }
}

/** An MMS subject line, above the message in the composer, with an X to drop it. */
@Composable
private fun SubjectField(subject: TextFieldState, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val length = subject.text.length
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (length == 0) {
                Text("Subject", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
            BasicTextField(
                state = subject,
                inputTransformation = SubjectInput,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = modifier.fillMaxWidth().semantics { contentDescription = "Subject" },
            )
        }
        if (length >= ThreadViewModel.MAX_SUBJECT - 10) {
            Text(
                "$length/${ThreadViewModel.MAX_SUBJECT}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove subject", Modifier.size(20.dp)) }
    }
}

/** The composer's "+": a photo from the gallery, or a new one from the camera. */
@Composable
private fun AttachMenu(
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onVideo: () -> Unit,
    onContact: () -> Unit,
    onVoice: () -> Unit,
    onLocation: () -> Unit,
    quickReplies: List<String> = emptyList(),
    onQuickReply: (String) -> Unit = {},
    /** Adds a subject line; null when there already is one. */
    onSubject: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    // The menu turns into the list of quick replies.
    var replies by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true; replies = false }) { Icon(Icons.Outlined.AddCircle, contentDescription = "Attach") }
        DropdownMenu(expanded = open && replies, onDismissRequest = { open = false }) {
            quickReplies.forEach { reply ->
                DropdownMenuItem(text = { Text(reply, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { open = false; onQuickReply(reply) })
            }
        }
        DropdownMenu(expanded = open && !replies, onDismissRequest = { open = false }) {
            if (quickReplies.isNotEmpty()) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    text = { Text("Quick reply") },
                    onClick = { replies = true },
                )
            }
            DropdownMenuItem(
                leadingIcon = { Icon(painterResource(R.drawable.ic_photo), contentDescription = null) },
                text = { Text("Gallery") },
                onClick = { open = false; onGallery() },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                text = { Text("Camera") },
                onClick = { open = false; onCamera() },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(painterResource(R.drawable.ic_videocam), contentDescription = null) },
                text = { Text("Video") },
                onClick = { open = false; onVideo() },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                text = { Text("Contact") },
                onClick = { open = false; onContact() },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                text = { Text("Voice message") },
                onClick = { open = false; onVoice() },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                text = { Text("Location") },
                onClick = { open = false; onLocation() },
            )
            if (onSubject != null) {
                DropdownMenuItem(
                    leadingIcon = { Icon(painterResource(R.drawable.ic_subject), contentDescription = null) },
                    text = { Text("Subject") },
                    onClick = { open = false; onSubject() },
                )
            }
        }
    }
}

/** A small badge with the SIM's number in its color; tapping it lists the SIMs to choose from. */
@Composable
private fun SimPicker(sims: List<SimCard>, selected: SimCard, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(selected.color))
                .clickable { open = true }
                .semantics { contentDescription = "Sending from ${selected.slotName}, ${selected.label}. Change SIM" },
            contentAlignment = Alignment.Center,
        ) {
            Text("${selected.slot + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sims.forEach { sim ->
                DropdownMenuItem(
                    leadingIcon = {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(sim.color)), contentAlignment = Alignment.Center) {
                            Text("${sim.slot + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column {
                            Text(sim.label, style = MaterialTheme.typography.bodyLarge)
                            Text(sim.slotName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = if (sim.subscriptionId == selected.subscriptionId) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                    onClick = { open = false; onSelect(sim.subscriptionId) },
                )
            }
        }
    }
}

/** "Sending…" with a countdown and Undo, while a sent message waits out the undo window. */
@Composable
private fun UndoBar(pending: ThreadViewModel.PendingSend, onUndo: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pending) {
        while (now < pending.sendsAt) {
            delay(100)
            now = System.currentTimeMillis()
        }
    }
    val left = ((pending.sendsAt - now).coerceAtLeast(0) + 999) / 1000
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column {
            LinearProgressIndicator(
                progress = { ((pending.sendsAt - now).toFloat() / pending.windowMillis).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(if (pending.separately) "Sending to each person in $left…" else "Sending in $left…", style = MaterialTheme.typography.labelLarge)
                    val body = pending.text.ifBlank { if (pending.attachments.isEmpty()) "" else attachmentSummary(pending.attachments.map { it.contentType }) }
                    Text(
                        buildAnnotatedString {
                            // The subject first, as the bubble shows it.
                            pending.subject?.let { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it) } }
                            if (pending.subject != null && body.isNotEmpty()) append(" · ")
                            append(body)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onUndo) { Text("Undo") }
            }
        }
    }
}

/** Marks every occurrence of [query] in the text. */
private fun AnnotatedString.highlighted(query: String?, color: Color): AnnotatedString {
    if (query.isNullOrEmpty()) return this
    val builder = AnnotatedString.Builder(this)
    var at = text.indexOf(query, ignoreCase = true)
    while (at >= 0) {
        builder.addStyle(SpanStyle(background = color), at, at + query.length)
        at = text.indexOf(query, at + query.length, ignoreCase = true)
    }
    return builder.toAnnotatedString()
}

/** The thread's top bar while searching: the query, which match is in view, and older/newer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    position: Pair<Int, Int>?,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") } },
        title = {
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search this conversation", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        actions = {
            if (query.trim().length >= 2) {
                Text(
                    position?.let { (n, of) -> "$n of $of" } ?: "No matches",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOlder, enabled = position != null && position.first < position.second) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Older match")
            }
            IconButton(onClick = onNewer, enabled = position != null && position.first > 1) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Newer match")
            }
        },
    )
}

/** "37 / 2": characters left in the current SMS segment, and how many segments this will take. */
@Composable
private fun SegmentCounter(text: String) {
    val (segments, _, remaining) = SmsMessage.calculateLength(text, false).let { Triple(it[0], it[1], it[2]) }
    Text(
        "$remaining / $segments",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp).semantics {
            contentDescription = "$remaining characters left in this text, ${if (segments == 1) "1 text" else "$segments texts"}"
        },
    )
}

/** Replaces the composer while recording: a pulsing dot, the time, discard and done. */
@Composable
private fun RecordingBar(elapsed: () -> Long, onCancel: () -> Unit, onDone: () -> Unit) {
    var millis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            millis = elapsed()
            delay(200)
        }
    }
    val pulse by rememberInfiniteTransition(label = "recording").animateFloat(
        initialValue = 1f, targetValue = 0.3f, animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot",
    )
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, contentDescription = "Discard recording") }
        Box(Modifier.size(12.dp).graphicsLayer { alpha = pulse }.background(colors.error, CircleShape))
        Spacer(Modifier.width(12.dp))
        val seconds = millis / 1000
        Text(
            "Recording %d:%02d".format(seconds / 60, seconds % 60),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(56.dp).clip(CircleShape).background(colors.primary).clickable(onClickLabel = "Done recording", onClick = onDone),
        ) { Icon(Icons.Filled.Check, contentDescription = "Done recording", tint = colors.onPrimary) }
    }
}

/** A message's text, selectable, for copying part of it. */
@Composable
private fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select text") },
        text = {
            SelectionContainer(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Replaces the top bar while messages are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    allStarred: Boolean,
    canCopy: Boolean,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") } },
        title = { Text("$count selected") },
        actions = {
            if (canCopy) IconButton(onClick = onCopy) { Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy text") }
            IconButton(onClick = onStar) {
                Icon(if (allStarred) Icons.Outlined.Star else Icons.Filled.Star, contentDescription = if (allStarred) "Unstar" else "Star")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}
