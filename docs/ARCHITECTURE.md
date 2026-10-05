# Architecture

## Classification is provider-agnostic

```
 app (Android)                         classifier (pure Kotlin/JVM)
 ─────────────                         ────────────────────────────
 SmsDeliverReceiver                    MessageClassifier
   → IncomingMessageHandler ──────────▶  1. LocalRules        sender rules, contacts, prior
       store in Telephony provider                            conversations, verification codes
       classify (7 s budget)             2. OnDeviceClassifier decides alone if it's ≥95% sure
       save verdict (Room)                                    (only when the user opts in)
       notify / silence / filter         3. privacy gate      drop providers the policy forbids
                                         4. Redactor          mask digits/emails, strip URL paths
                                         5. DecisionProvider  first eligible provider to answer
                                         6. OnDeviceClassifier fallback (or the only classifier)
                                         7. HeuristicScorer   only if the model can't load
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

Six categories (`classifier/…/message/Taxonomy.kt`): personal, reminder, transactional,
marketing, political and spam. Phishing and "likely scam" were categories of their own until
they were folded into spam (Room migration 16→17 and backup format 2 rename them; the user's
earlier labels, except political ones, are marked `recheck` and come first in Train).

A provider isn't asked to choose among the six. One Choice question, `category`, offers 79
finer kinds of text (`Subcategories.kt`), each described with the category it counts as, and
the answer's probabilities are added up into the six (`Subcategories.aggregate`); the verdict
keeps the finer kind as `subcategory`. TypeSafe's docs put a Choice's limit at 255 options and
recommend giving the model the full list rather than a shortlist. The parent's confidence is
its summed probability, not the provider's own `confidence` (which is over the 79). Personal
options are listed first and spam last because Jev leans toward earlier options. With
examples, the instructions also carry up to three of the user's own labeled texts per
category, redacted like the message. A provider that answers with the six directly still
works: `aggregate` falls back to category keys. The spam and political options were written
against patterns in a real spam folder: sensational "BREAKING" hooks, fake polls and
petitions, wrong-name fundraising, and "are you free to talk?" openers.

### Adding a provider

1. Implement `DecisionProvider` in `classifier/…/providers/` with tests that use a fake
   `HttpTransport` (see `SystemOneProviderTest`).
2. Add a `ProviderKind` in `app/…/data/SettingsRepository.kt` and map it in
   `ClassifierFactory.provider()`.

An on-device provider should report `DataHandling.ON_DEVICE`. A natural candidate is an
open System One model (e.g. decider-2b) running through llama.cpp on the phone, behind
the same wire format.

### The on-device model

`classifier/…/local/` is Winnow's own model, separate from `DecisionProvider` because it
reads the unredacted message: nothing leaves the phone, so there's nothing to redact.

- **Features** (`Featurizer`): words and word pairs after links, emails, money, phone
  numbers and digit runs are replaced by placeholders. Named signals cover what words
  miss: the kind of sender, the link's TLD, shorteners, a "risky" TLD, deceptive hosts
  (`sunpass.com-tollpay.vip`), brand words inside a link's host, shouting, length. A link
  needs no `https://`: a bare host counts when it ends in a real TLD, so
  `netflix-billing-help.top` is a link but "e.g." and "3.5" aren't.
- **Model** (`LocalModel`): softmax regression over 2^15 FNV-1a-hashed buckets, stored as
  int8 with a scale per class (230 KB), and temperature-calibrated.
- **Training** (`LocalModelTrainer`): AdaGrad with class weighting and a fixed seed, using
  StrictMath, so the model rebuilds bit-for-bit from `classifier/training/corpus/*.tsv`.
  `./gradlew :classifier:trainLocalModel` writes the model and `training/REPORT.md`.
  `LocalModelTest` fails if the shipped model is stale, if int8 quantization changes more
  than 1% of predictions, or if held-out accuracy drops below 85%.
