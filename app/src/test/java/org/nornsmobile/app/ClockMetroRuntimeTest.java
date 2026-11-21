package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Executes the real scheduler/clock/metro source from boot.lua with a deterministic host clock.
 * The test extracts the production _norns.tick implementation as well, so no scheduler copy is
 * maintained in the test fixture.
 */
public class ClockMetroRuntimeTest {
    private static String readBoot() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        if (!file.isFile()) throw new AssertionError("Missing bundled boot.lua");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Globals runtime() throws Exception {
        String boot = readBoot();
        String schedulerStart = "local timers = {}";
        String schedulerEnd = "-- ===================================================================== SuperCollider";
        int start = boot.indexOf(schedulerStart);
        int end = boot.indexOf(schedulerEnd, start);
        assertTrue("production scheduler block must exist", start >= 0 && end > start);

        String tickStart = "function _norns.tick()";
        int tick = boot.indexOf(tickStart);
        int tickEnd = boot.indexOf("\nend", tick);
        assertTrue("production _norns.tick implementation must exist", tick >= 0 && tickEnd > tick);
        String tickSource = boot.substring(tick, tickEnd + 4)
                .replace(tickStart, "function __tick()");

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "now = 0\n"
                + "last_wake = -1\n"
                + "last_log = ''\n"
                + "host = {"
                + "now = function() return now end,"
                + "wake_at = function(value) last_wake = value end,"
                + "log = function(message) last_log = tostring(message) end"
                + "}\n"
                + "util = {clamp = function(value, low, high) return math.max(low, math.min(high, value)) end}\n"
                + "safe = function(fn, ...) local ok, result = pcall(fn, ...); return ok, result end\n"
                + "noop = function() end\n"
                + "midi = nil\n",
                "clock-metro-host-fixture.lua").call();

