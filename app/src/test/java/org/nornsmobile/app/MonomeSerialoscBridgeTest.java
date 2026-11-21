package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MonomeSerialoscBridgeTest {
    @Test public void serialoscDiscoveryAnnouncesListAndNotifyOnTheStandardPort() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        assertTrue(source.contains("SERIALOSC_PORT = 12002"));
        assertTrue(source.contains("OscBridge.encode(\"/serialosc/list\""));
        assertTrue(source.contains("OscBridge.encode(\"/serialosc/notify\""));
        assertTrue(source.contains("InetAddress.getByName(\"255.255.255.255\")"));
        assertTrue(source.contains("refresh();"));
    }

    @Test public void serialoscMapsGridArcAndDeviceMetadataMessages() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        assertTrue(source.contains("\"/serialosc/device\""));
        assertTrue(source.contains("\"/serialosc/add\""));
        assertTrue(source.contains("\"/serialosc/remove\""));
        assertTrue(source.contains("\"/sys/size\""));
        assertTrue(source.contains("\"/sys/prefix\""));
        assertTrue(source.contains("sendControl(device, \"/sys/info\", localHost, Integer.valueOf(localPort))"));
        assertFalse(source.contains("sendControl(device, \"/sys/prefix\", \"/monome\")"));
        assertTrue(source.contains("path.endsWith(\"/grid/key\")"));
        assertTrue(source.contains("path.endsWith(\"/tilt\")"));
        assertTrue(source.contains("path.endsWith(\"/enc/delta\")"));
        assertTrue(source.contains("path.endsWith(\"/enc/key\")"));
    }

    @Test public void advertisedReplyAddressUsesOnlyInterfacesWithLanBroadcasts() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        String selector = source.substring(source.indexOf("private static NetworkTarget chooseNetworkTarget()"));
        assertTrue(selector.contains("if (!wifi && !ethernet) continue;"));
        assertTrue(selector.contains("entry.getBroadcast() == null"));
        assertFalse(selector.contains(": 2;"));
    }

    @Test public void serialoscBoundsPacketsAndStopsTheUdpReceiverOnClose() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        assertTrue(source.contains("MAX_PACKET_BYTES = 65507"));
        assertTrue(source.contains("data.length > MAX_PACKET_BYTES"));
        assertTrue(source.contains("current.close()"));
        assertTrue(source.contains("thread.join(250)"));
        assertTrue(source.contains("private void notifyListener(String action, Runnable callback)"));
        assertTrue(source.contains("callback.run()"));
        assertTrue(source.contains("catch (RuntimeException error)"));
        assertTrue(source.contains("notifyListener(\"grid key\""));
        assertTrue(source.contains("notifyListener(\"arc delta\""));
        assertTrue(source.contains("notifyListener(\"device info\""));
        assertFalse(source.contains("if (listener != null) listener.onDeviceInfo(device)"));
        assertTrue(source.contains("bySerial.clear()"));
        assertTrue(source.contains("byId.clear()"));
    }

    @Test public void serialoscRegistrationCapturesStableDeviceForAddedCallback() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        assertTrue(source.contains("final Device registered = device;"));
        assertTrue(source.contains("listener.onDeviceAdded(registered)"));
        assertFalse(source.contains("listener.onDeviceAdded(device)"));
    }

    @Test public void serialoscAddNotificationRegistersDeviceImmediately() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        int add = source.indexOf("if (\"/serialosc/add\".equals(path))");
        int remove = source.indexOf("if (\"/serialosc/remove\".equals(path))", add);
        assertTrue(add >= 0 && remove > add);
        String addBranch = source.substring(add, remove);
        assertTrue(addBranch.contains("args.length >= 3"));
        int register = addBranch.indexOf("registerDevice((String) args[0], (String) args[1], sourceHost,");
        int refresh = addBranch.indexOf("refresh();");
        assertTrue(register >= 0 && refresh > register);
        String removeBranch = source.substring(remove, source.indexOf("Device device = findDevice", remove));
        assertTrue(removeBranch.contains("removeDevice((String) args[0])"));
        assertTrue(removeBranch.contains("refresh();"));
    }

    @Test public void serialoscRejectsOutOfRangeGridAndArcEvents() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        assertTrue(source.contains("int maxCols = device.cols > 0 ? Math.min(128, device.cols) : 128;"));
        assertTrue(source.contains("int maxRows = device.rows > 0 ? Math.min(64, device.rows) : 64;"));
        assertTrue(source.contains("state == 0 || state == 1"));
        assertTrue(source.contains("ring < Math.max(1, device.rings)"));
        assertTrue(source.contains("delta >= -32768 && delta <= 32767"));
        assertTrue(source.contains("sensor >= 0 && sensor < 16"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void serialoscDropsQueuedPacketsAfterShutdownAndSerializesWithClose() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        assertTrue(source.contains("private synchronized void handleMessage(DatagramSocket listening"));
        assertTrue(source.contains("if (!running || socket != listening || listening.isClosed()) return;"));
        assertTrue(source.contains("handleMessage(listening, (String) decoded[0]"));
        assertTrue(source.contains("synchronized void close()"));
        assertTrue(source.indexOf("private synchronized void handleMessage(DatagramSocket listening")
                < source.indexOf("private synchronized void registerDevice("));
    }

    @Test public void identifiesGridAndArcDeviceModels() {
        assertTrue(MonomeSerialoscBridge.isArcType("arc 4"));
        assertTrue(MonomeSerialoscBridge.isArcType("monome arc 2"));
        assertFalse(MonomeSerialoscBridge.isArcType("monome 128"));
        assertEquals(4, MonomeSerialoscBridge.arcRingCount("arc 4"));
        assertEquals(2, MonomeSerialoscBridge.arcRingCount("arc 2"));
        assertEquals(1, MonomeSerialoscBridge.arcRingCount("arc 1"));
        assertEquals(0, MonomeSerialoscBridge.arcRingCount("monome grid 128"));
    }

    @Test public void normalizesPrefixesAndLeavesSerialoscSystemPathsUnprefixed() {
        assertEquals("/monome", MonomeSerialoscBridge.normalizePrefix(null));
        assertEquals("/custom", MonomeSerialoscBridge.normalizePrefix("custom"));
        assertEquals("/custom/grid/key", MonomeSerialoscBridge.prefixedPath("/custom", "/grid/key"));
        assertEquals("/custom/grid/key", MonomeSerialoscBridge.prefixedPath("/custom", "/custom/grid/key"));
        assertEquals("/sys/rotation", MonomeSerialoscBridge.devicePath("/custom", "/sys/rotation"));
        assertEquals("/custom/ring/set", MonomeSerialoscBridge.devicePath("/custom", "/ring/set"));
    }

    @Test public void mapsArcAnglesToThe64LedRing() {
        assertEquals(0, MonomeSerialoscBridge.arcLedIndex(0));
        assertEquals(16, MonomeSerialoscBridge.arcLedIndex(Math.PI / 2));
        assertEquals(32, MonomeSerialoscBridge.arcLedIndex(Math.PI));
        assertEquals(48, MonomeSerialoscBridge.arcLedIndex(-Math.PI / 2));
        assertEquals(0, MonomeSerialoscBridge.arcLedIndex(Math.PI * 2));
        assertEquals(0, MonomeSerialoscBridge.arcLedIndex(Double.NaN));
    }
}
