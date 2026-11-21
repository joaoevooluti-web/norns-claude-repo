package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ExternalNornsScriptCompatibilityTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing pinned upstream fixture: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void pinned32HarmonyScriptParsesAndExecutesItsTopLevelSetup() throws Exception {
        String source = read("app/build/compatibility-scripts/32-harmony/32-harmony.lua");
        Globals globals = JsePlatform.standardGlobals();
        globals.load("engine = {}\n", "32-harmony-runtime-fixture.lua").call();
        globals.load(source, "@upstream/32-harmony.lua").call();
        assertEquals("32Harmony", globals.get("engine").get("name").checkjstring());
        assertTrue("32Harmony script must define init()", globals.get("init").isfunction());
    }

    @Test public void pinnedOoooooScriptParsesAndExecutesTopLevelWithNornsModuleResolution() throws Exception {
        String source = read("app/build/compatibility-scripts/oooooo/oooooo.lua");
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine = {}\n"
                + "_path = {data='/tmp/data/', audio='/tmp/audio/'}\n"
                + "module_stub = {}\n"
                + "require = function(name) assert(name == 'formatters' or name == 'musicutil'); return module_stub end\n"
                + "include = function(name) assert(name == 'oooooo/lib/grido' or name == 'oooooo/lib/monosong'); return module_stub end\n",
                "oooooo-runtime-fixture.lua").call();
        globals.load(source, "@upstream/oooooo.lua").call();
        assertEquals("SimpleDelay", globals.get("engine").get("name").checkjstring());
        assertTrue("oooooo script must define init()", globals.get("init").isfunction());
    }

    @Test public void pinnedCirclesScriptRunsAgainstBundledNornsLibrariesAndRealCircleEngine() throws Exception {
        String script = read("app/build/compatibility-scripts/circles/circles.lua");
        String library = read("app/build/compatibility-scripts/circles/lib/libCircles.lua");
        String helpers = read("app/build/compatibility-scripts/circles/lib/math_helpers.lua");
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine = {release=function(v) last_release=v end, amp=function(v) last_amp=v end, "
                + "pw=function(v) last_pw=v end, hz=function(v) last_hz=v end, cutoff=function() end}\n"
                + "clock = {transport={}, run=function() return 1 end}\n"
                + "params = {get=function(self,id) if id=='root_note' then return 60 elseif id=='scale_mode' then return 5 "
                + "elseif id=='circles_output' then return 1 elseif id=='radius_affects' then return 1 end end}\n"
                + "musicutil = {SCALES={{name='major'}}, generate_scale=function() return {60,62,64,65,67,69,71,72} end, "
                + "note_num_to_name=function() return 'C4' end}\n"
                + "ui = {Message={new=function(lines) return {redraw=function() end} end}}\n"
                + "require = function(name) if name=='musicutil' then return musicutil elseif name=='ui' then return ui end error(name) end\n",
                "circles-runtime-fixture.lua").call();

        final LuaValue mathHelpers = globals.load(helpers, "@upstream/circles/lib/math_helpers.lua").call();
        globals.set("math_helpers_module", mathHelpers);
        globals.set("include", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                if ("lib/math_helpers".equals(name)) return mathHelpers;
                if ("lib/libCircles".equals(name)) {
                    LuaValue module = globals.load(library, "@upstream/circles/lib/libCircles.lua").call();
                    globals.set("circle_engine", module);
                    return module;
                }
                throw new AssertionError("Unexpected circles include: " + name);
            }
        });

        globals.load(script, "@upstream/circles/circles.lua").call();
        assertEquals("PolyPerc", globals.get("engine").get("name").checkjstring());
        assertTrue(globals.get("init").isfunction());
        globals.get("build_scale_notes").call();
        LuaValue note = globals.get("noteForCircle").call(
                globals.load("return {x=64,y=32,r=12}", "circle-fixture.lua").call());
        assertEquals(65, note.checkint());
        globals.get("handleCircleBurst").call(
                globals.load("return {x=64,y=32,r=12}", "circle-fixture.lua").call());
        assertTrue("audio output path must dispatch a note frequency", globals.get("last_hz").isnumber());
        assertTrue(globals.get("last_release").isnumber());
        assertTrue(globals.get("last_amp").isnumber());

        LuaTable engine = globals.get("circle_engine").checktable();
        globals.set("circle_engine_under_test", engine);
        globals.load(
                "circle_engine_under_test.removeAllCircles()\n"
                + "circle_engine_under_test.addCircle(10,20)\n"
                + "local state = circle_engine_under_test.getState()\n"
                + "assert(#state.circles == 1 and state.cursor.x == 64 and state.cursor.y == 32)\n"
                + "circle_engine_under_test.setState({circles={{x=30,y=40,r=7}},cursor={x=12,y=13}})\n"
                + "local restored = circle_engine_under_test.getState()\n"
                + "assert(#restored.circles == 1 and restored.circles[1].r == 7)\n"
                + "assert(restored.cursor.x == 12 and restored.cursor.y == 13)\n",
                "circles-engine-state-contract.lua").call();
    }
    @Test public void pinned32HarmonyInitializesWithRealParamSetAndRoutesActionsToEngine() throws Exception {
        String source = read("app/build/compatibility-scripts/32-harmony/32-harmony.lua");
        final String utilSource = read("app/src/main/assets/lua/lib/util.lua");
        final String controlspecSource = read("app/src/main/assets/lua/lib/controlspec.lua");
        final String paramsetSource = read("app/src/main/assets/lua/lib/paramset.lua");
        final Globals globals = JsePlatform.standardGlobals();
        final LuaTable loaded = globals.get("package").get("loaded").checktable();
        globals.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                LuaValue cached = loaded.get(name);
                if (!cached.isnil()) return cached;
                String moduleSource;
                if ("util".equals(name)) moduleSource = utilSource;
                else if ("controlspec".equals(name)) moduleSource = controlspecSource;
                else if ("paramset".equals(name)) moduleSource = paramsetSource;
                else throw new AssertionError("Unexpected module in 32Harmony: " + name);
                LuaValue module = globals.load(moduleSource, "@bundled/" + name + ".lua").call();
                loaded.set(name, module);
                return module;
            }
        });
        LuaValue controlspec = globals.get("require").call(LuaValue.valueOf("controlspec"));
        globals.set("controlspec", controlspec);
        LuaValue paramset = globals.get("require").call(LuaValue.valueOf("paramset"));
        globals.set("params", paramset.get("new").call());
        globals.load(
                "engine = setmetatable({commands={}}, {__index=function(self,name) "
                + "return function(value) self.commands[name]=value end end})\n"
                + "poll = {set=function(name) return {start=function() end, stop=function() end} end}\n"
                + "metro = {init=function() return {start=function() end, stop=function() end} end}\n"
                + "screen = setmetatable({}, {__index=function() return function(...) end end})\n",
                "32-harmony-init-fixture.lua").call();

        globals.load(source, "@upstream/32-harmony.lua").call();
        globals.get("init").call();
        assertEquals("taper", globals.get("params").get("lookup_param")
                .call(globals.get("params"), LuaValue.valueOf("rate")).get("kind").checkjstring());
        assertEquals(1.0, globals.get("params").get("get")
                .call(globals.get("params"), LuaValue.valueOf("rate")).checkdouble(), 0.01);

        globals.get("params").get("set").call(globals.get("params"),
                LuaValue.valueOf("rate"), LuaValue.valueOf(250));
        globals.get("params").get("set").call(globals.get("params"),
                LuaValue.valueOf("pitch"), LuaValue.valueOf(100));
        globals.get("params").get("set").call(globals.get("params"),
                LuaValue.valueOf("quantize"), LuaValue.valueOf(0.8));
        globals.get("init_scale").call();
        LuaValue commands = globals.get("engine").get("commands");
        assertEquals(250.0, commands.get("rate1In").checkdouble(), 0.01);
        assertEquals(250.0, commands.get("rate2In").checkdouble(), 0.01);
        assertEquals(100.0, commands.get("rootIn").checkdouble(), 0.01);
        assertTrue(commands.get("quantAmtIn").isnumber());
        assertTrue(commands.get("step0In").isnumber());
        globals.get("redraw").call();
        globals.get("cleanup").call();
    }


    @Test public void luaFileReadAcceptsAllAliasUsedByPinnedGlyphsModule() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue("production sandbox must map *all to *a",
                boot.contains("if fmt == \"all\" then fmt = \"a\" end"));
        File fixture = File.createTempFile("norns-glyphs-", ".json");
        try {
            Files.write(fixture.toPath(), "glyph-data".getBytes(StandardCharsets.UTF_8));
            Globals globals = JsePlatform.standardGlobals();
            globals.load(
                    "local native_open = io.open\n"
                    + "io.open = function(path, mode)\n"
                    + "  local file, err = native_open(path, mode); if not file then return nil, err end\n"
                    + "  local proxy = {}\n"
                    + "  function proxy:read(fmt) if fmt == '*all' then fmt = '*a' end; return file:read(fmt) end\n"
                    + "  function proxy:close() return file:close() end\n"
                    + "  return proxy\n"
                    + "end\n", "io-all-alias-test.lua").call();
            globals.set("fixture_path", LuaValue.valueOf(fixture.getAbsolutePath()));
            LuaValue content = globals.load(
                    "local f = assert(io.open(fixture_path, 'rb')); "
                    + "local data = f:read('*all'); f:close(); return data",
                    "io-all-alias-read.lua").call();
            assertEquals("glyph-data", content.checkjstring());
        } finally {
            fixture.delete();
        }
    }

    @Test public void pinnedOoooooSoftcutCallsAreAllPresentInTheMobileNornsApi() throws Exception {
        String source = read("app/build/compatibility-scripts/oooooo/oooooo.lua");
        String boot = read("app/src/main/assets/lua/boot.lua");
        String[] softcutCalls = {
                "buffer", "buffer_clear", "buffer_clear_region_channel", "buffer_copy_mono",
                "buffer_read_mono", "buffer_write_mono", "enable", "event_phase", "fade_time",
                "level", "level_input_cut", "level_slew_time", "loop", "loop_end", "loop_start",
                "pan", "phase_quant", "play", "poll_start_phase", "position", "post_filter_dry",
                "post_filter_fc", "post_filter_lp", "post_filter_rq", "pre_filter_dry",
                "pre_filter_fc", "pre_filter_lp", "pre_filter_rq", "pre_level", "rate",
                "rate_slew_time", "rec", "rec_level", "recpre_slew_time"
        };
        assertTrue("oooooo must retain its six-loop initialization", source.contains("init_loops(7)"));
        assertTrue("default engine must remain the onset detector", source.contains("engine.name=\"SimpleDelay\""));
        assertTrue("record threshold must reach the engine", source.contains("engine.threshold(x)"));
        for (String name : softcutCalls) {
            assertTrue("oooooo calls softcut." + name + " but boot.lua does not expose it",
                    boot.contains("function softcut." + name + "("));
        }
    }



    @Test(timeout = 15000) public void pinnedOoooooInitRunsWithUpstreamModulesAndSafeSixVoiceSoftcutMocks() throws Exception {
        final String root = "app/build/compatibility-scripts/oooooo/";
        final String script = read(root + "oooooo.lua");
        final String gridoSource = read(root + "lib/grido.lua");
        final String monosongSource = read(root + "lib/monosong.lua");
        final String jsonSource = read(root + "lib/json.lua");
        final String glyphsSource = read(root + "lib/glyphs.lua");
        final String tableAddonsSource = read(root + "lib/table_addons.lua");
        final String latticeSource = read(root + "lib/lattice.lua");
        final String musicutilSource = read(root + "lib/musicutil.lua");
        final String formattersSource = read("app/src/main/assets/lua/lib/formatters.lua");
        final String glyphsJson = read(root + "lib/glyphs.json");
        final File glyphsDir = new File("/tmp/code/oooooo/lib");
        assertTrue("create upstream glyph data directory", glyphsDir.mkdirs() || glyphsDir.isDirectory());
        Files.write(new File(glyphsDir, "glyphs.json").toPath(), glyphsJson.getBytes(StandardCharsets.UTF_8));
        final Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "local native_open = io.open\n"
                + "io.open = function(path, mode)\n"
                + "  local file, err = native_open(path, mode); if not file then return nil, err end\n"
                + "  local proxy = {}\n"
                + "  function proxy:read(fmt) if fmt == '*all' then fmt = '*a' end; return file:read(fmt) end\n"
                + "  function proxy:close() return file:close() end\n"
                + "  return proxy\n"
                + "end\n", "luaj-upstream-io-compat.lua").call();
        assertTrue("production FileR must normalize LuaJ *all reads",
                read("app/src/main/assets/lua/boot.lua").contains("if fmt == \"all\" then fmt = \"a\" end"));
        globals.load(
                "modules = {}\n"
                + "controlspec = {new=function(min,max,warp,step,default,units,quantum) "
                + "return {minval=min,maxval=max,default=default,units=units} end}\n"
                + "util = {file_exists=function() return false end, make_dir=function() end, "
                + "clamp=function(v,a,b) return math.max(a,math.min(b,v)) end, "
                + "round=function(v,q) q=q or 1; return math.floor(v/q+0.5)*q end}\n"
                + "params_values = {}; params_actions = {}\n"
                + "params = {}\n"
                + "function params:add_group(...) end; function params:add_separator(...) end\n"
                + "function params:add_option(id,name,options,default) params_values[id]=default or 1 end\n"
                + "function params:add_control(id,name,spec) params_values[id]=spec.default or 1 end\n"
                + "function params:add_file(id,name,default) params_values[id]=default or '' end\n"
                + "function params:add_text(id,name,default) params_values[id]=default or '' end\n"
                + "function params:add(spec) params_values[spec.id]=spec.default or 1 end\n"
                + "function params:set_action(id,fn) params_actions[id]=fn end\n"
                + "function params:set(id,value) params_values[id]=value end\n"
                + "function params:get(id) local v=params_values[id]; if v==nil then return 1 end; return v end\n"
                + "function params:hide(id) end\n"
                + "function params:delta(id,d) params_values[id]=(params_values[id] or 0)+d end\n"
                + "softcut_calls = {}\n"
                + "softcut = setmetatable({}, {__index=function(_,name) return function(...) "
                + "local list=softcut_calls[name] or {}; softcut_calls[name]=list; "
                + "list[#list+1]={...}; return true end end})\n"
                + "audio = {level_adc=function() end, level_adc_cut=function() end, "
                + "level_tape_cut=function() end}\n"
                + "clock = {get_tempo=function() return 120 end, get_beat_sec=function() return 0.5 end, "
                + "get_beats=function() return 0 end, run=function() return 1 end, cancel=function() end, "
                + "sleep=function() end, sync=function() end}\n"
                + "metro = {init=function() return {start=function() end,stop=function() end} end}\n"
                + "grid = {connect=function() return {cols=16,led=function() end,refresh=function() end} end}\n"
                + "midi = {devices={}}; osc = {}; _path={data='/tmp/data/',audio='/tmp/audio/',code='/tmp/code/'}\n"
                + "screen = setmetatable({}, {__index=function() return function(...) end end})\n"
                + "engine = setmetatable({name='none'}, {__index=function() return function() return true end end})\n"
                + "setup_sharing=function() end; params_read_silent=function() end\n"
                + "redraw=function() end; build_ji_rates=function() end\n"
                + "update_softcut_input=function() end; update_softcut_input_lag=function() end\n",
                "oooooo-init-host-mocks.lua").call();

        globals.set("include", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                LuaValue cached = globals.get("modules").get(name);
                if (!cached.isnil()) return cached;
                String source;
                if ("oooooo/lib/grido".equals(name)) source = gridoSource;
                else if ("oooooo/lib/monosong".equals(name)) source = monosongSource;
                else if ("oooooo/lib/json".equals(name)) source = jsonSource;
                else if ("oooooo/lib/glyphs".equals(name)) source = glyphsSource;
                else if ("oooooo/lib/table_addons".equals(name)) source = tableAddonsSource;
                else if ("oooooo/lib/lattice".equals(name)) source = latticeSource;
                else throw new AssertionError("Unexpected pinned oooooo include: " + name);
                LuaValue module = globals.load(source, "@upstream/" + name + ".lua").call();
                globals.get("modules").set(name, module);
                return module;
            }
        });
        globals.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                if ("controlspec".equals(name)) return globals.get("controlspec");
                if ("formatters".equals(name)) return globals.load(formattersSource, "@bundled/formatters.lua").call();
                if ("musicutil".equals(name) || "oooooo/lib/musicutil".equals(name))
                    return globals.load(musicutilSource, "@upstream/musicutil.lua").call();
                if ("sequins".equals(name))
                    return globals.load("return function(value) return function() return value end end",
                            "sequins-test-stub.lua").call();
                throw new AssertionError("Unexpected oooooo require: " + name);
            }
        });

        globals.load(script, "@upstream/oooooo.lua").call();
        globals.get("init").call();

        assertEquals("SimpleDelay", globals.get("engine").get("name").checkjstring());
        assertEquals("six loop voices plus the all-loops sentinel", 7, globals.get("uP").length());
        LuaValue calls = globals.get("softcut_calls");
        assertEquals("all six voices must be enabled", 6, calls.get("enable").length());
        assertEquals("all six voices must remain stopped", 6, calls.get("play").length());
        assertEquals("all six voices must remain out of record", 6, calls.get("rec").length());
        for (int i = 1; i <= 6; i++) {
            assertEquals(i, calls.get("enable").get(i).get(1).checkint());
            assertEquals(1, calls.get("enable").get(i).get(2).checkint());
            assertEquals(i, calls.get("play").get(i).get(1).checkint());
            assertEquals(0, calls.get("play").get(i).get(2).checkint());
            assertEquals(i, calls.get("rec").get(i).get(1).checkint());
            assertEquals(0, calls.get("rec").get(i).get(2).checkint());
        }
        assertEquals(1, calls.get("poll_start_phase").length());
        assertEquals(1, calls.get("event_phase").length());
        assertTrue("real upstream grido module must have initialized",
                globals.get("oooooo_grid").istable());
        assertTrue("real upstream monosong module must have initialized",
                globals.get("monosong").istable());
    }



    @Test public void upstreamSimpleDelayKolorRequirementsAreDocumentedAndNotFalselyAdvertised() throws Exception {
        String kolor = read("app/build/compatibility-scripts/oooooo/lib/Engine_SimpleDelayKolor.sc");
        String boot = read("app/src/main/assets/lua/boot.lua");
        String core = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        assertTrue(kolor.contains("Buffer.read(context.server"));
        assertTrue(kolor.contains("/home/we/dust/code/kolor/samples/silence.wav"));
        assertTrue(kolor.contains("SynthDef(\"player\""));
        assertTrue(kolor.contains("Array.fill(12"));
        assertTrue(kolor.contains("this.addCommand(\"kolorsample\""));
        assertTrue(kolor.contains("this.addCommand(\"kolorplay\""));
        assertTrue(kolor.contains("sampleStartMin") && kolor.contains("delayFeedbackMax"));
        assertTrue("Kolor must use the native Android sample-player engine",
                boot.contains("engine.name == \"SimpleDelayKolor\""));
        assertTrue(boot.contains("NornsKolorPlayer"));
        assertTrue(boot.contains("kolorsample") && boot.contains("kolorplay"));
        assertTrue(boot.contains("host.resolve_sample_path(path_value)"));
        assertFalse("Kolor is native scsynth, not a Java fallback engine", core.contains("SimpleDelayKolor"));
    }


}
