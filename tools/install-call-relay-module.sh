#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
apk="$repo_dir/android/app/build/outputs/apk/debug/app-debug.apk"
tinymix="$repo_dir/root-assets/a520f-audio-tools/tinymix"
module_prop="$repo_dir/tools/call-relay-module/module.prop"
sepolicy_rule="$repo_dir/tools/call-relay-module/sepolicy.rule"
stage="/data/local/tmp/nextnotif-call-module"
module="/data/adb/modules/nextnotif_privapp"

test -f "$apk" || { echo "Missing APK: $apk" >&2; exit 1; }
test -f "$tinymix" || { echo "Missing A520F tinymix: $tinymix" >&2; exit 1; }

adb shell "mkdir -p '$stage'"
adb push "$apk" "$stage/NextNotif.apk"
adb push "$tinymix" "$stage/nextnotif-tinymix"
adb push "$module_prop" "$stage/module.prop"
adb push "$sepolicy_rule" "$stage/sepolicy.rule"

adb shell su -c "mkdir -p '$module/system/priv-app/NextNotif' '$module/system/bin'"
adb shell su -c "cp '$stage/NextNotif.apk' '$module/system/priv-app/NextNotif/NextNotif.apk'"
adb shell su -c "cp '$stage/nextnotif-tinymix' '$module/system/bin/nextnotif-tinymix'"
adb shell su -c "chmod 0644 '$module/system/priv-app/NextNotif/NextNotif.apk'"
adb shell su -c "chmod 0755 '$module/system/bin/nextnotif-tinymix'"
adb shell su -c "cp '$stage/module.prop' '$module/module.prop'"
adb shell su -c "cp '$stage/sepolicy.rule' '$module/sepolicy.rule'"
adb shell su -c "chmod 0644 '$module/module.prop' '$module/sepolicy.rule'"

echo "Installed NextNotif call-relay module. Reboot is required for APK and SELinux policy changes."
