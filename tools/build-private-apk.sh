#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
android_dir="$repo_dir/android"
apk="$android_dir/app/build/outputs/apk/release/app-release.apk"
private_apk_dir="$android_dir/app/build/private-apks"
regular_apk="$private_apk_dir/nextnotif-api26.apk"
htc_apk="$private_apk_dir/nextnotif-htc-api25.apk"
expected_min_sdk=26
if [[ "${1:-}" == "--htc-api25" && $# -eq 1 ]]; then
  expected_min_sdk=25
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--htc-api25]" >&2
  exit 2
fi

required=(
  NEXTNOTIF_SIGNING_STORE_FILE
  NEXTNOTIF_SIGNING_STORE_PASSWORD
  NEXTNOTIF_SIGNING_KEY_ALIAS
  NEXTNOTIF_SIGNING_KEY_PASSWORD
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing $name" >&2
    exit 2
  fi
done
if [[ ! -f "$NEXTNOTIF_SIGNING_STORE_FILE" ]]; then
  echo "Signing keystore file does not exist" >&2
  exit 2
fi

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" || ! -d "$sdk_dir/build-tools" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to an installed Android SDK" >&2
  exit 2
fi
apksigner=""
aapt=""
while IFS= read -r candidate; do
  apksigner="$candidate"
done < <(find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner | sort -V)
while IFS= read -r candidate; do
  aapt="$candidate"
done < <(find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt | sort -V)
if [[ -z "$apksigner" ]]; then
  echo "Android SDK apksigner is unavailable" >&2
  exit 2
fi
if [[ -z "$aapt" ]]; then
  echo "Android SDK aapt is unavailable" >&2
  exit 2
fi

python3 "$repo_dir/tools/check-release-firebase-config.py" "$android_dir/app/google-services.json"

if [[ "$expected_min_sdk" == 25 ]]; then
  apk="$android_dir/app/build/outputs/apk/htcReceiver/app-htcReceiver.apk"
  (cd "$android_dir" && ./gradlew testHtcReceiverUnitTest lintHtcReceiver assembleHtcReceiver --no-daemon -PnextnotifCompatibilityTestMinSdk=25)
else
  (cd "$android_dir" && ./gradlew testDebugUnitTest lintDebug assembleRelease --no-daemon)
fi
if [[ ! -f "$apk" ]]; then
  echo "Release APK was not created" >&2
  exit 1
fi
"$apksigner" verify --verbose --print-certs "$apk"
if ! "$aapt" dump badging "$apk" | grep -q "sdkVersion:'$expected_min_sdk'"; then
  echo "Release APK minSdk did not match the requested target" >&2
  exit 1
fi
if [[ "$expected_min_sdk" == 25 ]]; then
  mkdir -p "$private_apk_dir"
  cp "$apk" "$htc_apk"
  shasum -a 256 "$htc_apk"
  echo "Signed HTC API 25 exception APK: $htc_apk"
else
  mkdir -p "$private_apk_dir"
  cp "$apk" "$regular_apk"
  shasum -a 256 "$regular_apk"
  echo "Signed private APK: $regular_apk"
fi
