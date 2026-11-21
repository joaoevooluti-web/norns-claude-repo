package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class OscLuaRuntimeTest {
    private static Globals runtime() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        if (!file.isFile()) throw new AssertionError("Missing boot.lua");
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String startMarker = "function _norns.osc_event(path, args, from)";
        String endMarker = "-- norns listens on UDP 10111";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue("production OSC dispatcher must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "calls = {}\n"
                + "osc = {event = function(path,args,from) calls[#calls+1] = 'user:' .. path end}\n"
                + "paramset = {sets = {}}\n"
                + "_norns = {"
                + "key = function(n,z) calls[#calls+1] = 'key:' .. n .. ':' .. z end,"
                + "enc = function(n,d) calls[#calls+1] = 'enc:' .. n .. ':' .. d end"
                + "}\n"
                + "keyboard = {process = function(device,code,value) "
                + "calls[#calls+1] = 'brd:' .. device .. ':' .. code .. ':' .. value; return true end}\n"
                + "safe = function(fn, ...) local ok = pcall(fn, ...); return ok end\n",
                "osc-runtime-fixture.lua").call();
        globals.load(boot.substring(start, end), "norns-osc-dispatch.lua").call();
        return globals;
    }

    @Test public void parameterOscRoutesSupportDefaultAndNamedParamSets() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local defaultValue, engineValue\n"
                + "paramset.sets[''] = {lookup_param=function(self,id) "
                + "assert(id == 'cutoff'); return {id=id,set=function(self,v) defaultValue=v end} end}\n"
                + "paramset.sets['engine'] = {lookup_param=function(self,id) "
                + "assert(id == 'resonance'); return {id=id,set=function(self,v) engineValue=v end} end}\n"
                + "_norns.osc_event('/param/cutoff', {1200}, {'127.0.0.1',9000})\n"
                + "_norns.osc_event('/param/engine/resonance', {0.75}, {'127.0.0.1',9000})\n"
                + "assert(defaultValue == 1200 and engineValue == 0.75)\n"
                + "assert(calls[1] == 'user:/param/cutoff' and calls[2] == 'user:/param/engine/resonance')\n"
                + "return true",
                "osc-param-routes.lua");
    }

    @Test public void remoteOscRoutesDispatchKeysEncodersAndKeyboardCodes() throws Exception {
        Globals globals = runtime();
        run(globals,
                "_norns.osc_event('/remote/key', {2,1}, {'127.0.0.1',9000})\n"
                + "_norns.osc_event('/remote/enc', {3,-4}, {'127.0.0.1',9000})\n"
                + "_norns.osc_event('/remote/brd', {30,1}, {'127.0.0.1',9000})\n"
                + "_norns.osc_event('/remote/key/1', {0}, {'127.0.0.1',9000})\n"
                + "_norns.osc_event('/remote/enc/2', {-7}, {'127.0.0.1',9000})\n"
                + "assert(calls[1] == 'user:/remote/key' and calls[2] == 'key:2:1')\n"
                + "assert(calls[3] == 'user:/remote/enc' and calls[4] == 'enc:3:-4')\n"
                + "assert(calls[5] == 'user:/remote/brd' and calls[6] == 'brd:1:30:1')\n"
                + "assert(calls[7] == 'user:/remote/key/1' and calls[8] == 'key:1:0')\n"
                + "assert(calls[9] == 'user:/remote/enc/2' and calls[10] == 'enc:2:-7')\n"
                + "return true",
                "osc-remote-routes.lua");
    }

    @Test public void remoteKeyboardMapsEvdevCodesToNornsLogicalNames() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String startMarker = "function keyboard.process(device, code, value)";
        String endMarker = "gamepad = {";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "hid = {codes={KEY_A=30,KEY_ESC=1,KEY_LEFTSHIFT=42}}\n"
                + "keyboard = {code_to_char=function() return nil end,shift=function() return false end,"
                + "alt=function() return false end,ctrl=function() return false end,meta=function() return false end}\n"
                + "_norns = {keyboard_event=function(code,value) last_code=code; last_value=value; return true end}\n",
                "osc-keyboard-fixture.lua").call();
        globals.load(boot.substring(start, end), "norns-keyboard-process.lua").call();
        run(globals,
                "assert(keyboard.process(1,30,1) and last_code == 'A' and last_value == 1)\n"
                + "assert(keyboard.process(1,1,0) and last_code == 'ESCAPE' and last_value == 0)\n"
                + "assert(keyboard.process(1,42,1) and last_code == 'LEFTSHIFT')\n"
                + "return true",
                "osc-keyboard-evdev-map.lua");
    }

    private static void run(Globals globals, String source, String name) {
        LuaValue result = globals.load(source, name).call();
        assertTrue(name + " should return true", result.toboolean());
    }
    @Test public void remoteOscRejectsNonFiniteFractionalAndOutOfRangeValues() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local before = #calls\n"
                + "assert(_norns.osc_event('/remote/key', {0/0,1}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/key', {math.huge,1}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/key', {1.5,1}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/key', {4,1}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/key', {1,2}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/enc', {2,math.huge}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/brd', {30,0/0}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/brd', {768,1}, {}) == false)\n"
                + "assert(_norns.osc_event('/remote/brd', {30,2}, {}) == false)\n"
                + "local keys, encoders, board = 0,0,0\n"
                + "for i=before+1,#calls do\n"
                + "  if string.sub(calls[i],1,4)=='key:' then keys=keys+1 end\n"
                + "  if string.sub(calls[i],1,4)=='enc:' then encoders=encoders+1 end\n"
                + "  if string.sub(calls[i],1,4)=='brd:' then board=board+1 end\n"
                + "end\n"
                + "assert(keys==0 and encoders==0 and board==0, 'invalid remote OSC data must never reach input callbacks')\n"
                + "return true",
                "osc-remote-invalid-values.lua");
    }

}
