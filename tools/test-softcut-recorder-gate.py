#!/usr/bin/env python3
"""Static contract test for the SoftcutRecorder write gate and Java lifecycle."""
from pathlib import Path

synth = Path("tools/compile-softcut-synthdef.scd").read_text()
java = Path("app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java").read_text()
driver = Path("tools/SC_AndroidAudio.cpp").read_text()
engine_bus = Path("tools/SoftcutEngineSendBus.h").read_text()

required_synth = [
    "|buf = 20001, in_bus = 2, engine_bus = 31", "engine_level = 0", "enabled = 0",
    "var engineInput = In.ar(engine_bus, 1)",
    "var incoming = scaledInput + engineInput + cutMix",
    "var recordGate = record * enabled;", "diagnostic_bus = 19",
    # Out.ar requires audio-rate channels; keep both boolean probes converted.
    "var scaledInput = physicalInput * Lag.kr(input_level, level_slew_time.max(0))",
    "Out.ar(diagnostic_bus, [physicalInput, scaledInput, cutMix, incoming,",
    "BufWr.ar(written.clip(-1, 1), buf, writePhase, 0);",
]
for token in required_synth:
    assert token in synth, f"SoftcutRecorder contract missing: {token}"
assert "BUFFER_IDS = {20001, 20002};" in java, "Java mirror must address both native buffers"
assert '"diagnostic_bus", Integer.valueOf(19 + index * 6)' in java, "each recorder must use its isolated diagnostic bus"
assert "SoftcutEngineSendBus::captureNextBlock" in driver, "native driver must capture the engine send after master mixing"
assert "constexpr int kBusIndex = 31;" in engine_bus, "engine send bus must remain reserved at 31"
assert "audioBusTouched[kBusIndex] = nextBufferCounter;" in engine_bus, "engine send must be owned by the next SC block"
assert "new float[54]" in java, "worker must read the complete 54-value native diagnostic snapshot"
assert "resetSoftcutRecorderDiagnostics(channel)" in java, "confirmed recorder start must reset per-session signal metrics"
assert 'logSignalDiagnostics(channel, "stop-confirmed")' in java, "stop must log accumulated signal metrics"
assert 'logSignalDiagnostics(channel, "start-confirmed")' not in java, "start must not report stale pre-session metrics"

start = java.find('"enabled", Integer.valueOf(1)')
record = java.find('"record", Integer.valueOf(1)', start)
assert start >= 0 and record > start, "record start must enable the BufWr gate before record=1"
start_end = java.find("if (!started)", start)
start_block = java[start:start_end]
assert start_block.count('"enabled", Integer.valueOf(1)') == 1
assert start_block.count('"record", Integer.valueOf(1)') == 1
confirmed_end = java.find("if (!confirmed)", start)
assert confirmed_end > start and "recordingActive[index] = true;" in java[start:confirmed_end], \
    "start result must remain conservatively active before handling an uncertain confirmation"

stop = java.find('"record", Integer.valueOf(0)')
disable = java.find('"enabled", Integer.valueOf(0)', stop)
assert stop >= 0 and disable > stop, "record stop must close record and enabled gates"
uncertain = java.find("if (!confirmed)")
assert '"enabled", Integer.valueOf(0)' in java[uncertain:uncertain + 900], "unconfirmed start must close enabled gate"

assert 'READBACK_WORKER' not in java, "readback must not have a competing OSC poller"
print("OK: SoftcutRecorder enabled/record gate and serialized readback lifecycle")
