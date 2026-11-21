package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class FormattersCompatibilityTest {
    private static String readFormatters() throws Exception {
        File file = new File("app/src/main/assets/lua/lib/formatters.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/lib/formatters.lua");
        if (!file.isFile()) throw new AssertionError("Missing bundled formatters.lua");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Globals runtime() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
            "util = {"
            + "round = function(v, p) p = p or 1; return math.floor(v / p + 0.5) * p end,"
            + "clamp = function(v, lo, hi) return math.max(lo, math.min(hi, v)) end"
            + "}\n"
            + "controlspec = {BIPOLAR = {map = function(_, v) return (v * 2) - 1 end}}\n"
            + "require = function(name) assert(name == 'controlspec'); return controlspec end",
            "formatters-test-runtime.lua").call();
        return globals;
    }

    @Test public void bundledFormatterModuleLoadsAndFormatsFrequencyAndTime() throws Exception {
        Globals globals = runtime();
        LuaValue formatters = globals.load(readFormatters(), "@lua/lib/formatters.lua").call();
        assertEquals("440 Hz", formatters.get("format_freq_raw").call(LuaValue.valueOf(440)).tojstring());
        assertEquals("1.5 kHz", formatters.get("format_freq_raw").call(LuaValue.valueOf(1500)).tojstring());
        assertEquals("2 min", formatters.get("format_secs_raw").call(LuaValue.valueOf(120)).tojstring());
        assertEquals("0.50 s", formatters.get("format_secs_raw").call(LuaValue.valueOf(0.5)).tojstring());
    }

    @Test public void parameterFormattersAcceptNornsStyleParameterObjects() throws Exception {
        Globals globals = runtime();
        LuaValue formatters = globals.load(readFormatters(), "@lua/lib/formatters.lua").call();
        globals.set("formattersUnderTest", formatters);
        LuaValue result = globals.load(
            "local p = {controlspec = {units = 'dB'}, get = function() return 0.5 end}\n"
            + "assert(formattersUnderTest.percentage(p) == '50 %')\n"
            + "assert(formattersUnderTest.secs_as_ms(p) == '500 ms')\n"
            + "assert(formattersUnderTest.round(0.1)(p) == '0.5 dB')\n"
            + "assert(formattersUnderTest.unipolar_as_true_false(p) == 'false')\n"
            + "assert(type(formattersUnderTest.bipolar_as_pan_widget(p)) == 'string')\n"
            + "assert(type(formattersUnderTest.unipolar_as_multimode_filter_freq(p)) == 'string')\n"
            + "return true",
            "formatters-parameter-contract.lua").call();
        assertTrue(result.toboolean());
    }
}
