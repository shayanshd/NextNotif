#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
apk="${1:-$repo_dir/android/app/build/outputs/apk/release/app-release.apk}"
tinymix="$repo_dir/root-assets/a520f-audio-tools/tinymix"
module_prop="$repo_dir/tools/call-relay-module/module.prop"
sepolicy_rule="$repo_dir/tools/call-relay-module/sepolicy.rule"
privapp_permissions="$repo_dir/root-assets/nextnotif-privapp-module/system/etc/permissions/privapp-permissions-nextnotif.xml"
stage="/data/local/tmp/nextnotif-call-module"
module="/data/adb/modules/nextnotif_privapp"

test -f "$apk" || { echo "Missing APK: $apk" >&2; exit 1; }
test -f "$tinymix" || { echo "Missing A520F tinymix: $tinymix" >&2; exit 1; }
test -f "$privapp_permissions" || { echo "Missing privileged permission allowlist: $privapp_permissions" >&2; exit 1; }

adb shell "mkdir -p '$stage'"
adb push "$apk" "$stage/NextNotif.apk"
adb push "$tinymix" "$stage/nextnotif-tinymix"
adb push "$module_prop" "$stage/module.prop"
adb push "$sepolicy_rule" "$stage/sepolicy.rule"
adb push "$privapp_permissions" "$stage/privapp-permissions-nextnotif.xml"

adb shell su -c "mkdir -p '$module/system/priv-app/NextNotif' '$module/system/bin' '$module/system/etc/permissions'"
adb shell su -c "cp '$stage/NextNotif.apk' '$module/system/priv-app/NextNotif/NextNotif.apk'"
adb shell su -c "cp '$stage/nextnotif-tinymix' '$module/system/bin/nextnotif-tinymix'"
adb shell su -c "chmod 0644 '$module/system/priv-app/NextNotif/NextNotif.apk'"
adb shell su -c "chmod 0755 '$module/system/bin/nextnotif-tinymix'"
adb shell su -c "cp '$stage/module.prop' '$module/module.prop'"
adb shell su -c "cp '$stage/sepolicy.rule' '$module/sepolicy.rule'"
adb shell su -c "cp '$stage/privapp-permissions-nextnotif.xml' '$module/system/etc/permissions/privapp-permissions-nextnotif.xml'"
adb shell su -c "chmod 0644 '$module/module.prop' '$module/sepolicy.rule'"
adb shell su -c "chmod 0644 '$module/system/etc/permissions/privapp-permissions-nextnotif.xml'"

echo "Installed NextNotif call-relay module. Reboot is required for APK and SELinux policy changes."
