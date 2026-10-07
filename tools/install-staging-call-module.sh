#!/usr/bin/env bash
set -euo pipefail

# Dedicated Samsung A5 staging-only module. Never invoke install-call-relay-module.sh:
# that script writes to the original nextnotif_privapp module.
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
apk="$repo_dir/android/app/build/outputs/apk/stagingCall/app-stagingCall.apk"
module_prop="$repo_dir/tools/staging-call-module/module.prop"
allowlist="$repo_dir/tools/staging-call-module/privapp-permissions-nextnotif-staging-call.xml"
serial="52006a98f0ac6489"
stage="/data/local/tmp/nextnotif-staging-call-module"
module="/data/adb/modules/nextnotif_staging_call"
original="/data/adb/modules/nextnotif_privapp/system/priv-app/NextNotif/NextNotif.apk"
expected_apk_sha="a8d0894c44dce0b256d3a4f7fc8600060b5a6a9df545b2140dfa7dcb83b6a650"

adb_cmd=(adb -H 127.0.0.1 -P 5038 -s "$serial")
test -f "$apk" && test -f "$module_prop" && test -f "$allowlist"
actual_apk_sha="$(shasum -a 256 "$apk" | awk '{print $1}')"
test "$actual_apk_sha" = "$expected_apk_sha" || { echo "Staging APK checksum changed; review before install" >&2; exit 1; }
"${adb_cmd[@]}" get-state | grep -qx device
"${adb_cmd[@]}" shell "test \"\$(getprop ro.product.model)\" = SM-A520F" \
  || { echo "Samsung model check failed" >&2; exit 1; }
"${adb_cmd[@]}" shell "su -c 'test -f $original && ! test -e $module'" \
  || { echo "Samsung/original-module check failed or staging target exists" >&2; exit 1; }

# Create only a new, uniquely named device staging directory and module.
"${adb_cmd[@]}" shell "mkdir -p '$stage'"
"${adb_cmd[@]}" push "$apk" "$stage/NextNotifStagingCall.apk" >/dev/null
"${adb_cmd[@]}" push "$module_prop" "$stage/module.prop" >/dev/null
"${adb_cmd[@]}" push "$allowlist" "$stage/privapp-permissions-nextnotif-staging-call.xml" >/dev/null
"${adb_cmd[@]}" shell "test \"\$(sha256sum '$stage/NextNotifStagingCall.apk' | cut -d ' ' -f 1)\" = '$expected_apk_sha'"
"${adb_cmd[@]}" shell "su -c 'test -f $original && ! test -e $module && mkdir $module && touch $module/disable && mkdir -p $module/system/priv-app/NextNotifStagingCall $module/system/etc/permissions && cp $stage/NextNotifStagingCall.apk $module/system/priv-app/NextNotifStagingCall/NextNotifStagingCall.apk && cp $stage/module.prop $module/module.prop && cp $stage/privapp-permissions-nextnotif-staging-call.xml $module/system/etc/permissions/privapp-permissions-nextnotif-staging-call.xml && chmod 0644 $module/module.prop $module/system/priv-app/NextNotifStagingCall/NextNotifStagingCall.apk $module/system/etc/permissions/privapp-permissions-nextnotif-staging-call.xml'"
"${adb_cmd[@]}" shell "su -c 'sha256sum $module/system/priv-app/NextNotifStagingCall/NextNotifStagingCall.apk | grep -q ^$expected_apk_sha'"
"${adb_cmd[@]}" shell "su -c 'test -f $original && test -f $module/disable && rm $module/disable'"

echo "Staging-only Magisk module enabled. A Samsung reboot is required; original module was not written."
echo "Rollback: adb shell su -c 'touch /data/adb/modules/nextnotif_staging_call/disable', then reboot."
