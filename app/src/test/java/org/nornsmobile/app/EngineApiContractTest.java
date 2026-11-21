package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class EngineApiContractTest {
    @Test public void exposesNornsEngineRegistrationMetadataFunctions() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function engine.register(data, count)"));
        assertTrue(boot.contains("function engine.register_commands(data, count)"));
        assertTrue(boot.contains("function engine.add_command(id, name, fmt)"));
        assertTrue(boot.contains("function engine.list_commands()"));
        assertTrue(boot.contains("function engine.load(name, cb)"));
        assertTrue(boot.contains("if engine._native_state == \"loading\" then return false end"));
    }

    @Test public void fmEngineIsExposedAsJavaFallbackWithCommonControls() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("engine.name == \"FM\""));
        assertTrue(boot.contains("engine.name ~= \"FM\""));
        assertTrue(boot.contains("return {\"hz\", \"amp\", \"ratio\", \"index\", \"release\", \"pan\"}"));
        assertTrue(boot.contains("engine.name == \"Pluck\""));
        assertTrue(boot.contains("return {\"hz\", \"amp\", \"decay\", \"damp\", \"pan\"}"));
        assertTrue(read("app/src/main/java/org/nornsmobile/app/SynthCore.java").contains("static final int ENGINE_PLUCK = 4"));
        assertTrue(boot.contains("engine._native_state = \"java\""));
        String synth = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        assertTrue(synth.contains("static final int ENGINE_FM = 3"));
        assertTrue(synth.contains("private void triggerFm(double hz)"));
    }

    @Test public void engineLoadCallbackReceivesCommandList() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("pcall(callback, engine.list_commands())"));
        assertTrue(boot.contains("engine._registered_commands[i]"));
    }

    @Test public void unknownEnginesRemainUnavailableEvenIfRegisteredByName() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("engine.name ~= \"PolyPerc\" and engine.name ~= \"TestSine\""));
        assertTrue(boot.contains("and engine.name ~= \"32Harmony\""));
        assertTrue(boot.contains("engine._native_state = \"unavailable\""));
        assertTrue(boot.contains("host.engine_load(\"none\")"));
    }

    @Test public void bundled32HarmonyEngineUsesPrecompiledSynthDefAndNativeCommandBridge() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String compiler = read("tools/compile-softcut-synthdef.scd");
        assertTrue(boot.contains("engine.name == \"32Harmony\" then synthdef = \"Norns32Harmony.scsyndef\""));
        assertTrue(boot.contains("sc_api.new(\"Norns32Harmony\""));
        assertTrue(boot.contains("if not sc_api.set(engine._native_node, {[name] = value}) then"));
        assertTrue(compiler.contains("SynthDef(\\Norns32Harmony"));
        String validator = read("tools/validate-synthdef.py");
        assertTrue(validator.contains("elif name == \"Norns32Harmony\":"));
        assertTrue(validator.contains("expected 32 "));
        assertTrue(boot.contains("return {\"rate1In\", \"rate2In\", \"rootIn\""));
    }

    @Test public void simpleDelayKolorPortsTwelveSafeSampleBanksAndAllPlaybackControls() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String compiler = read("tools/compile-softcut-synthdef.scd");
        String validator = read("tools/validate-synthdef.py");
        assertTrue(boot.contains("engine.name == \"SimpleDelayKolor\""));
        assertTrue(boot.contains("engine.name ~= \"SimpleDelayKolor\""));
        assertTrue(boot.contains("engine_kolor_sample(args[1], args[2])"));
        assertTrue(boot.contains("engine_kolor_play(args)"));
        assertTrue(boot.contains("host.resolve_sample_path(path_value)"));
        assertTrue(boot.contains("sc_api.send(\"/b_allocRead\""));
        assertTrue(boot.contains("engine_kolor_poll_load"));
        assertTrue(boot.contains("local function engine_poll_kolor_native"));
        assertTrue(boot.contains("local reply_sync_id = tonumber(reply.sync_id)"));
        assertTrue(boot.contains("reply_sync_id == engine._kolor_phase_sync_id"));
        assertTrue(boot.contains("local sync_id = sc_api.sync()"));
        assertTrue(boot.contains("30000 + request.slot"));
        assertTrue(boot.contains("return {\"delay\", \"volume\", \"kolorsample\", \"kolorplay\"}"));
        assertTrue(host.contains("H_RESOLVE_SAMPLE_PATH = 66"));
        assertTrue(host.contains("dust.resolve(a.checkjstring(1))"));
        assertTrue(host.contains("f.length() > 100L * 1024 * 1024"));
        assertTrue(host.contains("\"wav\".equals(Dust.extension(f.getName()))"));
        assertTrue(host.contains("f.getCanonicalPath()"));
        assertTrue(compiler.contains("SynthDef(\\NornsKolorDelay"));
        assertTrue(compiler.contains("SynthDef(\\NornsKolorPlayer"));
        assertTrue(compiler.contains("BufRd.ar(2, sampleBufnum"));
        assertTrue(compiler.contains("MoogFF.ar(bufsnd, lpf, resonance)"));
        assertTrue(compiler.contains("CombN.ar(dry"));
        assertTrue(validator.contains("name == \"NornsKolorDelay\""));
        assertTrue(validator.contains("name == \"NornsKolorPlayer\""));
        String[] controls = {
                "ampMin", "ampMax", "ampLFOMin", "ampLFOMax",
                "rateMin", "rateMax", "rateLFOMin", "rateLFOMax",
                "panMin", "panMax", "panLFOMin", "panLFOMax",
                "lpfMin", "lpfMax", "lpfLFOMin", "lpfLFOMax",
                "resonanceMin", "resonanceMax", "resonanceLFOMin", "resonanceLFOMax",
                "hpfMin", "hpfMax", "hpfLFOMin", "hpfLFOMax",
                "sampleStartMin", "sampleStartMax", "sampleStartLFOMin", "sampleStartLFOMax",
                "sampleEndMin", "sampleEndMax", "sampleEndLFOMin", "sampleEndLFOMax",
                "retrigMin", "retrigMax", "retrigLFOMin", "retrigLFOMax",
                "delaySendMin", "delaySendMax", "delaySendLFOMin", "delaySendLFOMax",
                "delayFeedbackMin", "delayFeedbackMax", "delayFeedbackLFOMin", "delayFeedbackLFOMax"
        };
        for (String control : controls) {
            assertTrue("Lua playback mapping missing " + control,
                    boot.contains("\"" + control + "\""));
            assertTrue("SynthDef control missing " + control, compiler.contains(control));
        }
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) file = new File(".." + File.separator + path);
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
@Test public void fmAndPluckExposeJavaFallbackCommands() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        assertTrue(boot.contains("engine.name == \"FM\""));
        assertTrue(boot.contains("engine.name == \"Pluck\""));
        assertTrue(boot.contains("elseif engine.name == \"FM\" or engine.name == \"Pluck\" or engine.name == \"SimpleDelay\" then"));
        assertTrue(boot.contains("return host.engine_cmd(name, ...)"));
        assertTrue(core.contains("ENGINE_FM"));
        assertTrue(core.contains("ENGINE_PLUCK"));
        assertTrue(core.contains("triggerFm(a)"));
        assertTrue(core.contains("triggerPluck(a)"));
    }

