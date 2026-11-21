package org.nornsmobile.app;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import static org.junit.Assert.*;

public class OscApiContractTest {
    @Test public void luaApiMatchesNornsDestinationTupleAndDefaultPort() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("send = function(to, path, args, legacy_args)"));
        assertTrue(boot.contains("hostname, port, payload = to[1], to[2], args or {}"));
        assertTrue(boot.contains("osc.listen(10111)"));
        assertTrue(boot.contains("tonumber(port) or 10111"));
    }

    @Test public void incomingOscEventUsesNornsSourceTuple() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("LuaTable source = new LuaTable()"));
        assertTrue(host.contains("source.set(1, LuaValue.valueOf(from))"));
        assertTrue(host.contains("source.set(2, LuaValue.valueOf(fromPort))"));
        assertTrue(host.contains("LuaValue.valueOf(path), args, source"));
    }

    @Test public void oscBundlesAreDecodedRecursivelyWithBounds() throws Exception {
        String osc = read("app/src/main/java/org/nornsmobile/app/OscBridge.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(osc.contains("static byte[] encodeBundle"));
        assertTrue(osc.contains("static List<Object[]> decodeAll"));
        assertTrue(osc.contains("MAX_BUNDLE_DEPTH = 8"));
        assertTrue(osc.contains("MAX_BUNDLE_MESSAGES = 256"));
        assertTrue(osc.contains("total[0] > MAX_BUNDLE_MESSAGES"));
        assertTrue(boot.contains("cleanup = function() host.osc_close() end"));
    }

    @Test public void oscSupportsExtendedScalarAndBlobTypeTags() throws Exception {
        String osc = read("app/src/main/java/org/nornsmobile/app/OscBridge.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(osc.contains("tags.append('h')"));
        assertTrue(osc.contains("tags.append('d')"));
        assertTrue(osc.contains("tags.append('b')"));
        assertTrue(host.contains("__osc_type"));
        assertTrue(host.contains("value instanceof byte[]"));
        assertTrue(boot.contains("int64 = function(value)"));
        assertTrue(boot.contains("float64 = function(value)"));
        assertTrue(boot.contains("blob = function(bytes)"));
    }

    @Test public void luaCanSendOscBundlesWithOptionalUnixMillisecondTimetag() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String osc = read("app/src/main/java/org/nornsmobile/app/OscBridge.java");
        assertTrue(boot.contains("send_bundle = function(to, messages, timetag_ms)"));
        assertTrue(boot.contains("host.osc_send_bundle(hostname, port, messages"));
        assertTrue(host.contains("case H_OSC_SEND_BUNDLE:"));
        assertTrue(host.contains("OscBridge.unixMillisToNtp((long) requestedTime)"));
        assertTrue(osc.contains("synchronized void sendBundle"));
    }

    @Test public void oscBundleTimetagsAreScheduledAndQueueIsBounded() throws Exception {
        String osc = read("app/src/main/java/org/nornsmobile/app/OscBridge.java");
        assertTrue(osc.contains("decodeScheduled("));
        assertTrue(osc.contains("ntpToUnixMillis"));
        assertTrue(osc.contains("TimeUnit.MILLISECONDS"));
        assertTrue(osc.contains("incrementAndGet() > 1024"));
        assertTrue(osc.contains("scheduler.shutdownNow()"));
    }

    @Test public void oscTransportBoundsStringsAndUsesOneSocketPerListenerGeneration() throws Exception {
        String osc = read("app/src/main/java/org/nornsmobile/app/OscBridge.java");
        assertTrue(osc.contains("final DatagramSocket listening"));
        assertTrue(osc.contains("socket == listening"));
        assertTrue(osc.contains("if (end >= length) return null"));
        assertTrue(osc.contains("port < 1 || port > 65535"));
    }

    @Test public void incomingOscUsesNornsCoreDispatcherAndPreservesUserCallback() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(host.contains("nornsTable.get(\"osc_event\")"));
        assertTrue(host.contains("if (!event.isfunction()) event = oscTable.get(\"event\")"));
        assertTrue(boot.contains("function _norns.osc_event(path, args, from)"));
        assertTrue(boot.contains("safe(osc.event, path, args, from)"));
        assertTrue(boot.contains("parts[1] == \"param\""));
        assertTrue(boot.contains("string.sub(path, 1, 11) == \"/remote/key\""));
        assertTrue(boot.contains("string.sub(path, 1, 11) == \"/remote/enc\""));
        assertTrue(boot.contains("string.sub(path, 1, 11) == \"/remote/brd\""));
        assertTrue(boot.contains("keyboard.process(1, n, value)"));
    }

    @Test public void oscRemoteKeyboardUsesNornsEvdevKeyCodes() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function keyboard.process(device, code, value)"));
        assertTrue(boot.contains("string.sub(name, 1, 4) == \"KEY_\""));
        assertTrue(boot.contains("_norns.keyboard_event(logical, value"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
