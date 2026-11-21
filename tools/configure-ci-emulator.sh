#!/usr/bin/env bash
set -euo pipefail

# The GitHub-hosted Linux runner may have less free disk than the default
# Android 35 AVD userdata image requests (~7.3 GB). Keep the emulator's data
# partition bounded; the Screen golden tests need only a clean install and
# a small app sandbox, not a multi-gigabyte userdata image.
avd_root="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
avd_config="$avd_root/test.avd/config.ini"

if [[ ! -f "$avd_config" ]]; then
  echo "Android AVD config not found: $avd_config" >&2
  exit 1
fi

sed -i '/^disk\.dataPartition\.size=/d' "$avd_config"
printf '\ndisk.dataPartition.size=2048M\n' >> "$avd_config"

grep -F 'disk.dataPartition.size=2048M' "$avd_config"
df -h "$avd_root"
