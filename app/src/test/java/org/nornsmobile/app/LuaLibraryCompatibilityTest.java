package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class LuaLibraryCompatibilityTest {
    private static File asset(String name) {
        File file = new File("app/src/main/assets/lua/lib/" + name + ".lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/lib/" + name + ".lua");
        return file;
    }

    @Test public void everyBundledNornsLibraryLoadsThroughLuaRequire() throws Exception {
        final String[] names = {
                "util", "controlspec", "tabutil", "formatters", "paramset",
                "filters", "lattice", "lfo", "musicutil", "sequins", "ui",
                "beatclock", "pattern_time", "voice"
        };
        final Map<String, String> sources = new LinkedHashMap<String, String>();
        for (String name : names) {
            File file = asset(name);
            assertTrue("Missing bundled Lua library: " + name, file.isFile());
            sources.put(name, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }

        final Globals globals = JsePlatform.standardGlobals();
        globals.load("clock = {get_beat_sec = function() return 0.5 end}\n",
                "norns-library-runtime-fixture.lua").call();
        final LuaTable loaded = globals.get("package").get("loaded").checktable();
        globals.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                LuaValue cached = loaded.get(name);
                if (!cached.isnil()) return cached;
                String source = sources.get(name);
                if (source == null && name.startsWith("lib/")) source = sources.get(name.substring(4));
                if (source == null) throw new LuaError("module '" + name + "' not found");
                loaded.set(name, LuaValue.TRUE);
                LuaValue result = globals.load(source, "@lua/lib/" + name + ".lua").call();
                if (!result.isnil()) loaded.set(name, result);
                return loaded.get(name);
            }
        });

        for (String name : names) {
            LuaValue module = globals.get("require").call(LuaValue.valueOf(name));
            assertFalse("require('" + name + "') must return a module", module.isnil());
            assertFalse("require('" + name + "') must not return false", module.isboolean() && !module.toboolean());
        }

        LuaValue formatterAlias = globals.get("require").call(LuaValue.valueOf("lib/formatters"));
        assertFalse(formatterAlias.isnil());
        assertTrue(formatterAlias.get("format_freq_raw").isfunction());
    }
    @Test public void everyBundledExampleScriptParsesWithLuaJ() throws Exception {
        String[] paths = {
                "dust/code/hello_norns/hello_norns.lua",
                "dust/code/midi_test_seq/midi_test_seq.lua",
                "dust/code/polyperc_seq/polyperc_seq.lua",
                "dust/code/device_check/device_check.lua",
                "dust/code/mobile_selftest/mobile_selftest.lua"
        };
        Globals globals = JsePlatform.standardGlobals();
        for (String path : paths) {
            File file = new File("app/src/main/assets/" + path);
            if (!file.isFile()) file = new File("src/main/assets/" + path);
            assertTrue("Missing bundled example script: " + path, file.isFile());
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertNotNull("LuaJ must parse bundled script " + path, globals.load(source, "@" + path));
        }
    }

    @Test public void upstreamPatternClockAndVoiceLibrariesExecuteWithMobileContracts() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "monotonicNow = 12.375\n"
                + "_host = {now = function() return monotonicNow end}\n"
                + "clock = {get_beat_sec = function() return 0.5 end}\n"
                + "local nextId = 0\n"
                + "metro = {init = function(event, time, count) "
                + "nextId = nextId + 1; return {event=event, time=time or 1, count=count or -1, "
                + "start=function(self) self.running=true end, stop=function(self) self.running=false end} end}\n"
                + "midi = {devices = {}}\n"
                + "params = {}\n",
                "upstream-library-fixture.lua").call();

        File utilFile = asset("util");
        String utilSource = new String(Files.readAllBytes(utilFile.toPath()), StandardCharsets.UTF_8);
        globals.set("util", globals.load(utilSource, "@lua/lib/util.lua").call());
        assertEquals(12.375, globals.get("util").get("time").call().todouble(), 0.000001);
        globals.load("monotonicNow = 12.5", "advance-monotonic-clock.lua").call();
        assertEquals(12.5, globals.get("util").get("time").call().todouble(), 0.000001);

        String[] names = {"beatclock", "pattern_time", "voice"};
        for (String name : names) {
            File file = asset(name);
            assertTrue("Missing bundled library: " + name, file.isFile());
            LuaValue module = globals.load(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
                    "@lua/lib/" + name + ".lua").call();
            globals.get("package").get("loaded").checktable().set(name, module);
        }
        globals.load(
                "local Voice = require('voice')\n"
                + "local pool = Voice.new(2, Voice.MODE_ROTATE)\n"
                + "local a = pool:get(); local b = pool:get(); local c = pool:get()\n"
                + "assert(a.id == 1 and b.id == 2 and c == a and a.active)\n"
                + "pool:release(a); assert(not a.active)\n"
                + "local Pattern = require('pattern_time')\n"
                + "local p = Pattern.new(); assert(p.count == 0 and p.time_factor == 1)\n"
                + "p.process = function() end; p:rec_start(); p:rec_event('a'); p:rec_event('b')\n"
                + "assert(p.count == 2 and p.event[1] == 'a' and p.event[2] == 'b')\n"
                + "p:set_time_factor(0.5); assert(p.time_factor == 0.5); p:clear(); assert(p.count == 0)\n"
                + "local BeatClock = require('beatclock'); local bc = BeatClock.new('test')\n"
                + "local steps = 0; bc.on_step = function() steps = steps + 1 end\n"
                + "bc:start(); for i=1,6 do bc:tick() end\n"
                + "assert(steps == 1 and bc.playing and bc.step == 0)\n"
                + "bc:stop(); assert(not bc.playing)\n",
                "upstream-library-behavior.lua").call();
    }

    @Test public void audioFileInfoLuaContractReturnsChannelsSamplesAndRate() throws Exception {
        File bootFile = new File("app/src/main/assets/lua/boot.lua");
        if (!bootFile.isFile()) bootFile = new File("src/main/assets/lua/boot.lua");
        String boot = new String(Files.readAllBytes(bootFile.toPath()), StandardCharsets.UTF_8);
        String declaration = "file_info = function(file) return host.audio_file_info(tostring(file)) end,";
        assertTrue("boot.lua must expose audio.file_info through the host", boot.contains(declaration));
        Globals globals = JsePlatform.standardGlobals();
        globals.load("host = {audio_file_info=function(path) "
                + "assert(path == '/dust/audio/test.wav'); return 2, 96000, 48000 end}; audio = {\n"
                + declaration + "\n}\n"
                + "local channels, samples, rate = audio.file_info('/dust/audio/test.wav')\n"
                + "assert(channels == 2 and samples == 96000 and rate == 48000)",
                "audio-file-info-contract.lua").call();
    }

    @Test public void failedRequireDoesNotPoisonModuleCacheAndCanRetry() throws Exception {
        File bootFile = new File("app/src/main/assets/lua/boot.lua");
        if (!bootFile.isFile()) bootFile = new File("src/main/assets/lua/boot.lua");
        assertTrue("Missing boot.lua", bootFile.isFile());
        String boot = new String(Files.readAllBytes(bootFile.toPath()), StandardCharsets.UTF_8);
        String startMarker = "local loaded = package.loaded";
        String endMarker = "local included = {}";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue("production require implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "assets = {}\n"
                + "host = {read_asset=function(path) return assets[path] end, read_file=function() return nil end}\n"
                + "_path = {code='/dust/code/'}\n"
                + "norns = {state={path=''}}\n",
                "require-cache-fixture.lua").call();
        globals.load(boot.substring(start, end), "production-require-under-test.lua").call();
        globals.load(
                "assets['lua/lib/retryable.lua'] = \"error('temporary module failure')\"\n"
                + "local ok = pcall(require, 'retryable')\n"
                + "assert(not ok and package.loaded.retryable == nil, 'failed module must not remain cached')\n"
                + "assets['lua/lib/retryable.lua'] = \"return {ready=true}\"\n"
                + "local module = require('retryable')\n"
                + "assert(module.ready and require('retryable') == module, 'retry must load and cache the module')\n"
                + "return true",
                "require-cache-retry-contract.lua").call();
    }

}