@Test public void engineLoadCallbacksAlwaysReceiveCommandListAndAreProtected() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int none = boot.indexOf("if engine.name == \"none\" or engine.name == \"None\" then");
        int nativeStart = boot.indexOf("if not sc_api.is_running() or not sc_api.load_synthdef(synthdef) then");
        assertTrue(none >= 0 && nativeStart > none);
        String noneBranch = boot.substring(none, boot.indexOf("return false", none));
        String fallbackBranch = boot.substring(nativeStart, boot.indexOf("return false", nativeStart));
        assertTrue(noneBranch.contains("pcall(callback, engine.list_commands())"));
        assertTrue(fallbackBranch.contains("pcall(callback, engine.list_commands())"));
        assertFalse(noneBranch.contains("callback(true)"));
        assertFalse(fallbackBranch.contains("callback()"));
    }

@Test public void harmony32HasJavaFallbackWhenNativeSynthDefCannotLoad() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        assertTrue(boot.contains("elseif engine.name == \"32Harmony\" then"));
        assertTrue(boot.contains("elseif engine._native_state == \"native\" then"));
        assertTrue(boot.contains("return host.engine_cmd(name, ...)"));
        assertTrue(core.contains("ENGINE_32HARMONY"));
        assertTrue(core.contains("if (name.equals(\"rate1In\"))"));
        assertTrue(core.contains("if (name.equals(\"rate2In\"))"));
        assertTrue(core.contains("if (name.equals(\"saturationAmtIn\"))"));
    }

