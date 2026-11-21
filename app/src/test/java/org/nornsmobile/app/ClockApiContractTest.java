package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ClockApiContractTest {
    @Test public void clockExposesInternalAndMidiSourcesAndRejectsUnsupportedLink() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function clock.set_source(source)"));
        assertTrue(boot.contains("source ~= \"internal\" and source ~= \"midi\""));
        assertTrue(boot.contains("function clock.get_source()"));
        assertTrue(boot.contains("ainda não suportada"));
    }

    @Test public void midiClockUsesTwentyFourPulsesPerBeatAndMeasuresTempo() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("midi_beat = midi_beat + 1 / 24"));
        assertTrue(boot.contains("60 / ((total / #midi_pulse_intervals) * 24)"));
        assertTrue(boot.contains("function clock._midi_event(status)"));
    }

    @Test public void externalSyncWaitsForAnIncomingMidiPulseBoundary() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("if clock_source == \"midi\" then"));
        assertTrue(boot.contains("midi_sync_waiters[#midi_sync_waiters + 1]"));
        assertTrue(boot.contains("if current + 1e-6 >= waiter.target then"));
    }

    @Test public void midiTransportCallsScriptCallbacksForStartContinueAndStop() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(boot.contains("if status == 0xfa then"));
        assertTrue(boot.contains("elseif status == 0xfb then"));
        assertTrue(boot.contains("elseif status == 0xfc then"));
        assertTrue(boot.contains("safe(clock.transport.start)"));
        assertTrue(boot.contains("safe(clock.transport.stop)"));
        assertTrue(host.contains("clockEvent.call(LuaValue.valueOf(status))"));
    }

    @Test public void midiClockEventsReachTheClockBeforeTheScriptMidiHandler() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int clock = host.indexOf("clockEvent.call(LuaValue.valueOf(status))");
        int script = host.indexOf("deviceEvent.call(data)", clock);
        assertTrue(clock >= 0 && script > clock);
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