- **Explanations:** the features with the largest margin toward the chosen category, with
  redundant ones dropped ("pay now" makes "pay" redundant). They're stored with the verdict
  and shown in the banner.
- **Policy:** an on-device verdict needs 85% confidence to take its category's full action
  (`ActionPolicy.onDeviceMinConfidence`); below that it's softened a step. A spam verdict
  also needs a hook (`Featurizer.hasHook`) before it can filter. A hook is a link off the
  company's real domain, money, a phone number, an email address, or payment, code, PIN,
  job, prize or crypto words. Without one the text is only silenced, and let through when
  the model is under 60% sure (`hooklessNotifiesBelow`): bare wrong-number openers are
  indistinguishable from real people on new numbers, and an alert with no foreign link has
  nothing to phish with. Of the floors tried, 0.6 kept 91.7% of unwanted test texts quiet
  while muting 2.2% of wanted ones (letting every unsure one through: 87.8% and 1.0%; none:
  92.8% and 3.8%). Deciding without the provider needs 95%.
- **Bagging:** the shipped model is the average of 5 models trained on bootstrap resamples.
  For a linear model that equals averaging their scores, so the ensemble costs nothing at
  run time; it improved recall and calibration (ECE 0.015 → 0.009).
- **Deeper models:** a wide-and-deep network (the linear model plus a 16–64-unit hidden
  layer) did worse in cross-validation on every measure, so it doesn't ship.
  `DeepExperiment` keeps it reproducible; it's worth revisiting once there's much more data.

- **Learning from corrections** (`Personalizer`, app `Learner`): overriding a verdict
  stores the newest incoming message's feature buckets, never its text, in Room
  (`corrections`). The label is the likeliest category whose action matches the user's
  choice. Per-bucket adjustments, with no bias term and an L2 pull toward zero, are refit
  from all corrections in milliseconds and added to the bundled scores. On the corpus, a
  correction changes under 2% of other predictions. Corrections are included in backups.

