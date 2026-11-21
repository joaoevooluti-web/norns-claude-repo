package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;

public class PollApiLifecycleTest {
    @Test
    public void pollInstancesUpdateCallbackAndStopRestartWithoutLeakingClockTasks() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        if (!file.isFile()) throw new AssertionError("Missing boot.lua");
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String startMarker = "poll = {available = true, _instances = {}}";
        String endMarker = "-- Physical Android keyboard mapped to the norns keyboard module.";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        if (start < 0 || end <= start) throw new AssertionError("Could not isolate poll module");

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "local next_id = 0\n"
                + "clock = {\n"
                + " run = function(fn) next_id = next_id + 1; return next_id end,\n"
                + " cancel = function(id) cancelled[id] = (cancelled[id] or 0) + 1 end\n"
                + "}\n"
                + "cancelled = {}\n"
                + "values = {amp_in_l = 0.25, cpu_avg = 12.5}\n"
                + "host = {poll_value = function(name) return values[name] or -1 end, "
                + "log = function() end}\n"
                + "safe = function(fn, ...) return pcall(fn, ...) end\n",
                "poll-fixture.lua").call();
        assertNotNull(globals.load(boot.substring(start, end), "poll-module.lua").call());
        globals.load(
                "local names = poll.list_names()\n"
                + "assert(#names == 8 and names[1] == 'amp_in_l' and names[8] == 'pitch_in_r')\n"
                + "assert(poll.set('not_a_poll') == nil)\n"
                + "local p = poll.set('amp_in_l')\n"
                + "local observed = -1\n"
                + "p.callback = function(value) observed = value end\n"
                + "assert(p:update() == 0.25 and observed == 0.25)\n"
                + "assert(p:start() == true)\n"
                + "assert(p:start() == false)\n"
                + "local first = p._clock\n"
                + "assert(p:stop() == true and p._clock == nil)\n"
                + "assert(cancelled[first] == 1)\n"
                + "assert(p:start() == true)\n"
                + "local second = p._clock\n"
                + "assert(second ~= first)\n"
                + "local q = poll.set('cpu_avg')\n"
                + "assert(q:start() == true)\n"
                + "local third = q._clock\n"
                + "assert(poll.clear_all() == true)\n"
                + "assert(p._clock == nil and q._clock == nil)\n"
                + "assert(cancelled[second] == 1 and cancelled[third] == 1)\n"
                + "assert(#poll._instances == 0)\n",
                "poll-lifecycle-contract.lua").call();
    }
}
