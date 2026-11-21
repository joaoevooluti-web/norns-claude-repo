package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SoftcutOutputOwnershipTest {
    private static final class FakeGate implements SoftcutOutputOwnership.Gate {
        final List<String> events = new ArrayList<String>();
        boolean nativeEnabled, failEnable, failDisable, failDrain;
        public void setJavaEnabled(boolean enabled) { events.add("java:" + enabled); }
        public boolean awaitJavaDrain() { events.add("java:drain"); return !failDrain; }
        public boolean setNativeEnabled(boolean enabled) {
            events.add("native:" + enabled);
            if ((enabled && failEnable) || (!enabled && failDisable)) return false;
            nativeEnabled = enabled; return true;
        }
    }
    @Test public void nativeSoftcutOwnershipRequiresBothRuntimeRoutingProofs() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String probes = read("app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java");
        assertTrue(engine.contains("!SuperColliderSoftcutBuffers.isNativeRoutingValidated()"));
        assertTrue(engine.contains("!SuperColliderSoftcutBuffers.isReady()"));
        assertTrue(host.contains("SuperColliderSoftcutBuffers.isNativeRoutingValidated()"));
        assertTrue(probes.contains("lastBufferIsolationProof && lastStereoRoutingProof"));
        assertTrue(probes.contains("setSoftcutOutputProbeMode(true)"));
        assertTrue(probes.contains("values[13] != before[13]"));
        assertTrue(probes.contains("mutedLeftPeak < 0.001f && mutedRightPeak < 0.001f"));
    }

    private static String read(String path) throws IOException {
        java.io.File source = new java.io.File(path);
        if (!source.isFile() && path.startsWith("app/")) source = new java.io.File(path.substring(4));
        if (!source.isFile() && path.startsWith("tools/")) source = new java.io.File(".." + java.io.File.separator + path);
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] data = new byte[input.available()];
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    @Test public void nativeBridgeAbsenceCountsAsConfirmedMuteAndKeepsJavaFallback() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(engine.contains("if (!enabled && !SuperColliderBridge.isAvailable()) return true;"));
    }

    @Test public void audioStopMutesNativeOutputBeforeTapeAndTrackTeardown() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        int stop = engine.indexOf("synchronized void stop()");
        int runningFalse = engine.indexOf("running = false;", stop);
        int nativeMute = engine.indexOf("softcutOutputOwnership.handoffToJava()", stop);
        int tapeStop = engine.indexOf("currentTape.playStop();", stop);
        int join = engine.indexOf("t.join(800)", stop);
        assertTrue(stop >= 0 && runningFalse > stop);
        assertTrue("native output must be muted before recorder and AudioTrack teardown",
                nativeMute > runningFalse && tapeStop > nativeMute && join > tapeStop);
        assertTrue(engine.contains("saída Java mantida desativada"));
    }

    @Test public void startsJavaOwnedAndNativeMuted() {
        FakeGate g = new FakeGate(); SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertTrue(r.isJavaEnabled()); assertFalse(r.isNativeOwner()); assertFalse(g.nativeEnabled);
    }
    @Test public void requiresReadinessAndRouteProof() {
        FakeGate g = new FakeGate(); SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertFalse(r.handoffToNative(false, true)); assertFalse(r.handoffToNative(true, false));
        assertTrue(r.isJavaEnabled()); assertFalse(g.nativeEnabled);
    }
    @Test public void mutesJavaBeforeEnablingNative() {
        FakeGate g = new FakeGate(); SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertTrue(r.handoffToNative(true, true)); int n = g.events.size();
        assertEquals("java:false", g.events.get(n - 3)); assertEquals("java:drain", g.events.get(n - 2)); assertEquals("native:true", g.events.get(n - 1));
        assertTrue(r.isNativeOwner()); assertFalse(r.isJavaEnabled());
    }
    @Test public void refusesNativeHandoffWhenJavaQueueDoesNotDrain() {
        FakeGate g = new FakeGate(); g.failDrain = true;
        SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertFalse(r.handoffToNative(true, true));
        assertEquals("java:false", g.events.get(g.events.size() - 3));
        assertEquals("java:drain", g.events.get(g.events.size() - 2));
        assertEquals("java:true", g.events.get(g.events.size() - 1));
        assertFalse(g.nativeEnabled); assertTrue(r.isJavaEnabled());
    }
    @Test public void rollbackConfirmsNativeMuteBeforeReenablingJava() {
        FakeGate g = new FakeGate(); g.failEnable = true; SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertFalse(r.handoffToNative(true, true));
        int n = g.events.size();
        assertEquals("native:false", g.events.get(n - 2));
        assertEquals("java:true", g.events.get(n - 1));
        assertTrue(r.isJavaEnabled()); assertFalse(r.isNativeOwner());
    }

    @Test public void keepsBothOutputsMutedWhenFailedNativeEnableCannotBeConfirmedMuted() {
        FakeGate g = new FakeGate(); g.failEnable = true; g.failDisable = true;
        SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertFalse(r.handoffToNative(true, true));
        assertFalse(r.isJavaEnabled());
        assertFalse(r.isNativeOwner());
        g.failDisable = false;
        assertTrue(r.handoffToJava());
        assertTrue(r.isJavaEnabled());
    }

    @Test public void startupDoesNotEnableJavaUntilNativeMuteIsConfirmed() {
        FakeGate g = new FakeGate(); g.failDisable = true;
        SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertFalse(r.isJavaEnabled());
        assertFalse(r.handoffToNative(true, true));
        g.failDisable = false;
        assertTrue(r.handoffToJava());
        assertTrue(r.isJavaEnabled());
    }
    @Test public void failedShutdownMuteKeepsJavaSilentUntilNativeMuteIsConfirmed() {
        FakeGate g = new FakeGate();
        SoftcutOutputOwnership ownership = new SoftcutOutputOwnership(g);
        assertTrue(ownership.handoffToNative(true, true));
        g.failDisable = true;
        assertFalse(ownership.handoffToJava());
        assertTrue(ownership.isNativeOwner());
        assertFalse(ownership.isJavaEnabled());
        assertTrue(g.nativeEnabled);
        g.failDisable = false;
        assertTrue(ownership.handoffToJava());
        assertFalse(ownership.isNativeOwner());
        assertTrue(ownership.isJavaEnabled());
        assertFalse(g.nativeEnabled);
    }

    @Test public void disablesNativeBeforeReenablingJava() {
        FakeGate g = new FakeGate(); SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertTrue(r.handoffToNative(true, true)); int n = g.events.size();
        assertTrue(r.handoffToJava()); assertEquals("native:false", g.events.get(n));
        assertEquals("java:true", g.events.get(n + 1)); assertTrue(r.isJavaEnabled());
    }
    @Test public void repeatedNativeHandoffDoesNotToggleEitherOutput() {
        FakeGate g = new FakeGate(); SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertTrue(r.handoffToNative(true, true));
        int afterFirst = g.events.size();
        assertTrue(r.handoffToNative(true, true));
        assertEquals(afterFirst, g.events.size());
    }

    @Test public void doesNotEnableJavaIfNativeMuteFails() {
        FakeGate g = new FakeGate(); SoftcutOutputOwnership r = new SoftcutOutputOwnership(g);
        assertTrue(r.handoffToNative(true, true)); g.failDisable = true;
        int n = g.events.size(); assertFalse(r.handoffToJava());
        assertEquals("native:false", g.events.get(n)); assertEquals(n + 1, g.events.size());
        assertFalse(r.isJavaEnabled());
    }

    @Test public void javaMuteAndDrainAreSerializedWithTheWholeAudioBlock() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        int loop = engine.indexOf("private void loop(AudioTrack t)");
        int lock = engine.indexOf("synchronized (outputOwnershipLock)", loop);
        int render = engine.indexOf("softcut.render(buf, frames", lock);
        int write = engine.indexOf("t.write(buf", render);
        int close = blockEnd(engine, engine.indexOf('{', lock));
        assertTrue("audio loop must exist", loop >= 0);
        assertTrue("audio block must be guarded by output ownership lock", lock > loop);
        assertTrue("Softcut render must occur inside the ownership lock", render > lock && render < close);
        assertTrue("AudioTrack write must finish before ownership lock is released", write > render && write < close);
        assertTrue("mute updates Java ownership under the same lock",
                engine.contains("nativeSoftcutJavaEnabled = enabled;"));
    }

    @Test public void nativeMuteWaitsForCallbackAcknowledgementBeforeJavaCanResume() throws Exception {
        String driver = read("tools/SC_AndroidAudio.cpp");
        int start = driver.indexOf("bool setNativeSoftcutOutputEnabled(bool enabled)");
        int end = blockEnd(driver, driver.indexOf('{', start));
        assertTrue(start >= 0 && end > start);
        String method = driver.substring(start, end);
        int closeGate = method.indexOf("mNativeSoftcutOutputEnabled.store(enabled");
        int drain = method.indexOf("while (mOutputCallbacksInFlight.load");
        int ack = method.indexOf("mOutputCallbacksCompleted.load", drain);
        assertTrue("native gate must close before waiting", closeGate >= 0 && drain > closeGate);
        assertTrue("native mute must await a completed silent callback", ack > drain);
        assertTrue("callback lifetime must be counted", driver.contains("OutputCallbackGuard callbackGuard"));
    }


    @Test public void TapeSendAndNativeSoftcutOwnershipCannotOverlap() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(engine.contains("tapeCutLevel > 0 || tapeCutLevelPending || nativeSoftcutHandoffInProgress"));
        assertTrue(engine.contains("bounded > 0 && (!nativeSoftcutJavaEnabled || nativeSoftcutHandoffInProgress)"));
        assertTrue(engine.contains("nativeSoftcutHandoffInProgress = true"));
        assertTrue(engine.contains("nativeSoftcutHandoffInProgress = false"));
        assertTrue("native handoff must wait until the complete Tape recording is closed",
                engine.contains("currentTape.hasOpenRecordSession()"));
        assertTrue("Tape recording must be rejected while native Softcut owns output",
                engine.contains("if (!nativeSoftcutJavaEnabled || nativeSoftcutHandoffInProgress)"));
    }

    @Test public void audioShutdownAlwaysFinalizesTapeRecorder() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(engine.contains("currentTape.recordStop();"));
        int finallyAt = engine.indexOf("} finally {", engine.indexOf("private void loop(AudioTrack t)"));
        int stopAt = engine.indexOf("currentTape.recordStop();", finallyAt);
        assertTrue("audio loop failure must stop and finalize Tape recording", finallyAt >= 0 && stopAt > finallyAt);
    }


    @Test public void unexpectedAudioWriterExitMutesNativeSoftcutBeforeTapeAndTrackRelease() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        int loop = engine.indexOf("private void loop(AudioTrack t)");
        int cleanup = engine.indexOf("} finally {", loop);
        int runningFalse = engine.indexOf("running = false;", cleanup);
        int mute = engine.indexOf("softcutOutputOwnership.handoffToJava()", cleanup);
        int tapeStop = engine.indexOf("currentTape.playStop();", cleanup);
        int release = engine.indexOf("t.release();", cleanup);
        assertTrue(loop >= 0 && cleanup > loop);
        assertTrue(runningFalse > cleanup && mute > runningFalse);
        assertTrue("unexpected writer exit must mute native output before teardown",
                tapeStop > mute && release > tapeStop);
    }

    @Test public void stereoProbeReassertsProductionMuteBeforeLeavingProbeMode() throws Exception {
        String probes = read("app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java");
        int method = probes.indexOf("static boolean verifyNativeStereoRouting");
        int cleanup = probes.indexOf("} finally {", method);
        int forceMute = probes.indexOf("setSoftcutOutputEnabled(false)", cleanup);
        int leaveProbe = probes.indexOf("setSoftcutOutputProbeMode(false)", cleanup);
        assertTrue(method >= 0 && cleanup > method);
        assertTrue("production output must be muted again after probe cleanup",
                forceMute > cleanup && leaveProbe > forceMute);
    }

    @Test public void failedStereoReprobeInvalidatesEarlierRoutingProof() throws Exception {
        String probes = read("app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java");
        int method = probes.indexOf("static boolean verifyNativeStereoRouting");
        int invalidate = probes.indexOf("lastStereoRoutingProof = false;", method);
        int precondition = probes.indexOf("if (listener == null || !SuperColliderBridge.isAvailable()", method);
        assertTrue(method >= 0 && invalidate > method);
        assertTrue("proof must be invalidated before early-return checks", precondition > invalidate);
    }


    @Test public void nativeNodesAreFreedOnlyAfterOutputOwnershipReturnsToJava() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int fallback = boot.indexOf("local function softcut_native_fallback(reason)");
        int handoff = boot.indexOf("local outputReturned = host.sc_softcut_output(false, false)", fallback);
        int failureGate = boot.indexOf("if not outputReturned then", handoff);
        int readyReset = boot.indexOf("host.sc_softcut_ready(false)", failureGate);
        int recorderFree = boot.indexOf("sc_api.free(recorder)", readyReset);
        assertTrue(fallback >= 0 && handoff > fallback);
        assertTrue("failed handoff must stop fallback before native resources are freed",
                failureGate > handoff && readyReset > failureGate && recorderFree > readyReset);
    }

    @Test public void TapeSendUsesJavaFallbackAndRejectsNativeEngineSend() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int tape = boot.indexOf("level_tape_cut = function(level)");
        int nativeEngineGuard = boot.indexOf("softcut._engine_cut_source == \"native\"", tape);
        int fallback = boot.indexOf("softcut_native_fallback(\"envio Tape->Softcut usa o roteamento Java\")", tape);
        int javaSend = boot.indexOf("host.tape_level_cut(level)", tape);
        assertTrue(boot.contains("softcut._tape_cut_level or 0) > 0"));
        assertTrue(boot.contains("softcut._tape_record_active"));
        assertTrue(boot.contains("pare a gravação Tape antes de ativar o engine nativo"));
        assertTrue(boot.contains("desative Tape->Softcut antes de iniciar a rota nativa"));
        assertTrue(boot.contains("or (softcut._tape_cut_level or 0) > 0 then return false end"));
        assertTrue(tape >= 0 && nativeEngineGuard > tape);
        assertTrue("native recorder path must fall back before Java Tape send", fallback > nativeEngineGuard);
        assertTrue(javaSend > fallback);
    }


    @Test public void nativeResetAndFallbackRefuseUnsafeActiveRecorderTeardown() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("if nativeEngineSend then"));
        String normalizedBoot = boot.replaceAll("\\s+", " ");
        assertTrue(normalizedBoot.contains("if (softcut._native_output_owner or softcut._native_output_uncertain) and softcut_native_has_active_recording() then"));
        assertTrue(normalizedBoot.contains("if nativeEngineSend or ((softcut._native_output_owner or softcut._native_output_uncertain) and softcut_native_has_active_recording()) then"));
        assertTrue(boot.contains("if not softcut._reset_native() then return false end"));
        int reset = boot.indexOf("function softcut._reset_native()");
        int activeGuard = boot.indexOf("softcut_native_has_active_recording()", reset);
        int outputGate = boot.indexOf("host.sc_softcut_output(false, false)", reset);
        int freeRecorder = boot.indexOf("sc_api.free(recorder)", reset);
        assertTrue(reset >= 0 && activeGuard > reset);
        assertTrue("active recorder guard must run before native output teardown", outputGate > activeGuard);
        assertTrue("native recorder nodes are freed only after output gate handling", freeRecorder > outputGate);
    }

    @Test public void failedAudioThreadStartReleasesTrackAndStopsTape() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        int start = engine.indexOf("thread.start();");
        int cleanup = engine.indexOf("catch (Throwable startFailure)", start);
        int runningFalse = engine.indexOf("running = false;", cleanup);
        int tapeStop = engine.indexOf("currentTape.playStop();", cleanup);
        int inputStop = engine.indexOf("input.stop()", tapeStop);
        int release = engine.indexOf("t.release()", inputStop);
        assertTrue(start >= 0 && cleanup > start);
        assertTrue(runningFalse > cleanup && tapeStop > runningFalse);
        assertTrue(inputStop > tapeStop && release > inputStop);
        assertTrue(engine.indexOf("track = null;", release) > release);
    }

    @Test public void audioRestartWaitsUntilPreviousWriterThreadHasExited() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        int start = engine.indexOf("synchronized boolean start()");
        int previous = engine.indexOf("Thread previousThread = thread;", start);
        int aliveGuard = engine.indexOf("if (previousThread != null && previousThread.isAlive())", previous);
        int trackCreate = engine.indexOf("track = new AudioTrack.Builder()", aliveGuard);
        assertTrue(start >= 0 && previous > start && aliveGuard > previous);
        assertTrue("new AudioTrack must not open while the previous writer is alive", trackCreate > aliveGuard);
        int stop = engine.indexOf("synchronized void stop()");
        int join = engine.indexOf("t.join(800)", stop);
        int alive = engine.indexOf("if (t.isAlive())", join);
        assertTrue(stop >= 0 && join > stop && alive > join);
        assertTrue(engine.contains("else if (thread == t)"));
    }

    private static int blockEnd(String source, int openBrace) {
        if (openBrace < 0) return -1;
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }


    @Test public void failedAudioTrackInitializationReleasesPartialResources() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        assertTrue(engine.contains("track.getState() != AudioTrack.STATE_INITIALIZED"));
        int openFailure = engine.indexOf("catch (Throwable t)", engine.indexOf("track = new AudioTrack.Builder()"));
        int release = engine.indexOf("failedTrack.release()", openFailure);
        int tapeStop = engine.indexOf("failedTape.recordStop()", release);
        int inputStop = engine.indexOf("input.stop()", tapeStop);
        assertTrue(openFailure >= 0 && release > openFailure);
        assertTrue(tapeStop > release && inputStop > tapeStop);
        assertTrue(engine.indexOf("return false;", inputStop) > inputStop);
    }

}
