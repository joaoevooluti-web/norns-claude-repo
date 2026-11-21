package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StratumCompatibilityTest {
    private static final String ROOT = "app/build/compatibility-scripts/stratum/";
    private static final String[] LUA_FILES = {
            "stratum.lua",
            "lib/deja_vu.lua",
            "lib/output.lua",
            "lib/stratum_clouds.lua",
            "lib/stratum_lfo.lua",
            "lib/stratum_mod.lua",
            "lib/stratum_page.lua",
            "lib/stratum_rings.lua",
            "lib/stratum_seq.lua",
            "lib/t_section.lua",
            "lib/x_section.lua"
    };

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing pinned upstream Stratum fixture: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everyPinnedLuaModuleParsesWithTheBundledLuaJRuntime() throws Exception {
        for (String relative : LUA_FILES) {
            Globals globals = JsePlatform.standardGlobals();
            globals.load(read(ROOT + relative), "@upstream/stratum/" + relative);
        }
    }

    @Test
    public void upstreamScriptRequiresBothMiUgensAndDoesNotPretendTheyAreOptionalForAudio() throws Exception {
        String script = read(ROOT + "stratum.lua");
        assertTrue(script.contains("MiRings.so"));
        assertTrue(script.contains("MiClouds.so"));
        assertTrue(script.contains("return any_exists(rings_paths) and any_exists(clouds_paths)"));
        assertTrue(script.contains("if check_mi_ugens() then"));
        assertTrue(script.contains("engine.name = \"Stratum\""));
        assertTrue("MIDI/crow output module must remain part of the upstream script",
                script.contains("include(\"stratum/lib/output\")"));
    }

    @Test
    public void engineClassUsesTheCroneOutputBusAndDeclaresEveryRingsAndCloudsCommand() throws Exception {
        String engine = read(ROOT + "lib/Engine_Stratum.sc");
        assertTrue(engine.contains("context.out_b.index"));
        assertTrue(engine.contains("MiRings.ar("));
        assertTrue(engine.contains("MiClouds.ar("));
        String[] commands = {
                "rings_gate", "rings_pit", "rings_struct", "rings_bright", "rings_damp",
                "rings_pos", "rings_model", "rings_poly", "rings_easteregg", "rings_amp",
                "rings_odd_even", "rings_bypass", "clouds_pos", "clouds_size", "clouds_pitch",
                "clouds_dens", "clouds_tex", "clouds_spread", "clouds_fb", "clouds_freeze",
                "clouds_rvb", "clouds_lofi", "clouds_mode", "clouds_mix", "clouds_bypass"
        };
        for (String command : commands) {
            assertTrue("Engine_Stratum.sc missing command " + command,
                    engine.contains("this.addCommand(\\" + command));
            assertTrue("Engine_Stratum.sc does not map " + command + " to a Synth control",
                    engine.contains("synth.set(\\" + command));
        }
    }

    @Test
    public void upstreamEngineDoesNotSubstitutePolyPercWhenMiUgensAreMissing() throws Exception {
        String script = read(ROOT + "stratum.lua");
        assertTrue(script.contains("if output.engine_ready() and engine[cmd] then"));
        assertTrue(script.contains("if not output.engine_ready() then return end"));
        assertTrue("missing Mi-UGens must leave the script in its documented MIDI/crow-capable mode",
                script.contains("local function check_mi_ugens()"));
        assertTrue("Stratum must not silently change its engine to PolyPerc",
                !script.contains("engine.name = \"PolyPerc\""));
    }

    @Test
    public void nativeEngineClassKeepsRingsIntoCloudsSerialFlowAndStereoOutput() throws Exception {
        String engine = read(ROOT + "lib/Engine_Stratum.sc");
        assertTrue(engine.contains("rings_out = [rings_sig, rings_sig]"));
        assertTrue(engine.contains("MiClouds.ar(\n        rings_out"));
        assertTrue(engine.contains("blend = (rings_out * (1 - clouds_mix)) + (clouds_sig * clouds_mix)"));
        assertTrue(engine.contains("wet = (blend * (1 - clouds_bypass)) + (rings_out * clouds_bypass)"));
        assertTrue(engine.contains("Out.ar(out, wet)"));
    }


    @Test
    public void upstreamScriptSelectsStratumOnlyWhenBothNativePluginsArePresent() throws Exception {
        String script = read(ROOT + "stratum.lua");
        int start = script.indexOf("local function check_mi_ugens()");
        int end = script.indexOf("local MusicUtil", start);
        assertTrue(start >= 0 && end > start);
        String detection = script.substring(start, end);

        Globals available = JsePlatform.standardGlobals();
        available.load("engine = {name = 'none'}\n"
                + "util = {file_exists = function(path) "
                + "return string.match(path, 'MiRings%.so$') ~= nil "
                + "or string.match(path, 'MiClouds%.so$') ~= nil end}\n",
                "stratum-plugin-detection.lua").call();
        available.load(detection, "stratum-detection-upstream.lua").call();
        assertTrue("both plugins should enable the native Stratum engine",
                available.get("engine").get("name").tojstring().equals("Stratum"));

        Globals missingClouds = JsePlatform.standardGlobals();
        missingClouds.load("engine = {name = 'none'}\n"
                + "util = {file_exists = function(path) return string.match(path, 'MiRings%.so$') ~= nil end}\n",
                "stratum-missing-clouds.lua").call();
        missingClouds.load(detection, "stratum-detection-upstream.lua").call();
        assertTrue("a partial plugin install must not claim Rings audio support",
                missingClouds.get("engine").get("name").tojstring().equals("none"));
    }



    @Test(timeout = 15000) public void pinnedStratumInitRunsWithRealModulesAndRoutesRingsCommands() throws Exception {
        final String root = "app/build/compatibility-scripts/stratum/";
        final String script = read(root + "stratum.lua");
        final String boot = read("app/src/main/assets/lua/boot.lua");
        final String dejaVuSource = read(root + "lib/deja_vu.lua");
        final String outputSource = read(root + "lib/output.lua");
        final String cloudsSource = read(root + "lib/stratum_clouds.lua");
        final String lfoSource = read(root + "lib/stratum_lfo.lua");
        final String modSource = read(root + "lib/stratum_mod.lua");
        final String pageSource = read(root + "lib/stratum_page.lua");
        final String ringsSource = read(root + "lib/stratum_rings.lua");
        final String seqSource = read(root + "lib/stratum_seq.lua");
        final String tSectionSource = read(root + "lib/t_section.lua");
        final String xSectionSource = read(root + "lib/x_section.lua");
        final String musicutilSource = read("app/src/main/assets/lua/lib/musicutil.lua");
        final String latticeSource = read("app/src/main/assets/lua/lib/lattice.lua");

        final Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "modules = {}; required = {}; plugins_present = true\n"
                + "engine_calls = {}; clock_tasks = {}; clock_cancelled = {}; clock_next_id = 0\n"
                + "clock_cancel_count = 0; crow_reset_count = 0; internal_clock_started = false\n"
                + "controlspec = {new=function(min,max,warp,step,default,units,quantum,wrap) "
                + "return {minval=min,maxval=max,warp=warp,step=step,default=default,units=units,quantum=quantum,wrap=wrap} end}\n"
                + "util = {file_exists=function(path) return plugins_present and "
                + "(string.match(path,'MiRings%.so$') ~= nil or string.match(path,'MiClouds%.so$') ~= nil) end, "
                + "clamp=function(v,a,b) return math.max(a,math.min(b,v)) end, "
                + "round=function(v,q) q=q or 1; return math.floor(v/q+0.5)*q end, "
                + "linlin=function(a,b,c,d,v) if a==b then return c end; return c+(v-a)*(d-c)/(b-a) end, "
                + "time=function() return 0 end}\n"
                + "tab = {key=function(options,value) for i,v in ipairs(options) do if v==value then return i end end return 1 end}\n"
                + "norns = {}; params_changed_count=0\n"
                + "local function record(name, ...) local t=engine_calls[name] or {}; engine_calls[name]=t; t[#t+1]={...} end\n"
                + "engine = {name='none'}\n"
                + "local commands = {'rings_struct','rings_bright','rings_damp','rings_pos','rings_odd_even','rings_poly',"
                + "'rings_model','rings_easteregg','rings_amp','rings_bypass','rings_pit','rings_gate',"
                + "'clouds_pitch','clouds_pos','clouds_size','clouds_dens','clouds_tex','clouds_spread',"
                + "'clouds_fb','clouds_freeze','clouds_rvb','clouds_lofi','clouds_mode','clouds_mix','clouds_bypass'}\n"
                + "for _,name in ipairs(commands) do engine[name]=function(...) record(name, ...) end end\n"
                + "params = {values={},actions={},_lookup={},hidden={}}\n"
                + "local function add_param(id,name,default,min,max,options,cs)\n"
                + "  local p={id=id,name=name,min=min,max=max,options=options,controlspec=cs}\n"
                + "  function p:get() return params.values[self.id] end\n"
                + "  function p:set(v) params:set(self.id,v) end\n"
                + "  function p:bang() local a=params.actions[self.id]; if a then a(params.values[self.id]) end end\n"
                + "  function p:get_range() return {self.min,self.max} end\n"
                + "  params.values[id]=default; params._lookup[id]=p; return p\n"
                + "end\n"
                + "function params:add_group(...) end; function params:add_separator(...) end\n"
                + "function params:add_number(id,name,min,max,default,formatter) add_param(id,name,default or min,min,max,nil,nil) end\n"
                + "function params:add_control(id,name,spec) add_param(id,name,spec.default or 0,spec.minval,spec.maxval,nil,spec) end\n"
                + "function params:add_option(id,name,options,default) add_param(id,name,default or 1,1,#options,options,nil) end\n"
                + "function params:add_binary(id,name,behavior,default) add_param(id,name,default or 0,0,1,{'off','on'},nil) end\n"
                + "function params:add_text(id,name,default) add_param(id,name,default or '',nil,nil,nil,nil) end\n"
                + "function params:add_trigger(id,name) add_param(id,name,0,0,1,nil,nil) end\n"
                + "function params:add(spec) local c=spec.controlspec; add_param(spec.id,spec.name,c.default or 0,c.minval,c.maxval,nil,c) end\n"
                + "function params:set_action(id,fn) params.actions[id]=fn end\n"
                + "function params:set(id,value) params.values[id]=value; local a=params.actions[id]; if a then a(value) end end\n"
                + "function params:get(id) local v=params.values[id]; if v==nil then return 1 end; return v end\n"
                + "function params:string(id) if id=='clock_source' then return 'internal' end; "
                + "local p=params._lookup[id]; if p and p.options then return p.options[params.values[id]] end; return tostring(params:get(id)) end\n"
                + "function params:lookup_param(id) local p=params._lookup[id]; if not p then error('unknown param '..id) end; return p end\n"
                + "function params:lookup(id) return params._lookup[id] end\n"
                + "function params:hide(id) params.hidden[id]=true; if self.on_change then self.on_change(self) end end; function params:show(id) params.hidden[id]=false; if self.on_change then self.on_change(self) end end\n"
                + "function params:delta(id,d) local p=params._lookup[id]; local v=(params.values[id] or 0)+d; "
                + "if p and p.min~=nil then v=math.max(p.min,math.min(p.max,v)) end; params:set(id,v) end\n"
                + "function params:bang() for id,a in pairs(params.actions) do a(params.values[id]) end end\n"
                + "clock = {internal={start=function() internal_clock_started=true end},"
                + "run=function(fn,...) clock_next_id=clock_next_id+1; clock_tasks[clock_next_id]={fn=fn,args={...}}; return clock_next_id end,"
                + "cancel=function(id) clock_cancelled[id]=true; clock_cancel_count=clock_cancel_count+1 end,"
                + "sleep=function(_) end,sync=function(_) end,get_beat_sec=function() return 0.5 end,"
                + "get_beats=function() return 0 end,time=function() return 0 end,get_tempo=function() return 120 end}\n"
                + "midi={vports={},connect=function(index) return {note_on=function(...) end,note_off=function(...) end,cc=function(...) end} end}\n"
                + "crow={connected=function() return false end,output={{},{},{},{}},reset=function() crow_reset_count=crow_reset_count+1 end}\n"
                + "screen=setmetatable({}, {__index=function() return function(...) end end})\n"
                + "_norns={}; menu_mode=false; menu_callbacks={key=function() end,enc=function() end}\n"
                + "encoder_last_time={}; encoder_sensitivity=1; encoder_acceleration=0\n"
                + "host={now=function() return 1 end,params_changed=function() params_changed_count=params_changed_count+1 end}; params.on_change=function() host.params_changed() end; safe=function(fn,...) local ok=pcall(fn,...); return ok end\n",
                "stratum-init-host-mocks.lua").call();

        assertTrue("the fixture must not predefine the private norns menu object", globals.get("_menu").isnil());
        int menuShimStart = boot.indexOf("params.on_change = function() host.params_changed() end");
        int menuShimEnd = boot.indexOf("\nlocal function add_system_params()", menuShimStart);
        assertTrue("production boot must expose the upstream _menu.rebuild_params hook",
                menuShimStart >= 0 && menuShimEnd > menuShimStart);
        globals.load(boot.substring(menuShimStart, menuShimEnd), "@norns-mobile-menu-compat.lua").call();
        assertTrue("Stratum menu refresh must be bridged to Android params_changed",
                !globals.get("_menu").get("rebuild_params").isnil());

        globals.set("include", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                LuaValue cached = globals.get("modules").get(name);
                if (!cached.isnil()) return cached;
                String source;
                if ("stratum/lib/deja_vu".equals(name)) source = dejaVuSource;
                else if ("stratum/lib/output".equals(name)) source = outputSource;
                else if ("stratum/lib/stratum_clouds".equals(name)) source = cloudsSource;
                else if ("stratum/lib/stratum_lfo".equals(name)) source = lfoSource;
                else if ("stratum/lib/stratum_mod".equals(name)) source = modSource;
                else if ("stratum/lib/stratum_page".equals(name)) source = pageSource;
                else if ("stratum/lib/stratum_rings".equals(name)) source = ringsSource;
                else if ("stratum/lib/stratum_seq".equals(name)) source = seqSource;
                else if ("stratum/lib/t_section".equals(name)) source = tSectionSource;
                else if ("stratum/lib/x_section".equals(name)) source = xSectionSource;
                else throw new AssertionError("Unexpected pinned Stratum include: " + name);
                LuaValue module = globals.load(source, "@upstream/" + name + ".lua").call();
                globals.get("modules").set(name, module);
                return module;
            }
        });
        globals.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                LuaValue cached = globals.get("required").get(name);
                if (!cached.isnil()) return cached;
                String source;
                if ("musicutil".equals(name)) source = musicutilSource;
                else if ("lattice".equals(name)) source = latticeSource;
                else throw new AssertionError("Unexpected Stratum require: " + name);
                LuaValue module = globals.load(source, "@bundled/" + name + ".lua").call();
                globals.get("required").set(name, module);
                return module;
            }
        });

        int keyDispatchStart = boot.indexOf("function _norns.key(n, z)");
        int keyboardDispatchStart = boot.indexOf("function _norns.keyboard_event", keyDispatchStart);
        int encDispatchStart = boot.indexOf("function _norns.enc(n, d)", keyboardDispatchStart);
        int cleanupDispatchStart = boot.indexOf("function _norns.cleanup()", encDispatchStart);
        assertTrue("production Norns key dispatcher must exist",
                keyDispatchStart >= 0 && keyboardDispatchStart > keyDispatchStart);
        assertTrue("production Norns encoder dispatcher must exist",
                encDispatchStart >= 0 && cleanupDispatchStart > encDispatchStart);
        globals.load(boot.substring(keyDispatchStart, keyboardDispatchStart)
                        + boot.substring(encDispatchStart, cleanupDispatchStart),
                "@norns-mobile-input-dispatchers.lua").call();

        globals.load(script, "@upstream/stratum.lua").call();
        assertEquals("Stratum", globals.get("engine").get("name").checkjstring());
        globals.get("init").call();

        assertTrue("Stratum LFO parameter visibility must reach the Android menu bridge",
                globals.get("params_changed_count").checkint() > 0);
        assertTrue("the native Rings/Clouds engine must be marked ready after init",
                globals.get("STRATUM_LIB").get("output").get("engine_ready").call().toboolean());
        assertTrue("internal clock should start for the default clock source",
                globals.get("internal_clock_started").toboolean());
        assertEquals(1, globals.get("params").get("values").get("output_mode").checkint());
        assertEquals(0.45, globals.get("params").get("values").get("clouds_mix").todouble(), 0.0001);
        assertTrue("parameter bang must send Rings controls to the native engine",
                globals.get("engine_calls").get("rings_struct").length() > 0);
        assertTrue("parameter bang must send Clouds mix to the native engine",
                globals.get("engine_calls").get("clouds_mix").length() > 0);

        int generated = globals.get("STRATUM_LIB").get("x_section").get("generate")
                .call(LuaValue.valueOf(1)).checkint();
        assertTrue("X section should generate an in-range Rings MIDI pitch", generated >= 48 && generated <= 72);

        LuaValue output = globals.get("STRATUM_LIB").get("output");
        output.get("fire").call(LuaValue.valueOf("t1"), LuaValue.valueOf(60), LuaValue.valueOf(0.25));
        output.get("fire").call(LuaValue.valueOf("t2"), LuaValue.valueOf(62), LuaValue.valueOf(0.25));
        output.get("fire").call(LuaValue.valueOf("t3"), LuaValue.valueOf(67), LuaValue.valueOf(0.25));
        assertEquals("t1 and t3 should excite the single Rings voice; t2 remains MIDI-only",
                2, globals.get("engine_calls").get("rings_pit").length());
        assertEquals(2, globals.get("engine_calls").get("rings_gate").length());
        assertEquals(1, globals.get("engine_calls").get("rings_gate").get(1).get(1).checkint());
        assertEquals(1, globals.get("engine_calls").get("rings_gate").get(2).get(1).checkint());

        globals.get("midi").get("add").call(LuaValue.valueOf("late-midi-device"));
        globals.get("key").call(LuaValue.valueOf(1), LuaValue.valueOf(1));
        globals.get("enc").call(LuaValue.valueOf(2), LuaValue.valueOf(1));
        globals.get("redraw").call();
        // Retain generation-one callback references and the stable production
        // dispatch entry points that Android input events use.
        globals.load(
                "local first_key=key; old_key_calls=0; old_key=function(...) old_key_calls=old_key_calls+1; return first_key(...) end; key=old_key\n"
                + "local first_enc=enc; old_enc_calls=0; old_enc=function(...) old_enc_calls=old_enc_calls+1; return first_enc(...) end; enc=old_enc\n"
                + "stale_key_dispatch=_norns.key; stale_enc_dispatch=_norns.enc",
                "stratum-retain-generation-one-callbacks.lua").call();
        globals.get("cleanup").call();

        assertEquals("cleanup must reset the Crow outputs", 1, globals.get("crow_reset_count").checkint());
        assertTrue("cleanup must cancel scheduled clock tasks", globals.get("clock_cancel_count").checkint() > 0);
        LuaValue lifecycle = globals.load(
                "local scheduled = 0\n"
                + "for id, _ in pairs(clock_tasks) do scheduled = scheduled + 1 end\n"
                + "assert(scheduled >= 3)\n"
                + "assert(clock_cancelled[1] == true, 't_section loop must be cancelled')\n"
                + "assert(clock_cancelled[3] == true, 'redraw loop must be cancelled')\n"
                + "return scheduled",
                "stratum-cleanup-retires-all-scheduled-work.lua").call();
        // Stratum also schedules finite-delay MIDI retry and gate coroutines. They
        // are one-shot work and should finish naturally, not be required to cancel.
        // Only the persistent t_section and redraw loops must be retired by cleanup.
        assertTrue("Stratum cleanup must retire both persistent loops",
                lifecycle.checkint() >= 3);
        // Reload in the same Lua VM: old generation resources must be retired before new init.
        int firstGenerationTasks = lifecycle.checkint();
        globals.load(script, "@upstream/stratum-reload.lua").call();
        assertEquals("reload must reselect Stratum, not a fallback engine",
                "Stratum", globals.get("engine").get("name").checkjstring());
        globals.get("init").call();
        assertTrue("reloaded generation must become engine-ready",
                globals.get("STRATUM_LIB").get("output").get("engine_ready").call().toboolean());
        globals.load(
                "new_key_calls=0; local active_key=key; key=function(...) new_key_calls=new_key_calls+1; return active_key(...) end\n"
                + "new_enc_calls=0; local active_enc=enc; enc=function(...) new_enc_calls=new_enc_calls+1; return active_enc(...) end",
                "stratum-wrap-generation-two-callbacks.lua").call();
        // A delayed Android event holding generation one's dispatcher must
        // resolve the current callback, never the retained generation-one one.
        globals.get("stale_key_dispatch").call(LuaValue.valueOf(1), LuaValue.valueOf(1));
        globals.get("stale_enc_dispatch").call(LuaValue.valueOf(2), LuaValue.valueOf(1));
        assertEquals(1, globals.get("new_key_calls").checkint());
        assertEquals(1, globals.get("new_enc_calls").checkint());
        assertEquals(0, globals.get("old_key_calls").checkint());
        assertEquals(0, globals.get("old_enc_calls").checkint());
        globals.get("redraw").call();
        globals.get("cleanup").call();
        assertEquals("each generation cleanup must reset Crow exactly once",
                2, globals.get("crow_reset_count").checkint());
        LuaValue reloadLifecycle = globals.load(
                "local scheduled = 0\n"
                + "for id, _ in pairs(clock_tasks) do scheduled = scheduled + 1 end\n"
                + "assert(scheduled >= " + firstGenerationTasks + ")\n"
                + "assert(clock_cancelled[" + (firstGenerationTasks + 1) + "] == true, 'reloaded t_section loop must be cancelled')\n"
                + "assert(clock_cancelled[" + (firstGenerationTasks + 3) + "] == true, 'reloaded redraw loop must be cancelled')\n"
                + "return scheduled",
                "stratum-reload-retires-all-scheduled-work.lua").call();
        assertTrue("reload must leave both persistent loops retired",
                reloadLifecycle.checkint() >= firstGenerationTasks);
        // The upstream script's old function is not the Android dispatch gate;
        // cross-generation dispatch is exercised by NornsScriptLifecycleTest.
    }

}
