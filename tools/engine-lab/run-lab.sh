#!/usr/bin/env bash
# Uso: run-lab.sh <pasta-do-script> <classe-da-engine> <pasta-de-saída>
# Requer sclang/scsynth no PATH, um servidor JACK (dummy) e o clone do monome/norns em $NORNS_DIR.
set -u
SRC=$(realpath "$1"); ENGINE=$2; OUT=$(realpath -m "$3")
HERE=$(cd "$(dirname "$0")" && pwd)
EXT="$HOME/.local/share/SuperCollider/Extensions"
rm -rf "$EXT/lab"; mkdir -p "$EXT/lab/norns" "$EXT/lab/script" "$OUT/synthdefs"
cp "$NORNS_DIR"/sc/core/*.sc "$EXT/lab/norns/"
rm -f "$EXT/lab/norns/CroneTester.sc"
cp "$HERE"/classes/*.sc "$EXT/lab/"
(cd "$SRC" && find . -name "*.sc" -exec cp --parents {} "$EXT/lab/script/" \;)
export LAB_ENGINE="$ENGINE" LAB_OUT="$OUT" QT_QPA_PLATFORM=offscreen
timeout -k 5 90 xvfb-run -a sclang -D "$HERE/lab.scd" > "$OUT/sclang.log" 2>&1
echo "exit=$?" >> "$OUT/sclang.log"
pkill -f scsynth || true
