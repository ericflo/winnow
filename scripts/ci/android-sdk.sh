#!/bin/sh
# Installs the Android SDK pieces Winnow builds against into $ANDROID_HOME, as an
# unprivileged user, starting from a plain JDK image. Safe to run again: whatever
# is already installed is kept.
set -eu
: "${ANDROID_HOME:?ANDROID_HOME must be set}"

# Android command-line tools 23.0. Pinned by checksum, not "latest".
TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
TOOLS_SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -f "$sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  zip="$ANDROID_HOME/cmdline-tools.zip"
  curl -fsSL --retry 3 -o "$zip" "$TOOLS_URL"
  echo "$TOOLS_SHA256  $zip" | sha256sum -c -
  # The JDK image has no unzip; the JDK's own jar tool reads zips, but drops the
  # execute bit, which sdkmanager and the native tool it hands off to need back.
  (cd "$ANDROID_HOME/cmdline-tools" && jar xf "$zip")
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
  rm "$zip"
fi

yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true
# Matches compileSdk and the build tools the Android Gradle plugin expects.
"$sdkmanager" --install "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
test -d "$ANDROID_HOME/platforms/android-37.0" && test -d "$ANDROID_HOME/build-tools/37.0.0"