@Test public void harmonyJavaFallbackUsesSmoothedTwoPoleFilters() throws Exception {
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        assertTrue(core.contains("private static final class Biquad"));
        assertTrue(core.contains("hRootSmooth += (hRoot - hRootSmooth) * controlSlew"));
        assertTrue(core.contains("hLpLeft.setLowPass(hLpfSmooth, sr)"));
        assertTrue(core.contains("hHpLeft.setHighPass(hHpf, sr)"));
        assertTrue(core.contains("Math.min(20000.0, sampleRate * 0.45)"));
        assertTrue(core.contains("double engineMaster = engine == ENGINE_32HARMONY ? 1.0 : master"));
    }

@Test public void nativeEngineRuntimeFailureRestoresJavaEngineAndCachedControls() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int fallback = boot.indexOf("local function engine_fallback()");
        int reselect = boot.indexOf("host.engine_load(engine.name)", fallback);
        int harmonyRestore = boot.indexOf("engine._native_params.harmony", reselect);
        int pendingReplay = boot.indexOf("for i = 1, #pending do", harmonyRestore);
        assertTrue(fallback >= 0 && reselect > fallback);
        assertTrue("32Harmony controls must survive native-to-Java fallback", harmonyRestore > reselect);
        assertTrue(pendingReplay > harmonyRestore);
        assertTrue(boot.contains("if not sc_api.set(engine._native_node, {[name] = value}) then"));
        assertTrue(boot.contains("engine._native_node = nil"));
    }

@Test public void formattersLibraryIsLoadedAsNornsGlobal() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String formatters = read("app/src/main/assets/lua/lib/formatters.lua");
        assertTrue(boot.contains("formatters = require 'formatters'"));
        assertTrue(formatters.contains("function Formatters.format_freq_raw(freq)"));
        assertTrue(formatters.contains("function Formatters.unipolar_as_multimode_filter_freq(param)"));
        assertTrue(formatters.contains("function Formatters.round(precision)"));
    }

@Test public void synthCoreSourceContainsNoEscapedLineBreakArtifacts() throws Exception {
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        assertFalse(core.contains("\\n"));
        assertTrue(core.contains("static final int ENGINE_32HARMONY = 5;"));
    }

@Test public void harmonyNativeSynthDefLuaApiAndJavaFallbackShareAllCommandNames() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String compiler = read("tools/compile-softcut-synthdef.scd");
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        String[] commands = {
                "rate1In", "rate2In", "rootIn", "range1In", "range2In",
                "attackIn", "decayIn", "lpfCutoffIn", "hpfCutoffIn", "quantAmtIn",
                "step0In", "step1In", "step2In", "step3In", "step4In",
                "freqMultIn", "saturationAmtIn"
        };
        assertTrue("native SynthDef must be compiled from source",
                compiler.contains("SynthDef(\\Norns32Harmony"));
        assertTrue("Lua must expose the 32Harmony engine",
                boot.contains("elseif engine.name == \"32Harmony\" then"));
        for (String command : commands) {
            assertTrue("Lua command registry missing " + command,
                    boot.contains(command + "=true") || boot.contains(command + " = true"));
            assertTrue("native SynthDef missing " + command, compiler.contains(command));
            assertTrue("Java fallback missing " + command,
                    core.contains("name.equals(\"" + command + "\")"));
        }
    }

@Test public void tapeTransportAndIndependentSoftcutSendAreExposed() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String audio = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String tape = read("app/src/main/java/org/nornsmobile/app/TapeCore.java");
        String reader = read("app/src/main/java/org/nornsmobile/app/WavReader.java");
        String softcut = read("app/src/main/java/org/nornsmobile/app/SoftcutCore.java");
        String[] api = {
                "tape_play_open", "tape_play_start", "tape_play_pause", "tape_play_stop",
                "tape_play_loop", "tape_record_open", "tape_record_start",
                "tape_record_pause", "tape_record_stop", "level_tape", "level_tape_cut"
        };
        for (String name : api) {
            assertTrue("Lua Tape API missing " + name, boot.contains(name + " = function"));
            String hostName = "level_tape".equals(name) ? "tape_level"
                    : ("level_tape_cut".equals(name) ? "tape_level_cut" : name);
            assertTrue("LuaHost Tape binding missing " + hostName, host.contains("\"" + hostName + "\""));
        }
        assertTrue(tape.contains("norns-tape-writer"));
        assertTrue(tape.contains("ArrayBlockingQueue<TapeBlock>"));
        assertTrue(host.contains("WavReader.mapStereo(source)"));
        assertTrue(reader.contains("static MappedStereoData mapStereo(File file)"));
        assertTrue(tape.contains("mapped.sample(a, 0)"));
        assertTrue(audio.contains("currentTape.render(buf, frames, tapeBlock)"));
        assertTrue(audio.contains("currentTape.recordBlock(buf, frames)"));
        int tapeMix = audio.indexOf("currentTape.render(buf, frames, tapeBlock)");
        int softcutMix = audio.indexOf("softcut.render(buf, frames, sampleRate, inputBlock, engineBlock,\n                                tapeBlock, softcutReverbBlock)");
        int tapeRecord = audio.indexOf("currentTape.recordBlock(buf, frames)");
        int outputWrite = audio.indexOf("t.write(buf", tapeRecord);
        assertTrue("Tape playback must enter the master before Softcut", tapeMix < softcutMix);
        assertTrue("Tape recording must capture the completed master after Softcut", softcutMix < tapeRecord);
        assertTrue("Tape recording must precede the AudioTrack write", tapeRecord < outputWrite);
        assertTrue(softcut.contains("incoming += tapeInput[i] * tapeLevel"));
        assertFalse(boot.contains("barramento tape->softcut ainda não implementado"));
    }

