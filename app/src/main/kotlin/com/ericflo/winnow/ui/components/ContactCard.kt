package com.ericflo.winnow.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.VCard
import com.ericflo.winnow.data.VCardContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A shared contact (a vCard attachment) drawn as a card, with Add contact and Message.
 * An unreadable card shows as a plain "Contact card" chip.
 */
@Composable
fun ContactCardAttachment(uri: String, outgoing: Boolean, onMessage: (String) -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val contacts by produceState<List<VCardContact>?>(null, uri) { value = withContext(Dispatchers.IO) { readContacts(context, uri) } }
    val list = contacts ?: return
    val colors = MaterialTheme.colorScheme
    val container = if (outgoing) colors.primaryContainer else colors.surfaceContainerHigh
    val content = if (outgoing) colors.onPrimaryContainer else colors.onSurface
    if (list.isEmpty()) {
        Surface(color = container, contentColor = content, shape = RoundedCornerShape(18.dp)) {
            Text("Contact card", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.forEach { contact ->
            val phone = contact.phones.firstOrNull()
            Surface(
                color = container,
                contentColor = content,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(max = 280.dp).combinedClickable(onClick = { addContact(context, contact) }, onLongClick = onLongClick),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
                        Avatar(contact.name.ifBlank { phone.orEmpty() }, seed = phone ?: contact.name, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(contact.name.ifBlank { "Contact" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val detail = phone?.let(ContactLookup::formatAddress) ?: contact.emails.firstOrNull()
                            val more = contact.phones.size + contact.emails.size - 1
                            detail?.let {
                                Text(
                                    if (more > 0) "$it · $more more" else it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = content.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = content.copy(alpha = 0.12f))
                    Row(Modifier.padding(horizontal = 4.dp)) {
                        TextButton(onClick = { addContact(context, contact) }) { Text("Add contact") }
                        if (phone != null) TextButton(onClick = { onMessage(phone) }) { Text("Message") }
                    }
                }
            }
        }
    }
}

private fun readContacts(context: Context, uri: String): List<VCardContact> = runCatching {
    context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
        VCard.parse(VCard.read(input))
    }.orEmpty()
}.getOrDefault(emptyList())

/** Opens [number] in the Contacts app: its contact if there is one, else an offer to add it. */
fun showOrCreateContact(context: Context, number: String) {
    try {
        // An email address goes in as one; anything else as a phone number.
        val scheme = if (com.ericflo.winnow.data.isEmailAddress(number)) "mailto" else "tel"
        context.startActivity(Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, android.net.Uri.fromParts(scheme, number.trim(), null)))
    } catch (_: ActivityNotFoundException) {
        // No contacts app.
    }
}

/** Opens the Contacts app's new-contact form, filled in; nothing is saved until the user does. */
private fun addContact(context: Context, contact: VCardContact) {
    val insert = ContactsContract.Intents.Insert::class.java
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
    listOf(ContactsContract.Intents.Insert.PHONE, ContactsContract.Intents.Insert.SECONDARY_PHONE, ContactsContract.Intents.Insert.TERTIARY_PHONE)
        .zip(contact.phones).forEach { (key, value) -> intent.putExtra(key, value) }
    listOf(ContactsContract.Intents.Insert.EMAIL, ContactsContract.Intents.Insert.SECONDARY_EMAIL, ContactsContract.Intents.Insert.TERTIARY_EMAIL)
        .zip(contact.emails).forEach { (key, value) -> intent.putExtra(key, value) }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No contacts app; the card still shows the number.
    }
}
