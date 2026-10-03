# Winnow

An Android SMS app that classifies every incoming text before it can buzz your phone.
Personal and expected messages come through. Phishing, scams, spam and political blasts go
to Filtered without a notification, and marketing arrives silently. Nothing is deleted. One
tap fixes a wrong call and teaches Winnow about that sender.

Winnow uses a pluggable classifier. Jev by TypeSafe is the first provider, but the app depends only on a small decision interface, so any
compatible service, a general-purpose LLM, or an on-device model can replace it. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Status

A working SMS/MMS app, developed and verified on the Android emulator only. It hasn't run on a phone yet.

- **Messaging:** SMS and MMS send/receive as the default SMS app; group MMS (participants
  threaded correctly, sender names and avatars); photos in and out (downscaled to carrier
  limits) with a full-screen viewer; iPhone tapbacks and SMS reactions drawn on the message
  they react to; retry for failed sends and MMS downloads; opt-in delivery reports;
  scheduled send; drafts; SMS segment counter.
- **Conversations:** pin, archive, mute, mark read/unread, delete, block (Android's system
  block list), multi-select; swipe to archive (inbox), to mark not spam (Filtered), or to
  unarchive (Archived); a details screen with participants and per-sender decisions;
  full-text search across SMS and MMS; New chat with contacts and Create group; contact
  photos throughout.
- **Messages:** copy, forward, delete, details; tappable links, emails and numbers, except
  in phishing/scam verdicts, where links are disabled; "Copy code" for verification codes.
- **Notifications:** Android conversation notifications (shortcuts, Conversations section,
  priority), stacked per thread, with inline Reply, Mark as read and Copy code.
- **Classification:** every incoming SMS and MMS goes through the provider-agnostic classifier
  (Jev, any System One server, any OpenAI-compatible LLM, or on-device rules) with a privacy
  gate and redaction. Filtered conversations go to a "Spam & blocked"-style list, and each
  carries a banner saying why, with "Not spam" and "Report" (to the carrier's 7726). Older
  conversations from before Winnow can be reviewed on request.
- **First run:** onboarding explains the RCS trade-off before asking to become the SMS app,
  then offers a choice of classifier.
- **UI:** modeled on Google Messages: large-title inbox on a rounded sheet, avatar menu,
  timestamped conversation blocks, Material You colors, dark mode.

Not yet: RCS (see below), backup/import, an on-device model, multi-SIM choice.

## Categories

Personal, Transactional and Marketing (silenced) reach the inbox. Political, Phishing, Likely
scam and Spam are filtered. Each category's action can be changed in Settings. A verdict
below 70% confidence is softened one step: Filter becomes Silence, Silence becomes Notify.

To check the live classifier, run the opt-in test. It uses `OPENROUTER_API_KEY` and/or `TYPESAFE_API_KEY`:

```sh
WINNOW_LIVE_TESTS=1 ./gradlew :classifier:test --tests '*LiveProviderTest*' --rerun
```

## RCS

Android has no public RCS API. Only Google Messages (and formerly Samsung Messages) can
use it, so a third-party default SMS app gets SMS/MMS only. Making Winnow the default
means RCS chats fall back to SMS/MMS. The transport is isolated behind `MessageRepository`
so an RCS transport can be added if Google ever opens one. Details and alternatives are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#rcs).

## Build

Needs JDK 17+ (21 recommended) and an Android SDK with API 37.

```sh
export JAVA_HOME=~/.local/opt/jdk-21          # wherever your JDK lives
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :classifier:test :mms:test :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On an emulator, `adb emu sms send 4155550123 "hello"` delivers an SMS. Debug builds also
accept fake MMS through the real receive path (emulators have no MMS server):

```sh
adb shell am broadcast -n com.ericflo.winnow/.debug.DebugMmsReceiver \
    --es from +14155550181 --es to "+15551234567,+14155550182" --es text "hi" --ez photo true
```

Use fictional 555-01xx numbers when testing. `scripts/emulator-smoke.sh` does all of the above
in one go (install, SMS role, sample SMS, a tapback, group MMS and a failed MMS download), and
refuses to run unless exactly one device is attached and that device is an emulator.

Then open Winnow, tap **Set as default**, and pick a provider in Settings. Until a
provider is configured, Winnow runs on-device only: keyword rules can silence messages
but never hide them.

## Layout

```
classifier/   Pure Kotlin/JVM. Decision interface, providers, message taxonomy, privacy, tests.
mms/          Pure Kotlin/JVM. MMS PDU encoder/decoder (OMA-MMS-ENC over WSP), tests.
app/          Android app. Compose + Material 3; Room for verdicts, conversation state and
              scheduled sends; DataStore for settings; the system SMS/MMS store for messages.
docs/         Architecture and decisions.
```
