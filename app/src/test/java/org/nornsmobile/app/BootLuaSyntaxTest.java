package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ensures the bundled Lua runtime remains syntactically valid during engine changes. */
public class BootLuaSyntaxTest {
    @Test
    public void bootScriptParsesWithLuaJ() throws Exception {
        File sourceFile = new File("src/main/assets/lua/boot.lua");
        if (!sourceFile.isFile()) {
            sourceFile = new File("app/src/main/assets/lua/boot.lua");
        }
        if (!sourceFile.isFile()) {
            throw new AssertionError("Could not locate app/src/main/assets/lua/boot.lua");
        }
        String source = new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8);
        Globals globals = JsePlatform.standardGlobals();
        assertNotNull(globals.load(source, "boot.lua"));
    }
    @Test
    public void nativeOutputBridgeArgumentsAreStrictBooleans() throws Exception {
        File sourceFile = new File("src/main/assets/lua/boot.lua");
        if (!sourceFile.isFile()) {
            sourceFile = new File("app/src/main/assets/lua/boot.lua");
        }
        if (!sourceFile.isFile()) {
            throw new AssertionError("Could not locate app/src/main/assets/lua/boot.lua");
        }
        String source = new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8);

        // LuaHost usa optboolean(); 0/1 da API norns não pode cruzar a ponte como número.
        assertTrue(source.contains("enabled = sc_flag(enabled) ~= 0"));
        Pattern call = Pattern.compile(
                "host\\.sc_softcut_output\\(([^,\\r\n]+),\\s*([^\\)\\r\n]+)\\)");
        Matcher matcher = call.matcher(source);
        int calls = 0;
        while (matcher.find()) {
            String enabled = matcher.group(1).trim();
            String validated = matcher.group(2).trim();
            assertTrue("first arg must be boolean: " + enabled,
                    enabled.equals("false") || enabled.equals("enabled"));
            assertTrue("second arg must be boolean: " + validated,
                    validated.equals("false")
                            || validated.equals("softcut._native_output_validated == true"));
            calls++;
        }
        assertEquals("reset, failure and ownership paths must all be covered", 3, calls);
    }

    @Test
    public void nativeSoftcutUsesPrivateSuperColliderApiReference() throws Exception {
        File sourceFile = new File("src/main/assets/lua/boot.lua");
        if (!sourceFile.isFile()) {
            sourceFile = new File("app/src/main/assets/lua/boot.lua");
        }
        String source = new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(source.contains("local sc_api\nsc_api = {"));
        assertTrue("sc_api must be in lexical scope before closure initializers",
                !source.contains("local sc_api = {"));
        assertTrue(source.contains("sc = sc_api"));
        assertTrue(source.contains("sc_api.buffer_free(softcut._native_buffers[i])"));
        assertTrue(source.contains("if not sc_api.is_running()"));
        assertTrue(source.contains("sc_api.new(\"SoftcutVoice\""));
        assertTrue("internal boot code must not dereference the script-overridable global sc",
                !source.matches("(?s).*\\bsc\\.[A-Za-z_][A-Za-z0-9_]*.*"));
    }

    @Test
    public void paramDefaultWithoutIdRunsActionsNeededByCircles() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        LuaValue loaded = globals.get("package").get("loaded");
        loaded.set("util", globals.load(readLuaAsset("lib/util.lua"), "util.lua").call());
        loaded.set("controlspec", globals.load(readLuaAsset("lib/controlspec.lua"), "controlspec.lua").call());

        LuaValue paramSet = globals.load(readLuaAsset("lib/paramset.lua"), "paramset.lua").call();
        globals.set("ParamSetUnderTest", paramSet);
        LuaValue result = globals.load(
                "local ps = ParamSetUnderTest.new('circles-regression', 'circles-regression')\n"
                + "local scale_notes = nil\n"
                + "local calls = 0\n"
                + "local scale = ps:add_number('scale', 'Scale', 1, 4, 2)\n"
                + "scale:set_action(function(value) calls = calls + 1; scale_notes = {value, value + 1} end)\n"
                + "ps:default()\n"
                + "assert(scale_notes ~= nil, 'params:default() must invoke the scale callback')\n"
                + "assert(#scale_notes == 2, 'scale callback must initialize scale_notes')\n"
                + "assert(calls == 1, 'default without ID must bang each registered parameter once')\n"
                + "assert(ps:get('scale') == 2, 'default must preserve the parameter default value')\n"
                + "return calls\n",
                "paramset-default-circles-regression.lua").call();
        assertEquals(1, result.toint());
    }


    @Test
    public void formattersLibraryLoadsWithBundledLuaDependencies() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        LuaValue loaded = globals.get("package").get("loaded");
        LuaValue util = globals.load(readLuaAsset("lib/util.lua"), "util.lua").call();
        loaded.set("util", util);
        globals.set("util", util);
        loaded.set("controlspec", globals.load(readLuaAsset("lib/controlspec.lua"), "controlspec.lua").call());
        String boot = readLuaAsset("boot.lua");
        assertTrue(boot.contains("string.sub(rel, 1, 4) == \"lib/\""));
        assertTrue(boot.contains("host.read_asset(\"lua/\" .. rel .. \".lua\")"));
        LuaValue formatters = globals.load(
                readLuaAsset("lib/formatters.lua"), "lib/formatters.lua").call();
        assertTrue(formatters.get("format_freq_raw").isfunction());
        assertTrue(formatters.get("format_secs_raw").isfunction());
        assertEquals("440 Hz", formatters.get("format_freq_raw")
                .call(LuaValue.valueOf(440)).tojstring());
    }

    @Test
    public void bootRequireResolvesBundledFormattersLibrary() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local loaded = package.loaded");
        int end = boot.indexOf("local included = {}", start);
        assertTrue("boot module resolver must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        LuaValue loaded = globals.get("package").get("loaded");
        LuaValue util = globals.load(readLuaAsset("lib/util.lua"), "util.lua").call();
        loaded.set("util", util);
        globals.set("util", util);
        loaded.set("controlspec",
                globals.load(readLuaAsset("lib/controlspec.lua"), "controlspec.lua").call());
        globals.set("formattersSource", LuaValue.valueOf(readLuaAsset("lib/formatters.lua")));
        globals.load(
                "host = {read_asset = function(path) "
                + "if path == 'lua/lib/formatters.lua' then return formattersSource end "
                + "return nil end}\n"
                + "norns = {state = {path = ''}}\n"
                + "_path = {code = ''}\n",
                "formatters-host-fixture.lua").call();
        globals.load(boot.substring(start, end), "boot-module-resolver.lua").call();

        LuaValue result = globals.load(
                "local f = require('formatters')\n"
                + "assert(type(f) == 'table', 'formatters must load through boot require')\n"
                + "assert(f.format_freq_raw(440) == '440 Hz')\n"
                + "assert(f.format_secs_raw(0.5) == '0.50 s')\n"
                + "local p = {controlspect = nil, controlspec = {units = 'Hz'}, "
                + "get = function() return 0.5 end}\n"
                + "assert(f.percentage(p) == '50 %')\n"
                + "assert(f.unipolar_as_percentage(p) == '50 %')\n"
                + "assert(f.bipolar_as_percentage(p) == '50 %')\n"
                + "assert(f.secs_as_ms(p) == '500 ms')\n"
                + "assert(f.unipolar_as_true_false(p) == 'false')\n"
                + "assert(f.unipolar_as_enabled_disabled(p) == 'disabled')\n"
                + "assert(type(f.bipolar_as_pan_widget(p)) == 'string')\n"
                + "assert(type(f.unipolar_as_multimode_filter_freq(p)) == 'string')\n"
                + "assert(type(f.round(0.1)(p)) == 'string')\n"
                + "assert(type(f.format_freq(p)) == 'string')\n"
                + "assert(type(f.format_secs(p)) == 'string')\n"
                + "return true",
                "formatters-boot-require-regression.lua").call();
        assertTrue("bundled formatters must load and expose the norns API", result.toboolean());
    }

    @Test
    public void ioLinesClosesItsFileWhenIteratorReachesEof() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("function io.lines(path, fmt)");
        int end = boot.indexOf("\nend", start);
        assertTrue("io.lines implementation must exist", start >= 0 && end > start);
        String implementation = boot.substring(start, end + 4);
        assertTrue("iterator must read from the opened file", implementation.contains("f:read(fmt or \"l\")"));
        assertTrue("iterator must close the file at EOF", implementation.contains("if line == nil then"));
        assertTrue("EOF path must close before marking iterator complete",
                implementation.contains("f:close()") && implementation.contains("closed = true"));
        assertTrue("iterator must remain exhausted after EOF", implementation.contains("if closed or not f then return nil end"));
    }

    @Test
    public void fileLinesClosesItsHandleWhenIteratorReachesEof() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local FileR = {}");
        int end = boot.indexOf("local FileW = {}", start);
        assertTrue("FileR implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        LuaValue fileR = globals.load(
                boot.substring(start, end) + "\nreturn FileR",
                "FileR-under-test.lua").call();
        globals.set("FileRUnderTest", fileR);
        LuaValue result = globals.load(
                "local handle = setmetatable({data = 'one\\ntwo', pos = 1, closed = false}, FileRUnderTest)\n"
                + "local iter = handle:lines()\n"
                + "assert(iter() == 'one')\n"
                + "assert(iter() == 'two')\n"
                + "assert(iter() == nil)\n"
                + "assert(handle.closed == true, 'EOF must close the file handle')\n"
                + "assert(iter() == nil, 'exhausted iterator must stay exhausted')\n"
                + "return handle.closed",
                "file-lines-runtime-regression.lua").call();
        assertTrue("EOF must close the actual Lua file handle", result.toboolean());
    }


    @Test
    public void fileReadNumberSupportsSequentialReads() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local FileR = {}");
        int end = boot.indexOf("local FileW = {}", start);
        assertTrue("FileR implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        LuaValue fileR = globals.load(
                boot.substring(start, end) + "\nreturn FileR",
                "FileR-under-test.lua").call();
        globals.set("FileRUnderTest", fileR);
        LuaValue result = globals.load(
                "local handle = setmetatable({data = '12 34 -5.5', pos = 1, closed = false}, FileRUnderTest)\n"
                + "assert(handle:read('n') == 12, 'first numeric read must return 12')\n"
                + "assert(handle:read('n') == 34, 'second numeric read must advance past the first number')\n"
                + "assert(handle:read('n') == -5.5, 'third numeric read must preserve signed decimals')\n"
                + "return handle:read('n')",
                "file-read-number-regression.lua").call();
        assertTrue("no further number should remain", result.isnil());
    }


    @Test
    public void writeHandleKeepsDataWhenFlushFailsAndRejectsUseAfterClose() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local FileW = {}");
        int end = boot.indexOf("local real_tostring = tostring", start);
        assertTrue("FileW implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        LuaValue result = globals.load(
                "local writes = {}\n"
                + "local fail = true\n"
                + "host = {write_file = function(path, data, append) if fail then return false end; writes[#writes + 1] = {path, data, append}; return true end}\n"
                + boot.substring(start, end)
                + "local f = setmetatable({path = 'test.lua', data = '', pos = 1, closed = false}, FileW)\n"
                + "assert(f:write('hello', 42) == f)\nlocal unread, readErr = f:read()\nassert(unread == nil and readErr ~= nil, 'write-only handle read must report an error')\nlocal canLines = pcall(function() return f:lines() end)\nassert(canLines == false, 'write-only handle lines must report an error')\n"
                + "local ok, err = f:flush()\n"
                + "assert(ok == nil and err ~= nil, 'failed flush must report an error')\n"
                + "assert(f.data == 'hello42', 'failed flush must preserve buffered data')\n"
                + "local closed, closeErr = f:close()\n"
                + "assert(closed == nil and closeErr ~= nil and f.closed == false, 'failed close must leave the handle retryable')\n"
                + "fail = false\n"
                + "assert(f:close() == true)\n"
                + "assert(f.closed == true and f.data == 'hello42')\n"
                + "assert(writes[1][1] == 'test.lua' and writes[1][2] == 'hello42' and writes[1][3] == false)\n"
                + "local written, writeErr = f:write('after close')\n"
                + "assert(written == nil and writeErr ~= nil, 'write after close must fail')\n"
                + "local flushed, flushErr = f:flush()\n"
                + "assert(flushed == nil and flushErr ~= nil, 'flush after close must fail')\n"
                + "return true",
                "file-write-lifecycle-regression.lua").call();
        assertTrue("write handle lifecycle contract must pass", result.toboolean());
    }


    @Test
    public void ioLinesClosesTheOpenedFileAtEof() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local FileR = {}");
        int end = boot.indexOf("function io.popen(", start);
        assertTrue("sandbox file APIs must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        LuaValue result = globals.load(
                "local noop = function() end\n"
                + boot.substring(start, end)
                + "local closed = false\n"
                + "io.open = function() local n = 0; return {read = function() n = n + 1; if n == 1 then return 'first' elseif n == 2 then return 'second' end return nil end, close = function() closed = true; return true end} end\n"
                + "local iter = io.lines('virtual.txt')\n"
                + "assert(iter() == 'first')\n"
                + "assert(iter() == 'second')\n"
                + "assert(iter() == nil)\n"
                + "assert(closed == true, 'io.lines must close its file at EOF')\n"
                + "assert(iter() == nil, 'io.lines iterator must remain exhausted')\n"
                + "return true",
                "io-lines-runtime-regression.lua").call();
        assertTrue("io.lines lifecycle contract must pass", result.toboolean());
    }


    @Test
    public void ioTypeDistinguishesOpenClosedAndNonFileValues() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local FileR = {}");
        int end = boot.indexOf("function io.lines(path, fmt)", start);
        assertTrue("sandbox file APIs must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        LuaValue result = globals.load(
                "local noop = function() end\n"
                + boot.substring(start, end)
                + "local reader = setmetatable({data = 'x', pos = 1, closed = false}, FileR)\n"
                + "local writer = setmetatable({path = 'x', buf = {}, closed = false}, FileW)\n"
                + "assert(io.type(reader) == 'file')\n"
                + "assert(io.type(writer) == 'file')\n"
                + "assert(io.type({}) == nil)\n"
                + "reader:close(); writer.closed = true\n"
                + "assert(io.type(reader) == 'closed file')\n"
                + "assert(io.type(writer) == 'closed file')\n"
                + "return true",
                "io-type-runtime-regression.lua").call();
        assertTrue("io.type contract must match Lua file semantics", result.toboolean());
    }

    @Test
    public void readHandleSeekRejectsInvalidOriginAndClosedHandles() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local FileR = {}");
        int end = boot.indexOf("local FileW = {}", start);
        assertTrue("FileR implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        LuaValue fileR = globals.load(
                boot.substring(start, end) + "\nreturn FileR",
                "FileR-under-test.lua").call();
        globals.set("FileRUnderTest", fileR);
        LuaValue result = globals.load(
                "local f = setmetatable({data = 'abc', pos = 1, closed = false}, FileRUnderTest)\n"
                + "assert(f:seek('set', 1) == 1)\n"
                + "local pos, err = f:seek('middle', 0)\n"
                + "assert(pos == nil and err ~= nil, 'unknown seek origin must report an error')\n"
                + "local bad, badErr = f:seek('set', 1.5)\n"
                + "assert(bad == nil and badErr ~= nil, 'fractional seek offset must be rejected')\n"
                + "f:close()\n"
                + "local closed, closedErr = f:seek('set', 0)\n"
                + "assert(closed == nil and closedErr ~= nil, 'closed handle must reject seek')\n"
                + "return true",
                "file-seek-runtime-regression.lua").call();
        assertTrue("read handle seek contract must pass", result.toboolean());
    }

    @Test
    public void unsupportedProcessAndInteractiveApisFailExplicitly() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("function io.read(fmt)");
        int end = boot.indexOf("\nluajava = nil", start);
        assertTrue("process and standard-stream compatibility block must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "local noop = function() end\n"
                + "host = {remove = function() return false end, rename = function() return false end}\n"
                + "_path = {data = '/tmp/'}\n"
                + boot.substring(start, end),
                "unsupported-process-api-contract.lua").call();
        LuaValue result = globals.load(
                "local popen, popenErr = io.popen('anything')\n"
                + "assert(popen == nil and type(popenErr) == 'string' and string.find(popenErr, 'não é suportado'))\n"
                + "local status, executeErr = os.execute('anything')\n"
                + "assert(status == nil and type(executeErr) == 'string' and string.find(executeErr, 'não é suportado'))\n"
                + "assert(os.getenv('HOME') == nil, 'unavailable Android environment variables must remain nil')\n"
                + "assert(io.stdin:read() == nil, 'stdin must report EOF when no interactive input channel exists')\n"
                + "return true",
                "unsupported-process-api-regression.lua").call();
        assertTrue("unsupported APIs must report absence rather than fake success", result.toboolean());
    }

    @Test
    public void monomeDeviceRemovalNotifiesScriptAndReleasesVirtualPorts() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local function monome_assign_port");
        int end = boot.indexOf("\nhid = {", start);
        assertTrue("Grid/Arc lifecycle implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "local noop = function() end\n"
                + "safe = function(fn, ...) return pcall(fn, ...) end\n"
                + "grid = {devices = {}, vports = {{name='none', device=nil, available=false, connected=false}}}\n"
                + "arc = {devices = {}, vports = {{name='none', device=nil, available=false, connected=false}}}\n"
                + "_norns = {}\n"
                + "grid.add = noop; arc.add = noop\n"
                + boot.substring(start, end),
                "monome-device-lifecycle.lua").call();

        LuaValue result = globals.load(
                "local grid_removed, arc_removed, grid_port_removed, arc_port_removed = 0, 0, 0, 0\n"
                + "grid.remove = function(device) assert(device.id == 11); grid_removed = grid_removed + 1 end\n"
                + "arc.remove = function(device) assert(device.id == 22); arc_removed = arc_removed + 1 end\n"
                + "grid.vports[1].remove = function() grid_port_removed = grid_port_removed + 1 end\n"
                + "arc.vports[1].remove = function() arc_port_removed = arc_port_removed + 1 end\n"
                + "_norns.grid_device_add(11, 'g-1', 'Grid', 16, 8)\n"
                + "_norns.arc_device_add(22, 'a-1', 'Arc', 4)\n"
                + "assert(grid.vports[1].connected and grid.vports[1].available)\n"
                + "assert(arc.vports[1].connected and arc.vports[1].available)\n"
                + "assert(_norns.grid_device_remove(11) == true)\n"
                + "assert(_norns.arc_device_remove(22) == true)\n"
                + "assert(grid_removed == 1 and arc_removed == 1)\n"
                + "assert(grid_port_removed == 1 and arc_port_removed == 1)\n"
                + "assert(grid.devices[11] == nil and arc.devices[22] == nil)\n"
                + "assert(grid.vports[1].device == nil and not grid.vports[1].connected and not grid.vports[1].available)\n"
                + "assert(arc.vports[1].device == nil and not arc.vports[1].connected and not arc.vports[1].available)\n"
                + "assert(_norns.grid_device_remove(11) == false and _norns.arc_device_remove(22) == false)\n"
                + "return true",
                "monome-device-removal-regression.lua").call();
        assertTrue("Grid/Arc removal must notify callbacks and release stale ports", result.toboolean());
    }

    @Test
    public void hidRemovalNotifiesVirtualPortAndReassignsDeterministically() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("hid = {\n  devices = {}, vports = make_vports(\"hid\", 4)");
        int end = boot.indexOf("\n-- Crow:", start);
        assertTrue("HID lifecycle implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "local noop = function() end\n"
                + "safe = function(fn, ...) return pcall(fn, ...) end\n"
                + "safe_port = function(ports, n) return ports[n] end\n"
                + "unsupported_api = function() return function() return false end end\n"
                + "make_vports = function() return {"
                + "{name='none', device=nil, available=false, connected=false},"
                + "{name='none', device=nil, available=false, connected=false}} end\n"
                + "_norns = {}\n"
                + boot.substring(start, end),
                "hid-device-lifecycle.lua").call();
        LuaValue result = globals.load(
                "local module_removed, device_removed, port_removed = 0, 0, 0\n"
                + "hid.remove = function(device) assert(device.id == 7); module_removed = module_removed + 1 end\n"
                + "local first = _norns.hid_device_add(7, 'USB Controller', 1, 2)\n"
                + "assert(first.port == 1 and hid.vports[1].device == first)\n"
                + "first.remove = function() device_removed = device_removed + 1 end\n"
                + "hid.vports[1].remove = function() port_removed = port_removed + 1 end\n"
                + "assert(_norns.hid_device_remove(7) == true)\n"
                + "assert(module_removed == 1 and device_removed == 1 and port_removed == 1)\n"
                + "assert(hid.devices[7] == nil and hid.vports[1].device == nil)\n"
                + "assert(not hid.vports[1].available and not hid.vports[1].connected)\n"
                + "assert(_norns.hid_device_remove(7) == false)\n"
                + "assert(module_removed == 1 and device_removed == 1 and port_removed == 1)\n"
                + "local next_device = _norns.hid_device_add(8, 'USB Controller', 1, 2)\n"
                + "assert(next_device.port == 1 and hid.vports[1].device == next_device)\n"
                + "return true",
                "hid-device-removal-regression.lua").call();
        assertTrue("HID removal must notify callbacks and deterministically reuse freed ports", result.toboolean());
    }

    @Test
    public void gamepadStateRemainsAggregatedUntilLastControllerReleasesInput() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("gamepad = {");
        int end = boot.indexOf("\n-- Android USB CDC-ACM serial backend.", start);
        assertTrue("Gamepad state implementation must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "local noop = function() end\n"
                + "safe = function(fn, ...) return pcall(fn, ...) end\n"
                + "_norns = {}\n"
                + boot.substring(start, end),
                "gamepad-multi-device.lua").call();
        LuaValue result = globals.load(
                "_norns.gamepad_button('A', 1, 1, 'Controller One')\n"
                + "_norns.gamepad_button('A', 1, 2, 'Controller Two')\n"
                + "assert(gamepad.state.A == true and gamepad.connected)\n"
                + "_norns.gamepad_device(1, nil, false)\n"
                + "assert(gamepad.state.A == true, 'disconnecting one controller must not release another controller button')\n"
                + "assert(gamepad.connected)\n"
                + "_norns.gamepad_button('A', 0, 2, 'Controller Two')\n"
                + "assert(gamepad.state.A == false)\n"
                + "_norns.gamepad_axis('leftx', 1, 1, 1, 'Controller One')\n"
                + "_norns.gamepad_axis('leftx', -1, 1, 2, 'Controller Two')\n"
                + "assert(gamepad.state.LRIGHT and gamepad.state.LLEFT)\n"
                + "_norns.gamepad_device(1, nil, false)\n"
                + "assert(not gamepad.state.LRIGHT and gamepad.state.LLEFT, 'remaining controller axis must survive peer disconnect')\n"
                + "_norns.gamepad_device(2, nil, false)\n"
                + "assert(not gamepad.state.LLEFT and not gamepad.state.LRIGHT)\n"
                + "assert(not gamepad.connected and next(gamepad.devices) == nil)\n"
                + "return true",
                "gamepad-multi-device-regression.lua").call();
        assertTrue("gamepad state must be aggregated from connected controllers", result.toboolean());
    }

    private static String readLuaAsset(String relativePath) throws Exception {
        File sourceFile = new File("src/main/assets/lua/" + relativePath);
        if (!sourceFile.isFile()) {
            sourceFile = new File("app/src/main/assets/lua/" + relativePath);
        }
        if (!sourceFile.isFile()) {
            throw new AssertionError("Could not locate app/src/main/assets/lua/" + relativePath);
        }
        return new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void engineSendApiRoutesJavaAndNativeSourcesWithoutDoubleRecording() throws Exception {
        String source = readLuaAsset("boot.lua");
        assertTrue(source.contains("softcut._engine_cut_level = 0"));
        assertTrue(source.contains("level_eng_cut = function(level)"));
        assertTrue(source.contains("level_tape_cut = function(level)"));
        assertTrue(source.contains("math.min(1, level)"));
        assertTrue(source.contains("host.softcut(voice, \"engine_level\", javaLevel)"));
        assertTrue(source.contains("sc_api.set(recorder, {engine_level = nativeLevel})"));
        assertTrue(source.contains("engine._native_state == \"native\""));
        assertTrue(source.contains("engine_bus = 31"));
        assertTrue(source.contains("engine nativo exige o gravador Softcut nativo pronto"));
        assertTrue(source.contains("state.recording == true or state.recovery_pending == true"));
        assertTrue(source.contains("aguarde a gravação terminar e sincronizar"));
        assertTrue(source.contains("softcut._engine_cut_source ~= \"native\" then return false end"));
        assertTrue(source.contains("targetSource ~= softcut._engine_cut_source"));
        assertTrue(source.contains("pare a gravação Softcut antes de trocar a fonte do engine"));
        assertTrue(source.contains("if level == 0 then"));
        assertTrue(source.contains("local voiceRecording = false"));
    }



    @Test
    public void upstreamAudioHelpersPreserveBackendTruthAndFileMetadata() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local function audio_route_unavailable");
        int end = boot.indexOf("\n-- Tape state mirrors", start);
        assertTrue("audio API table must be isolated before Tape state", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "noop = function() end\n"
                + "norns = {state = {out = 64}}\n"
                + "engine = {_native_state = 'native'}\n"
                + "softcut = {VOICE_COUNT = 6}\n"
                + "host = {\n"
                + "  log = function() end,\n"
                + "  disk_free_bytes = function() return 1000000000 end,\n"
                + "  audio_file_info = function() return 2, 96000, 48000 end,\n"
                + "  audio_level_dac = function(level) last_dac = level; return true end,\n"
                + "  audio_fx = function(action, effect, source, value) last_fx_action=action; last_fx_effect=effect; last_fx_source=source; last_fx_value=value; return true end,\n"
                + "}\n",
                "audio-api-host-fixture.lua").call();
        globals.load(boot.substring(start, end), "audio-api.lua").call();

        globals.load(
                "local channels, samples, rate = audio.file_info('test.wav')\n"
                + "file_channels, file_samples, file_rate = channels, samples, rate\n"
                + "diskfree_default = audio.tape_compute_diskfree()\n"
                + "diskfree_custom = audio.tape_compute_diskfree(-5)\n"
                + "rejected_level = audio.set_audio_level(32)\n"
                + "state_after_reject = norns.state.out\n"
                + "engine._native_state = 'java'\n"
                + "accepted_level = audio.set_audio_level(32)\n"
                + "state_after_accept = norns.state.out\n"
                + "audio.adjust_output_level(100)\n"
                + "state_after_adjust = norns.state.out\n"
                + "dac_after_adjust = last_dac\n"
                 + "engine._native_state = 'native'\n"
                 + "rejected_cut_rev = audio.level_cut_rev(0.5)\n"
                 + "engine._native_state = 'java'\n"
                 + "softcut._native_output_owner = true\n"
                 + "rejected_cut_rev_owner = audio.level_cut_rev(0.5)\n"
                 + "softcut._native_output_owner = false\n"
                 + "softcut._native_output_uncertain = true\n"
                 + "rejected_cut_rev_uncertain = audio.level_cut_rev(0.5)\n"
                 + "softcut._native_output_uncertain = false\n"
                 + "accepted_cut_rev = audio.level_cut_rev(2)\n"
                 + "cut_rev_source = last_fx_source\n"
                 + "cut_rev_value = last_fx_value\n"
                + "input_port_count = #audio.input_ports()\n"
                + "connect_result = audio.port_connect('missing:out', 'missing:in')\n"
                + "routing_result = audio.routing_connect({['missing:out']='missing:in'})\n"
                + "tracking_result = audio.routing_is_altered()\n",
                "audio-api-contract.lua").call();

        assertEquals(2, globals.get("file_channels").toint());
        assertEquals(96000, globals.get("file_samples").toint());
        assertEquals(48000, globals.get("file_rate").toint());
        assertEquals(4958, globals.get("diskfree_default").toint());
        assertEquals(5208, globals.get("diskfree_custom").toint());
        assertTrue(!globals.get("rejected_level").toboolean());
        assertEquals(64, globals.get("state_after_reject").toint());
        assertTrue(globals.get("accepted_level").toboolean());
        assertEquals(32, globals.get("state_after_accept").toint());
        assertEquals(64, globals.get("state_after_adjust").toint());
        assertEquals(1.0, globals.get("dac_after_adjust").todouble(), 0.000001);
        assertTrue("native scsynth must reject the Java-only Softcut reverb tap",
                !globals.get("rejected_cut_rev").toboolean());
        assertTrue("native Softcut output ownership must reject a Java-only FX tap",
                !globals.get("rejected_cut_rev_owner").toboolean());
        assertTrue("uncertain Softcut output ownership must fail closed",
                !globals.get("rejected_cut_rev_uncertain").toboolean());
        assertTrue(globals.get("accepted_cut_rev").toboolean());
        assertEquals("softcut", globals.get("cut_rev_source").tojstring());
        assertEquals(1.0, globals.get("cut_rev_value").todouble(), 0.000001);
        assertEquals(0, globals.get("input_port_count").toint());
        assertTrue(!globals.get("connect_result").toboolean());
        assertTrue(!globals.get("routing_result").toboolean());
        assertTrue(!globals.get("tracking_result").toboolean());
    }

    @Test
    public void tapeStateMatchesUpstreamNestedSnapshotAndRecordingPredicate() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("local TAPE_PLAY_STATE_EMPTY = 0");
        int end = boot.indexOf("\npoll = {available = true", start);
        assertTrue("upstream Tape state block must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "audio = {}\n"
                + "host = {\n"
                + "  tape_state = function() return {play_state=0, play_pos_s=0, play_len_s=0, "
                + "loop_enabled=true, rec_state=0, rec_pos_s=0} end,\n"
                + "  log = function() end\n"
                + "}\n",
                "tape-state-host-fixture.lua").call();
        globals.load(boot.substring(start, end), "tape-state-api.lua").call();

        LuaValue tape = globals.get("audio").get("tape");
        assertEquals(0, tape.get("get_state").call().get("play").get("state").toint());
        assertEquals(0, tape.get("get_state").call().get("rec").get("state").toint());
        assertEquals(1, tape.get("constants").get("TAPE_PLAY_STATE_READY").toint());
        assertEquals(2, tape.get("constants").get("TAPE_PLAY_STATE_PLAYING").toint());
        assertEquals(3, tape.get("constants").get("TAPE_PLAY_STATE_PAUSED").toint());
        assertEquals(1, tape.get("constants").get("TAPE_REC_STATE_READY").toint());
        assertEquals(2, tape.get("constants").get("TAPE_REC_STATE_RECORDING").toint());
        assertEquals(3, tape.get("constants").get("TAPE_REC_STATE_PAUSED").toint());
        assertTrue(!globals.get("audio").get("tape_is_recording").call().toboolean());

        globals.load(
                "event_count = 0\n"
                + "observed_record_state = -1\n"
                + "audio.tape.subscribe('test', function(snapshot)\n"
                + "  event_count = event_count + 1\n"
                + "  observed_record_state = snapshot.rec.state\n"
                + "  snapshot.rec.state = 99\n"
                + "end)\n"
                + "audio.tape._update_state({play_state=2, play_pos_s=1.25, play_len_s=8, "
                + "rec_state=2, rec_pos_s=0.5, loop_enabled=false})\n",
                "tape-state-update.lua").call();
        assertEquals(2, tape.get("get_state").call().get("play").get("state").toint());
        assertEquals(2, tape.get("get_state").call().get("rec").get("state").toint());
        assertTrue(globals.get("audio").get("tape_is_recording").call().toboolean());
        assertEquals(1, globals.get("event_count").toint());
        assertEquals(2, globals.get("observed_record_state").toint());
        assertTrue(!tape.get("get_state").call().get("play").get("loop").toboolean());
        assertEquals(2, tape.get("get_state").call().get("rec").get("state").toint());

        globals.load(
                "audio.tape._on_status(3, 2.5, 8, 3, 0.75, 0)\n"
                + "status_play_state = audio.tape.get_state().play.state\n"
                + "status_rec_state = audio.tape.get_state().rec.state\n"
                + "status_loop = audio.tape.get_state().play.loop\n"
                + "audio.tape.unsubscribe('test')\n"
                + "audio.tape._update_state({rec_state=2})\n",
                "tape-state-status.lua").call();
        assertEquals(3, globals.get("status_play_state").toint());
        assertEquals(3, globals.get("status_rec_state").toint());
        assertTrue(!globals.get("status_loop").toboolean());
        assertEquals(2, globals.get("event_count").toint());
        assertEquals(2, tape.get("get_state").call().get("rec").get("state").toint());
        assertTrue(globals.get("audio").get("tape_is_recording").call().toboolean());

        globals.load(
                "local snapshot = audio.tape.get_state()\n"
                + "snapshot.rec.state = 0\n"
                + "assert(audio.tape_is_recording(), 'get_state must return an isolated snapshot')\n"
                + "audio.tape._on_play_file('/dust/audio/tape/playback.wav')\n"
                + "audio.tape._on_rec_file('C:\\\\dust\\\\audio\\\\take.wav')\n",
                "tape-state-files.lua").call();
        assertEquals("playback.wav", tape.get("get_state").call().get("play").get("file").tojstring());
        assertEquals("take.wav", tape.get("get_state").call().get("rec").get("file").tojstring());
    }

    @Test
    public void audioAddParamsBuildsSupportedMixerControlsAndRollsBackRejectedChanges() throws Exception {
        String boot = readLuaAsset("boot.lua");
        int start = boot.indexOf("  add_params = function()");
        int end = boot.indexOf("\n  end,\n}", start);
        assertTrue("audio.add_params implementation must exist", start >= 0 && end > start);
        String addParams = boot.substring(start, end + "\n  end".length());

        Globals globals = JsePlatform.standardGlobals();
        LuaValue loaded = globals.get("package").get("loaded");
        LuaValue util = globals.load(readLuaAsset("lib/util.lua"), "util.lua").call();
        LuaValue controlspec = globals.load(
                readLuaAsset("lib/controlspec.lua"), "controlspec.lua").call();
        loaded.set("util", util);
        loaded.set("controlspec", controlspec);
        globals.set("util", util);
        globals.set("controlspec", controlspec);
        globals.set("ParamSetUnderTest", globals.load(
                readLuaAsset("lib/paramset.lua"), "paramset.lua").call());
        globals.load(
                "norns = {state = {mix = {"
                + "output=0,input=0,monitor=0,engine=0,cut=0,tape=0,monitor_mode=1,headphone_gain=40,aux=2,ins=1,"
                + "rev_eng_input=-9,rev_cut_input=-9,rev_monitor_input=-math.huge,rev_tape_input=-math.huge,"
                + "rev_return_level=0,rev_pre_delay=60,rev_lf_fc=200,rev_low_time=6,rev_mid_time=6,rev_hf_damping=6000,"
                + "comp_mix=1,comp_ratio=4,comp_threshold=-18,comp_attack=5,comp_release=50,comp_pre_gain=0,comp_post_gain=9,"
                + "cut_input_adc=0,cut_input_eng=0,cut_input_tape=-math.huge}}}\n"
                + "params = ParamSetUnderTest.new('audio-mixer-test', 'Audio mixer')\n"
                + "audio = {\n" + addParams + "\n}\n",
                "audio-add-params-fixture.lua").call();
        globals.load(
                "rev_on_calls = 0; comp_on_calls = 0\n"
                + "audio.level_dac = function() return true end\n"
                + "audio.level_adc = function() return true end\n"
                + "audio.level_monitor = function() return true end\n"
                + "audio.level_eng = function() return true end\n"
                + "audio.level_cut = function() return true end\n"
                + "audio.level_tape = function() return true end\n"
                + "audio.headphone_gain = function() return false end\n"
                + "audio.monitor_mono = function() return true end\n"
                + "audio.rev_on = function() rev_on_calls = rev_on_calls + 1; return true end\n"
                + "audio.rev_off = function() return false end\n"
                + "audio.level_eng_rev = function() return true end\n"
                + "audio.level_cut_rev = function() return false end\n"
                + "audio.level_monitor_rev = function() return true end\n"
                + "audio.level_tape_rev = function() return true end\n"
                + "audio.level_rev_dac = function() return true end\n"
                + "audio.rev_param = function() return true end\n"
                + "audio.comp_on = function() comp_on_calls = comp_on_calls + 1; return true end\n"
                + "audio.comp_off = function() return false end\n"
                + "audio.comp_mix = function() return true end\n"
                + "audio.comp_param = function() return true end\n"
                + "audio.level_adc_cut = function() return true end\n"
                + "audio.level_eng_cut = function() return true end\n"
                + "audio.level_tape_cut = function() return true end\n"
                + "assert(audio.add_params() == true)\n"
                + "assert(params:lookup_param('output_level') ~= nil)\n"
                + "assert(params:lookup_param('rev_cut_input') ~= nil)\n"
                + "assert(params:lookup_param('comp_ratio') ~= nil)\n"
                + "assert(params:lookup_param('cut_input_tape') ~= nil)\nassert(params:lookup_param('monitoring_separator') ~= nil)\n"
                + "assert(params.lookup.headphone_gain ~= nil, 'upstream headphone gain parameter ID must remain available')\n"
                + "assert(params:visible('headphone_gain') == false, 'unsupported headphone control must remain hidden')\n"
                + "params:set('headphone_gain', 41)\n"
                + "assert(params:get('headphone_gain') == 40 and norns.state.mix.headphone_gain == 40)\n"
                + "assert(#params:lookup_param('monitor_mode').options == 1)\n"
                + "assert(params:lookup_param('monitor_mode').options[1] == 'MONO')\n"
                + "params:set('output_level', -6)\n"
                + "assert(math.abs(params:get('output_level') + 6) < 0.001)\n"
                + "assert(math.abs(norns.state.mix.output + 6) < 0.001)\n"
                + "params:set('reverb', 1)\n"
                + "assert(params:get('reverb') == 2 and norns.state.mix.aux == 2)\n"
                + "assert(rev_on_calls == 0, 'failed OFF action must not fall through and re-enable reverb')\n"
                + "params:set('rev_cut_input', -3)\n"
                + "assert(math.abs(params:get('rev_cut_input') + 9) < 0.001, 'Softcut send must roll back when native ownership rejects it')\n"
                + "params:set('input_level', -6); assert(math.abs(norns.state.mix.input + 6) < 0.001)\n"
                + "params:set('monitor_level', -3); assert(math.abs(norns.state.mix.monitor + 3) < 0.001)\n"
                + "params:set('engine_level', -4); assert(math.abs(norns.state.mix.engine + 4) < 0.001)\n"
                + "params:set('softcut_level', -5); assert(math.abs(norns.state.mix.cut + 5) < 0.001)\n"
                + "params:set('tape_level', -7); assert(math.abs(norns.state.mix.tape + 7) < 0.001)\n"
                + "params:bang('reverb'); assert(norns.state.mix.aux == 2 and rev_on_calls == 1, 'params:bang must apply the active reverb state; aux=' .. tostring(norns.state.mix.aux) .. ', rev_on_calls=' .. tostring(rev_on_calls))\n"
                + "params:set('rev_eng_input', -6); assert(math.abs(norns.state.mix.rev_eng_input + 6) < 0.001)\n"
                + "params:set('rev_monitor_input', -3); assert(math.abs(norns.state.mix.rev_monitor_input + 3) < 0.001)\n"
                + "params:set('rev_tape_input', -4); assert(math.abs(norns.state.mix.rev_tape_input + 4) < 0.001)\n"
                + "params:set('rev_return_level', -1); assert(math.abs(norns.state.mix.rev_return_level + 1) < 0.001)\n"
                + "params:set('rev_pre_delay', 70); assert(math.abs(norns.state.mix.rev_pre_delay - 70) < 0.001)\n"
                + "params:set('rev_lf_fc', 300); assert(math.abs(norns.state.mix.rev_lf_fc - 300) < 0.001)\n"
                + "params:set('rev_low_time', 4); assert(math.abs(norns.state.mix.rev_low_time - 4) < 0.001)\n"
                + "params:set('rev_mid_time', 8); assert(math.abs(norns.state.mix.rev_mid_time - 8) < 0.001)\n"
                + "params:set('rev_hf_damping', 8000); assert(math.abs(norns.state.mix.rev_hf_damping - 8000) < 0.01)\n"
                + "params:set('compressor', 2); assert(norns.state.mix.ins == 2 and comp_on_calls == 1)\n"
                + "params:set('comp_mix', 0.5); assert(math.abs(norns.state.mix.comp_mix - 0.5) < 0.001)\n"
                + "params:set('comp_ratio', 6); assert(math.abs(norns.state.mix.comp_ratio - 6) < 0.001)\n"
                + "params:set('comp_threshold', -12); assert(math.abs(norns.state.mix.comp_threshold + 12) < 0.001)\n"
                + "params:set('comp_attack', 10); assert(math.abs(norns.state.mix.comp_attack - 10) < 0.001)\n"
                + "params:set('comp_release', 80); assert(math.abs(norns.state.mix.comp_release - 80) < 0.001)\n"
                + "params:set('comp_pre_gain', 3); assert(math.abs(norns.state.mix.comp_pre_gain - 3) < 0.001)\n"
                + "params:set('comp_post_gain', 6); assert(math.abs(norns.state.mix.comp_post_gain - 6) < 0.001)\n"
                + "params:set('cut_input_adc', -2); assert(math.abs(norns.state.mix.cut_input_adc + 2) < 0.001)\n"
                + "params:set('cut_input_eng', -3); assert(math.abs(norns.state.mix.cut_input_eng + 3) < 0.001)\n"
                + "params:set('cut_input_tape', -4); assert(math.abs(norns.state.mix.cut_input_tape + 4) < 0.001)\n"
                + "local function group_size(name) for _, p in ipairs(params.params) do if p.t == params.tGROUP and p.name == name then return p.n end end end\n"
                + "assert(group_size('LEVELS') == 9 and group_size('REVERB') == 11)\n"
                + "assert(group_size('COMPRESSOR') == 8 and group_size('SOFTCUT') == 3)\n"
                + "assert(params.lookup.LEVELS == nil and params.lookup.REVERB == nil and params.lookup.COMPRESSOR == nil and params.lookup.SOFTCUT == nil, 'group labels are display names, not parameter IDs')\n"
                + "return true",
                "audio-add-params-runtime.lua").call();
    }

}
