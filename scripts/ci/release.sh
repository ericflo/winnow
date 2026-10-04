#!/bin/bash
# Builds, signs and publishes the release APK for the tag being built (CI_COMMIT_TAG,
# e.g. v0.2.0): a GitHub Release for that tag with the APK and its SHA-256 attached.
# Runs in Woodpecker after the gate; see "Releases" in README.md for the secrets.
set -euo pipefail

tag="${CI_COMMIT_TAG:?not a tag build}"
# owner/name. The server always sets CI_REPO; the split form covers local woodpecker-cli exec.
repo="${CI_REPO:-${CI_REPO_OWNER:+$CI_REPO_OWNER/${CI_REPO_NAME:-}}}"
[[ "$repo" == */?* ]] || { echo "CI_REPO must be owner/name" >&2; exit 1; }
for secret in GH_TOKEN WINNOW_KEYSTORE_BASE64 WINNOW_KEYSTORE_PASSWORD WINNOW_KEY_ALIAS WINNOW_KEY_PASSWORD; do
  [ -n "${!secret:-}" ] || { echo "Missing $secret; see Releases in README.md" >&2; exit 1; }
done

# v1.2.3 -> versionName 1.2.3, versionCode 10203. A suffix (v1.2.3-rc1) makes a pre-release.
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Tag $tag isn't vMAJOR.MINOR.PATCH[-suffix]" >&2
  exit 1
fi
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]} suffix=${BASH_REMATCH[4]}
if (( minor > 99 || patch > 99 )); then echo "Minor and patch must be under 100 for the versionCode" >&2; exit 1; fi
version="${tag#v}"
version_code=$(( major * 10000 + minor * 100 + patch ))

./gradlew --no-daemon --stacktrace \
  -Pwinnow.versionName="$version" -Pwinnow.versionCode="$version_code" \
  :app:assembleRelease

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
build_tools="$ANDROID_HOME/build-tools/37.0.0"
printf '%s' "$WINNOW_KEYSTORE_BASE64" | base64 -d > "$work/release.jks"
apk_name="winnow-$version.apk"
# 16 KB page alignment for uncompressed native code, as Android 15+ devices expect.
"$build_tools/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$work/aligned.apk"
bash "$build_tools/apksigner" sign \
  --ks "$work/release.jks" --ks-pass env:WINNOW_KEYSTORE_PASSWORD \
  --ks-key-alias "$WINNOW_KEY_ALIAS" --key-pass env:WINNOW_KEY_PASSWORD \
  --out "$work/$apk_name" "$work/aligned.apk"
# verify's exit status is the check; the certificate lines are for the log, whatever their format.
bash "$build_tools/apksigner" verify --print-certs "$work/$apk_name" > "$work/verify.txt"
grep -E 'certificate (DN|SHA-256 digest)' "$work/verify.txt" || cat "$work/verify.txt"
(cd "$work" && sha256sum "$apk_name" > "$apk_name.sha256")

# GitHub CLI 2.102.0, pinned by checksum, to create the release and attach the files.
gh_dir="$work/gh"
mkdir -p "$gh_dir"
curl -fsSL --retry 3 -o "$work/gh.tgz" https://github.com/cli/cli/releases/download/v2.102.0/gh_2.102.0_linux_amd64.tar.gz
echo "bb766f710eef8ede859c18578c72c327597cd4c8a85b06001b1f3843c6019386  $work/gh.tgz" | sha256sum -c -
tar xzf "$work/gh.tgz" -C "$gh_dir" --strip-components=1
gh="$gh_dir/bin/gh"

# Made here, or reused if it already exists (a re-run, or one drafted by hand).
if ! "$gh" release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  flags=(--title "Winnow $version" --generate-notes --verify-tag)
  [ -n "$suffix" ] && flags+=(--prerelease)
  "$gh" release create "$tag" --repo "$repo" "${flags[@]}"
fi
"$gh" release upload "$tag" --repo "$repo" --clobber "$work/$apk_name" "$work/$apk_name.sha256"
echo "Published $apk_name to https://github.com/$repo/releases/tag/$tag"
