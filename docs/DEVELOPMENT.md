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

The Lab's "words that mean alike" groups ship as `word-clusters.txt.gz`: the 30,000 commonest words of GloVe's vectors (public domain), clustered into 512 groups by `classifier/training/word_clusters.py`, whose header says how to rebuild them. On the hand-written texts, with only 10–50% of them as labels, they added about a point of accuracy for both linear and neural models.

"What the whole text means" (linear models) ships as `word-meanings.bin.gz`: GloVe's 50-number vectors for the 20,000 commonest words, one byte a number, made by `classifier/training/word_meanings.py`. A text's meaning is its known words' vectors averaged. With 10–50% of the hand-written texts as labels it added 0.8–1.4 points to a linear model (more than the groups); with 80%, nothing.

## On an emulator

```sh
scripts/emulator-smoke.sh
```

The smoke script:

- installs the app and takes the SMS role;
- feeds it sample traffic: SMS through the emulator's modem, a tapback, a group MMS with a photo, and an MMS whose download fails;
- refuses to run unless the only attached device is an emulator.

### MMS carrier and recovery regression checks

After the smoke script, on a rootable emulator:

```sh
adb root
python3 scripts/emulator-mms-regression.py
```

This exercises the installed app and the system MMS provider with a temporary carrier-config
override. It checks distinct notifications sharing an incomplete URL, duplicate delivery,
retry URL stability, absolute expiry, repair of old placeholders, preservation of deferred
messages, and recovery of abandoned downloads. It restores the carrier flag and removes its
own fictional messages. It does not test a live carrier's APN or MMS server.

The October 2026 MMS receive fix addresses app-side protocol requirements that
`SmsManager.downloadMultimediaMessage` does not perform:

- `enabledTransID`: when enabled, a notification URL ending in `=` needs its transaction ID
  appended **before both persistence and downloading**. Otherwise the server receives an
  incomplete URL, and separate messages sharing that URL can be mistaken for duplicates.
- `enabledNotifyWapMMSC`: replies go to the notification URL when the carrier requests it.
- Relative `X-Mms-Expiry` values must be converted to epoch seconds in the shared provider.
  Older Winnow stored the duration verbatim, making messages appear to expire near 1970 to
  other SMS apps. Startup/role recovery corrects these values using the original receipt time.
- A carrier error in a valid `RetrieveConf` must not be saved or acknowledged as a message.
- Download attempts need a persisted timeout even when Android never sends a callback. The
  retry job also runs without a general internet connection; Android acquires the MMS network.

These rules are documented in [SmsManager](https://developer.android.com/reference/android/telephony/SmsManager#MMS_CONFIG_APPEND_TRANSACTION_ID)
and implemented by [AOSP Messaging](https://android.googlesource.com/platform/packages/apps/Messaging/+/master/src/com/android/messaging/sms/MmsUtils.java).
[Android's Verizon configuration](https://android.googlesource.com/platform/packages/apps/CarrierConfig/+/main/assets/carrier_config_carrierid_1839_Verizon-Wireless.xml)
enables both carrier flags. The reported 404 is consistent with the missing transaction ID;
the screenshots alone do not prove that the carrier expired or deleted those messages.

Before releasing, verify a fresh photo and group MMS on a real SIM with Winnow default and
the app closed, recovery after temporarily losing service, manual download with auto-download
disabled, and the receiving SIM on a dual-SIM phone. Existing placeholders can be repaired,
but a message already removed from the carrier's server requires the sender to resend it.

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
