package org.nornsmobile.app;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import static org.junit.Assert.*;

public class MidiHotplugContractTest {
    @Test public void bridgeRegistersAndroidHotplugAndStatusCallbacks() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        assertTrue(bridge.contains("registerDeviceCallback(deviceCallback, handler)"));
        assertTrue(bridge.contains("onDeviceAdded(MidiDeviceInfo info)"));
        assertTrue(bridge.contains("onDeviceRemoved(MidiDeviceInfo info)"));
        assertTrue(bridge.contains("notifyDeviceChanged(device, true)"));
        assertTrue(bridge.contains("notifyDeviceChanged(device, false)"));
        assertTrue(bridge.contains("unregisterDeviceCallback(deviceCallback)"));
    }

    @Test public void luaReceivesNornsMidiAddAndRemoveCallbacks() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(host.contains("dispatchMidiDeviceChanged"));
        assertTrue(host.contains("midiTable.get(added ? \"add\" : \"remove\")"));
        assertTrue(host.contains("refresh.call()"));
        assertTrue(boot.contains("midi.add = function() end"));
        assertTrue(boot.contains("midi.remove = function() end"));
    }

    @Test public void activityDisposesMidiCallbackWithLuaHost() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(host.contains("host.dispose()"));
    }

    @Test public void hotplugRemovalMetadataUsesTheSameNornsPortDirectionMappingAsRefresh() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        int start = bridge.indexOf("private static Device describePort(");
        int end = bridge.indexOf("\n    }", start);
        assertTrue("describePort must exist", start >= 0 && end > start);
        String describe = bridge.substring(start, end);
        assertTrue("hotplug metadata must reuse the canonical direction mapper",
                describe.contains("deviceForAndroidPort(logicalId, portName, portIndex"));
        assertTrue(describe.contains("info.getInputPortCount(),"));
        assertTrue(describe.contains("info.getOutputPortCount()"));
        assertFalse("do not independently invert Android input/output port directions",
                describe.contains("portIndex < info.getOutputPortCount() ? 1 : 0"));
    }

    @Test public void canonicalAndroidPortMapperKeepsNornsDirectionsForAsymmetricHotplugDevices() {
        MidiBridge.Device androidOutput = MidiBridge.deviceForAndroidPort(
                41, "Android output", 0, 0, 1);
        assertEquals("Android output ports deliver MIDI into Norns", 1, androidOutput.inputs);
        assertEquals(0, androidOutput.outputs);

        MidiBridge.Device androidInput = MidiBridge.deviceForAndroidPort(
                42, "Android input", 0, 1, 0);
        assertEquals(0, androidInput.inputs);
        assertEquals("Android input ports receive MIDI from Norns", 1, androidInput.outputs);
    }

    @Test public void midiReceiverDropsEventsFromClosedOrReplacedConnections() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        assertTrue(bridge.contains("if (connections.get(id) != connection) return;"));
        assertTrue(bridge.contains("parse(id, connection, data, offset, count)"));
        assertTrue(bridge.contains("if (connections.get(id) != source) return;"));
        assertTrue(bridge.contains("if (connections.get(id) == source)"));
        assertTrue(bridge.contains("if (connections.get(id) != connection) return;"));
        assertTrue(bridge.contains("catch (RuntimeException error)"));
        assertTrue(bridge.contains("private void notifyMidi(int id, int status, int data1, int data2)"));
        assertTrue(bridge.contains("private void notifyMidiSystemExclusive(int id, byte[] data)"));
        assertTrue(bridge.contains("private void notifyDeviceChanged(Device device, boolean added)"));
        assertTrue(bridge.contains("notifyConnection(id, true, null);"));
    }

    @Test public void midiBridgeRejectsQueuedHotplugAndOpenWorkAfterShutdown() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        assertTrue(bridge.contains("private volatile boolean closed;"));
        assertTrue(bridge.contains("if (closed) return;"));
        assertTrue(bridge.contains("if (closed || manager == null) return Collections.emptyList();"));
        assertTrue(bridge.contains("if (closed) return false;"));
        assertTrue(bridge.contains("closed = true;"));
        assertTrue(bridge.contains("closeAll();"));
        assertTrue(bridge.contains("unregisterDeviceCallback(deviceCallback)"));
    }

    @Test public void asyncMidiConnectionRecomputesExternalClockSelection() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int start = host.indexOf("private void dispatchMidiConnection");
        int end = host.indexOf("private void dispatchMidi(int deviceId", start);
        assertTrue(start >= 0 && end > start);
        String dispatch = host.substring(start, end);
        int connected = dispatch.indexOf("port.set(\"connected\", LuaValue.valueOf(connected))");
        int refresh = dispatch.indexOf("midiTable.get(\"update_clock_receive\")");
        assertTrue("connection state must update before clock selection", connected >= 0 && refresh > connected);
        assertTrue(dispatch.contains("updateClockReceive.call()"));
        assertTrue(dispatch.contains("MIDI clock connection refresh"));
    }

    @Test public void luaMidiVportsExposeSixteenStableSlotsAndKeepHotplugAssignments() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("for i = 1, 16 do"));
        assertTrue(boot.contains("name = \"none\""));
        assertTrue(boot.contains("midi.vports[1].id, midi.vports[1].name = 0, \"virtual\""));
        assertTrue(boot.contains("function midi_slot_for_name(name)"));
        assertTrue(boot.contains("slot.info = nil"));
        assertTrue(boot.contains("function midi.connect(n)"));
        assertTrue(boot.contains("return slot"));
        assertTrue(boot.contains("slot.device, slot.connected, slot.connecting = port, false, port.connecting == true"));
    }

    @Test public void javaMidiDispatchResolvesCallbacksFromPersistentVportSlots() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("LuaValue vports = midiTable.get(\"vports\")"));
        assertTrue(host.contains("slot.get(\"event\")"));
        assertTrue(host.contains("slot.set(\"connected\", LuaValue.valueOf(connected))"));
        assertTrue(host.contains("slot.set(\"connecting\", LuaValue.FALSE)"));
    }

    @Test public void midiDeviceMapAndCallbacksFollowNornsAddRemoveEventLifecycle() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(boot.contains("local previous = midi.devices or {}"));
        assertTrue(boot.contains("devices[info.id] = device"));
        assertTrue(boot.contains("device.port = slot.index"));
        assertTrue(boot.contains("device.connected, device.connecting = slot.connected, slot.connecting == true"));
        assertTrue(boot.contains("function device:send(data)"));
        assertTrue(boot.contains("\"clock_receive\""));
        assertTrue(boot.contains("device[method_name] = function(self, ...)"));
        assertTrue(host.contains("previousDevice = devices.istable() ? devices.get(device.id) : LuaValue.NIL"));
        assertTrue(host.contains("callback.call(deviceObject)"));
        assertTrue(host.contains("LuaValue remove = deviceObject.get(\"remove\")"));
        assertTrue(host.contains("deviceEvent.call(data)"));
        assertTrue(host.contains("vportEvent.call(data)"));
        assertTrue(host.contains("deviceEvent.call(bytes)"));
        assertTrue(host.contains("vportEvent.call(bytes)"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void midiOpenRequestIsSubmittedOnlyWhileItsTokenIsCurrent() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        int gate = bridge.indexOf("if (closed || !openingRequests.isCurrent(id, token)) return true;");
        int request = bridge.indexOf("manager.openDevice(info", gate);
        int callback = bridge.indexOf("onDeviceOpened(MidiDevice device)", request);
        assertTrue(gate >= 0);
        assertTrue(request > gate);
        assertTrue(callback > request);
        assertTrue(bridge.substring(bridge.lastIndexOf("synchronized (this)", gate), request)
                .contains("synchronized (this)"));
    }
}
