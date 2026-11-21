#!/usr/bin/env bash
# Build only the two mi-UGens required by Stratum for Android ARM64.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${SC_PROBE_DIR:-$ROOT/.sc-probe}"
SRC="${SC_SOURCE_DIR:-$WORK/supercollider}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
MI_COMMIT="10f6824f68ad63475f67dfe3fe5016e2d2634f84"
MI_SRC="$WORK/mi-UGens-$MI_COMMIT"
OUT="$WORK/build-arm64/server/plugins"

[[ -f "$SRC/include/plugin_interface/SC_PlugIn.h" ]] || {
  echo "::error::SuperCollider 3.14.1 plugin headers not found at $SRC"
  exit 1
}
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
  echo "::error::Android NDK toolchain not found: $NDK"
  exit 1
}

mkdir -p "$WORK" "$WORK/logs"
archive="$WORK/mi-UGens-$MI_COMMIT.tar.gz"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 --silent --show-error \
    "https://github.com/v7b1/mi-UGens/archive/$MI_COMMIT.tar.gz" -o "$archive"
fi
sha256sum "$archive" | tee "$WORK/logs/mi-ugens-source-sha256.txt"
if [[ ! -f "$MI_SRC/CMakeLists.txt" ]]; then
  rm -rf "$MI_SRC"
  mkdir -p "$MI_SRC"
  tar -xzf "$archive" --strip-components=1 -C "$MI_SRC"
fi

python3 "$ROOT/tools/patch-mi-ugens-android.py" "$MI_SRC"

# The pinned upstream project CMake files predate current CMake policy checks.
for name in MiRings MiClouds; do
  cmake_file="$MI_SRC/projects/$name/CMakeLists.txt"
  [[ -f "$cmake_file" ]] || {
    echo "::error::Missing upstream CMakeLists for $name"
    exit 1
  }
  if ! grep -Eq '^[[:space:]]*cmake_minimum_required[[:space:]]*\(' "$cmake_file"; then
    tmp="$cmake_file.tmp"
    {
      printf 'cmake_minimum_required(VERSION 3.22)\n\n'
      cat "$cmake_file"
    } > "$tmp"
    mv "$tmp" "$cmake_file"
  fi
done

build_plugin() {
  local name="$1"
  local build="$WORK/build-mi-$name"
  cmake -S "$MI_SRC/projects/$name" -B "$build" -G Ninja \
    -DSC_PATH="$SRC" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=17 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  cmake --build "$build" --target "$name" --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
  local binary
  binary="$(find "$build" -type f \( -name "$name.so" -o -name "lib$name.so" \) -print -quit)"
  [[ -n "$binary" && -s "$binary" ]] || {
    echo "::error::$name target built without producing a plugin .so"
    return 1
  }
  mkdir -p "$OUT"
  cp "$binary" "$OUT/$name.so"
  readelf -h "$OUT/$name.so" | grep -q 'Class:.*ELF64'
  readelf -h "$OUT/$name.so" | grep -q 'Machine:.*AArch64'
  echo "OK: $name ARM64 $(stat -c '%s' "$OUT/$name.so") bytes"
}

build_plugin MiRings
build_plugin MiClouds

test -s "$OUT/MiRings.so"
test -s "$OUT/MiClouds.so"
sha256sum "$OUT/MiRings.so" "$OUT/MiClouds.so" | tee "$WORK/logs/mi-ugens-sha256.txt"
