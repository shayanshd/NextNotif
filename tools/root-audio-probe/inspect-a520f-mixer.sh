#!/system/bin/sh
set -eu

tinymix=/data/local/tmp/a520f-audio-tools/tinymix

"$tinymix" -D 0 get "AudioMixer CH2 DOUT Select"
"$tinymix" -D 0 get "AudioMixer Mixer En"
"$tinymix" -D 0 get "AudioMixer SRC2 En"
"$tinymix" -D 0 get "AudioMixer CH2 Mixer En"
