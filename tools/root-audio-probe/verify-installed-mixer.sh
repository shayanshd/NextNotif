#!/system/bin/sh
set -eu

if [ "$#" -gt 0 ]; then
    /system/bin/nextnotif-tinymix -D 0 set "AudioMixer CH2 DOUT Select" "$1"
fi
/system/bin/nextnotif-tinymix -D 0 get "AudioMixer CH2 DOUT Select"
