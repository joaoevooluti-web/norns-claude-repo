package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class AudioRoutingApiContractTest {
    // [run-ci] Final branch validation: Tape, Java engine/Softcut gains, Faust DSP, direct JNI buffers and finite output guard.
    @Test public void adcToSoftcutIsImplementedButMissingBusesAreExplicitlyRejected() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("level_adc_cut = function(level)"));
        assertTrue(boot.contains("host.softcut(0, \"adc_level\", level)"));
        assertTrue(boot.contains("level_eng_cut = function(level)"));
        assertTrue(boot.contains("level_tape_cut = function(level)"));
        assertTrue(boot.contains("level_eng_cut = function(level)"));
        assertTrue(boot.contains("level_tape_cut = function(level)"));
        assertFalse(boot.contains("barramento engine->softcut ainda não implementado"));
        assertFalse(boot.contains("barramento tape->softcut ainda não implementado"));
    }

    @Test public void adcInputLevelControlsCapturedInputBeforeAnalysisAndRouting() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(boot.contains("level_adc = function(level)"));
        assertTrue(boot.contains("host.audio_level_adc(math.max(0, math.min(1, level)))"));
        assertTrue(boot.contains("controle da entrada ADC nativa ainda indisponível"));
        assertTrue(host.contains("H_AUDIO_LEVEL_ADC = 72"));
        assertTrue(host.contains("audio_level_adc"));
        assertTrue(host.contains("audio.setInputAdcLevel(a.optdouble(1, 1))"));
        assertTrue(engine.contains("AudioInputGain.apply(inputBlock, inputAdcLevel)"));
        assertTrue(engine.contains("boolean setInputAdcLevel(double level)"));
        assertTrue(engine.indexOf("AudioInputGain.apply(inputBlock, inputAdcLevel)")
                < engine.indexOf("simpleOnsetDetector.process(inputBlock"));
    }

    @Test public void engineLevelIsAppliedBeforeTheEngineSendAndRejectedForNativeScsynth() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(boot.contains("level_eng = function(level)"));
        assertTrue(boot.contains("host.audio_level_eng(math.max(0, math.min(1, level)))"));
        assertTrue(boot.contains("ganho do engine nativo ainda indisponível"));
        assertTrue(host.contains("H_AUDIO_LEVEL_ENG = 77"));
        assertTrue(host.contains("\"audio_level_eng\""));
        assertTrue(host.contains("audio.setEngineLevel(a.optdouble(1, 1))"));
        assertTrue(engine.indexOf("AudioEngineGain.apply(buf, engineOutputLevel)")
                < engine.indexOf("engineBlock[i] = (buf[2 * i] + buf[2 * i + 1]) * 0.5f"));
    }

    @Test public void softcutMasterLevelHasAnImplementedJavaOutputGate() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String audio = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String softcut = read("app/src/main/java/org/nornsmobile/app/SoftcutCore.java");
        assertTrue(boot.contains("level_cut = function(level)"));
        assertTrue(boot.contains("host.audio_level_cut(math.max(0, math.min(1, level)))"));
        assertTrue(host.contains("H_AUDIO_LEVEL_CUT = 78"));
        assertTrue(host.contains("\"audio_level_cut\""));
        assertTrue(host.contains("audio.setSoftcutMasterLevel(a.optdouble(1, 1))"));
        assertTrue(audio.contains("!nativeSoftcutJavaEnabled"));
        assertTrue(softcut.contains("cutBus[currentVoice][i] = routedSample"));
        assertTrue(softcut.contains("float outputSample = routedSample * outputGain"));
    }

    @Test public void tapePauseResumeAndLoopMatchUpstreamCombinedControls() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("tape_pause = function()"));
        assertTrue(boot.contains("local playback = host.tape_play_pause(true)"));
        assertTrue(boot.contains("local recording = host.tape_record_pause(true)"));
        assertTrue(boot.contains("tape_resume = function()"));
        assertTrue(boot.contains("local playback = host.tape_play_pause(false)"));
        assertTrue(boot.contains("local recording = host.tape_record_pause(false)"));
        assertTrue(boot.contains("tape_loop = function(enabled) return host.tape_play_loop(sc_flag(enabled)) end"));
    }

    @Test public void monitorLevelApiUsesTheJavaMonoAdcMixAndRejectsNativeMode() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(boot.contains("level_monitor = function(level)"));
        assertTrue(boot.contains("host.audio_level_monitor(math.max(0, math.min(1, level)))"));
        assertTrue(boot.contains("audio.level_monitor: monitor ADC nativo ainda indisponível."));
        assertTrue(host.contains("H_AUDIO_LEVEL_MONITOR = 71"));
        assertTrue(host.contains("audio_level_monitor"));
        assertTrue(host.contains("audio.setMonitorLevel(a.optdouble(1, 1))"));
        assertTrue(engine.contains("AudioMonitorMix.apply(inputBlock, buf, monitorLevel)"));
        assertTrue(engine.contains("boolean setMonitorLevel(double level)"));
    }

    @Test public void monitorModeExposesMonoAndRejectsStereoWithoutStereoAdc() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(boot.contains("monitor_mono = function()"));
        assertTrue(boot.contains("return host.audio_monitor_mode(false)"));
        assertTrue(boot.contains("monitor_stereo = function()"));
        assertTrue(boot.contains("return host.audio_monitor_mode(true)"));
        assertTrue(host.contains("H_AUDIO_MONITOR_MODE = 73"));
        assertTrue(host.contains("audio_level_adc\", \"audio_monitor_mode"));
        assertTrue(host.contains("audio.setMonitorStereo("));
        assertTrue(engine.contains("boolean setMonitorStereo(boolean stereo)"));
        assertTrue(engine.contains("if (stereo)"));
        assertTrue(engine.contains("captura Android atual fornece um único canal ADC"));
    }

    @Test public void pitchAnalysisApiIsOptInAndDisablesTheEstimator() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String metrics = read("app/src/main/java/org/nornsmobile/app/AudioPollMetrics.java");
        assertTrue(boot.contains("pitch_on = function() return host.audio_pitch_on() end"));
        assertTrue(boot.contains("pitch_off = function() return host.audio_pitch_off() end"));
        assertTrue(host.contains("H_AUDIO_PITCH_ON = 69"));
        assertTrue(host.contains("H_AUDIO_PITCH_OFF = 70"));
        assertTrue(host.contains("audio.setPitchAnalysisEnabled(true)"));
        assertTrue(host.contains("audio.setPitchAnalysisEnabled(false)"));
        assertTrue(engine.contains("pollMetrics.setPitchAnalysisEnabled(enabled)"));
        assertTrue(metrics.contains("pitchAnalysisEnabled ? estimatePitch() : -1"));
        assertTrue(metrics.contains("if (pitchAnalysisEnabled)"));
    }

    @Test public void audioVuUsesCroneTaperAndScriptCallbackOnLuaExecutor() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String meter = read("app/src/main/java/org/nornsmobile/app/AudioVuMeter.java");
        assertTrue(boot.contains("vu = noop"));
        assertTrue(host.contains("setVuListener(new AudioEngine.VuListener()"));
        assertTrue(host.contains("dispatchAudioVu(inputLeft, inputRight, outputLeft, outputRight)"));
        assertTrue(host.contains("LuaValue callback = audioTable.get(\"vu\")"));
        assertTrue(host.contains("callback.invoke(LuaValue.varargsOf"));
        assertTrue(host.contains("\"native\".equals(engineState.tojstring())"));
        assertTrue(engine.contains("private final AudioVuMeter vuMeter = new AudioVuMeter()"));
        assertTrue(engine.contains("vuMeter.update(inputBlock, buf, frames, sampleRate)"));
        assertTrue(engine.contains("vuMeter.reset()"));
        assertTrue(engine.contains("50_000_000L"));
        assertTrue(meter.contains("AMP_POSITION"));
        assertTrue(meter.contains("RELEASE_SECONDS = 0.3"));
        assertTrue(meter.contains("return 64"));
    }

    @Test public void reverbAndCompressorUseUpstreamFaustDspOnJavaMixerOnly() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String core = read("app/src/main/cpp/NornsFaustEffectsCore.h");
        String jni = read("app/src/main/cpp/norns_faust_fx_jni.cpp");
        String facade = read("app/src/main/java/org/nornsmobile/app/NornsFaustEffects.java");
        String cmake = read("app/src/main/cpp/CMakeLists.txt");
        String compressor = read("app/src/main/cpp/faust/effects/StereoCompressor.h");
        String reverb = read("app/src/main/cpp/faust/effects/ZitaReverb.h");
        assertTrue(boot.contains("rev_on = function()"));
        assertTrue(boot.contains("rev_param = function(name, value)"));
        assertTrue(boot.contains("level_rev_dac = function(level)"));
        assertTrue(boot.contains("level_monitor_rev = function(level)"));
        assertTrue(boot.contains("level_eng_rev = function(level)"));
        assertTrue(boot.contains("level_tape_rev = function(level)"));
        assertTrue(boot.contains("host.audio_fx(\"send\", \"reverb\", \"monitor\""));
        assertTrue(boot.contains("host.audio_fx(\"send\", \"reverb\", \"engine\""));
        assertTrue(boot.contains("host.audio_fx(\"send\", \"reverb\", \"tape\""));
        assertTrue(boot.contains("comp_on = function()"));
        assertTrue(boot.contains("comp_mix = function(level)"));
        assertTrue(boot.contains("comp_param = function(name, value)"));
        assertTrue(boot.contains("host.audio_fx("));
        assertTrue(boot.contains("DSP de efeitos ainda não está roteado no mixer nativo."));
        assertTrue(host.contains("H_AUDIO_FX = 76"));
        assertTrue(host.contains("audio_fx"));
        assertTrue(host.contains("case H_AUDIO_FX:"));
        assertTrue(host.contains("isNativeAudioEngineActive()"));
        assertTrue(engine.contains("NornsFaustEffects.create(sampleRate, frames)"));
        assertTrue(engine.contains("NornsFaustEffects.process(fxHandle, fxMasterBytes,\n                                fxMonitorBytes, fxEngineBytes, fxTapeBytes,\n                                fxSoftcutBytes, frames)"));
        assertTrue(engine.contains("CMD_FX_PARAMETER"));
        assertTrue(core.contains("StereoCompressor_dsp compressor_"));
        assertTrue(core.contains("ZitaReverb_dsp reverb_"));
        assertTrue(core.contains("compressor_.compute(frames, inputs, outputs)"));
        assertTrue(core.contains("reverb_.compute(frames, inputs, outputs)"));
        assertTrue(core.contains("setReverbSendLevel(int source, float value)"));
        assertTrue(core.contains("monitor * reverbSendLevels_[0]"));
        assertTrue(core.contains("engine * reverbSendLevels_[1]"));
        assertTrue(core.contains("tape * reverbSendLevels_[2]"));
        assertTrue(core.contains("softcutLeft * reverbSendLevels_[3]"));
        assertTrue(core.contains("softcutRight * reverbSendLevels_[3]"));
        assertTrue(jni.contains("nativeSetReverbSendLevel"));
        assertTrue(facade.contains("setReverbSendLevel(long handle, int source, double value)"));
        assertTrue(facade.contains("\"softcut\".equals(source)"));
        assertTrue(facade.contains("source < 4"));
        assertTrue(engine.contains("fxMonitorBytes"));
        assertTrue(engine.contains("fxEngineBytes"));
        assertTrue(engine.contains("fxTapeBytes"));
        assertTrue(engine.contains("softcutReverbBlock"));
        assertTrue(engine.contains("fxSoftcutBytes"));
        assertTrue(engine.contains("CMD_FX_SEND"));
        assertTrue(host.contains("audio.setReverbSendLevel(source, a.optdouble(4, Double.NaN))"));
        assertTrue(jni.contains("nativeProcess"));
        assertTrue(jni.contains("GetDirectBufferAddress"));
        assertTrue(jni.contains("GetDirectBufferCapacity"));
        assertTrue(jni.contains("env->GetDirectBufferCapacity(softcut) < masterBytes"));
        assertTrue(facade.contains("ByteBuffer master"));
        assertTrue(facade.contains("nativeSetParameter(long handle, int effect, int parameter, float value)"));
        assertTrue(engine.contains("ByteBuffer.allocateDirect(frames * 2 * Float.BYTES)"));
        assertTrue(engine.contains("if (!Float.isFinite(buf[i])) buf[i] = 0f;"));
        assertTrue(cmake.contains("norns_faust_fx_jni.cpp"));
        assertTrue(compressor.contains("Code generated with Faust 2.7.4"));
        assertTrue(reverb.contains("Code generated with Faust 2.7.4"));
    }

    @Test public void remainingUpstreamAudioHelpersArePresentAndPlatformRoutingFailsClosed() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("reserve_s = tonumber(reserve_s) or 250"));
        assertTrue(boot.contains("audio.headphone_gain: ganho físico de fone não está disponível pelo backend Android."));
        assertTrue(boot.contains("audio.restart: o ciclo de vida de áudio Android não permite reiniciar sclang/scsynth por esta API."));
        assertTrue(boot.contains("level_cut_rev = function(level)"));
        assertTrue(boot.contains("host.audio_fx(\"send\", \"reverb\", \"softcut\""));
        assertTrue(boot.contains("envio Softcut para reverb indisponível fora da saída Java confirmada."));
        assertTrue(boot.contains("default_softcut_routes = {}"));
        assertTrue(boot.contains("default_supercollider_routes = {}"));
        assertTrue(boot.contains("default_system_routes = {}"));
        assertTrue(boot.contains("input_ports = function() return {} end"));
        assertTrue(boot.contains("output_ports = function() return {} end"));
        assertTrue(boot.contains("port_connections = function() return {} end"));
        assertTrue(boot.contains("routing_disconnect_all = function() return audio_route_unavailable"));
        assertTrue(boot.contains("routing_default = function() return audio_route_unavailable"));
        assertTrue(boot.contains("with_change_tracking_disabled = function(fn)"));
        assertTrue(boot.contains("set_audio_level = function(value)"));
        assertTrue(boot.contains("adjust_output_level = function(delta)"));
        assertTrue(boot.contains("add_params = function()"));
    }

    @Test public void tapeStateApiUsesSnapshotsAndSerializedSubscriberEvents() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String tape = read("app/src/main/java/org/nornsmobile/app/TapeCore.java");
        assertTrue(boot.contains("audio.tape.get_state()"));
        assertTrue(boot.contains("audio.tape.subscribe(key, fn)"));
        assertTrue(boot.contains("audio.tape.unsubscribe(key)"));
        assertTrue(boot.contains("local initial_tape_state = host.tape_state()"));
        assertTrue(boot.contains("play = {"));
        assertTrue(boot.contains("rec = {"));
        assertTrue(boot.contains("function audio.tape_is_recording()"));
        assertTrue(boot.contains("TAPE_PLAY_STATE_READY = 1"));
        assertTrue(boot.contains("TAPE_PLAY_STATE_PLAYING = 2"));
        assertTrue(boot.contains("TAPE_PLAY_STATE_PAUSED = 3"));
        assertTrue(boot.contains("TAPE_REC_STATE_READY = 1"));
        assertTrue(boot.contains("TAPE_REC_STATE_RECORDING = 2"));
        assertTrue(boot.contains("TAPE_REC_STATE_PAUSED = 3"));
        assertTrue(boot.contains("function audio.tape._on_play_file(path)"));
        assertTrue(boot.contains("function audio.tape._on_rec_file(path)"));
        assertTrue(boot.contains("pcall(fn, tape_state_copy(audio.tape._state))"));
        assertTrue(host.contains("H_TAPE_STATE = 74"));
        assertTrue(host.contains("H_DISK_FREE_BYTES = 75"));
        assertTrue(host.contains("dust.root.getUsableSpace()"));
        assertTrue(boot.contains("tape_compute_diskfree = function(reserve_s)"));
        assertTrue(boot.contains("local bytes_per_second = 48000 * 2 * 2"));
        assertTrue(host.contains("case H_TAPE_STATE:"));
        assertTrue(host.contains("dispatchTapeState(state)"));
        assertTrue(host.contains("callNorns(\"tape_status\""));
        assertTrue(host.contains("callNorns(\"tape_play_file\""));
        assertTrue(host.contains("callNorns(\"tape_rec_file\""));
        assertTrue(engine.contains("interface TapeStateListener"));
        assertTrue(engine.contains("renderStartNanos - lastTapeStatePublishNanos >= 100_000_000L"));
        assertTrue(tape.contains("State getState()"));
        assertTrue(tape.contains("final int playState"));
        assertTrue(tape.contains("final int recordState"));
        assertTrue(tape.contains("playing ? 2 : (playPaused ? 3 : 1)"));
        assertTrue(tape.contains("current.isRecording() ? 2 : (current.isPaused() ? 3 : 1)"));
        assertTrue(tape.contains("final String playFile"));
        assertTrue(tape.contains("final String recordFile"));
        assertTrue(host.contains("value.set(\"play_file\""));
        assertTrue(host.contains("value.set(\"rec_file\""));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
