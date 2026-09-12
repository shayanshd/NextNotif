#!/system/bin/sh
set -eu

tinymix=/data/local/tmp/a520f-audio-tools/tinymix
control="AudioMixer CH2 DOUT Select"

restore_uplink() {
    "$tinymix" -D 0 set "$control" AIF4IN
    echo ROUTE_RESTORED
    "$tinymix" -D 0 get "$control"
}

trap restore_uplink EXIT INT TERM

echo ROUTE_BEFORE
"$tinymix" -D 0 get "$control"
"$tinymix" -D 0 set "$control" DMIX_OUT
echo ROUTE_DURING
"$tinymix" -D 0 get "$control"

am start -n com.nextnotif.app/.MainActivity --ez run_call_injection_probe true
sleep 7
