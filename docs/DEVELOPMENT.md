# Developing Winnow

## Build and test

You need JDK 17+ (21 recommended) and an Android SDK with API 37. Winnow runs on Android 12 (API 31) and later.

```sh
export JAVA_HOME=~/.local/opt/jdk-21          # wherever your JDK lives
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :classifier:test :mms:test :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:lintDebug :app:assembleDebug                   # lint, then app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Winnow, follow onboarding, and pick a classifier. Until a provider is set up, everything is decided on the phone.

| Module | What it is |
|---|---|
| `classifier/` | Pure Kotlin/JVM: the `DecisionProvider` interface, providers, taxonomy, privacy and redaction, the on-phone model and its training corpus |
| `mms/` | Pure Kotlin/JVM: MMS PDU encoder and decoder (OMA-MMS-ENC over WSP) |
| `app/` | The Android app: Compose + Material 3, Room for verdicts and conversation state, DataStore for settings, the system SMS/MMS store for messages |

[ARCHITECTURE.md](ARCHITECTURE.md) covers how a text is classified and how to add a provider.

## The on-phone model

`./gradlew :classifier:trainLocalModel` retrains the shipped model from `classifier/training/corpus` and rewrites its metrics and [REPORT.md](../classifier/training/REPORT.md). It's deterministic, and a test fails if the shipped model is stale. Other tasks:

- `tuneLocalModel`: a grid search over training settings;
- `evalMistakes`: lists the shipped model's mistakes on the held-out set;
- `labCeilingExperiment`: what label noise and per-sender labels do to accuracy.

## On an emulator

```sh
scripts/emulator-smoke.sh
```

The smoke script:

- installs the app and takes the SMS role;
- feeds it sample traffic: SMS through the emulator's modem, a tapback, a group MMS with a photo, and an MMS whose download fails;
- refuses to run unless the only attached device is an emulator.

Every number is fictional (555-01xx), and nothing leaves the machine.

By hand, `adb emu sms send 4155550123 "hello"` delivers an SMS. Debug builds also accept MMS through the real receive path, since emulators have no MMS server:

```sh
adb shell "am broadcast -n com.ericflo.winnow/.debug.DebugMmsReceiver \
    --es from +14155550181 --es to +15551234567,+14155550182 --es text 'hi' --ez photo true"
```

`--ez voice true`, `--ez video true` and `--ez contact true` attach a voice memo, a clip or a contact card. `DebugSeedReceiver` writes thousands of synthetic messages for testing at scale. Each debug receiver documents its options at the top of its file.

## Live classifier test (opt-in)

This runs the real pipeline against Jev with your own key, and never runs in CI:

```sh
WINNOW_LIVE_TESTS=1 ./gradlew :classifier:test --tests '*LiveProviderTest*' --rerun
```

It reads `OPENROUTER_API_KEY` and/or `TYPESAFE_API_KEY`.

## CI and releases

CI runs on [Woodpecker](https://woodpecker-ci.org/) from `.woodpecker.yml`.

- **Every push to `main`** runs the gate: unit tests for all three modules, Android lint, and a debug build, in `eclipse-temurin:21-jdk` as an unprivileged user. `scripts/ci/android-sdk.sh` installs the SDK, with the command-line tools pinned by checksum.
- **The gate locally:**

  ```sh
  ./gradlew :classifier:test :mms:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  ```

To cut a release, push the commit to `main`, let its pipeline pass, then tag that commit:

```sh
git tag -a v0.2.0 -m "Winnow 0.2.0"
git push origin v0.2.0
```

The tag's pipeline runs `scripts/ci/release.sh`, which:

1. builds `:app:assembleRelease` with versionName `0.2.0` and versionCode MAJOR×10000 + MINOR×100 + PATCH;
2. aligns and signs the APK with the release key;
3. attaches `winnow-0.2.0.apk` and its `.sha256` to the GitHub Release.

A suffix such as `v0.2.0-rc1` makes it a pre-release.

### Release secrets

The release step needs five repository secrets, each limited to the `tag` event so ordinary pushes never see them:

| Secret | What it holds |
|---|---|
| `winnow_github_token` | A fine-grained GitHub token for this repository only, with Contents: read and write |
| `winnow_keystore_base64` | The release keystore, base64-encoded on one line |
| `winnow_keystore_password` | The keystore's password |
| `winnow_key_alias` | The signing key's alias |
| `winnow_key_password` | The signing key's password |

Make the keystore once and keep it safe: Android only installs an update signed with the same key.

```sh
keytool -genkeypair -v -keystore winnow-release.jks -alias winnow -keyalg RSA -keysize 4096 -validity 10000
woodpecker-cli repo secret add --repository <owner>/winnow --event tag \
  --name winnow_keystore_base64 --value "$(base64 -w0 winnow-release.jks)"
```

A fork builds and tests without any secrets; only the tag's release step needs them.