- **Metrics** (`ClassifierMetrics`, `MetricsCalculator`): the trainer runs 5-fold
  stratified cross-validation and fits the temperature on the out-of-fold scores. From
  those same scores it computes accuracy, macro and weighted F1, Cohen's κ, multiclass MCC
  (Gorodkin's R_K), log loss, Brier score and ECE. It also computes per-category precision,
  recall, F1 and one-vs-rest ROC AUC, and the confusion matrix. Finally it computes "unwanted
  vs. wanted" ROC and precision–recall curves with AUC and average precision, a threshold
  table, and Winnow's actual operating point. They're written to
  `winnow-local-metrics.json` beside the model, which the app's **Classifier accuracy**
  screen (Filtered → How accurate is Winnow?) reads. The stale check covers that file too.
  `MetricsCalculatorTest` checks the math against hand-computed values.
- **Dev tasks:** `./gradlew :classifier:tuneLocalModel` grid-searches the trainer settings
  by cross-validated macro F1, and `:classifier:evalMistakes` lists the evaluation set's
  misses.

`training/eval.tsv` is a separate set the model never trains on. Its second half was
written before any feature or corpus tuning, so tuning couldn't leak into it. The corpus and the eval
set are both hand-written, so the report's numbers are an upper bound.

### Privacy defaults

What a provider sees for a stranger's text (also visible in Settings → Try it):

```json
{"message": "Toll balance unpaid, pay at ezpass-help.top/…",
 "sender": {"kind": "phone_number", "in_contacts": false, "user_has_messaged_sender": false}}
```

- Contacts, people you've texted, and verification codes are decided locally and never sent.
  A contact is someone in the phone's contact list (`ContactLookup` indexes every number in
  one query) or a colleague in a work profile, which Android only answers for one number at a
  time (`PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI`): asked only where it decides something
  (`isContact`; counting and choosing use `inContactList`), kept ten minutes, and kept while
  work apps are paused. If contacts can't be read at all, a contact can't be told from a
  stranger, so `ClassifierFactory` sets `MessageClassifier.keepOnPhone` and nothing is sent
  anywhere (Settings' Try it excepted, as it's no one's message); the inbox says so.
- Runs of 4+ digits become `####`, emails become `[email]`, links keep only their domain.
- The sender's number isn't sent unless you opt in.
- "Zero-retention providers only" skips every provider not marked ZDR. Marking is the user's
  attestation per provider; Winnow can't verify it.

### Failure behavior

Classification fails open. If no provider answers within the budget, the on-device model
decides under its stricter confidence bar. Only if the model can't load does the keyword
heuristic decide, and it may only silence, never filter. If anything throws, the message is delivered
with a notification. The message is written to the SMS store before classification starts,
so nothing is ever lost.

Confidence below `ActionPolicy.minConfidence` (0.7) softens the action one step
(Filter → Silence → Notify).

## App structure

- **Messages** live in the system Telephony provider. `TelephonyMessageRepository` reads
  threads from `content://mms-sms/conversations?simple=true` plus canonical addresses (so
  groups have every participant), merges SMS and MMS, and writes as the default SMS app.
  `NoAccessMessageRepository` (empty) stands in until SMS access is granted, and
  `SwitchingMessageRepository` picks between them. It also shares one conversation list among
  everything that reads it (the inbox, Filtered, the widget, Train, a backlog run). Who sent a
  received MMS is a query per message, so a one-to-one conversation takes the other person,
  and a group first loads with its newest 60 senders and then with all of them; a long
  conversation's list of bubbles is built off the main thread.
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
  newest incoming message that has no verdict, under the same privacy gate, three at a time.
  It never notifies. When the classifier service is asked and doesn't answer, nothing is saved
  for that conversation (it waits for the next review), and six such in a row end the review.
- **Activity** summarizes the verdict table: actions, categories, and who decided (on the
  phone or a classifier service).
- **Dual SIM** (`data/Sims.kt`): `SimCards` lists active subscriptions (READ_PHONE_STATE,
  requested only when the phone has two or more modems). `SimChoice.pick` chooses the
  conversation's saved SIM (`conversation_state.subscriptionId`), else the SIM of its newest
  incoming message, else the system default. The SMS and MMS senders send on that
  subscription, retries reuse the one a message was first sent on, and scheduled texts store
  theirs. Debug builds can add a pretend second SIM that routes to the real one.
- **App lock** (`ui/lock/AppLock.kt`): process-wide, so rotation doesn't re-lock, and the
  app container watches its setting, so a bubble opened in a fresh process is covered too. A cold
  start, or a return after more than a minute away, covers the UI and shows the platform
  `BiometricPrompt` (strong biometric or device credential). The minute's grace means picking a
  photo doesn't trip it. It fails open if the phone can no longer authenticate (the screen
  lock was removed). The cover is its own `LockActivity` above the task rather than a
  composable, so it also hides dialogs, sheets and viewers that were open when Winnow left
  the screen; Back from it sends the task home instead of revealing them. A link, share or
  notification reaching the singleTask `MainActivity` clears LockActivity, so `onResume` and
  `onNewIntent` open it again while locked, and the app's own content stays covered underneath
  in the meantime.
  `setRecentsScreenshotEnabled(false)` blanks the recents card on 13+ (`FLAG_SECURE` below).
- **Theme and text size:** the theme setting goes to `UiModeManager.setApplicationNightMode`
  (Android 12+ keeps a night mode per app), so system bars and dialogs follow it with no
  AppCompat. Notifications stay on the system theme. Message text size multiplies the body style.
  A pinch on the conversation, read in the `Initial` pointer pass and only with two fingers
  down, so one finger still scrolls, changes it live and saves it when the fingers lift.
- **Chat bubbles:** message notifications carry `BubbleMetadata` that opens `BubbleActivity`
  (embedded, resizable, one document per conversation) with the same `ThreadScreen`. Reading
  in a bubble marks the conversation read but leaves its notification alone: a bubble lives
  only as long as its notification.
