# Third-party notices

## SuperCollider

The native server and UGen plugin binaries in this project are built from or supplied for the SuperCollider 3.14.1 code line. SuperCollider is distributed under the GNU General Public License, version 2 or (at your option) any later version. See the upstream `COPYING` file and https://github.com/supercollider/supercollider for the complete license and corresponding source.

The file `tools/scsynth/SC_ExternalDriver.cpp` is an Android host-pumped backend based on the design published by scsynth-rs (https://github.com/mitchmindtree/scsynth-rs), adapted for the SuperCollider 3.14.1 driver API. Preserve the upstream license notices when redistributing modified sources and binaries.

The 23 `.so` plugin assets were provided in the user-supplied `scsynth-android-arm64-probe.zip`; their binary metadata was checked for ARM64 Android ABI and SuperCollider plugin entry points. They should be rebuilt from the matching upstream source with `tools/build-scsynth-android.sh` before release distribution.

## Android NDK / AAudio

The app uses Android NDK AAudio APIs supplied by the Android platform. No Oboe library is vendored in this revision.


## Mutable Instruments mi-UGens (MiRings / MiClouds)

The Stratum native engine uses the SuperCollider UGens from `v7b1/mi-UGens`, pinned to commit `10f6824f68ad63475f67dfe3fe5016e2d2634f84`. The UGen wrapper sources declare GPL version 3 or later; the embedded Mutable Instruments DSP components retain their upstream MIT notices. The Android build applies the reproducible lifecycle/allocation patch in `tools/patch-mi-ugens-android.py` and compiles only `MiRings.so` and `MiClouds.so` from source. Corresponding source is available from the pinned upstream revision together with the project patch and build script.

The SuperCollider class definitions used by CI to compile `NornsStratum.scsyndef` are preserved in `tools/mi-ugens-classes/`. They are derived from the same pinned mi-UGens revision.

## Stratum compatibility fixtures

CI fetches the upstream Stratum Lua script and `Engine_Stratum.sc` from `mrmattallison/stratum` at commit `8c7ca83e0b4ff8f3b20f170a68d7b782cf98ba74` for syntax and command-contract tests. The upstream repository does not include a license file; these fixtures are fetched into the CI workspace only and are not bundled in the APK. Users must obtain and import the script under its upstream terms.
