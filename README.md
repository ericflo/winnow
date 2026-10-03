# Winnow

An Android SMS app that classifies every incoming text before it can buzz your phone.
Personal and expected messages come through. Phishing, scams, spam and political blasts go
to Filtered without a notification, and marketing arrives silently. Nothing is deleted. One
tap fixes a wrong call and teaches Winnow about that sender.

Winnow uses a pluggable classifier. Jev by TypeSafe is the first provider, but the app depends only on a small decision interface, so any
compatible service, a general-purpose LLM, or an on-device model can replace it. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Status

v0.1 scaffold. Works today:

- Default-SMS-app plumbing: receive, store, send, "respond via message", role request
- Classification pipeline with privacy gate, redaction, timeouts, fail-open delivery
- Providers: System One wire (Jev via TypeSafe or OpenRouter, any self-hosted server) and
  OpenAI-compatible chat completions; on-device keyword fallback
- UI modeled on Google Messages: large-title inbox on a rounded sheet, avatar menu with
  Filtered (Winnow's "Spam & blocked"), timestamped conversation blocks, New chat with
  grouped contacts. Each verdict banner says what Winnow decided and who decided it, with a
  one-tap "Not spam". Settings has a live "Try it" box that shows exactly what would leave the phone.
- Sample conversations until Winnow is made the default SMS app

Not yet: MMS (receive or send), RCS (see below), group threads, search inside threads,
backup/import, an on-device model.

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
./gradlew :classifier:test                     # JVM unit tests for the classification layer
./gradlew :app:assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open Winnow, tap **Set as default**, and pick a provider in Settings. Until a
provider is configured, Winnow runs on-device only: keyword rules can silence messages
but never hide them.

## Layout

```
classifier/   Pure Kotlin/JVM. Decision interface, providers, message taxonomy, privacy, tests.
app/          Android app. Compose + Material 3, Room for verdicts, DataStore for settings.
docs/         Architecture and decisions.
```
