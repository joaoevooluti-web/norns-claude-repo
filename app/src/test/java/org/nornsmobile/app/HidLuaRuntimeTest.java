package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class HidLuaRuntimeTest {
    // Source-contract tests read production files from the repository root.
    private static String read(String path) throws Exception {
        File file = new File(path);
        // Gradle may execute tests with the app module as the working directory.
        if (!file.isFile() && path.startsWith("app/")) {
            file = new File(path.substring("app/".length()));
        }
        if (!file.isFile()) throw new AssertionError("Missing source fixture: " + path
                + " (checked " + file.getAbsolutePath() + ")");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Globals runtime() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        if (!file.isFile()) throw new AssertionError("Missing boot.lua");
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String startMarker = "hid = {\n  devices = {}, vports = make_vports(\"hid\", 4),";
        String endMarker = "-- Crow: USB CDC-ACM serial bridge";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue("production HID API block must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "noop = function() end\n"
                + "safe = function(fn, ...) local ok, result = pcall(fn, ...); if ok then return result end; return false end\n"
                + "make_vports = function(kind, count) local ports = {}; for i=1,count do ports[i]={name='none',event=nil,remove=nil} end; return ports end\n"
                + "safe_port = function(ports,n,kind) n=tonumber(n) or 1; if n~=math.floor(n) then return nil end; return ports[n] end\n"
                + "host = {log=function() end}\n"
                + "_norns = {}\n",
                "hid-host-fixture.lua").call();
        globals.load(boot.substring(start, end), "norns-hid-api.lua").call();
        return globals;
    }

    @Test public void hidVportNameAndCallbacksSurviveDeviceRemovalAndReplug() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "_norns.hid_device_add(1,'USB Keyboard',0x1234,0x5678,true,false,false)\n"
                + "local slot = hid.vports[1]\n"
                + "local assigned = slot.name\n"
                + "assert(assigned == 'USB Keyboard [1234:5678]' and slot.device.id == 1)\n"
                + "local removed = 0; local events = 0\n"
                + "slot.event = function(t,c,v) events = events + 1 end\n"
                + "slot.remove = function() removed = removed + 1 end\n"
                + "assert(_norns.hid_device_remove(1))\n"
                + "assert(slot.name == assigned and slot.device == nil and slot.available == false)\n"
                + "assert(type(slot.event) == 'function' and removed == 1)\n"
                + "assert(not _norns.hid.event(1,1,30,1) and events == 0)\n"
                + "_norns.hid_device_add(9,'USB Keyboard',0x1234,0x5678,true,false,false)\n"
                + "assert(hid.vports[1] == slot and slot.device.id == 9 and slot.name == assigned)\n"
                + "_norns.hid.event(1,1,30,1)\n"
                + "assert(events == 0)\n"
                + "_norns.hid.event(9,1,30,1)\n"
                + "assert(events == 1)\n"
                + "assert(_norns.hid_device_remove(9))\n"
                + "assert(not _norns.hid.event(9,1,30,1) and events == 1)\n"
                + "return true",
                "hid-vport-hotplug.lua").call();
        assertTrue("HID vport hotplug contract should pass", result.toboolean());
    }

    @Test public void hidVportsRemainFourStableSelectableSlots() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "assert(#hid.vports == 4)\n"
                + "assert(hid.send == nil)\n"
                + "assert(hid.connect(4) == hid.vports[4])\n"
                + "assert(hid.connect(5) == nil)\n"
                + "return true",
                "hid-vport-slots.lua").call();
        assertTrue(result.toboolean());
    }

    @Test public void hidDispatcherRejectsMalformedAndUnmappedEvdevEvents() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "_norns.hid_device_add(4,'USB Keyboard',0x1234,0x5678,true,false,false)\n"
                + "local device = hid.devices[4]; local port = hid.vports[1]\n"
                + "local deviceEvents, portEvents = 0,0\n"
                + "device.event = function() deviceEvents=deviceEvents+1 end\n"
                + "port.event = function() portEvents=portEvents+1 end\n"
                + "assert(_norns.hid.event(4,hid.types.EV_KEY,hid.codes.KEY_A,1))\n"
                + "assert(not _norns.hid.event(4.5,hid.types.EV_KEY,hid.codes.KEY_A,1))\n"
                + "assert(not _norns.hid.event(4,hid.types.EV_KEY,hid.codes.KEY_A,0/0))\n"
                + "assert(not _norns.hid.event(4,hid.types.EV_KEY,hid.codes.KEY_A,1.5))\n"
                + "assert(not _norns.hid.event(4,99,hid.codes.KEY_A,1))\n"
                + "assert(not _norns.hid.event(4,hid.types.EV_KEY,65535,1))\n"
                + "assert(not _norns.hid.event(999,hid.types.EV_KEY,hid.codes.KEY_A,1))\n"
                + "assert(deviceEvents==1 and portEvents==1)\n"
                + "return true",
                "hid-invalid-evdev-events.lua").call();
        assertTrue("malformed HID events must be dropped before callbacks", result.toboolean());
    }


    @Test public void hidDispatcherRoutesRelativeAndAbsoluteAxesAndStopsAfterRemoval() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "_norns.hid_device_add(7,'USB Trackball',0x1234,0x5678,false,true,false)\n"
                + "local device, port = hid.devices[7], hid.vports[1]\n"
                + "local deviceEvents, portEvents = {}, {}\n"
                + "device.event = function(t,c,v) deviceEvents[#deviceEvents+1]={t,c,v} end\n"
                + "port.event = function(t,c,v) portEvents[#portEvents+1]={t,c,v} end\n"
                + "assert(_norns.hid.event(7,hid.types.EV_REL,hid.codes.REL_X,12))\n"
                + "assert(_norns.hid.event(7,hid.types.EV_ABS,hid.codes.ABS_X,-25))\n"
                + "assert(#deviceEvents==2 and #portEvents==2)\n"
                + "assert(deviceEvents[1][1]==hid.types.EV_REL and deviceEvents[1][3]==12)\n"
                + "assert(deviceEvents[2][1]==hid.types.EV_ABS and deviceEvents[2][3]==-25)\n"
                + "assert(_norns.hid_device_remove(7))\n"
                + "assert(not _norns.hid.event(7,hid.types.EV_REL,hid.codes.REL_X,1))\n"
                + "assert(#deviceEvents==2 and #portEvents==2)\n"
                + "return true",
                "hid-relative-absolute-axis-routing.lua").call();
        assertTrue("HID relative/absolute axes must reach device and vport callbacks", result.toboolean());
    }

    @Test public void gamepadDisconnectReleasesHeldAxesAndButtonsBeforeHidRemoval() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        int disconnect = host.indexOf("void gamepadDeviceChanged(");
        int axisRelease = host.indexOf("callNorns(\"gamepad_axis\"", disconnect);
        int buttonRelease = host.indexOf("callNorns(\"gamepad_button\"", axisRelease);
        int deviceRemoved = host.indexOf("callNorns(\"gamepad_device\"", buttonRelease);
        assertTrue(disconnect >= 0 && axisRelease > disconnect);
        assertTrue(buttonRelease > axisRelease && deviceRemoved > buttonRelease);
        String removedCallback = activity.substring(
                activity.indexOf("@Override public void onInputDeviceRemoved"),
                activity.indexOf("@Override public void onInputDeviceChanged"));
        assertTrue(removedCallback.indexOf("updateGamepadDevice(deviceId, false)")
                < removedCallback.indexOf("updateHidDevice(deviceId, false)"));
        assertTrue(host.contains("gamepadHidAxisCode(axis)"));
        assertTrue(host.contains("gamepadHidButtonCode(button)"));
        assertTrue(host.contains("if (\"leftx\".equals(axis)) return 0;"));
        assertTrue(host.contains("if (\"dpadx\".equals(axis)) return 0x10;"));
        assertTrue(host.contains("if (\"A\".equals(button)) return 0x130;"));
        assertTrue(host.contains("if (number >= 10 && number <= 16) return 0x2c0 + number - 10;"));
    }


    @Test public void hidDeviceCallbackMayRemoveItselfWithoutLeakingEventToStaleVport() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "_norns.hid_device_add(14,'USB Controller',0x4321,0x8765,false,false,true)\n"
                + "local device, port = hid.devices[14], hid.vports[1]\n"
                + "local deviceEvents, portEvents, removes = 0,0,0\n"
                + "device.event = function() deviceEvents=deviceEvents+1; "
                + "assert(_norns.hid_device_remove(14)) end\n"
                + "device.remove = function() removes=removes+1 end\n"
                + "port.event = function() portEvents=portEvents+1 end\n"
                + "assert(_norns.hid.event(14,hid.types.EV_KEY,hid.codes.BTN_A,1))\n"
                + "assert(deviceEvents==1 and removes==1 and portEvents==0)\n"
                + "assert(port.device==nil and port.connected==false)\n"
                + "assert(not _norns.hid.event(14,hid.types.EV_KEY,hid.codes.BTN_A,0))\n"
                + "assert(portEvents==0)\n"
                + "return true", "hid-reentrant-device-removal.lua").call();
        assertTrue("HID callbacks must not forward to a vport after reentrant removal",
                result.toboolean());
    }

    @Test public void hidMultipleControllersDetachIndependentlyAndRejectStaleCallbacks() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "_norns.hid_device_add(50,'Controller A',0x1111,0x0001,false,false,true)\n"
                + "_norns.hid_device_add(51,'Controller B',0x2222,0x0002,false,false,true)\n"
                + "local a,b = hid.devices[50],hid.devices[51]\n"
                + "local aEvents,bEvents,aRemoves,bRemoves = 0,0,0,0\n"
                + "a.event=function() aEvents=aEvents+1 end; b.event=function() bEvents=bEvents+1 end\n"
                + "a.remove=function() aRemoves=aRemoves+1 end; b.remove=function() bRemoves=bRemoves+1 end\n"
                + "assert(_norns.hid.event(50,hid.types.EV_KEY,hid.codes.BTN_A,1))\n"
                + "assert(_norns.hid.event(51,hid.types.EV_KEY,hid.codes.BTN_B,1))\n"
                + "assert(_norns.hid_device_remove(50))\n"
                + "assert(aRemoves==1 and bRemoves==0 and hid.devices[50]==nil and hid.devices[51]==b)\n"
                + "assert(not _norns.hid.event(50,hid.types.EV_KEY,hid.codes.BTN_A,0))\n"
                + "assert(_norns.hid.event(51,hid.types.EV_KEY,hid.codes.BTN_B,0))\n"
                + "assert(aEvents==1 and bEvents==2)\n"
                + "assert(_norns.hid_device_remove(51))\n"
                + "assert(bRemoves==1 and not _norns.hid.event(51,hid.types.EV_KEY,hid.codes.BTN_B,0))\n"
                + "return true", "hid-multi-controller-detach.lua").call();
        assertTrue("detaching one HID controller must not disturb another controller's Lua callbacks",
                result.toboolean());
    }

    @Test public void hidRemoveCallbackCanReplugSameDeviceIdWithoutLosingReplacement() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "_norns.hid_device_add(70,'Controller old',0x1111,0x0001,false,false,true)\n"
                + "local old, port = hid.devices[70], hid.vports[1]\n"
                + "local removed, replacementAdds, oldEvents, newEvents = 0,0,0,0\n"
                + "old.event = function() oldEvents=oldEvents+1 end\n"
                + "old.remove = function() removed=removed+1; "
                + "local replacement=_norns.hid_device_add(70,'Controller old',0x1111,0x0001,false,false,true); "
                + "assert(replacement and replacement~=old); replacement.event=function() newEvents=newEvents+1 end; "
                + "replacementAdds=replacementAdds+1 end\n"
                + "assert(_norns.hid_device_remove(70))\n"
                + "assert(hid.devices[70] and hid.devices[70]~=old)\n"
                + "assert(hid.devices[70].name=='Controller old [1111:0001]')\n"
                + "assert(port.device==hid.devices[70] and port.available and port.connected)\n"
                + "assert(oldEvents==0 and removed==1 and replacementAdds==1)\n"
                + "assert(_norns.hid.event(70,1,30,1) and newEvents==1 and oldEvents==0)\n"
                + "return true", "hid-reentrant-replug-same-id.lua").call();
        assertTrue("replug from a removal callback must not be erased by the old removal",
                result.toboolean());
    }



    @Test public void hidHotplugStressKeepsLuaDevicesAndVportsIsolatedAcrossReplugCycles() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "local adds, removes, events = {}, {}, {}\n"
                + "local ids = {81,82,83,84}\n"
                + "for _,id in ipairs(ids) do adds[id]=0; removes[id]=0; events[id]=0 end\n"
                + "for cycle=1,12 do\n"
                + "  for _,id in ipairs(ids) do\n"
                + "    local deviceId = id -- stable callback capture\n"
                + "    local device = _norns.hid_device_add(deviceId,'USB HID '..deviceId,0x1200,deviceId,false,false,true)\n"
                + "    assert(device and hid.devices[deviceId]==device)\n"
                + "    adds[deviceId]=adds[deviceId]+1\n"
                + "    device.event=function() events[deviceId]=events[deviceId]+1 end\n"
                + "    device.remove=function() assert(device.available==false); removes[deviceId]=removes[deviceId]+1 end\n"
                + "    assert(_norns.hid.event(deviceId,hid.types.EV_KEY,hid.codes.BTN_A,1))\n"
                + "    assert(events[deviceId]==cycle)\n"
                + "  end\n"
                + "  for _,id in ipairs(ids) do\n"
                + "    local old=hid.devices[id]\n"
                + "    assert(_norns.hid_device_remove(id))\n"
                + "    assert(hid.devices[id]==nil and old.available==false)\n"
                + "    assert(removes[id]==cycle)\n"
                + "    assert(not _norns.hid.event(id,hid.types.EV_KEY,hid.codes.BTN_A,0))\n"
                + "    assert(events[id]==cycle)\n"
                + "  end\n"
                + "end\n"
                + "for _,id in ipairs(ids) do assert(adds[id]==12 and removes[id]==12 and events[id]==12) end\n"
                + "return true", "hid-hotplug-replug-stress.lua").call();
        assertTrue("repeated HID attach/detach must not leak stale device or vport callbacks",
                result.toboolean());
    }

}
