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

## Messages

Messages live in the system Telephony provider, as Android expects of a default SMS app.
Winnow stores only what it adds, in Room: one verdict per message (`sms:<id>`), plus
per-sender rules from "Not spam" / "Filter sender".

`MessageRepository` hides the transport. `TelephonyMessageRepository` is the real one;
`DemoMessageRepository` serves sample threads until SMS access is granted, which also
makes the UI developable on an emulator.

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
