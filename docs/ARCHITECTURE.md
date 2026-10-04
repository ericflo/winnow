# Architecture

## Classification is provider-agnostic

```
 app (Android)                         classifier (pure Kotlin/JVM)
 ─────────────                         ────────────────────────────
 SmsDeliverReceiver                    MessageClassifier
   → IncomingMessageHandler ──────────▶  1. LocalRules        sender rules, contacts, prior
       store in Telephony provider                            conversations, verification codes
       classify (7 s budget)             2. privacy gate      drop providers the policy forbids
       save verdict (Room)               3. Redactor          mask digits/emails, strip URL paths
       notify / silence / filter         4. DecisionProvider  first eligible provider to answer
                                         5. HeuristicScorer   offline fallback, capped at SILENCE
 ClassifierFactory ── settings ──────▶ DecisionProvider (interface)
                                         ├─ SystemOneProvider       Jev (TypeSafe, OpenRouter),
                                         │                          openjev-sglang, decider.serve, …
                                         └─ ChatCompletionsProvider any OpenAI-compatible LLM
```

The seam is `DecisionProvider`:

```kotlin
interface DecisionProvider {
    val descriptor: ProviderDescriptor          // id, name, DataHandling
    suspend fun decide(request: DecisionRequest): DecisionResponse
}
// DecisionRequest  = state (any JSON) + keyed Choice questions (options → rubric)
// DecisionResponse = a normalized probability Distribution per question
```

This is the System One decision shape, which Jev speaks natively, so Jev needs no adapter.
It isn't Jev-specific, though: a decision is just "here is some state, here are
multiple-choice questions, give me probabilities". Any classifier can produce that. The
app never imports a provider class except in `ClassifierFactory`.

Every verdict records which provider produced it (`VerdictSource.Provider(id, model)`),
so providers can be compared on real traffic before switching.

### Taxonomy

One Choice question, `category`, with seven options. Each option's rubric is the
instruction the provider sees (`classifier/…/message/Taxonomy.kt`). The political, scam and
phishing rubrics were tuned against patterns in a real spam folder: sensational "BREAKING"
hooks, fake polls and petitions, wrong-name fundraising, and "are you free to talk?" openers.
With Jev 1.13 on OpenRouter, the live test's 12 samples all land where they should. The two
hardest (a fake "approval poll" and a wrong-name "tough news" text) score 0.55–0.61, so they
are silenced rather than filtered.

### Adding a provider

1. Implement `DecisionProvider` in `classifier/…/providers/` with tests that use a fake
   `HttpTransport` (see `SystemOneProviderTest`).
2. Add a `ProviderKind` in `app/…/data/SettingsRepository.kt` and map it in
   `ClassifierFactory.provider()`.

An on-device provider should report `DataHandling.ON_DEVICE`. A natural candidate is an
open System One model (e.g. decider-2b) running through llama.cpp on the phone, behind
the same wire format.

### Privacy defaults

What a provider sees for a stranger's text (also visible in Settings → Try it):

```json
{"message": "Toll balance unpaid, pay at ezpass-help.top/…",
 "sender": {"kind": "phone_number", "in_contacts": false, "user_has_messaged_sender": false}}
```

- Contacts, people you've texted, and verification codes are decided locally and never sent.
- Runs of 4+ digits become `####`, emails become `[email]`, links keep only their domain.
- The sender's number isn't sent unless you opt in.
- "Zero-retention providers only" skips every provider not marked ZDR. Marking is the user's
  attestation per provider; Winnow can't verify it.

### Failure behavior

Classification fails open. If no provider answers within the budget, the keyword heuristic
decides but may only silence, never filter. If anything throws, the message is delivered
with a notification. The message is written to the SMS store before classification starts,
so nothing is ever lost.

Confidence below `ActionPolicy.minConfidence` (0.7) softens the action one step
(Filter → Silence → Notify).

## App structure

- **Messages** live in the system Telephony provider. `TelephonyMessageRepository` reads
  threads from `content://mms-sms/conversations?simple=true` plus canonical addresses (so
  groups have every participant), merges SMS and MMS, and writes as the default SMS app.
  `DemoMessageRepository` serves the sample conversations until SMS access is granted.
  `SwitchingMessageRepository` picks between them.
- **Winnow's own state** is in Room (`WinnowDatabase`, auto-migrated):
  - `verdicts`: one row per message key (`sms:<id>` / `mms:<id>`), with the user's correction
  - `sender_rules`: always allow or always filter, per sender
  - `conversation_state`: pinned, archived, muted, draft
  - `scheduled_messages`
- **Settings** are in DataStore. API keys are sealed with an Android Keystore AES-GCM key
  (`SecretBox`).
