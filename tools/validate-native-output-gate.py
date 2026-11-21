#!/usr/bin/env python3
"""Fail closed while native Softcut and Java still use separate output streams."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lua = (root / "app/src/main/assets/lua/boot.lua").read_text(encoding="utf-8")
scd = (root / "tools/compile-softcut-synthdef.scd").read_text(encoding="utf-8")
driver = (root / "tools/SC_AndroidAudio.cpp").read_text(encoding="utf-8")
audio = (root / "app/src/main/java/org/nornsmobile/app/AudioEngine.java").read_text(encoding="utf-8")
bridge = (root / "app/src/main/cpp/sc_bridge.cpp").read_text(encoding="utf-8")

assert 'out = 9' in lua and 'play = 0' in lua, "native voice must use dedicated bus and stay muted"
assert 'elseif command == "play" then\n    params.play = value\n    params.phase_play = (params.play ~= 0 or params.rec ~= 0) and 1 or 0\n    control = "phase_play"' in lua, "play must only update the muted phase tracker"
assert 'control = "play"' not in lua, "native audible play must remain disconnected"
assert 'enable = 0' in scd and 'fade_time' in scd, "voice gate controls missing"
assert 'phaseTracking = phase_play * phase_enable' in scd
assert 'Out.ar(position_bus, boundedPhase / bufferSampleRate)' in scd
assert 'position_bus = 10 + voice' in lua
assert 'builder.setDirection(oboe::Direction::Output)' in driver
assert 'builder.setChannelCount(2)' in driver
assert 'mWorld->mNumOutputs != 2' in driver
assert 'destination[i] = 0.0f' in driver or 'std::fill(output, output +' in driver
assert 'AudioTrack' in audio and 't.write(buf' in audio, "Java output path unexpectedly changed"
assert 'nativeReadOutput' not in bridge and 'NornsSC_ReadOutput' not in driver
assert 'NornsSC_EnableNativeSoftcut' not in driver
assert 'options.mNumInputBusChannels = 1;' in driver, "scsynth must expose one physical input bus"
assert 'mWorld->mAudioBus + mWorld->mNumOutputs * blockFrames' in driver, "input bus offset must follow output buses"
assert 'mWorld->mAudioBusTouched + mWorld->mNumOutputs' in driver, "input touched flags must use matching bus offset"
assert 'touched[channel] = bufferCounter' in driver, "each input bus must be marked touched per SC block"
assert 'mScInputRing.readWithBacklogLimit(destination, blockFrames,' in driver, "captured samples must fill the SC input bus with bounded backlog"
print("OK: muted native output gate, Oboe/Java separation, SC input bus offset and touched flags")

assert 'mSoftcutOutputProbeMode' in driver and 'mPendingProbeMode' in driver
assert 'NornsSC_SetSoftcutOutputProbeMode' in driver
assert 'nativeSetSoftcutOutputProbeMode' in bridge
assert 'mSoftcutProbeOutputPeaks' in driver
assert 'std::fill(destination, destination + static_cast<size_t>(count) * channels, 0.0f)' in driver
print("OK: muted Softcut stereo route probe never forwards probe PCM to Oboe")
