#!/usr/bin/env bash
set -euo pipefail

# Samsung A5 only. Stages a new Magisk module for the signed private MVP APK.
# The already installed app keeps its data; a reboot activates the system copy.
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
apk="$repo_dir/android/app/build/private-apks/nextnotif-api26.apk"
helper="$repo_dir/root-assets/a520f-audio-tools/tinymix"
allowlist="$repo_dir/root-assets/nextnotif-privapp-module/system/etc/permissions/privapp-permissions-nextnotif.xml"
policy="$repo_dir/tools/call-relay-module/sepolicy.rule"
stage="/data/local/tmp/nextnotif-private-call-v3"
module="/data/adb/modules/nextnotif_private_call"
adb_cmd=(adb -H 127.0.0.1 -P 5038 -s 52006a98f0ac6489)

for file in "$apk" "$helper" "$allowlist" "$policy"; do
  test -f "$file" || { echo "Missing module input: $file" >&2; exit 1; }
done
apk_sha="$(shasum -a 256 "$apk" | awk '{print $1}')"
"${adb_cmd[@]}" get-state | grep -qx device
"${adb_cmd[@]}" shell 'test "$(getprop ro.product.model)" = SM-A520F && test "$(getprop ro.build.version.sdk)" = 26'
"${adb_cmd[@]}" shell "su -c 'test ! -e $module && test ! -e /data/adb/modules/nextnotif_privapp && test ! -e /data/adb/modules/nextnotif_staging_call'"
"${adb_cmd[@]}" shell "mkdir -p '$stage'"
"${adb_cmd[@]}" push "$apk" "$stage/NextNotif.apk" >/dev/null
"${adb_cmd[@]}" push "$helper" "$stage/nextnotif-tinymix" >/dev/null
"${adb_cmd[@]}" push "$allowlist" "$stage/privapp-permissions-nextnotif.xml" >/dev/null
"${adb_cmd[@]}" push "$policy" "$stage/sepolicy.rule" >/dev/null
"${adb_cmd[@]}" shell "test \"\$(sha256sum '$stage/NextNotif.apk' | cut -d ' ' -f 1)\" = '$apk_sha'"

"${adb_cmd[@]}" shell "su -c 'mkdir $module && touch $module/disable && mkdir -p $module/system/priv-app/NextNotif $module/system/bin $module/system/etc/permissions && cp $stage/NextNotif.apk $module/system/priv-app/NextNotif/NextNotif.apk && cp $stage/nextnotif-tinymix $module/system/bin/nextnotif-tinymix && cp $stage/privapp-permissions-nextnotif.xml $module/system/etc/permissions/privapp-permissions-nextnotif.xml && cp $stage/sepolicy.rule $module/sepolicy.rule && chmod 0644 $module/system/priv-app/NextNotif/NextNotif.apk $module/system/etc/permissions/privapp-permissions-nextnotif.xml $module/sepolicy.rule && chmod 0755 $module/system/bin/nextnotif-tinymix'"
"${adb_cmd[@]}" shell "su -c 'printf \"id=nextnotif_private_call\\nname=NextNotif Private Call Relay\\nversion=1.0\\nversionCode=3\\nauthor=NextNotif\\ndescription=Signed private MVP call audio bridge for Samsung A5\\n\" > $module/module.prop && chmod 0644 $module/module.prop'"
"${adb_cmd[@]}" shell "su -c 'sha256sum $module/system/priv-app/NextNotif/NextNotif.apk | grep -q ^$apk_sha && test -f $module/disable && rm $module/disable'"

echo "Enabled Samsung private call module for APK $apk_sha; reboot Samsung, then verify privileged grants and call capability."
