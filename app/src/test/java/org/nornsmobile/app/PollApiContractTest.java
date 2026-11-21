package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PollApiContractTest {
    @Test public void luaPollModuleProvidesNornsLifecycleAndDefaultNames() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("poll._names = {\"amp_in_l\""));
        assertTrue(boot.contains("function poll.list_names()"));
        assertTrue(boot.contains("function poll.set(name, callback)"));
        assertTrue(boot.contains("function instance:update(fn)"));
        assertTrue(boot.contains("function instance:start()"));
        assertTrue(boot.contains("function instance:stop()"));
        assertTrue(boot.contains("function poll.clear_all()"));
        assertTrue(boot.contains("poll.clear_all() end"));
    }

    @Test public void pollValuesAreProvidedByAudioEngineAndHost() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(engine.contains("pollMetrics.update(inputBlock, buf, frames, sampleRate"));
        assertTrue(engine.contains("double pollValue(String name)"));
        assertTrue(host.contains("case H_POLL_VALUE:"));
        assertTrue(host.contains("audio.pollValue(a.checkjstring(1))"));
    }

    @Test public void nativeOutputPollMetersUseFinalOboePcmSnapshot() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String bridge = read("app/src/main/java/org/nornsmobile/app/SuperColliderBridge.java");
        assertTrue(engine.contains("SuperColliderBridge.readSoftcutDiagnostics(diagnostics)"));
        assertTrue(engine.contains("diagnostics[\"amp_out_l\".equals(name) ? 10 : 11]"));
        assertTrue(bridge.contains("destination.length >= 54"));
    }

    @Test public void nativeCpuPollMetersUseOboeCallbackLoadInsteadOfJavaFallback() throws Exception {
        String engine = read("app/src/main/java/org/nornsmobile/app/AudioEngine.java");
        String bridge = read("app/src/main/java/org/nornsmobile/app/SuperColliderBridge.java");
        String jni = read("app/src/main/cpp/sc_bridge.cpp");
        String nativeAudio = read("tools/SC_AndroidAudio.cpp");
        assertTrue(engine.contains("SuperColliderBridge.readCpuMetrics(cpu)"));
        assertTrue(bridge.contains("nativeReadCpuMetrics(destination)"));
        assertTrue(jni.contains("NornsSC_ReadCpuMetrics"));
        assertTrue(nativeAudio.contains("mCpuAverageSnapshot.store"));
        assertTrue(nativeAudio.contains("mCpuPeakSnapshot.store"));
    }


    @Test public void pollSetAcceptsAndStoresTheNornsCallbackArgument() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("function poll.set(name, callback)");
        int end = boot.indexOf("function instance:start()", start);
        assertTrue(start >= 0 && end > start);
        String api = boot.substring(start, end);
        assertTrue(api.contains("callback = type(callback) == \"function\" and callback or nil"));
        assertTrue(api.contains("function instance:update(fn)"));
        assertTrue(api.contains("if type(fn) == \"function\" then self.callback = fn end"));
        assertTrue(api.contains("if type(self.callback) == \"function\" then safe(self.callback, value) end"));
    }

    @Test public void pollInstancesExposeStableIdsAndRejectUnknownNames() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local poll_id"));
        assertTrue(boot.contains("if not poll_id then"));
        assertTrue(boot.contains("id = poll_id, name = name, time = 0.1"));
        assertTrue(boot.contains("return nil"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile() && path.startsWith("tools/")) file = new File(".." + File.separator + path);
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
