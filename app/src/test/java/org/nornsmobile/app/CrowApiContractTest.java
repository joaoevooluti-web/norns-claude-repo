package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CrowApiContractTest {
    @Test public void crowUsesTheUsbSerialTransportAndOfficialDeviceIdentity() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("pcall(host.usb_serial_devices)"));
        assertTrue(boot.contains("host.usb_serial_open(id, speed"));
        assertTrue(boot.contains("host.usb_serial_write(id, data)"));
        assertTrue(boot.contains("attrs.vendor_id == 1155 and attrs.product_id == 22336"));
        assertTrue(boot.contains("attrs.model == \"crow: telephone line\""));
        assertTrue(boot.contains("attrs.vendor == \"monome & whimsical raps\""));
        assertTrue(boot.contains("attrs.interface == \"00\""));
        assertTrue(boot.contains("crowDevice = dev"));
        assertTrue(boot.contains("norns.crow.dev = dev"));
    }

    @Test public void crowReceivesFragmentedSerialLinesAndDispatchesEvents() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local function crow_receive(data)"));
        assertTrue(boot.contains("crowEventBuffer = crowEventBuffer .. incoming"));
        assertTrue(boot.contains("string.find(crowEventBuffer, \"[\\r\\n]\")"));
        assertTrue(boot.contains("local function crow_process_line(line)"));
        assertTrue(boot.contains("local callback = norns.crow.events[event]"));
        assertTrue(boot.contains("local values = crow_parse_args(args or \"\")"));
        assertTrue(boot.contains("local function crow_decode_string(token)"));
        assertTrue(boot.contains("string.char(tonumber(hex, 16))"));
        assertTrue(boot.contains("string.char(value)"));
        assertTrue(boot.contains("local digits = string.match(string.sub(token, i), \"^(%d%d?%d?)\")"));
        assertFalse(boot.contains("[\"0\"]=\"\\\\0\""));
        assertFalse(boot.contains("load(\"return \" .. token)"));
        assertTrue(boot.contains("result.n = result.n + 1"));
        assertTrue(boot.contains("(values, 1, values.n)"));
    }

    @Test public void crowDropsOversizedSerialLinesWithoutDispatchingTheirTail() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local crowEventDroppingOversizeLine = false"));
        assertTrue(boot.contains("if #crowEventBuffer > 8192 then"));
        assertTrue(boot.contains("crowEventBuffer = " + "\"\""));
        assertTrue(boot.contains("crowEventDroppingOversizeLine = true"));
        assertTrue(boot.contains("local _, finish = string.find(incoming,"));
        assertTrue(boot.contains("[\\r\\n]"));
        assertTrue(boot.contains("crowEventDroppingOversizeLine = false"));
        assertTrue(boot.contains("if #line <= 8192 then"));
        assertFalse(boot.contains("string.sub(crowEventBuffer, -4096)"));
    }

    @Test public void crowDecoderSupportsLuaWhitespaceEscape() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("escaped == \"z\""));
        assertTrue(boot.contains("string.sub(token, i + 1, i + 1), \"%s\""));
        assertTrue(boot.contains("i = i + 1"));
    }

    @Test public void crowExposesPublicIiAndEventCallbackApis() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("crow.output = crow_proxy(\"output\")"));
        assertTrue(boot.contains("crow.input = crow_proxy(\"input\")"));
        assertTrue(boot.contains("crow.ii = crow_proxy(\"ii\")"));
        assertTrue(boot.contains("crow.public = norns.crow.public.io"));
        assertTrue(boot.contains("register_event = crow_register_event"));
        assertTrue(boot.contains("crow.clock_enable"));
    }


    @Test public void crowPublicVariablesSupportDiscoveryStateAndUserCallbacks() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function norns.crow.public.clear()"));
        assertTrue(boot.contains("function norns.crow.public.reset()"));
        assertTrue(boot.contains("function norns.crow.public.discover()"));
        assertTrue(boot.contains("crow_send(\"public.discover()\")"));
        assertTrue(boot.contains("ready = norns.crow.public.ready"));
        assertTrue(boot.contains("pub = norns.crow.public.add"));
        assertTrue(boot.contains("pupdate = norns.crow.public.update"));
        assertTrue(boot.contains("pubview = norns.crow.public.view"));
        assertTrue(boot.contains("public._names[name] = index"));
        assertTrue(boot.contains("public.viewing[direction][channel] = value"));
        assertTrue(boot.contains("function norns.crow.public.get_count()"));
        assertTrue(boot.contains("function norns.crow.public.get_index(index)"));
        assertTrue(boot.contains("function norns.crow.public.lookup(name)"));
        assertTrue(boot.contains("function norns.crow.public.capture_type(entry, kind)"));
        assertTrue(boot.contains("public.capture_type(entry, kind)"));
        assertTrue(boot.contains("listix = 1, listlen = type(value) == \"table\" and #value or 0"));
        assertTrue(boot.contains("function norns.crow.public.delta(index, delta, alternate)"));
        assertTrue(boot.contains("function norns.crow.public.increment(value, delta, entry)"));
        assertTrue(boot.contains("public.io[entry.name]"));
        assertTrue(boot.contains("util.clamp(value, entry.min, entry.max)"));
        assertFalse(boot.contains("reset = function() end"));
        assertFalse(boot.contains("ready = function() end"));
    }


    @Test public void crowLifecycleResetsFirmwareEventsAndPublicCallbacksOnScriptCleanup() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int init = boot.indexOf("crow.init = function()");
        int initEnd = boot.indexOf("\nend", init);
        int cleanup = boot.indexOf("function _norns.cleanup()");
        int cleanupEnd = boot.indexOf("\nend", cleanup);
        assertTrue(init >= 0 && initEnd > init && cleanup > initEnd);
        String initBody = boot.substring(init, initEnd);
        String cleanupBody = boot.substring(cleanup, cleanupEnd);
        assertTrue(initBody.contains("crow_send(\"crow.reset()\")"));
        assertTrue(initBody.contains("norns.crow.reset_events()"));
        assertTrue(initBody.contains("norns.crow.public.reset()"));
        assertTrue(cleanupBody.contains("safe(norns.crow.init)"));
    }

    @Test public void crowScriptUploadIsBoundedAndUsesFirmwareCommands() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("script excede o limite de 16 KiB"));
        assertTrue(boot.contains("crow_send(\"^^s\")"));
        assertTrue(boot.contains("crow_send(is_persistent and \"^^w\" or \"^^e\")"));
        assertTrue(boot.contains("crow_send(\"^^c\")"));
    }

    @Test public void crowFirmwareEventPrefixIsRecognizedBeforeDispatch() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("if string.sub(line, 1, 2) == \">>\" then"));
        assertTrue(boot.contains("local event, args = string.match(line,"));
        assertFalse(boot.contains("== \"^^\" then"));
    }

    @Test public void serialCleanupPreservesRegisteredHandlersAcrossScriptReloads() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int cleanup = boot.indexOf("function serial.cleanup()");
        int cleanupEnd = boot.indexOf("\nend", cleanup);
        assertTrue(cleanup >= 0 && cleanupEnd > cleanup);
        String body = boot.substring(cleanup, cleanupEnd);
        assertTrue(body.contains("serial._ports, serial._pending = {}, {}"));
        assertFalse(body.contains("serial._handlers = {}"));
        assertTrue(boot.contains("norns.crow.usb"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
