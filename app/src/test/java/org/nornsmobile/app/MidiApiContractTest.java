package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MidiApiContractTest {
    @Test public void channelAndSystemMessagesUseCorrectMIDIByteLengths() {
        assertArrayEquals(new byte[]{(byte)0x90, 60, 100},
                MidiBridge.encodeMessage(0x90, 60, 100));
        assertArrayEquals(new byte[]{(byte)0xc0, 10},
                MidiBridge.encodeMessage(0xc0, 10, 0));
        assertArrayEquals(new byte[]{(byte)0xf2, 1, 2},
                MidiBridge.encodeMessage(0xf2, 1, 2));
        assertArrayEquals(new byte[]{(byte)0xf3, 7},
                MidiBridge.encodeMessage(0xf3, 7, 0));
        assertArrayEquals(new byte[]{(byte)0xf8},
                MidiBridge.encodeMessage(0xf8, 0, 0));
        assertArrayEquals(new byte[]{(byte)0xfa},
                MidiBridge.encodeMessage(0xfa, 0, 0));
    }

    @Test public void midiChannelDataBytesOutsideSevenBitRangeAreRejected() {
        assertEquals(0, MidiBridge.encodeMessage(0x90, -1, 100).length);
        assertEquals(0, MidiBridge.encodeMessage(0x90, 60, 128).length);
        assertEquals(0, MidiBridge.encodeMessage(0xc0, 128, 0).length);
        assertArrayEquals(new byte[]{(byte)0x90, 127, 127},
                MidiBridge.encodeMessage(0x90, 127, 127));
        assertArrayEquals(new byte[]{(byte)0xc0, 127},
                MidiBridge.encodeMessage(0xc0, 127, 0));
    }

    @Test public void nornsMidiPortExposesSendAndTransportMethods() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function port:send(data)"));
        assertTrue(boot.contains("host.midi_send_bytes(port.id, data)"));
        assertTrue(boot.contains("function port:key_pressure(note, value, ch)"));
        assertTrue(boot.contains("function port:channel_pressure(value, ch)"));
        assertTrue(boot.contains("function port:start()"));
        assertTrue(boot.contains("function port:continue()"));
        assertTrue(boot.contains("function port:stop()"));
        assertTrue(boot.contains("function port:clock()"));
        assertTrue(boot.contains("function port:song_position(lsb, msb)"));
        assertTrue(boot.contains("function port:song_select(value)"));
        assertTrue(boot.contains("function port:clock_receive(enabled)"));
        assertTrue(boot.contains("function midi.cleanup()"));
        assertTrue(boot.contains("function midi.new(id, name, dev)"));
        assertTrue(boot.contains("midi.note_on = function(...)"));
        assertTrue(boot.contains("midi.song_position = function(...)"));
        assertTrue(boot.contains("midi.clock_receive = function(enabled)"));
    }

    @Test public void androidMidiDevicePortsAreExposedAsIndependentNornsPorts() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        assertTrue(bridge.contains("int portCount = Math.max(sendPorts, receivePorts)"));
        assertTrue(bridge.contains("portIds.idFor(sessionId, portIndex)"));
        assertTrue(bridge.contains("name + \" — porta \" + (portIndex + 1)"));
        assertTrue(bridge.contains("device.openInputPort(portIndex)"));
        assertTrue(bridge.contains("device.openOutputPort(portIndex)"));
        assertTrue(bridge.contains("portIndices.get(id)"));
    }

    @Test public void androidMidiPortDirectionsAreMappedFromTheNornsPointOfView() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(bridge.contains("portIndex < androidOutputPorts ? 1 : 0,"));
        assertTrue(bridge.contains("portIndex < androidInputPorts ? 1 : 0);"));
        assertTrue(boot.contains("inputs recebem dados enviados pelo Norns"));
        assertTrue(boot.contains("outputs enviam dados ao Norns"));
        assertTrue(boot.contains("or ((d.outputs or 0) > 0 and \"input\" or \"output\")"));
        assertTrue(bridge.contains("device.openInputPort(portIndex)"));
        assertTrue(bridge.contains("device.openOutputPort(portIndex)"));
    }

    @Test public void asymmetricAndroidMidiPortsKeepNornsInputAndOutputDirectionsCorrect() {
        MidiBridge.Device deviceInputOnly =
                MidiBridge.deviceForAndroidPort(1, "MIDI input", 0, 0, 1);
        assertEquals(1, deviceInputOnly.inputs);
        assertEquals(0, deviceInputOnly.outputs);

        MidiBridge.Device deviceOutputOnly =
                MidiBridge.deviceForAndroidPort(2, "MIDI output", 0, 1, 0);
        assertEquals(0, deviceOutputOnly.inputs);
        assertEquals(1, deviceOutputOnly.outputs);

        MidiBridge.Device duplex =
                MidiBridge.deviceForAndroidPort(3, "MIDI duplex", 0, 1, 1);
        assertEquals(1, duplex.inputs);
        assertEquals(1, duplex.outputs);
    }

    @Test public void virtualMidiPortIsFirstLikeNorns() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("midi.vports[1].id, midi.vports[1].name = 0, \"virtual\""));
        assertTrue(boot.contains("midi.vports[1].virtual, midi.vports[1].type = true, \"output\""));
        assertTrue(boot.contains("if n == 1 then return midi.connect_virtual() end"));
    }

    @Test public void midiConnectReusesLivePortsAndCloseCannotRemoveReplacement() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local existing = midi._ports[0]"));
        assertTrue(boot.contains("if existing and existing.connected then"));
        assertTrue(boot.contains("slot.device, slot.connected, slot.connecting = existing,"));
        assertTrue(boot.contains("if midi._ports[0] == self then midi._ports[0] = nil end"));
        assertTrue(boot.contains("if midi._ports[self.id] == self then midi._ports[self.id] = nil end"));
    }


    @Test public void midiClockInputCanBePinnedToAnExplicitConnectedDevice() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int setter = boot.indexOf("function midi.set_clock_receive_device");
        int updater = boot.indexOf("function midi.update_clock_receive()", setter);
        int send = boot.indexOf("function midi.send(data)", updater);
        assertTrue(setter >= 0 && updater > setter && send > updater);
        String selector = boot.substring(setter, send);
        assertTrue(selector.contains("midi.clock_receive_device = id"));
        assertTrue(selector.contains("id == 0"));
        assertTrue(selector.contains("candidate.connected"));
        assertTrue(selector.contains("candidate.type == \"input\" or candidate.type == \"duplex\""));
        assertTrue(selector.contains("local requested = tonumber(midi.clock_receive_device)"));
        assertTrue(selector.contains("midi.clock_receive_device = nil"));
        assertTrue(selector.contains("table.sort(ids)"));
    }

    @Test public void midiClockInputSelectsOneConnectedExternalInputAndRefreshesOnHotplug() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(boot.contains("function midi.update_clock_receive()"));
        assertTrue(boot.contains("port.type == \"input\" or port.type == \"duplex\""));
        assertTrue(boot.contains("port:clock_receive(port == selected)"));
        assertTrue(boot.contains("if midi and type(midi.update_clock_receive) == \"function\""));
        assertTrue(host.contains("midiTable.get(\"update_clock_receive\")"));
        assertTrue(host.contains("MIDI clock input refresh"));
        assertTrue(boot.contains("id ~= 0 and port.connected"));
    }

    @Test public void midiToDataSupportsProgramAndSongPosition() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("msg.program or msg.val"));
        assertTrue(boot.contains("t == \"song_position\""));
        assertTrue(boot.contains("msg.position or msg.val"));
        assertTrue(boot.contains("t == \"song_select\""));
        assertTrue(boot.contains("t == \"pitchbend\""));
        assertTrue(boot.contains("bend % 128, math.floor(bend / 128)"));
    }

    @Test public void systemCommonInputRetainsOneOrTwoDataBytes() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("status == 0xf1 || status == 0xf3"));
        assertTrue(host.contains("else if (status == 0xf2)"));
    }

    @Test public void clockReceiveFilterDoesNotSuppressRawMidiScriptEvents() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int dispatch = host.indexOf("private void dispatchMidi(int deviceId");
        int clockGate = host.indexOf("if (clockReceiveEnabled && clockEvent.isfunction() && realtimeClock)", dispatch);
        int rawEvent = host.indexOf("LuaValue devices = midiTable.get(\"devices\")", clockGate);
        assertTrue(dispatch >= 0 && clockGate > dispatch && rawEvent > clockGate);
        String body = host.substring(dispatch, rawEvent);
        assertTrue(body.contains("port.get(\"_clock_receive_enabled\")"));
        assertTrue(body.contains("clockReceiveEnabled && clockEvent.isfunction() && realtimeClock"));
        assertTrue(host.contains("if (!deviceEvent.isfunction() && !vportEvent.isfunction()"));
        assertTrue(host.contains("case H_MIDI_SEND_BYTES:"));
        assertTrue(host.contains("midi.sendBytes(a.checkint(1), bytes)"));
    }

    @Test public void luaMidiSendReportsWhetherTheBridgeAcceptedTheMessage() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(bridge.contains("synchronized boolean send(int id, int status, int data1, int data2)"));
        assertTrue(bridge.contains("synchronized boolean sendBytes(int id, byte[] message)"));
        assertTrue(bridge.contains("return openingRequests.offer(id, bytes);"));
        assertTrue(bridge.contains("if (closed) return false;"));
        assertTrue(bridge.contains("if (closed || message == null || message.length == 0"));
        assertTrue(bridge.contains("boolean accepted = NornsMidiDeviceService.sendMessage"));
        int send = host.indexOf("case H_MIDI_SEND:");
        int bytes = host.indexOf("case H_MIDI_SEND_BYTES:", send);
        int usb = host.indexOf("case H_USB_SERIAL_DEVICES:", bytes);
        assertTrue(send >= 0 && bytes > send && usb > bytes);
        String midiCases = host.substring(send, usb);
        assertTrue(midiCases.contains("return LuaValue.valueOf(midi.send("));
        assertTrue(midiCases.contains("if (count < 1 || count > 65536) return LuaValue.FALSE;"));
        assertTrue(midiCases.contains("if (!value.isnumber()) return LuaValue.FALSE;"));
        assertTrue(midiCases.contains("number != Math.floor(number)"));
        assertTrue(midiCases.contains("number < 0 || number > 255"));
        assertTrue(midiCases.contains("return LuaValue.valueOf(midi.sendBytes(a.checkint(1), bytes));"));
    }

    @Test public void nornsLuaMidiPortReturnsHostAcceptanceForRawMessages() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int methods = boot.indexOf("local function install_midi_port_methods");
        int connect = boot.indexOf("function midi.connect(n)", methods);
        assertTrue(methods >= 0 && connect > methods);
        String api = boot.substring(methods, connect);
        assertTrue(api.contains("return host.midi_send_bytes(port.id, data)"));
        assertTrue(api.contains("if port.connected or port.connecting then"));
        assertTrue(api.contains("function port:send(data)"));
        assertTrue(boot.contains("function midi.send(data)"));
        assertTrue(boot.contains("return port and port:send(data) or false"));
        assertTrue(boot.contains("function midi.connect_virtual()"));
        assertTrue(boot.contains("midi._ports[0] = port"));
    }

    @Test public void rawMidiSendRejectsNonByteAndFractionalPayloadValues() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int start = boot.indexOf("function port:send(data)");
        int end = boot.indexOf("function port:note_on", start);
        assertTrue(start >= 0 && end > start);
        String api = boot.substring(start, end);
        assertTrue(api.contains("#data > 65536"));
        assertTrue(api.contains("byte ~= math.floor(byte)"));
        assertTrue(api.contains("byte < 0 or byte > 255"));
        assertTrue(host.contains("number != Math.floor(number)"));
        assertTrue(host.contains("number < 0 || number > 255"));
        assertFalse(host.contains("(bytesValue.get(i + 1).optint(0) & 0xff)"));
    }

    @Test public void midiShutdownIsTerminalAndRejectsLateSendsAndDeviceCallbacks() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        int shutdown = bridge.indexOf("void shutdown()");
        int open = bridge.indexOf("boolean open(final int id)");
        int send = bridge.indexOf("synchronized boolean send(int id");
        int sendBytes = bridge.indexOf("synchronized boolean sendBytes(int id");
        assertTrue(shutdown >= 0 && open > shutdown && send > open && sendBytes > send);
        String shutdownPath = bridge.substring(shutdown, open);
        assertTrue(shutdownPath.contains("if (closed) return;"));
        assertTrue(shutdownPath.contains("closed = true;"));
        assertTrue(shutdownPath.contains("closeAll();"));
        assertTrue(shutdownPath.contains("unregisterDeviceCallback(deviceCallback)"));
        assertTrue(bridge.contains("if (closed) return false;"));
        assertTrue(bridge.contains("if (closed || manager == null) return Collections.emptyList();"));
        String sendPath = bridge.substring(send, sendBytes);
        assertTrue(sendPath.contains("if (closed) return false;"));
        String sendBytesPath = bridge.substring(sendBytes);
        assertTrue(sendBytesPath.contains("if (closed || message == null"));
        assertTrue(bridge.contains("openingRequests.cancelAll();"));
    }

    @Test public void sysexInputIsForwardedAsRawNornsMidiByteArray() throws Exception {
        String bridge = read("app/src/main/java/org/nornsmobile/app/MidiBridge.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String parser = read("app/src/main/java/org/nornsmobile/app/MidiStreamParser.java");
        assertTrue(bridge.contains("notifyMidiSystemExclusive(id, message.clone())"));
        assertTrue(host.contains("dispatchMidiSystemExclusive(deviceId, data)"));
        int start = host.indexOf("private void dispatchMidiSystemExclusive");
        int end = host.indexOf("private void dispatchMidiDeviceChanged", start);
        assertTrue(start >= 0 && end > start);
        String dispatch = host.substring(start, end);
        assertTrue(dispatch.contains("LuaTable bytes = new LuaTable()"));
        assertTrue(dispatch.contains("data[i] & 0xff"));
        assertTrue(dispatch.contains("deviceEvent.call(bytes)"));
        assertTrue(dispatch.contains("vportEvent.call(bytes)"));
        assertTrue(parser.contains("listener.onSystemExclusive(sysEx.toByteArray())"));
        assertTrue(parser.contains("MAX_SYSEX_BYTES = 65536"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void logicalMidiPortIdsRemainDistinctAcrossMultiPortDevices() {
        MidiPortIdRegistry ids = new MidiPortIdRegistry();
        int first = ids.idFor(10, 0);
        int second = ids.idFor(10, 1);
        int other = ids.idFor(11, 0);
        assertNotEquals(first, second);
        assertNotEquals(second, other);
        assertEquals(first, ids.idFor(10, 0));
        assertEquals(10, ids.sessionForPort(first));
        assertEquals(11, ids.sessionForPort(other));
    }

}
