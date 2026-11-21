package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class UsbSerialLuaRuntimeTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source fixture: " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Globals runtime() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("serial = {\n");
        int end = boot.indexOf("\nserial.add_handler({", start);
        assertTrue("production serial API block must exist", start >= 0 && end > start);
        Globals g = JsePlatform.standardGlobals();
        g.load("_norns={}\n"
                + "safe=function(fn, ...) local ok, result=pcall(fn, ...); if ok then return result end; return false end\n"
                + "host={log=function() end, usb_serial_devices=function() return {} end, "
                + "usb_serial_open=function() return true end, usb_serial_write=function() return true end, "
                + "usb_serial_close=function() end}\n", "serial-host-fixture.lua").call();
        g.load(boot.substring(start, end), "norns-serial-api.lua").call();
        return g;
    }

    @Test public void serialHandlersReceiveBinaryChunksAndDisconnectReleasesPort() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "local adds, removes, chunks = 0,0,{}\n"
                + "assert(serial.add_handler({id='fixture',match=function() return false end,"
                + "add=function(id,name,dev) adds=adds+1; assert(id==42 and dev.id==42) end,"
                + "remove=function(id) removes=removes+1; assert(id==42) end,"
                + "event=function(id,data) chunks[#chunks+1]=data end}))\n"
                + "serial._pending[42]={handler_id='fixture',attrs={vendor='fixture'}}\n"
                + "assert(_norns.serial_connected(42,'USB fixture'))\n"
                + "local payload=string.rep('x',255)..'\\0'..'z'\n"
                + "assert(_norns.serial_data(42,payload))\n"
                + "assert(#chunks==2 and #chunks[1]==255 and #chunks[2]==2 and string.byte(chunks[2],1)==0)\n"
                + "assert(serial.connected==true and serial._ports[42]~=nil)\n"
                + "assert(_norns.serial_disconnected(42,'detached'))\n"
                + "assert(serial.connected==false and serial._ports[42]==nil and removes==1 and adds==1)\n"
                + "assert(not _norns.serial_data(42,'late'))\n"
                + "return true", "serial-hotplug-runtime.lua").call();
        assertTrue("serial callbacks must preserve binary payloads and release detached ports", result.toboolean());
    }

    @Test public void serialCleanupClosesPendingAndConnectedPortsButKeepsHandlers() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "local removed=0\n"
                + "assert(serial.add_handler({id='persist',match=function() return false end,"
                + "add=function() end,remove=function() removed=removed+1 end,event=function() end}))\n"
                + "serial._pending[7]={handler_id='persist',attrs={}}\n"
                + "serial._ports[8]={handler_id='persist',dev={id=8}}\n"
                + "serial.connected=true\n"
                + "serial.cleanup()\n"
                + "assert(serial._pending[7]==nil and serial._ports[8]==nil and serial.connected==false)\n"
                + "assert(serial._handlers.persist~=nil and removed==1)\n"
                + "return true", "serial-cleanup-runtime.lua").call();
        assertTrue(result.toboolean());
    }

    @Test public void serialHotplugTracksMultiplePortsAndAllowsCleanReconnect() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "local adds, removes, events = 0,0,0\n"
                + "assert(serial.add_handler({id='multi',match=function() return false end,"
                + "add=function() adds=adds+1 end,remove=function() removes=removes+1 end,"
                + "event=function(id,data) events=events+1 end}))\n"
                + "serial._pending[11]={handler_id='multi',attrs={}}\n"
                + "serial._pending[12]={handler_id='multi',attrs={}}\n"
                + "assert(_norns.serial_connected(11,'port A'))\n"
                + "assert(_norns.serial_connected(12,'port B'))\n"
                + "assert(serial.connected and serial._ports[11] and serial._ports[12])\n"
                + "assert(_norns.serial_data(12,'\\0binary'))\n"
                + "assert(events==1)\n"
                + "assert(_norns.serial_disconnected(11,'unplug A'))\n"
                + "assert(serial.connected and serial._ports[11]==nil and serial._ports[12]~=nil)\n"
                + "assert(not _norns.serial_data(11,'stale'))\n"
                + "assert(_norns.serial_disconnected(12,'unplug B'))\n"
                + "assert(not serial.connected and serial._ports[12]==nil and removes==2)\n"
                + "serial._pending[11]={handler_id='multi',attrs={}}\n"
                + "assert(_norns.serial_connected(11,'port A replugged'))\n"
                + "assert(serial.connected and serial._ports[11]~=nil and adds==3)\n"
                + "assert(_norns.serial_data(11,'fresh'))\n"
                + "assert(events==2)\n"
                + "serial.cleanup()\n"
                + "assert(not serial.connected and next(serial._ports)==nil)\n"
                + "assert(serial._handlers.multi~=nil)\n"
                + "return true", "serial-multi-hotplug-reconnect.lua").call();
        assertTrue("multiple USB serial ports must disconnect and reconnect independently", result.toboolean());
    }

    @Test public void serialDataStopsDeliveringChunksWhenHandlerDisconnectsReentrantly() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "local events, removes = 0,0\n"
                + "assert(serial.add_handler({id='reentrant',match=function() return false end,"
                + "add=function() end,remove=function() removes=removes+1 end,"
                + "event=function(id,data) events=events+1; assert(#data<=255); "
                + "if events==1 then assert(_norns.serial_disconnected(id,'removed inside callback')) end end}))\n"
                + "serial._pending[31]={handler_id='reentrant',attrs={}}\n"
                + "assert(_norns.serial_connected(31,'reentrant USB'))\n"
                + "assert(_norns.serial_data(31,string.rep('x',600)))\n"
                + "assert(events==1 and removes==1)\n"
                + "assert(serial._ports[31]==nil and serial.connected==false)\n"
                + "assert(not _norns.serial_data(31,'late'))\n"
                + "return true", "serial-reentrant-disconnect.lua").call();
        assertTrue("serial chunk dispatch must stop after a callback disconnects its own port",
                result.toboolean());
    }


    @Test public void serialRefreshDeduplicatesPendingAttachAndReopensAfterDetach() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "local available={{id=77,vendor='fixture',model='cdc',serial='one',interface='00'}}\n"
                + "local opens, closes, adds, removes, events = 0,0,0,0,0\n"
                + "host.usb_serial_devices=function() return available end\n"
                + "host.usb_serial_open=function(id) opens=opens+1; assert(id==77); return true end\n"
                + "host.usb_serial_close=function(id) closes=closes+1; assert(id==77) end\n"
                + "assert(serial.add_handler({id='hotplug',match=function(attrs) return attrs.vendor=='fixture' end,"
                + "add=function(id) adds=adds+1; assert(id==77) end,"
                + "remove=function(id) removes=removes+1; assert(id==77) end,"
                + "event=function(id,data) events=events+1 end}))\n"
                + "serial.refresh(); assert(opens==1 and serial._pending[77]~=nil)\n"
                + "serial.refresh(); assert(opens==1 and serial._pending[77]~=nil)\n"
                + "assert(_norns.serial_disconnected(77,'detached before permission'))\n"
                + "assert(serial._pending[77]==nil and serial._ports[77]==nil and not serial.connected)\n"
                + "assert(not _norns.serial_connected(77,'late permission grant'))\n"
                + "serial.refresh(); assert(opens==2 and serial._pending[77]~=nil)\n"
                + "assert(_norns.serial_connected(77,'replugged'))\n"
                + "assert(adds==1 and serial._ports[77]~=nil and serial.connected)\n"
                + "serial.refresh(); assert(opens==2 and adds==1)\n"
                + "assert(_norns.serial_data(77,'fresh data') and events==1)\n"
                + "assert(_norns.serial_disconnected(77,'detached after connect'))\n"
                + "assert(removes==1 and serial._ports[77]==nil and not serial.connected)\n"
                + "assert(not _norns.serial_data(77,'stale data'))\n"
                + "assert(not _norns.serial_disconnected(77,'duplicate detach'))\n"
                + "serial.refresh(); assert(opens==3 and serial._pending[77]~=nil)\n"
                + "assert(_norns.serial_connected(77,'second replug'))\n"
                + "assert(adds==2 and serial.connected)\n"
                + "serial.cleanup(); assert(not serial.connected and next(serial._ports)==nil)\n"
                + "assert(closes==1 and removes==2)\n"
                + "return true", "serial-attach-detach-permission-reconnect.lua").call();
        assertTrue("serial refresh must deduplicate attach, discard late grants, and support replug",
                result.toboolean());
    }


    @Test public void serialRemoveCallbackCanRefreshAndReopenSameDeviceIdReentrantly() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "local available={{id=77,vendor='fixture',model='cdc',serial='same',interface='00'}}\n"
                + "local opens, adds, removes, events = 0,0,0,0\n"
                + "host.usb_serial_devices=function() return available end\n"
                + "host.usb_serial_open=function(id) opens=opens+1; assert(id==77); return true end\n"
                + "host.usb_serial_close=function(id) assert(id==77) end\n"
                + "assert(serial.add_handler({id='reentrant-refresh',match=function(attrs) return attrs.vendor=='fixture' end,\n"
                + "add=function(id) adds=adds+1 end,\n"
                + "remove=function(id) removes=removes+1; if removes==1 then serial.refresh(); assert(serial._pending[77]~=nil) end end,\n"
                + "event=function(id,data) events=events+1 end}))\n"
                + "assert(opens==1 and serial._pending[77]~=nil)\n"
                + "assert(_norns.serial_connected(77,'old session'))\n"
                + "assert(serial._ports[77]~=nil and adds==1)\n"
                + "assert(_norns.serial_disconnected(77,'old session detached'))\n"
                + "assert(removes==1 and serial._ports[77]==nil and serial._pending[77]~=nil)\n"
                + "assert(not serial.connected)\n"
                + "assert(_norns.serial_connected(77,'replacement session'))\n"
                + "assert(serial._ports[77]~=nil and serial._ports[77].dev.name=='replacement session')\n"
                + "assert(serial.connected and adds==2 and opens==2)\n"
                + "assert(_norns.serial_data(77,'replacement data') and events==1)\n"
                + "assert(_norns.serial_disconnected(77,'replacement detached'))\n"
                + "assert(removes==2 and serial._ports[77]==nil and not serial.connected)\n"
                + "return true", "serial-reentrant-refresh-reconnect.lua").call();
        assertTrue("a remove callback may synchronously refresh and reconnect the same serial device ID",
                result.toboolean());
    }

}