- **Incoming:** `SmsDeliverReceiver` / `MmsWapPushReceiver` hand off to
  `IncomingMessageHandler`, which stores the message, classifies it within a 7-second budget
  (failing open), records the verdict, unarchives the thread, and then notifies, silences or
  filters. A conversation that's open on screen never notifies itself.
- **Notifications** (`Notifier`) are MessagingStyle conversations backed by long-lived
  shortcuts, with Reply, Mark as read and Copy-code actions (`NotificationActionReceiver`).
- **Scheduled send** (`MessageScheduler`) keeps texts in Room and sends them from AlarmManager
  alarms. They're exact when the user allows it and otherwise within a 10-minute window, and
  they're re-armed at boot, at app start and on exact-alarm permission changes. A message is
  deleted only after its send has been handed off.
- **Older conversations** (`HistoryReviewer`): only on request, classifies each thread's
  newest incoming message that has no verdict, under the same privacy gate. It never notifies.
- **Activity** summarizes the verdict table: actions, categories, and who decided (on the
  phone or a classifier service).
- **Tapbacks:** iPhone and Google Messages reaction texts (`Tapback.parse`) are folded into
  reaction pills on the message they quote.

## Backup and restore

`backup/` writes one zip, through a file the user picks with the system file picker:

- `winnow-backup.json`, written first: every SMS and MMS, grouped by conversation with each
  message's verdict; per-conversation state; sender rules; scheduled texts; and settings.
  API keys, onboarding and other per-phone flags are left out.
- `media/<part id>.<ext>`: MMS photos, video and audio, streamed from the MMS store.

Conversations are keyed by their participants, not thread ids, so a backup restores onto
another phone. `BackupArchive` is pure JVM and unit-tested; it reads media as streams, which
the restore spools to cache and deletes afterward, so large backups don't have to fit in
memory. It only accepts flat media names, so an entry can't climb out of the spool folder.

Restore only adds. Each message has a fingerprint (kind, time, direction, text and media
count). Messages already in the target thread are skipped, and if they have no verdict yet
(after a reinstall, say) they get the backed-up one. Missing SMS go in through `applyBatch`.
Missing MMS are rebuilt with a fresh SMIL part and inserted like a received or sent message.
Conversation state, sender rules and scheduled texts are only filled in where the phone has
none, and scheduled texts only when their send time is still ahead. Settings are replaced
only if the user ticks the box, and keys already on the phone are kept. Messages can only be
restored while Winnow is the default SMS app. Restored messages are marked seen, so they
don't announce themselves. Outbox and queued texts come back as failed, ready to retry.

## MMS

`mms/` is a standalone PDU codec. The app uses it like this:

- **Receive** (`sms/MmsReceiver`):
  1. A WAP push delivers m-notification-ind.
  2. A placeholder row goes into the system MMS store and the UI shows "Downloading MMS…".
  3. `SmsManager.downloadMultimediaMessage` downloads into a FileProvider-shared cache file.
  4. The m-retrieve-conf is parsed and written into the store, as message, parts and
     addresses (`sms/MmsStore`).
  5. An m-notifyresp-ind acknowledges the download to the carrier.
  6. `IncomingMessageHandler` classifies and notifies, the same path as SMS.
- **Group participants:** sender + To + Cc, minus this phone's own numbers. Those come from
  `SubscriptionManager.getPhoneNumber` on the default subscriptions, which needs only
  READ_PHONE_NUMBERS.
- **Send** (`sms/MmsSender`): SMIL + media + text as m-send-req, via
  `SmsManager.sendMultimediaMessage`. The m-send-conf moves the message to sent or failed.
  Photos are downscaled so the whole message fits about 900 KB.

Emulators have no MMS server. The debug-only `DebugMmsReceiver` feeds codec-built PDUs into
the receive path. Its push mode exercises the download-failure and retry path.

## RCS

Researched 2026-10-03:

- Android (through 17) has no public RCS API. Google's RCS stack (Jibe) is reachable only
  by Google Messages and allowlisted OEM apps. Beeper and others were told there are no
  plans to open it.
- Samsung retired Samsung Messages in July 2026, leaving Google Messages as effectively the
  only RCS client on Android.
- Consequence: as the default SMS app, Winnow sends and receives SMS/MMS only. Chats that
  were RCS (including with iPhones on iOS 18+) fall back to SMS/MMS, losing typing indicators,
  read receipts, full-resolution media and E2EE.

Paths kept open:

1. **Companion mode.** Leave Google Messages as the default, classify from its notifications
   with a `NotificationListenerService`, and silence or dismiss unwanted ones. This keeps RCS
   but can't move or delete messages in Google Messages. It would be a second
   `MessageRepository` and needs no classifier changes.
2. **A future public RCS API.** It would plug in as another transport behind
   `MessageRepository`.

The current decision is a full replacement on SMS/MMS, accepting the loss of RCS for now.