@Test public void harmonyNativeControlsUseTheSameBoundsAsJavaFallback() throws Exception {
        String compiler = read("tools/compile-softcut-synthdef.scd");
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        String[] nativeBounds = {
                "rootIn.clip(1, 20000)", "freqMultIn.clip(0.01, 32)",
                "step0In.clip(0.01, 32)", "step1In.clip(0.01, 32)",
                "step2In.clip(0.01, 32)", "step3In.clip(0.01, 32)",
                "step4In.clip(0.01, 32)", "attackIn.clip(0.001, 20)",
                "decayIn.clip(0.001, 30)"
        };
        String[] javaBounds = {
                "hRoot = clamp(a, 1, 20000)", "hFreqMult = clamp(a, 0.01, 32)",
                "hSteps[0] = clamp(a, 0.01, 32)", "hSteps[1] = clamp(a, 0.01, 32)",
                "hSteps[2] = clamp(a, 0.01, 32)", "hSteps[3] = clamp(a, 0.01, 32)",
                "hSteps[4] = clamp(a, 0.01, 32)", "hAttack = clamp(a, 0.001, 20)",
                "hDecay = clamp(a, 0.001, 30)"
        };
        for (int i = 0; i < nativeBounds.length; i++) {
            assertTrue("Native 32Harmony bound missing: " + nativeBounds[i],
                    compiler.contains(nativeBounds[i]));
            assertTrue("Java 32Harmony bound missing: " + javaBounds[i],
                    core.contains(javaBounds[i]));
        }
    }

@Test public void usbSerialTermiosMapsBaudAndFraming() {
        assertEquals(115200, UsbCdcSerialBridge.baudFromTermios(4098));
        assertEquals(4000000, UsbCdcSerialBridge.baudFromTermios(4111));
        assertEquals(-1, UsbCdcSerialBridge.baudFromTermios(4000001));

        UsbCdcSerialBridge.LineCoding eightNOne =
                UsbCdcSerialBridge.lineCodingFromTermios(115200, 0x30 | 0x80 | 0x800);
        assertNotNull(eightNOne);
        assertEquals(8, eightNOne.dataBits);
        assertEquals(0, eightNOne.stopBits);
        assertEquals(0, eightNOne.parity);

        UsbCdcSerialBridge.LineCoding sevenEvenTwo =
                UsbCdcSerialBridge.lineCodingFromTermios(9600, 0x20 | 0x40 | 0x100);
        assertNotNull(sevenEvenTwo);
        assertEquals(7, sevenEvenTwo.dataBits);
        assertEquals(2, sevenEvenTwo.stopBits);
        assertEquals(2, sevenEvenTwo.parity);
        assertNull(UsbCdcSerialBridge.lineCodingFromTermios(0, 0x30));
    }

@Test public void usbSerialBackendDeclaresItsSupportedScopeAndTermiosMapping() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(bridge.contains("Only standard CDC devices"));
        assertTrue(bridge.contains("findDataInterface"));
        assertTrue(bridge.contains("USB_ENDPOINT_XFER_BULK"));
        assertTrue(bridge.contains("SET_LINE_CODING"));
        assertTrue(bridge.contains("case 4098:return 115200"));
        assertTrue(bridge.contains("case 4111:return 4000000"));
        assertTrue(boot.contains("standard CDC-ACM devices"));
        assertTrue(boot.contains("FTDI/CP210x/CH340"));
        assertTrue(boot.contains("serial.add_handler"));
        assertTrue(boot.contains("norns.crow.usb"));
    }

