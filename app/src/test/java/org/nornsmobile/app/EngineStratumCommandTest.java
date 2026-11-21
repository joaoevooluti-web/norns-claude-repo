package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class EngineStratumCommandTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile() && path.startsWith("tools/")) file = new File("../" + path);
        assertTrue("Missing " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String dispatcher(String boot) {
        String startMarker = "engine_dispatch_command = function(name, ...)";
        String endMarker = "\n\nengine = setmetatable({name =";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        if (start < 0 || end <= start) throw new AssertionError("Could not isolate engine dispatcher");
        return boot.substring(start, end);
    }

    @Test
    public void stratumQueuesAndAppliesAllUpstreamRingsAndCloudsCommandsToNativeNode() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine = {name='Stratum', loaded=true, _native_state='loading', _native_node=77, "
                + "_native_pending={}, _native_params={stratum={}}}\n"
                + "applied = {}\n"
                + "sc_api = {set=function(node, controls) assert(node == 77); "
                + "applied[#applied + 1] = controls; return true end}\n"
                + "host = {engine_cmd=function() error('Stratum must not use Java fallback') end}\n"
                + "engine_fallback = function() error('unexpected native fallback') end\n",
                "stratum-command-fixture.lua").call();
        globals.load(dispatcher(boot), "stratum-dispatch.lua").call();
        globals.load(
                "local names = {'rings_gate','rings_pit','rings_struct','rings_bright','rings_damp',"
                + "'rings_pos','rings_model','rings_poly','rings_easteregg','rings_amp','rings_odd_even',"
                + "'rings_bypass','clouds_pos','clouds_size','clouds_pitch','clouds_dens','clouds_tex',"
                + "'clouds_spread','clouds_fb','clouds_freeze','clouds_rvb','clouds_lofi','clouds_mode',"
                + "'clouds_mix','clouds_bypass'}\n"
                + "for i = 1, #names do assert(engine_dispatch_command(names[i], i / 10) == true); "
                + "assert(engine._native_pending[i].name == names[i]); "
                + "assert(engine._native_pending[i].a == i / 10) end\n"
                + "assert(engine_dispatch_command('not_a_stratum_command', 1) == false)\n"
                + "engine._native_state = 'native'; engine._native_pending = {}\n"
                + "for i = 1, #names do assert(engine_dispatch_command(names[i], i / 20) == true); "
                + "assert(applied[i][names[i]] == i / 20); "
                + "assert(engine._native_params.stratum[names[i]] == i / 20) end\n"
                + "assert(#applied == #names)\n",
                "stratum-command-contract.lua").call();
    }

    @Test
    public void failedNativeParameterUpdateNeverFallsBackToPolyPercOrJavaEngine() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine = {name='Stratum', loaded=true, _native_state='native', _native_node=77, "
                + "_native_pending={}, _native_params={stratum={}}}\n"
                + "fallbacks = 0; javaCommands = 0\n"
                + "sc_api = {set=function() return false end}\n"
                + "engine_fallback = function() fallbacks = fallbacks + 1 end\n"
                + "host = {engine_cmd=function() javaCommands = javaCommands + 1 end}\n",
                "stratum-failure-fixture.lua").call();
        globals.load(dispatcher(boot), "stratum-dispatch-failure.lua").call();
        globals.load(
                "assert(engine_dispatch_command('rings_struct', 0.8) == false)\n"
                + "assert(fallbacks == 1 and javaCommands == 0)\n",
                "stratum-fail-closed.lua").call();
    }

    @Test
    public void AndroidPluginPresenceIsResolvedFromInstalledPrivateDirectoryOnlyWhileScsynthRuns() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(boot.contains("host.sc_plugin_exists(basename)"));
        assertTrue(host.contains("H_SC_PLUGIN_EXISTS = 79"));
        assertTrue(host.contains("\"sc_plugin_exists\""));
        assertTrue(host.contains("superColliderStarted && plugin != null"));
        assertTrue(host.contains("superColliderPluginDir = plugins"));
    }

    @Test
    public void StratumSynthDefIsCompiledWithBothNativeUgensAndSerialStereoRouting() throws Exception {
        String compiler = read("tools/compile-softcut-synthdef.scd");
        assertTrue(compiler.contains("SynthDef(\\NornsStratum"));
        assertTrue(compiler.contains("MiRings.ar("));
        assertTrue(compiler.contains("MiClouds.ar("));
        assertTrue(compiler.contains("ringsOut = [ringsSignal, ringsSignal]"));
        assertTrue(compiler.contains("blended = (ringsOut * (1 - clouds_mix))"));
        assertTrue(compiler.contains("Out.ar(out, wet)"));
        assertTrue(compiler.contains("rings_gate"));
        assertTrue(compiler.contains("clouds_bypass"));
        String validator = read("tools/validate-synthdef.py");
        assertTrue(validator.contains("elif name == \"NornsStratum\":"));
        assertTrue(validator.contains("MiClouds input is not driven by MiRings"));
        assertTrue(validator.contains("Clouds output is not connected to master Out"));
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("NornsStratum.scsyndef"));
        assertTrue(boot.contains("engine.name ~= \"Stratum\""));
        assertTrue(boot.contains("engine.name == \"Stratum\""));
    }

    @Test
    public void MiUgensBuildPinsSourcesAndPatchesAllocationAndDestructorContracts() throws Exception {
        String build = read("tools/build-mi-ugens-android.sh");
        String patch = read("tools/patch-mi-ugens-android.py");
        assertTrue(build.contains("10f6824f68ad63475f67dfe3fe5016e2d2634f84"));
        assertTrue(build.contains("ANDROID_ABI=arm64-v8a"));
        assertTrue(build.contains("build_plugin MiRings"));
        assertTrue(build.contains("build_plugin MiClouds"));
        assertTrue(build.contains("server/plugins"));
        assertTrue(patch.contains("RTFree(unit->mWorld, unit->silence)"));
        assertTrue(patch.contains("RTFree(unit->mWorld, unit->input)"));
        assertTrue(patch.contains("RTFree(unit->mWorld, unit->reverb_buffer)"));
        assertTrue(patch.contains("RTFree(unit->mWorld, unit->large_buffer)"));
        assertTrue(patch.contains("RTFree(unit->mWorld, unit->small_buffer)"));
        assertTrue(patch.contains("new (std::nothrow) clouds::GranularProcessor"));
        assertTrue(patch.contains("SETCALC(ClearUnitOutputs)"));
    }


    @Test
    public void StratumNativeFailureLeavesEngineDisabledAndNeverSelectsJavaOrPolyPerc() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String startMarker = "local function engine_fallback()";
        String endMarker = "\n\nlocal function engine_poll_native";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        String fallback = boot.substring(start, end).replace("local function engine_fallback()", "function engine_fallback()");

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine = {name='Stratum', loaded=true, _native_state='loading', "
                + "_native_generation=4, _native_node=77, _native_pending={{name='rings_gate',a=1,b=0}}}\n"
                + "freed = 0; selected = nil; completed = nil; javaCommands = 0; mode = 1; outputReady = true\n"
                + "STRATUM_LIB = {output = {set_engine_ready = function(value) outputReady = value end}}\n"
                + "params = {get = function(_, name) assert(name == 'output_mode'); return mode end, set = function(_, name, value) assert(name == 'output_mode'); mode = value end}\n"
                + "sc_api = {free=function(id) assert(id == 77); freed = freed + 1 end}\n"
                + "host = {engine_load=function(name) selected = name end, "
                + "log=function() end, engine_cmd=function() javaCommands = javaCommands + 1 end}\n"
                + "engine_finish_load=function(ok) completed = ok end\n"
                + "engine_kolor_cleanup=function() end\n",
                "stratum-fallback-fixture.lua").call();
        globals.load(fallback, "stratum-fallback.lua").call();
        globals.load(
                "engine_fallback()\n"
                + "assert(engine._native_state == 'unavailable')\n"
                + "assert(engine.loaded == false and engine._native_node == nil)\n"
                + "assert(engine._native_generation == 5 and freed == 1)\n"
                + "assert(selected == 'none' and completed == false)\n"
                + "assert(outputReady == false and mode == 2)\n"
                + "assert(javaCommands == 0)\n",
                "stratum-fallback-contract.lua").call();
    }


    @Test
    public void publicStratumCommandListMatchesTheTwentyFiveUpstreamEngineCommands() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String startMarker = "function engine.list_commands()";
        String endMarker = "\n-- ===================================================================== stubs";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        Globals globals = JsePlatform.standardGlobals();
        globals.load("engine = {name='Stratum', _registered_commands=nil}\n",
                "stratum-list-fixture.lua").call();
        globals.load(boot.substring(start, end), "stratum-list-commands.lua").call();
        globals.load(
                "local expected = {'rings_gate','rings_pit','rings_struct','rings_bright','rings_damp',"
                + "'rings_pos','rings_model','rings_poly','rings_easteregg','rings_amp','rings_odd_even',"
                + "'rings_bypass','clouds_pos','clouds_size','clouds_pitch','clouds_dens','clouds_tex',"
                + "'clouds_spread','clouds_fb','clouds_freeze','clouds_rvb','clouds_lofi','clouds_mode',"
                + "'clouds_mix','clouds_bypass'}\n"
                + "local actual = engine.list_commands(); assert(#actual == #expected)\n"
                + "for i = 1, #expected do assert(actual[i] == expected[i]) end\n",
                "stratum-public-command-list.lua").call();
    }


    @Test
    public void nativeOnlyEnginesStaySilentInJavaSynthCoreWithoutReplacingPolyPerc() {
        assertEquals("none", LuaHost.javaEngineSelection("Stratum"));
        assertEquals("none", LuaHost.javaEngineSelection("32Harmony"));
        assertEquals("none", LuaHost.javaEngineSelection("SimpleDelayKolor"));
        assertEquals("PolyPerc", LuaHost.javaEngineSelection("PolyPerc"));
        assertEquals("TestSine", LuaHost.javaEngineSelection("TestSine"));
        assertEquals("FM", LuaHost.javaEngineSelection("FM"));
        assertEquals("none", LuaHost.javaEngineSelection(null));
    }
}
