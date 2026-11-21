#!/usr/bin/env python3
"""Fail-closed checks for exclusive Java/Oboe Softcut output routing."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
driver = (root / "tools/SC_AndroidAudio.cpp").read_text()
synth = (root / "tools/compile-softcut-synthdef.scd").read_text()
lua = (root / "app/src/main/assets/lua/boot.lua").read_text()
audio = (root / "app/src/main/java/org/nornsmobile/app/AudioEngine.java").read_text()
bridge = (root / "app/src/main/java/org/nornsmobile/app/SuperColliderBridge.java").read_text()
jni = (root / "app/src/main/cpp/sc_bridge.cpp").read_text()
router = (root / "app/src/main/java/org/nornsmobile/app/SoftcutOutputOwnership.java").read_text()
tests = (root / "app/src/test/java/org/nornsmobile/app/SoftcutOutputOwnershipTest.java").read_text()
assert "|out = 9, position_bus = 11," in synth and "Out.ar(out, Pan2.ar" in synth
assert "Out.ar(position_bus, boundedPhase / bufferSampleRate)" in synth
assert "phaseTracking = phase_play * phase_enable" in synth
assert "out = 9, position_bus = 10 + voice, buf = params.buf" in lua
assert "phase_play = params.phase_play" in lua and "phase_enable = params.phase_enable" in lua
assert 'control = "phase_play"' in lua
assert "const int bus = 9 + channel" in driver
assert "const int positionBus = 11 + voice;" in driver
assert "NornsSC_ReadSoftcutDiagnostics" in driver
assert "NornsSC_ResetSoftcutRecorderDiagnostics" in driver
assert "mRecorderDiagnosticResetGeneration" in driver and "SoftcutSignalAccumulator" in driver
assert "mRecorderSignalAccumulators[accumulatorIndex].add" in driver
assert "mRecorderSignalAccumulators[accumulatorIndex].rms()" in driver
assert "nativeResetSoftcutRecorderDiagnostics" in jni
assert "mDiagnosticSnapshot.read" in driver and "mDiagnosticSnapshot.publish" in driver
assert "diagnosticValues[13]" in driver and "mDiagnosticCallbackSequence" in driver
assert "mRecorderPositionsSeconds" in driver and "positionBus = 17 + channel" in driver
assert "diagnosticValues[14 + channel]" in driver and "count < 54" in driver
assert "read(values, 54)" in jni and "length >= 54" in bridge
assert "new float[54]" in (root / "app/src/main/java/org/nornsmobile/app/LuaHost.java").read_text()
assert "position_bus = 17" in synth and "Out.ar(position_bus, writePhase / sampleRate)" in synth
assert "count < 54" in driver and "read(values, 54)" in jni
assert "length >= 54" in bridge and "new float[54]" in (root / "app/src/main/java/org/nornsmobile/app/LuaHost.java").read_text()
assert "javaWritePosition = javaHeadPosition" in lua
assert "local startSec = math.max(0, params.loop_start)" in lua
assert "local endSec = math.max(startSec, params.loop_end)" in lua
assert "local readbackStartSec = math.max(0, params.loop_start + math.min(0, offset))" in lua
assert "local readbackEndSec = math.max(readbackStartSec, params.loop_end + math.max(0, offset))" in lua
assert "host.sc_softcut_record_stop(channel, node, readbackStartSec, readbackEndSec)" in lua
assert "host.sc_softcut_recorder_sample" in lua
assert "host.sc_softcut_recorder_reanchor" in lua
buffers = (root / "app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java").read_text()
assert "recordingActive[index] || recordingStopPending[index]" not in buffers
assert "SoftcutPhaseSupervisor" in audio and "observeNativeSoftcutSnapshot" in audio
assert "softcut_native_supervisor_tick" in lua and "softcut_native_supervisor_start(generation)" in lua
assert "softcut_native_phase_reanchor" in lua and "pcall(sc_api.set, node, {position = position})" in lua
assert "snapshot_fresh" in lua and "nativeGateClosed" in lua and "should_reanchor" in lua
assert "nativeGateClosed = diagnostics.routing ~= nil and diagnostics.routing.native_gate_open == false" in lua
assert "function softcut.native_supervisor_diagnostics()" in lua
assert 'softcut_native_command(v, "position", position)' not in lua
assert "output[static_cast<size_t>(frame) * channels + channel]" in driver
assert "mRouteProbe[4 + channel] = peak" in driver
assert "mRouteProbe[2 + channel] = softcutPeak" in driver
assert "SoftcutDiagnosticSnapshot.h" in (root / "tools/patch-scsynth-android.py").read_text()
assert "mNativeSoftcutOutputEnabled{false}" in driver
assert "options.mNumBuffers = 20032" in driver
assert "ISOLATION_PROBE_BUFFER_CH1 = 20003" in buffers
assert "ISOLATION_PROBE_BUFFER_CH2 = 20004" in buffers
assert "awaitJavaDrain" in router and "javaMuteDrainTargetFrame" in audio
assert "getPlaybackHeadPosition()" in audio and "javaFramesWritten.addAndGet(w / 2L)" in audio
assert "mOutputCallbacksCompleted" in driver
assert "return driver->setNativeSoftcutOutputEnabled(enabled)" in driver
assert "refusesNativeHandoffWhenJavaQueueDoesNotDrain" in tests
assert "mOutputCallbacksInFlight" in driver
assert "while (mOutputCallbacksInFlight.load(std::memory_order_acquire) != 0)" in driver
assert "OutputCallbackGuard callbackGuard" in driver
assert "const bool nativeEnabled = mNativeSoftcutOutputEnabled.load(std::memory_order_acquire)" in driver
assert "const bool masterTouched = outputTouched[masterBus] == bufferCounter" in driver
assert "SoftcutOutputMixer::mixChannel(" in driver
assert "masterTouched ? master[frame] : 0.0f" in driver
assert "softcutTouched ? softcut[frame] : 0.0f" in driver
assert "std::copy(source, source + static_cast<size_t>(count) * channels, destination);" in driver
# Routing safety is asserted behaviorally by the mixer helper and its compiled
# tests: master buses 0/1 pass independently, while buses 9/10 are gated.
assert "if (mNativeSoftcutOutputEnabled.load(std::memory_order_acquire))" not in driver
assert "NornsSC_SetSoftcutOutputEnabled" in driver and "nativeSetSoftcutOutputEnabled" in jni
assert "setSoftcutOutputEnabled" in bridge
assert "readSoftcutDiagnostics" in bridge and "nativeReadSoftcutDiagnostics" in jni
assert "readWithBacklogLimit" in driver
ring_test = (root / "tools/test-oboe-input-ring.cpp").read_text()
assert "Bursty callback cadence" in ring_test and "Producer runs 1% faster" in ring_test
buffers = (root / "app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java").read_text()
assert "verifyNativeBufferIsolation" in buffers
assert "createBufWrIsolationPattern" in buffers
assert '"/s_new", "SoftcutIsolationInput"' in buffers
assert '"/s_new", "SoftcutRecorder"' in buffers
assert "ISOLATION_PROBE_INPUT_NODE_CH1 = 2147000000" in buffers
assert "ISOLATION_PROBE_INPUT_NODE_CH2 = 2147000001" in buffers
assert "ISOLATION_PROBE_RECORDER_NODE_CH1 = 2147000002" in buffers
assert "ISOLATION_PROBE_RECORDER_NODE_CH2 = 2147000003" in buffers
assert "ISOLATION_PROBE_INPUT_BUS_CH1 = 30" in buffers
assert "ISOLATION_PROBE_INPUT_BUS_CH2 = 31" in buffers
assert '"position_bus", Integer.valueOf(70)' in buffers
assert '"position_bus", Integer.valueOf(71)' in buffers
assert '"/n_free"' in buffers and "liveBuffersPreserved=true" in buffers
assert "SoftcutIsolationInput" in synth and "Out.ar(out_bus, DC.ar(value))" in synth
host = (root / "app/src/main/java/org/nornsmobile/app/LuaHost.java").read_text()
assert "SoftcutIsolationInput.scsyndef" in host and "SoftcutRecorder.scsyndef" in host
assert '"BufWr"' in (root / "tools/validate-synthdef.py").read_text()
assert "ISOLATION_PROBE_BUFFER_CH1 = 20003" in buffers
assert "ISOLATION_PROBE_BUFFER_CH2 = 20004" in buffers
assert 'requestBufferReadback(' in buffers and 'sampleFingerprint(first.samples)' in buffers
assert 'sampleFingerprint(second.samples)' in buffers and 'liveBuffersPreserved=true' in buffers
host = (root / "app/src/main/java/org/nornsmobile/app/LuaHost.java").read_text()
assert "H_SC_SOFTCUT_BUFFER_ISOLATION_PROBE" in host and "sc_softcut_buffer_isolation_probe" in host
assert "function softcut.native_buffer_isolation_probe()" in lua
assert "case H_SC_SOFTCUT_DIAGNOSTICS" in (root / "app/src/main/java/org/nornsmobile/app/LuaHost.java").read_text()
host = (root / "app/src/main/java/org/nornsmobile/app/LuaHost.java").read_text()
assert "case H_SC_SOFTCUT_RECORDER_SAMPLE" in host and "case H_SC_SOFTCUT_RECORDER_REANCHOR" in host
buffers = (root / "app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java").read_text()
assert "recordingStartPending" in buffers and "recordingStopPending" in buffers
assert "reanchorRecordingCursor" in buffers and "cursor mantido como ativo/incerto" in buffers
assert "SoftcutRecorderCursorMonitor" in audio and "sampleNativeSoftcutRecorderCursor" in audio
recorder_tests = (root / "app/src/test/java/org/nornsmobile/app/SoftcutRecorderCursorMonitorTest.java").read_text()
for name in ("tracksContinuousRecordingAtPositiveRate", "tracksContinuousRecordingAtNegativeRateAndLoopWrap",
             "driftDuringRecordingRequestsRecoveryButNeverReanchors", "confirmedIdleAllowsCursorRecovery",
             "recoveryClearsAfterTheCursorConverges", "invalidPositionsNeverAuthorizeRecovery",
             "resetClearsAccumulatedRecordingStatistics", "writeCursorComparisonIncludesRecOffset"):
    assert name in recorder_tests
assert "softcut.native_recorder_diagnostics" in lua
assert "state.stop_java_position = positionOk and tonumber(position) or params.position" in lua
assert "javaWritePosition = javaHeadPosition" in lua and "javaHeadPosition" in lua
assert "host.sc_softcut_recorder_sample" in lua
assert "host.sc_softcut_recorder_reanchor" in lua
assert "posição do cursor BufWr ausente ou inválida" in lua
assert "cursor BufWr ausente durante recovery pendente" in lua
core_tests = (root / "app/src/test/java/org/nornsmobile/app/SoftcutCoreTest.java").read_text()
for name in ("continuousRecordingAdvancesJavaCursorAndWritesSequentialFrames",
             "reverseRecordingAdvancesCursorBackwardAndWritesInReverseOrder",
             "nativeReadbackWritesExactBufferRegionAndGrowsBuffer",
             "readbackKeepsTwoNativeMirrorBuffersIndependent"):
    assert name in core_tests
buffer_tests = (root / "app/src/test/java/org/nornsmobile/app/SuperColliderSoftcutBuffersTest.java").read_text()
assert "recorderRecoveryIsRefusedUntilNativeServerIsReady" in buffer_tests
assert "sampleNativeSoftcutPhase" in audio
java_render_gate = audio.find("if (nativeSoftcutJavaEnabled)")
java_render_block = audio[java_render_gate:java_render_gate + 420] if java_render_gate >= 0 else ""
assert java_render_gate >= 0 and "softcut.render(buf, frames, sampleRate, inputBlock, engineBlock," in java_render_block
assert "tapeBlock, softcutReverbBlock);" in java_render_block
assert "java.util.Arrays.fill(softcutReverbBlock, 0f);" in audio
assert "synchronized (outputOwnershipLock)" in audio and "t.write(buf" in audio
assert "softcut._native_output_validated = false" in lua
assert "or not softcut._native_output_validated" in lua
assert "gate.setJavaEnabled(false)" in router and "gate.setNativeEnabled(true)" in router
assert "gate.setNativeEnabled(false)" in router and "gate.setJavaEnabled(true)" in router
for name in ("mutesJavaBeforeEnablingNative", "rollbackConfirmsNativeMuteBeforeReenablingJava", "disablesNativeBeforeReenablingJava", "doesNotEnableJavaIfNativeMuteFails"):
    assert name in tests
phase_tests = (root / "app/src/test/java/org/nornsmobile/app/SoftcutPhaseDiagnosticsTest.java").read_text()
for name in ("wrapsForwardAcrossLoopEnd", "wrapsBackwardAcrossLoopStart", "remainsLinearWithoutLoop", "tracksMaxAndRmsAndIgnoresNonFiniteSamples"):
    assert name in phase_tests
supervisor_tests = (root / "app/src/test/java/org/nornsmobile/app/SoftcutPhaseSupervisorTest.java").read_text()
for name in ("advancingCallbackSequenceStaysFresh", "unchangedSequenceEventuallyBecomesStale",
             "invalidSequenceIsNeverFresh", "resetStartsANewFreshnessEpoch",
             "thresholdUsesOneScBlockOrTwoFrames", "reanchorRequiresFiniteErrorBeyondThreshold"):
    assert name in supervisor_tests
assert 'elseif command == "play" then\n    params.play = value' in lua
print("OK: dedicated stereo bus, atomic mute gate, exclusive ownership and fail-closed play gate")

# The exact allocation-free mixer used at the Oboe PCM boundary is compiled
# and tested for master passthrough, per-channel isolation, gate and clipping.
mixer = (root / "tools/SoftcutOutputMixer.h").read_text()
mixer_tests = (root / "tools/test-softcut-output-mixer.cpp").read_text()
driver = (root / "tools/SC_AndroidAudio.cpp").read_text()
patcher = (root / "tools/patch-scsynth-android.py").read_text()
assert "SoftcutOutputMixer::mixChannel" in driver
assert "if (softcutEnabled && softcutTouched) sample += softcut" in mixer
assert "SoftcutOutputMixer.h" in patcher
for name in ("Gate closed", "Gate open", "Untouched buses", "Saturation and non-finite"):
    assert name in mixer_tests