        LuaValue api = globals.load(
                boot.substring(start, end) + "\n" + tickSource
                + "\nreturn {clock = clock, metro = metro, tick = __tick, "
                + "pending = function() return #timers end}",
                "clock-metro-production-under-test.lua").call();
        globals.set("SchedulerUnderTest", api);
        return globals;
    }

    private static void runLua(Globals globals, String source, String name) {
        LuaValue result = globals.load(source, name).call();
        assertTrue(name + " should return true", result.toboolean());
    }

    @Test public void clockSleepResumesAtDeadlineAndKeepsTimerOrdering() throws Exception {
        Globals globals = runtime();
        runLua(globals,
                "local clock = SchedulerUnderTest.clock\n"
                + "local events = {}\n"
                + "clock.run(function() clock.sleep(0.2); events[#events + 1] = 'late' end)\n"
                + "clock.run(function() clock.sleep(0.1); events[#events + 1] = 'early' end)\n"
                + "assert(#events == 0)\n"
                + "now = 0.1; SchedulerUnderTest.tick()\n"
                + "assert(#events == 1 and events[1] == 'early')\n"
                + "now = 0.2; SchedulerUnderTest.tick()\n"
                + "assert(#events == 2 and events[2] == 'late')\n"
                + "return true",
                "clock-sleep-order.lua");
    }

    @Test public void clockSyncUsesNextBeatAndTempoChangesPreserveBeatPosition() throws Exception {
        Globals globals = runtime();
        runLua(globals,
                "local clock = SchedulerUnderTest.clock\n"
                + "local resumed = nil\n"
                + "clock.run(function() resumed = clock.sync(1) end)\n"
                + "now = 0.499; SchedulerUnderTest.tick()\n"
                + "assert(resumed == nil, 'sync must wait for the next beat boundary')\n"
                + "now = 0.5; SchedulerUnderTest.tick()\n"
                + "assert(math.abs(resumed - 1) < 0.00001)\n"
                + "local before = clock.get_beats()\n"
                + "clock.set_tempo(60)\n"
                + "assert(math.abs(clock.get_beats() - before) < 0.00001, 'tempo change must not jump beat position')\n"
                + "now = 1.5\n"
                + "assert(math.abs(clock.get_beats() - 2) < 0.00001)\n"
                + "return true",
                "clock-sync-tempo.lua");
    }

    @Test public void cancellingClockAndChangingSourceRetireOldWaiters() throws Exception {
        Globals globals = runtime();
        runLua(globals,
                "local clock = SchedulerUnderTest.clock\n"
                + "local fired = false\n"
                + "local id = clock.run(function() clock.sleep(1); fired = true end)\n"
                + "clock.cancel(id)\n"
                + "now = 1; SchedulerUnderTest.tick()\n"
                + "assert(not fired, 'cancelled coroutine must not resume')\n"
                + "assert(clock.set_source('midi'))\n"
                + "local synced = false\n"
                + "clock.run(function() clock.sync(1); synced = true end)\n"
                + "assert(not synced)\n"
                + "assert(clock.set_source('internal'))\n"
                + "now = 1.5; SchedulerUnderTest.tick()\n"
                + "assert(synced, 'switching back to internal clock must re-arm MIDI sync waiters')\n"
                + "return true",
                "clock-cancel-source-switch.lua");
    }

    @Test public void metroHonorsCountStageStopRestartAndFree() throws Exception {
        Globals globals = runtime();
        runLua(globals,
                "local metro = SchedulerUnderTest.metro\n"
                + "local events = {}\n"
                + "local m = metro.init(function(stage) events[#events + 1] = stage end, 0.1, 2)\n"
                + "assert(m and m:start())\n"
                + "now = 0.1; SchedulerUnderTest.tick()\n"
                + "assert(#events == 1 and events[1] == 1)\n"
                + "now = 0.2; SchedulerUnderTest.tick()\n"
                + "assert(#events == 2 and events[2] == 2 and not m.is_running)\n"
                + "now = 0.3; SchedulerUnderTest.tick()\n"
                + "assert(#events == 2, 'counted metro must not fire beyond its count')\n"
                + "assert(m:start({time = 0.2, count = 1, stage = 7}))\n"
                + "m:stop()\n"
                + "now = 0.5; SchedulerUnderTest.tick()\n"
                + "assert(#events == 2, 'stopped metro must not invoke a retired callback')\n"
                + "assert(m:start({time = 0.1, count = 1, stage = 9}))\n"
                + "now = 0.6; SchedulerUnderTest.tick()\n"
                + "assert(#events == 3 and events[3] == 9)\n"
                + "local doomed = metro.init(function() events[#events + 1] = 99 end, 0.1, -1)\n"
                + "assert(doomed:start())\n"
                + "metro.free(doomed.id)\n"
                + "now = 0.7; SchedulerUnderTest.tick()\n"
                + "assert(events[#events] == 9 and doomed.freed)\n"
                + "return true",
                "metro-count-stop-restart-free.lua");
    }

    // MIDI Clock delivers 24 timing pulses per quarter-note beat.
    @Test public void midiSyncResumesOnlyOnTheTwentyFourthClockPulse() throws Exception {
        Globals globals = runtime();
        runLua(globals,
                "local clock = SchedulerUnderTest.clock\n"
                + "assert(clock.set_source('midi'))\n"
                + "local synced = false\n"
                + "clock.run(function() clock.sync(1); synced = true end)\n"
                + "for pulse = 1, 23 do now = pulse / 24; clock._midi_event(0xf8) end\n"
                + "assert(not synced, 'external sync must wait for a complete MIDI beat')\n"
                + "now = 1; clock._midi_event(0xf8)\n"
                + "assert(synced, '24 MIDI clock pulses must complete one beat')\n"
                + "return true",
                "clock-midi-24-ppqn.lua");
    }

    @Test public void cleanupCancelsClockCoroutinesAndAllMetroCallbacks() throws Exception {
        Globals globals = runtime();
        runLua(globals,
                "local clock, metro = SchedulerUnderTest.clock, SchedulerUnderTest.metro\n"
                + "local events = 0\n"
                + "clock.run(function() clock.sleep(0.1); events = events + 1 end)\n"
                + "local m = metro.init(function() events = events + 10 end, 0.1, -1)\n"
                + "assert(m:start())\n"
                + "clock.cleanup()\n"
                + "metro.free_all()\n"
                + "now = 0.1; SchedulerUnderTest.tick()\n"
                + "assert(events == 0, 'cleanup must prevent stale clock and metro callbacks')\n"
                + "assert(not m.is_running and m.freed)\n"
                + "return true",
                "clock-metro-cleanup.lua");
    }
}
