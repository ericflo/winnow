package com.ericflo.winnow.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.FileProvider
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ericflo.winnow.R
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.Member
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.data.showsInitial
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.ui.BubbleActivity
import com.ericflo.winnow.ui.MainActivity
import com.ericflo.winnow.ui.theme.avatarColorInts
import android.provider.Settings
import java.io.File

/** Conversation notifications with inline reply and mark-as-read. */
class Notifier(
    private val context: Context,
    /** The two faces a group's icon shows, as its avatar in the app does (see groupFaces). */
    private val groupFaces: (List<String>) -> List<Member> = { emptyList() },
) {
    private val manager = NotificationManagerCompat.from(context)

    /** The user's quick replies, offered as one-tap answers on message notifications. */
    @Volatile var quickReplies: List<String> = emptyList()

    init {
        val channel = NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_messages_description) }
        val notSent = NotificationChannel(CHANNEL_NOT_SENT, context.getString(R.string.channel_not_sent), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_not_sent_description) }
        val summary = NotificationChannel(CHANNEL_SUMMARY, context.getString(R.string.channel_summary), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(R.string.channel_summary_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, notSent, summary))
    }

    /** The evening summary (see DailySummary): quiet, and opens Filtered. */
    fun showSummary(filtered: Int, silenced: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        fun texts(n: Int) = if (n == 1) "1 text" else "$n texts"
        val title = if (filtered > 0) "Kept ${texts(filtered)} out of your inbox today" else "${texts(silenced)} arrived quietly today"
        val detail = when {
            filtered > 0 && silenced > 0 -> "And ${texts(silenced)} arrived without a notification. Tap to look them over."
            filtered > 0 -> "Tap to look them over, in case one belongs in your inbox."
            else -> "Delivered without a notification, as you set it up."
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_FILTERED)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, SUMMARY_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        manager.notify(TAG_SUMMARY, SUMMARY_ID, notification)
    }

    /**
     * A text that couldn't go out (no signal, airplane mode, a carrier refusal, or a scheduled one
     * that failed when its time came). Tapping opens the conversation, where it can be retried;
     * opening the conversation any other way clears it too.
     */
    fun showNotSent(threadId: Long, recipients: List<String>, title: String, body: String, scheduled: Boolean = false, retryKey: String? = null) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, joinAddresses(recipients))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val heading = if (scheduled) "Scheduled message not sent" else "Message not sent"
        val line = "To $title: ${body.ifBlank { "your message" }}"
        val notification = NotificationCompat.Builder(context, CHANNEL_NOT_SENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line\nOpen the conversation to try again."))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            // Updated rather than re-announced when another part or retry fails.
            .setOnlyAlertOnce(true)
            // Its own request code range, so it doesn't replace the conversation notification's intent.
            .setContentIntent(PendingIntent.getActivity(context, -1 - notificationId(threadId), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_NOT_SENT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(heading)
                    .build(),
            )
            .apply {
                if (retryKey != null) {
                    addAction(R.drawable.ic_notification, "Try again", actionIntent(NotificationActionReceiver.ACTION_RETRY, threadId, joinAddresses(recipients), mutable = false) {
                        putExtra(NotificationActionReceiver.EXTRA_KEY, retryKey)
                    })
                }
            }
            .build()
        manager.notify(TAG_NOT_SENT, notificationId(threadId), notification)
    }

    /**
     * Posts or extends the notification for [threadId]. Messages already showing for the thread
     * stay in the stack, so three texts in a row read as one conversation.
     */
    fun showMessage(
        threadId: Long,
        recipients: List<String>,
        conversationTitle: String,
        senderName: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        /** A verification code in [body], offered as a one-tap "Copy" action. */
        code: String? = null,
        senderPhotoUri: String? = null,
        /** Keep the notification off the lock screen entirely. */
        hideOnLockScreen: Boolean = false,
        /** Offer "Spam", for a stranger's text the classifier let through. */
        offerSpam: Boolean = false,
        /** One-tap answers; the latest known if not given (the app may only just have started). */
        quickReplies: List<String>? = null,
        /** A received photo (an MMS part) to show in the notification itself. */
        image: Uri? = null,
        /** The words sent with [image], shown after it; not a summary like "2 photos". */
        caption: String? = null,
    ) {
        val choices = quickReplies ?: this.quickReplies
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = notificationId(threadId)
        val photo = contactPhoto(senderPhotoUri)
        val sender = Person.Builder().setName(senderName).setKey(senderName).apply {
            photo?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }.build()
        // Before reading what's showing: decoding takes a while, and two texts at once would
        // otherwise both build on the same old stack, the second dropping the first.
        val picture = image?.let { notificationImage(it, "$threadId-$timestamp") }
        // Likewise the conversation's shortcut (its letter icon is drawn here): nothing waits on it.
        val joined = joinAddresses(recipients)
        val shortcutId = pushShortcut(threadId, joined, conversationTitle, sender, photo)
        synchronized(lockFor(threadId)) {
        // What was just posted, if it was a moment ago: Android takes a moment to list a new
        // notification, and a text arriving right behind another mustn't build on the one before.
        val now = System.currentTimeMillis()
        val justPosted = recent[threadId]?.takeIf { now - it.first < RECENT_MILLIS }?.second
        val previous = justPosted ?: manager.activeNotifications.firstOrNull { it.tag == TAG && it.id == id }
            ?.notification?.let(NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification)
        val style = previous ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        if (picture != null) {
            // A message with a picture shows the picture instead of its text; the caption follows it.
            style.addMessage(NotificationCompat.MessagingStyle.Message("Photo", timestamp, sender).setData("image/jpeg", picture))
            if (!caption.isNullOrBlank()) style.addMessage(caption, timestamp, sender)
        } else {
            style.addMessage(body, timestamp, sender)
        }
        if (recipients.size > 1) {
            style.conversationTitle = conversationTitle
            style.isGroupConversation = true
        }

        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, joined)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Reply",
            actionIntent(NotificationActionReceiver.ACTION_REPLY, threadId, joined, mutable = true),
        )
            .addRemoteInput(
                RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("Reply")
                    .apply { if (choices.isNotEmpty()) setChoices(choices.toTypedArray()) }
                    .build(),
            )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Mark as read",
            actionIntent(NotificationActionReceiver.ACTION_MARK_READ, threadId, joined, mutable = false),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            // What a locked phone shows when Android hides notification contents: no name, no text.
            .setVisibility(if (hideOnLockScreen) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("New message")
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .build(),
            )
        if (code != null) {
            val copy = actionIntent(NotificationActionReceiver.ACTION_COPY_CODE, threadId, joined, mutable = false) {
                putExtra(NotificationActionReceiver.EXTRA_CODE, code)
            }
            builder.addAction(R.drawable.ic_copy, "Copy $code", copy)
        }
        // Without a stored thread (the store refused the message) there's nothing to reply into.
        if (threadId >= 0) {
            builder.addAction(reply).addAction(markRead)
            if (offerSpam) {
                builder.addAction(R.drawable.ic_block, "Spam", actionIntent(NotificationActionReceiver.ACTION_SPAM, threadId, joined, mutable = false))
            }
            // Lets Android float the conversation as a chat bubble, if the user allows bubbles.
            val bubble = Intent(context, BubbleActivity::class.java)
                .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(MainActivity.EXTRA_ADDRESS, joined)
            builder.setBubbleMetadata(
                NotificationCompat.BubbleMetadata.Builder(
                    // Bubble intents must be mutable: the system adds the bubble's own extras.
                    PendingIntent.getActivity(context, id, bubble, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    photo?.let(IconCompat::createWithAdaptiveBitmap) ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher),
                )
                    .setDesiredHeight(640)
                    .build(),
            )
        }
        val notification = builder.build()
        manager.notify(TAG, id, notification)
        recent[threadId] = now to style
        // The rest are listed by Android by now: no need to hold on to them.
        recent.entries.removeIf { now - it.value.first >= RECENT_MILLIS }
        }
    }

    /** Each conversation's notification as last posted, and when: see showMessage. */
    private val recent = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, NotificationCompat.MessagingStyle>>()

    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>()
    private fun lockFor(threadId: Long): Any = locks.computeIfAbsent(threadId) { Any() }

    /**
     * A small copy of a received photo that the notification shade may read, or null if it can't
     * be read. Copies from a day ago are no longer showing, and go.
     */
    private fun notificationImage(part: Uri, name: String): Uri? = runCatching {
        purgeImages()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(part)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // However small the file, a picture that claims to be enormous isn't decoded here.
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_IMAGE_PIXELS) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_EDGE_PX) sample *= 2
        val bitmap = context.contentResolver.openInputStream(part)
            ?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val file = File(imageDir, "$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        // Android grants the shade access to a notification's own URIs, and takes it back when it goes.
        FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
    }.getOrNull()

    private val imageDir get() = File(context.cacheDir, "notified").apply { mkdirs() }

    /** Drops the photo copies from a day ago (those notifications are long gone), or all of [threadIds]'s. */
    fun purgeImages(threadIds: Collection<Long> = emptyList()) {
        val now = System.currentTimeMillis()
        val prefixes = threadIds.map { "$it-" }
        imageDir.listFiles()?.filter { f -> now - f.lastModified() > DAY_MILLIS || prefixes.any(f.name::startsWith) }?.forEach { it.delete() }
    }

    /**
     * A long-lived conversation shortcut puts the notification in the shade's Conversations
     * section (priority, bubbles) and the thread on the launcher icon's long-press menu.
     */
    private fun pushShortcut(threadId: Long, joined: String, title: String, person: Person, photo: android.graphics.Bitmap?): String {
        val shortcutId = shortcutId(threadId)
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(threadId, joined, title, person, photo)) }
        return shortcutId
    }

    private fun shortcut(threadId: Long, joined: String, title: String, person: Person, photo: android.graphics.Bitmap?): ShortcutInfoCompat {
        val shortcutId = shortcutId(threadId)
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, joined)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(title)
            .setLongLived(true)
            .setIsConversation()
            .setLocusId(LocusIdCompat(shortcutId))
            // A group's shortcut is the group's, not whoever texted it last.
            .setPerson(if (splitAddresses(joined).size > 1) Person.Builder().setName(title).setKey(joined).build() else person)
            .setIcon(icon(title, joined, photo))
            .setIntent(open)
            // Offered by name in the share sheet (see res/xml/shortcuts.xml).
            .setCategories(setOf(SHARE_CATEGORY))
            .build()
    }

    /**
     * A conversation's icon, as its avatar in the app: a group's two faces (never whoever texted
     * last), else the person's photo, else their letter.
     */
    private fun icon(title: String, joined: String, photo: Bitmap?): IconCompat {
        val people = splitAddresses(joined)
        if (people.size > 1) return runCatching { groupIcon(groupFaces(people)) }.getOrNull() ?: letterIcon(title, joined)
        return photo?.let(IconCompat::createWithAdaptiveBitmap) ?: letterIcon(title, joined)
    }

    /**
     * A group's two faces, overlapping as in the inbox, but with the first in front at the top
     * left: Android badges a conversation's icon at the bottom right, over whatever is there.
     * Null without two.
     */
    private fun groupIcon(faces: List<Member>): IconCompat? {
        if (faces.size < 2) return null
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        // The shade's own light or dark, which the app's theme setting doesn't change.
        val dark = systemDark()
        val background = context.getColor(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_100)
        canvas.drawColor(background)
        // Launchers and the shade show the middle two thirds; both faces fit inside that circle.
        val radius = size * 0.18f
        val offset = size * 0.085f
        val center = size / 2f
        drawFace(canvas, faces[1], center + offset, center + offset, radius, dark)
        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawCircle(center - offset, center - offset, radius + size * 0.012f, ring)
        drawFace(canvas, faces[0], center - offset, center - offset, radius, dark)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /** One face: the contact's photo, else their initial on their color, else a person glyph. */
    private fun drawFace(canvas: android.graphics.Canvas, member: Member, cx: Float, cy: Float, radius: Float, dark: Boolean) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val photo = contactPhoto(member.photoUri)
        if (photo != null) {
            // Center-cropped into the circle.
            val scale = 2 * radius / minOf(photo.width, photo.height)
            val matrix = android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - photo.width * scale / 2, cy - photo.height * scale / 2)
            }
            paint.shader = android.graphics.BitmapShader(photo, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(matrix) }
            canvas.drawCircle(cx, cy, radius, paint)
            photo.recycle()
            return
        }
        if (showsInitial(member.name)) {
            val (container, content) = avatarColorInts(member.address, dark)
            paint.color = container
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = content
            paint.textSize = radius * 0.95f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            canvas.drawText(member.name.first().uppercase(), cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
            return
        }
        paint.color = context.getColor(if (dark) android.R.color.system_neutral2_700 else android.R.color.system_neutral2_200)
        canvas.drawCircle(cx, cy, radius, paint)
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_person)?.mutate()?.let { glyph ->
            glyph.setTint(context.getColor(if (dark) android.R.color.system_neutral2_200 else android.R.color.system_neutral2_700))
            val half = radius * 0.55f
            glyph.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            glyph.draw(canvas)
        }
    }

    /**
     * Whether the system (the notification shade, the launcher) is dark. Not the app's own
     * configuration: Settings → Theme overrides that for Winnow alone.
     */
    private fun systemDark(): Boolean =
        android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * A contact's photo, sharp enough for an icon: the full-size display photo where there is one
     * (the usual photo URI is a 96-pixel thumbnail, soft once drawn larger), else the thumbnail,
     * at most [PHOTO_EDGE_PX] across.
     */
    private fun contactPhoto(thumbnail: String?): Bitmap? =
        listOfNotNull(ContactLookup.displayPhoto(thumbnail), thumbnail).firstNotNullOfOrNull { decodeAtMost(Uri.parse(it), PHOTO_EDGE_PX) }

    private fun decodeAtMost(uri: Uri, edge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    /** The conversation's first letter on its avatar color, as in the inbox; the app icon for a bare number. */
    private fun letterIcon(title: String, seed: String): IconCompat {
        val letter = title.firstOrNull()?.takeIf { it.isLetter() } ?: return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        // The inbox avatar's colors for the same conversation, light or dark as the shade is.
        val (container, content) = avatarColorInts(seed, systemDark())
        // An adaptive icon: full bleed, with the letter inside the middle two thirds launchers keep.
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(container)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = content
            textSize = size * 0.30f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter.uppercase(), size / 2f, baseline, paint)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /**
     * Asks the launcher to pin the conversation to the home screen (it asks the user to confirm).
     * False if the launcher doesn't take pinned shortcuts.
     */
    fun pinToHomeScreen(threadId: Long, recipients: List<String>, title: String, photoUri: String?): Boolean {
        if (threadId < 0 || !ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val photo = contactPhoto(photoUri)
        val joined = joinAddresses(recipients)
        val person = Person.Builder().setName(title).setKey(joined).apply { photo?.let { setIcon(IconCompat.createWithBitmap(it)) } }.build()
        val info = shortcut(threadId, joined, title, person, photo)
        // The same shortcut as the conversation's own: one shortcut, kept up to date, however reached.
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info) }
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
    }

    /**
     * Android's own notification settings for one conversation: its sound, vibration, priority
     * and bubble. Those settings hang off the conversation's shortcut, so it's made first.
     */
    fun conversationSettings(threadId: Long, recipients: List<String>, title: String, photoUri: String? = null): Intent {
        // The same shortcut as the conversation's own (its photo too), or this would replace it.
        val photo = contactPhoto(photoUri)
        val joined = joinAddresses(recipients)
        val person = Person.Builder().setName(title).setKey(joined).apply { photo?.let { setIcon(IconCompat.createWithBitmap(it)) } }.build()
        val shortcutId = pushShortcut(threadId, joined, title, person, photo)
        // Settings shows the general Messages page until the conversation has a channel of its
        // own. Notifications for this conversation move to it automatically, by shortcut ID.
        val system = context.getSystemService(NotificationManager::class.java)
        if (conversationChannel(system, shortcutId) == null) {
            val parent = system.getNotificationChannel(CHANNEL_MESSAGES)
            // A copy of Messages as the user has it, since an app can't change a channel's sound
            // or vibration once it exists; Android's own conversation channels copy it too.
            val channel = NotificationChannel("$CHANNEL_MESSAGES:$shortcutId", title, parent?.importance ?: NotificationManager.IMPORTANCE_HIGH).apply {
                setConversationId(CHANNEL_MESSAGES, shortcutId)
                parent?.let { p ->
                    description = p.description
                    setSound(p.sound, p.audioAttributes)
                    enableVibration(p.shouldVibrate())
                    vibrationPattern = p.vibrationPattern
                    enableLights(p.shouldShowLights())
                    lightColor = p.lightColor
                    setShowBadge(p.canShowBadge())
                    lockscreenVisibility = p.lockscreenVisibility
                    group = p.group
                }
            }
            system.createNotificationChannel(channel)
        }
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_MESSAGES)
            .putExtra(Settings.EXTRA_CONVERSATION_ID, shortcutId)
    }

    /** Clears the conversation's notifications, "not sent" included: the user is looking at it. */
    /**
     * Keeps a conversation the user texts in among Android's conversation shortcuts: the share
     * sheet's direct targets and the launcher icon's long-press menu, most used first.
     */
    fun publishConversation(threadId: Long, recipients: List<String>, title: String, photoUri: String?) {
        if (threadId < 0) return
        val photo = contactPhoto(photoUri)
        val person = Person.Builder().setName(title).setKey(joinAddresses(recipients)).apply {
            photo?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }.build()
        pushShortcut(threadId, joinAddresses(recipients), title, person, photo)
    }

    fun cancel(threadId: Long) {
        cancelMessages(threadId)
        manager.cancel(TAG_NOT_SENT, notificationId(threadId))
    }

    fun cancelNotSent(threadId: Long) {
        manager.cancel(TAG_NOT_SENT, notificationId(threadId))
    }

    /** Clears just the new-message notification, leaving any "not sent" one standing. */
    fun cancelMessages(threadId: Long) {
        // No lock: this runs on the main thread when a conversation opens, and a post racing it
        // only means one more notification, cleared when the conversation is next looked at.
        recent.remove(threadId)
        manager.cancel(TAG, notificationId(threadId))
    }

    /** Drops notifications and conversation shortcuts for deleted threads. */
    fun forget(threadIds: Collection<Long>) {
        threadIds.forEach(::cancel)
        runCatching { purgeImages(threadIds) }
        val system = context.getSystemService(NotificationManager::class.java)
        threadIds.forEach { id -> conversationChannel(system, shortcutId(id))?.let { system.deleteNotificationChannel(it.id) } }
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, threadIds.map(::shortcutId)) }
        // One pinned to the home screen stays there, greyed out, rather than opening nothing.
        runCatching { ShortcutManagerCompat.disableShortcuts(context, threadIds.map(::shortcutId), "This conversation was deleted") }
    }

    private fun shortcutId(threadId: Long) = "thread-$threadId"

    /** The conversation's own channel, if it has one. The platform call falls back to the parent channel, which mustn't be mistaken for it. */
    private fun conversationChannel(system: NotificationManager, shortcutId: String): NotificationChannel? =
        system.getNotificationChannel(CHANNEL_MESSAGES, shortcutId)?.takeIf { it.conversationId == shortcutId }

    private fun actionIntent(
        action: String,
        threadId: Long,
        recipients: String,
        mutable: Boolean,
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
            .putExtra(NotificationActionReceiver.EXTRA_RECIPIENTS, recipients)
            .apply(extras)
        // RemoteInput fills in the reply text, so the reply intent must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (action.hashCode() * 31) + notificationId(threadId), intent, flags)
    }

    private fun notificationId(threadId: Long) = threadId.toInt()

    private companion object {
        /** A contact photo's size for icons: an adaptive icon is 432 pixels across. */
        const val PHOTO_EDGE_PX = 432
        const val CHANNEL_MESSAGES = "messages"
        const val SHARE_CATEGORY = "com.ericflo.winnow.category.SHARE_TARGET"
        const val CHANNEL_NOT_SENT = "not_sent"
        const val TAG = "thread"
        const val TAG_NOT_SENT = "not_sent"
        const val CHANNEL_SUMMARY = "summary"
        const val TAG_SUMMARY = "summary"
        const val SUMMARY_ID = 1
        const val IMAGE_EDGE_PX = 1024
        const val MAX_IMAGE_PIXELS = 40_000_000L
        const val RECENT_MILLIS = 3_000L
        const val DAY_MILLIS = 24 * 60 * 60_000L
    }
}
