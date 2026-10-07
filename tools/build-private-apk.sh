#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
android_dir="$repo_dir/android"
apk="$android_dir/app/build/outputs/apk/release/app-release.apk"

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
while IFS= read -r candidate; do
  apksigner="$candidate"
done < <(find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner | sort -V)
if [[ -z "$apksigner" ]]; then
  echo "Android SDK apksigner is unavailable" >&2
  exit 2
fi

python3 "$repo_dir/tools/check-release-firebase-config.py" "$android_dir/app/google-services.json"

(cd "$android_dir" && ./gradlew testDebugUnitTest lintDebug assembleRelease --no-daemon)
if [[ ! -f "$apk" ]]; then
  echo "Release APK was not created" >&2
  exit 1
fi
"$apksigner" verify --verbose --print-certs "$apk"
shasum -a 256 "$apk"
echo "Signed private APK: $apk"
