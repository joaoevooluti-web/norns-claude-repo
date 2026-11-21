package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MidiLuaRuntimeTest {
    private static Globals runtime() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        if (!file.isFile()) throw new AssertionError("Missing boot.lua");
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String startMarker = "midi = { devices = {}, vports = {}, event = nil, _ports = {} }";
        String endMarker = "-- Grid/Arc usam serialosc";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue("production MIDI API block must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "sent = {}\n"
                + "sent_bytes = {}\n"
                + "host = {"
                + "midi_devices = function() return devices or {{id=7,name='USB MIDI',inputs=1,outputs=1}} end,"
                + "midi_open = function(id) return true end,"
                + "midi_send = function(...) sent[#sent + 1] = {...}; return true end,"
                + "midi_send_bytes = function(id, data) sent_bytes[#sent_bytes + 1] = {id=id,data=data}; return true end,"
                + "midi_close = function() return true end,"
                + "log = function() end"
                + "}\n"
                + "clock = {get_source = function() return 'internal' end}\n",
                "midi-host-fixture.lua").call();
        globals.load(boot.substring(start, end) + "\nreturn midi",
                "midi-production-api-under-test.lua").call();
        return globals;
    }

    private static void run(Globals globals, String source, String name) {
        LuaValue result = globals.load(source, name).call();
        assertTrue(name + " should return true", result.toboolean());
    }

    @Test public void nornsMidiVportsAreSixteenStableSlotsAcrossHotplug() throws Exception {
        Globals globals = runtime();
        run(globals,
                "midi.refresh()\n"
                + "assert(#midi.vports == 16)\n"
                + "local slot = midi.vports[2]\n"
                + "assert(slot.name == 'USB MIDI' and slot.id == 7 and slot.info.id == 7)\n"
                + "local port = midi.connect(2)\n"
                + "assert(port == slot and port.device and port.connecting)\n"
                + "devices = {{id=8,name='Second MIDI',inputs=1,outputs=0}}\n"
                + "midi.refresh()\n"
                + "assert(midi.vports[2] == slot and slot.name == 'USB MIDI' and slot.info == nil)\n"
                + "assert(midi.vports[3].name == 'Second MIDI' and midi.vports[3].id == 8)\n"
                + "devices = {{id=7,name='USB MIDI',inputs=1,outputs=1},"
                + "{id=8,name='Second MIDI',inputs=1,outputs=0}}\n"
                + "midi.refresh()\n"
                + "assert(midi.vports[2] == slot and slot.info.id == 7)\n"
                + "assert(midi.vports[3].info.id == 8)\n"
                + "return true",
                "midi-stable-vports-hotplug.lua");
    }

    @Test public void midiClockReceiveHonorsPinnedInputAndDeterministicFallback() throws Exception {
        Globals globals = runtime();
        run(globals,
                "devices = {{id=7,name='First MIDI',inputs=1,outputs=1},"
                + "{id=8,name='Second MIDI',inputs=1,outputs=1},"
                + "{id=9,name='Output Only',inputs=1,outputs=0}}\n"
                + "midi.refresh()\n"
                + "clock.get_source = function() return 'midi' end\n"
                + "local first = midi.connect(2); first.connected, first.connecting = true, false; first.device.connected, first.device.connecting = true, false\n"
                + "local second = midi.connect(3); second.connected, second.connecting = true, false; second.device.connected, second.device.connecting = true, false\n"
                + "local output = midi.connect(4); output.connected, output.connecting = true, false; output.device.connected, output.device.connecting = true, false\n"
                + "assert(midi.set_clock_receive_device(8))\n"
                + "assert(second.device._clock_receive_enabled == true and first.device._clock_receive_enabled == false)\n"
                + "assert(output.device._clock_receive_enabled == false)\n"
                + "assert(midi.set_clock_receive_device(nil))\n"
                + "assert(first.device._clock_receive_enabled == true and second.device._clock_receive_enabled == false)\n"
                + "assert(not midi.set_clock_receive_device(9))\n"
                + "return true",
                "midi-clock-input-selection.lua");
    }

    @Test public void nornsMidiDevicesAreIdIndexedObjectsWithStableCallbacksAndMethods() throws Exception {
        Globals globals = runtime();
        run(globals,
                "midi.refresh()\n"
                + "local device = midi.devices[7]\n"
                + "assert(device and device.id == 7 and device.dev == 7 and device.port == 2)\n"
                + "assert(device.name == 'USB MIDI' and device.type == 'duplex')\n"
                + "local events = 0; device.event = function(data) events = events + 1 end\n"
                + "device:note_on(60,100,1)\n"
                + "assert(#sent == 1 and sent[1][1] == 7 and sent[1][2] == 0x90)\n"
                + "assert(device:send({0x90,61,101}) == true and #sent_bytes == 1)\n"
                + "device:clock_receive(true)\n"
                + "midi.vports[2]:close()\n"
                + "assert(device.connected == false and device.connecting == false)\n"
                + "local same = device.event; midi.refresh()\n"
                + "assert(midi.devices[7] == device and device.event == same)\n"
                + "local removed = 0; device.remove = function() removed = removed + 1 end\n"
                + "devices = {}; midi.refresh()\n"
                + "assert(midi.devices[7] == nil and midi.vports[2].name == 'USB MIDI')\n"
                + "assert(device.remove ~= nil and removed == 0)\n"
                + "return true",
                "midi-device-object-contract.lua");
    }

    @Test public void midiConnectSendsValidatedRawBytesAndRejectsMalformedMessages() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local port = midi.connect(2)\n"
                + "assert(port and port.id == 7 and port.connecting)\n"
                + "assert(port:send({0x90, 60, 100}))\n"
                + "assert(#sent_bytes == 1 and sent_bytes[1].id == 7)\n"
                + "assert(not port:send({0x90, 60, 256}))\n"
                + "assert(not port:send({0x40, 60, 100}))\n"
                + "assert(not port:send('not-midi'))\n"
                + "return true",
                "midi-send-validation.lua");
    }

    @Test public void midiConvenienceMethodsEncodeChannelVoiceAndSystemMessages() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local port = midi.connect(2)\n"
                + "port:note_on(60, 99, 2)\n"
                + "port:note_off(60, 0, 2)\n"
                + "port:cc(74, 100, 3)\n"
                + "port:program_change(12, 4)\n"
                + "port:pitchbend(16383, 1)\n"
                + "port:start()\n"
                + "port:note_on(300,100,1)\n"
                + "port:cc(74,256,3)\n"
                + "port:note_on(128,100,1)\n"
                + "port:cc(74,255,3)\n"
                + "assert(#sent == 6)\n"
                + "assert(sent[1][2] == 0x91 and sent[1][3] == 60 and sent[1][4] == 99)\n"
                + "assert(sent[2][2] == 0x81 and sent[2][3] == 60 and sent[2][4] == 0)\n"
                + "assert(sent[3][2] == 0xB2 and sent[3][3] == 74 and sent[3][4] == 100)\n"
                + "assert(sent[4][2] == 0xC3 and sent[4][3] == 12)\n"
                + "assert(sent[5][2] == 0xE0 and sent[5][3] == 127 and sent[5][4] == 127)\n"
                + "assert(sent[6][2] == 0xFA)\n"
                + "return true",
                "midi-convenience-encoding.lua");
    }

    @Test public void midiMessageConversionRoundTripsNoteCcPitchAndTransport() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local cases = {"
                + "{type='note_on',ch=3,note=64,vel=110},"
                + "{type='note_off',ch=2,note=60,vel=0},"
                + "{type='cc',ch=4,cc=74,val=90},"
                + "{type='pitchbend',ch=1,val=12000},"
                + "{type='start'},{type='continue'},{type='stop'},{type='clock'}"
                + "}\n"
                + "for _, message in ipairs(cases) do "
                + "local bytes = midi.to_data(message); "
                + "local decoded = midi.to_msg(bytes); "
                + "assert(decoded.type == message.type, message.type .. ' round trip failed') "
                + "end\n"
                + "return true",
                "midi-message-roundtrip.lua");
    }

    @Test public void midiHelpersMatchNornsNoteOffSongPositionAndSysExContracts() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local off = midi.to_data({type='note_off',note=61})\n"
                + "assert(off[1] == 0x80 and off[2] == 61 and off[3] == 100)\n"
                + "local song = midi.to_data({type='song_position',lsb=5,msb=6})\n"
                + "assert(song[1] == 0xF2 and song[2] == 5 and song[3] == 6)\n"
                + "local sysex = midi.to_msg({0xF0,1,2,0xF7})\n"
                + "assert(sysex.type == 'sysex' and #sysex.raw == 4)\n"
                + "local other = midi.to_msg({0xF4})\n"
                + "assert(other.type == 'other' and other.raw[1] == 0xF4)\n"
                + "return true",
                "midi-norns-helper-contracts.lua");
    }

    @Test public void virtualNornsMidiOutputRemainsAvailableAndCleansUp() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local port = midi.connect(1)\n"
                + "assert(port and port.virtual and port.connected and port.id == 0)\n"
                + "assert(port:send({0x90, 60, 100}))\n"
                + "assert(#sent_bytes == 1 and sent_bytes[1].id == 0)\n"
                + "midi.cleanup()\n"
                + "assert(midi._ports[0] == nil)\n"
                + "return true",
                "midi-virtual-cleanup.lua");
    }
    @Test public void midiClockReceiveIsExclusiveAndDisabledForInternalClock() throws Exception {
        Globals globals = runtime();
        run(globals,
                "devices = {{id=7,name='First MIDI',inputs=1,outputs=1},"
                + "{id=8,name='Second MIDI',inputs=1,outputs=1}}\n"
                + "midi.refresh()\n"
                + "local first = midi.connect(2); first.device.connected = true; first.device.connecting = false; first.connected = true; first.connecting = false\n"
                + "local second = midi.connect(3); second.device.connected = true; second.device.connecting = false; second.connected = true; second.connecting = false\n"
                + "clock.get_source = function() return 'internal' end\n"
                + "assert(not midi.update_clock_receive())\n"
                + "assert(first.device._clock_receive_enabled == false and second.device._clock_receive_enabled == false)\n"
                + "clock.get_source = function() return 'midi' end\n"
                + "assert(midi.update_clock_receive())\n"
                + "assert(first.device._clock_receive_enabled == true and second.device._clock_receive_enabled == false)\n"
                + "assert(midi.set_clock_receive_device(8))\n"
                + "assert(first.device._clock_receive_enabled == false and second.device._clock_receive_enabled == true)\n"
                + "second.connected = false; second.device.connected = false\n"
                + "assert(midi.update_clock_receive())\n"
                + "assert(first.device._clock_receive_enabled == true and second.device._clock_receive_enabled == false)\n"
                + "assert(midi.clock_receive_device == 8)\n"
                + "second.connected = true; second.device.connected = true\n"
                + "assert(midi.update_clock_receive())\n"
                + "assert(first.device._clock_receive_enabled == false and second.device._clock_receive_enabled == true)\n"
                + "return true",
                "midi-clock-exclusive-source.lua");
    }

    @Test public void midiMessageConversionNormalizesFractionalAndNonFiniteChannels() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local fractional = midi.to_data({type='note_on',ch=2.9,note=60,vel=100})\n"
                + "assert(fractional[1] == 0x91, 'fractional channel must normalize to channel 2')\n"
                + "local nan = 0/0; local infinite = math.huge\n"
                + "local invalidNan = midi.to_data({type='note_on',ch=nan,note=60,vel=100})\n"
                + "local invalidInf = midi.to_data({type='note_on',ch=infinite,note=60,vel=100})\n"
                + "assert(invalidNan[1] == 0x90 and invalidInf[1] == 0x90, 'non-finite channels must safely default to channel 1')\n"
                + "return true",
                "midi-channel-normalization.lua");
    }


    @Test public void midiDataEncoderNormalizesNonFiniteAndOutOfRangePayloadValues() throws Exception {
        Globals globals = runtime();
        run(globals,
                "local nan, inf = 0/0, math.huge\n"
                + "local note = midi.to_data({type='note_on',ch=1,note=nan,vel=inf})\n"
                + "assert(note[1]==0x90 and note[2]==0 and note[3]==0)\n"
                + "local cc = midi.to_data({type='cc',cc=-12.7,val=999})\n"
                + "assert(cc[2]==0 and cc[3]==127)\n"
                + "local bend = midi.to_data({type='pitchbend',val=nan})\n"
                + "assert(bend[1]==0xE0 and bend[2]==0 and bend[3]==64)\n"
                + "local position = midi.to_data({type='song_position',position=inf,lsb=5,msb=6})\n"
                + "assert(position[1]==0xF2 and position[2]==5 and position[3]==6)\n"
                + "local select = midi.to_data({type='song_select',song=inf})\n"
                + "assert(select[1]==0xF3 and select[2]==0)\n"
                + "return true",
                "midi-nonfinite-payload-normalization.lua");
    }


}