- **Lock-screen privacy:** message notifications carry a public version ("New message", no
  sender or text). "Hide texts on the lock screen" makes them `VISIBILITY_SECRET`.
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

- **Automatic backups** (`backup/AutoBackup`): a persisted JobScheduler job (weekly, while
  charging and not low on battery) writes `winnow-auto-backup-<time>.zip` into a SAF folder
  the user granted lasting access to, through the same export as a manual backup but without
  touching the Settings progress UI. It prunes all but the newest four of its own files. The
  folder is this phone's alone and never goes into a backup.

## MMS

`mms/` is a standalone PDU codec. The app uses it like this:

- **Receive** (`sms/MmsReceiver`):
  1. A WAP push delivers m-notification-ind.
  2. A placeholder row goes into the system MMS store and the UI shows "Downloading MMS…".
  3. `SmsManager.downloadMultimediaMessage` downloads into a FileProvider-shared cache file.
  4. The m-retrieve-conf is parsed and written into the store, as message, parts and
     addresses (`sms/MmsStore`).
  5. An m-notifyresp-ind acknowledges the download to the carrier.

  With auto-download off (or roaming, unless allowed), step 3 waits: the placeholder is
  marked deferred (status 0x83), notifies as "Picture message · tap to download" (no content
  to classify, so only sender rules and mute apply), and a tap runs the download.
  6. `IncomingMessageHandler` classifies and notifies, the same path as SMS.
- **Group participants:** sender + To + Cc, minus this phone's own numbers. Those come from
  `SubscriptionManager.getPhoneNumber` on the default subscriptions, which needs only
  READ_PHONE_NUMBERS.
- **Send** (`sms/MmsSender`): SMIL + media + text as m-send-req, via
  `SmsManager.sendMultimediaMessage`. The m-send-conf moves the message to sent or failed.
  Photos are downscaled so the whole message fits about 900 KB.
- **Link previews** (`data/LinkPreviews`, opt-in): fetching a link tells its site your IP
  address and that the text was read, which is exactly what a spammer wants. So a message loads
  a preview only when the setting is on and either the user sent it, or its sender is trusted
  and its verdict is a plain "allow". A trusted sender is a contact, or the other person in a
  one-to-one conversation the user has texted; in groups only contacts count. A message with
  no verdict yet doesn't qualify.

  Each preview is one GET for the page (512 KB cap; Open Graph tags or `<title>` from the
  first 64 KB of `<head>`) and one for its image (2 MB cap, saved to the cache because Coil here
  has no network loader). There are no cookies or referrer, redirects are followed by hand (at
  most 5), and nothing goes to a private, loopback, link-local or CGNAT address. Private IP
  literals are refused before connecting, a DNS filter covers names, and a network interceptor
  checks every connection. The card always shows the real host, never the page's own site
  name. Definite answers are cached for a week; possibly temporary failures aren't.
- **Saving and sharing attachments** (`data/MediaExport`): parts live in the message store,
  which only the default SMS app can read, so Save copies into MediaStore (Pictures, Movies,
  Recordings or Download, in a Winnow folder; no permission needed) and Share copies into a
  FileProvider `outbox/` that `SharedFiles.cleanUp` empties after a day.
- **Contacts:** a vCard part (`text/x-vcard`, `text/vcard`) is drawn as a card
  (`data/VCard` parses 2.1 through 4.0, quoted-printable included). Sending uses the Contacts
  provider's own export (`Contacts.CONTENT_VCARD_URI`) with PHOTO and LOGO removed, since a
  photo can be most of a vCard and push it past the carrier's limit. Shared contacts get the
  same treatment.

Emulators have no MMS server. The debug-only `DebugMmsReceiver` feeds codec-built PDUs into
the receive path, with a photo, voice memo, video or contact card as asked. Its push mode exercises the download-failure and retry path.

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