@Test public void hidSurfaceMatchesNornsReadOnlyContract() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertFalse(boot.contains("hid.send"));
        assertFalse(boot.contains("unsupported_api"));
        assertTrue(boot.contains("connect = function(n)"));
    }

@Test public void metroRejectsInvalidTimingAndProtectsCallbacks() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("function Metro:start(time, count, stage)");
        int stop = boot.indexOf("function Metro:stop()", start);
        assertTrue(start >= 0 && stop > start);
        String implementation = boot.substring(start, stop);
        assertTrue(implementation.contains("if self.freed then return false end"));
        assertTrue(boot.indexOf("local function finite_number(value, fallback)")
                < boot.indexOf("function Metro:start(time, count, stage)"));
        assertTrue(implementation.contains("nextTime = finite_number(time, nil)"));
        assertTrue(implementation.contains("math.max(0.001, math.min(86400, nextTime))"));
        assertTrue(implementation.contains("math.floor(nextCount)"));
        assertTrue(implementation.contains("return true"));
        assertTrue(boot.contains("if m.event then safe(m.event, stage) end"));
        assertTrue(boot.contains("local function sched_remove(entry)"));
        assertTrue(boot.contains("sched_remove(self.scheduled_event)"));
        assertTrue(boot.contains("m.scheduled_event = entry"));
        assertTrue(boot.contains("if m.scheduled_event == entry then m.scheduled_event = nil end"));
        assertFalse(boot.contains("if m.event == entry then m.event = nil end"));
    }

@Test public void clockRejectsNonFiniteAndUnboundedSchedulerInputs() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local function finite_number(value, fallback)"));
        assertTrue(boot.contains("value ~= value or value == math.huge or value == -math.huge"));
        assertTrue(boot.contains("bpm = util.clamp(finite_number(bpm, tempo), 1, 300)"));
        assertTrue(boot.contains("s = math.max(0, math.min(86400, s))"));
        assertTrue(boot.contains("beat = math.min(1000000, beat)"));
        assertTrue(boot.contains("offset = math.max(-1000000, math.min(1000000, offset))"));
    }

@Test public void engineRegistrationRejectsMalformedCountsAndEntriesAtomically() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local function engine_registration_count(data, count)"));
        assertTrue(boot.contains("limit ~= math.floor(limit)"));
        assertTrue(boot.contains("limit > #data"));
        assertTrue(boot.contains("if type(name) ~= \"string\" or name == \"\" then return false end"));
        assertTrue(boot.contains("or id > 4095"));
        assertTrue(boot.contains("type(format) ~= \"string\""));
        int register = boot.indexOf("function engine.register_commands(data, count)");
        int commit = boot.indexOf("engine._registered_commands = commands", register);
        assertTrue("command metadata is published only after the full list validates",
                register >= 0 && commit > register);
    }
    @Test public void norns32HarmonySynthDefClampsUntrustedControlRanges() throws Exception {
        String compiler = read("tools/compile-softcut-synthdef.scd");
        int start = compiler.indexOf("SynthDef(\\Norns32Harmony");
        int end = compiler.indexOf("}).writeDefFile", start);
        assertTrue(start >= 0 && end > start);
        String harmony = compiler.substring(start, end);
        assertTrue(harmony.contains("step0In.clip(0.01, 32)"));
        assertTrue(harmony.contains("rootIn.clip(1, 20000)"));
        assertTrue(harmony.contains("freqMultIn.clip(0.01, 32)"));
        assertTrue(harmony.contains("attackIn.clip(0.001, 20)"));
        assertTrue(harmony.contains("decayIn.clip(0.001, 30)"));
        assertTrue(harmony.contains("randomStep + ((quantizedStep - randomStep) * quantAmtIn.clip(0, 1))"));
        assertFalse(harmony.contains("SelectX.ar"));
    }
    @Test public void simpleDelayIsExposedAsJavaOnsetEngineAndDispatchesNornsOscEvent() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        String audio = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(boot.contains("engine.name ~= \"SimpleDelay\""));
        assertTrue(boot.contains("engine.name == \"SimpleDelay\""));
        assertTrue(boot.contains("return {\"threshold\"}"));
        assertTrue(boot.contains("engine._native_state = \"java\""));
        assertTrue(core.contains("static final int ENGINE_SIMPLE_DELAY = 6"));
        assertTrue(core.contains("name.equals(\"threshold\")"));
        assertTrue(audio.contains("simpleOnsetDetector.process(inputBlock, frames, sampleRate"));
        assertTrue(host.contains("dispatchOsc(\"onset\", new Object[]{1, timeSeconds}, \"127.0.0.1\", 10111)"));
    }

}