@Test public void adcEngineAndTapeSendsHaveIndependentImplementedRoutes() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String audio = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String tape = read("app/src/main/java/org/nornsmobile/app/TapeCore.java");
        String softcut = read("app/src/main/java/org/nornsmobile/app/SoftcutCore.java");

        assertTrue(boot.contains("level_adc_cut = function(level)"));
        assertTrue(boot.contains("host.softcut(0, \"adc_level\", level)"));
        assertTrue(boot.contains("level_eng_cut = function(level)"));
        assertTrue(boot.contains("level_tape_cut = function(level)"));
        assertTrue(boot.contains("host.tape_level_cut(level)"));
        assertTrue(audio.contains("currentTape.render(buf, frames, tapeBlock)"));
        assertTrue(audio.contains("softcut.render(buf, frames, sampleRate, inputBlock, engineBlock,\n                                tapeBlock, softcutReverbBlock)"));
        assertTrue(tape.contains("tapeMono[i] = (left + right) * 0.5f"));
        assertTrue(softcut.contains("incoming += tapeInput[i] * tapeLevel"));
        assertFalse(boot.contains("barramento engine->softcut ainda não implementado"));
        assertFalse(boot.contains("barramento tape->softcut ainda não implementado"));
    }

    @Test public void adcInputGainsRejectNonFiniteValuesAndClampToSupportedRange() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("if not level or level ~= level or level == math.huge or level == -math.huge then return false end"));
        assertTrue(boot.contains("level = math.max(0, math.min(2, level))"));
        assertTrue(boot.contains("sc(cut, \"input_level\", input == 1 and level or 0)"));
        assertTrue(boot.contains("softcut._native_adc_level = level"));
        assertTrue(boot.contains("host.softcut(0, \"adc_level\", level)"));
        assertTrue(boot.contains("if recorder then sc_api.set(recorder, {adc_level = level}) end"));
    }

    @Test public void dacGainIsExposedOnlyForTheJavaOutputPath() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(boot.contains("level_dac = function(level)"));
        assertTrue(boot.contains("host.audio_level_dac(math.max(0, math.min(1, level)))"));
        assertTrue(boot.contains("controle do DAC nativo ainda indisponível"));
        assertTrue(host.contains("H_AUDIO_LEVEL_DAC = 68"));
        assertTrue(host.contains("audio.setMasterOutputLevel(a.optdouble(1, 1))"));
        assertTrue(engine.contains("AudioOutputGain.apply(buf, masterOutputLevel)"));
        assertTrue(engine.contains("boolean setMasterOutputLevel(double level)"));
    }
}
