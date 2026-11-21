package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MonomeLuaRuntimeTest {
    private static Globals runtime() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        if (!file.isFile()) throw new AssertionError("Missing boot.lua");
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String startMarker = "-- Grid/Arc usam serialosc via OSC/UDP";
        String endMarker = "\n_norns.hid = {";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        assertTrue("production Grid/Arc API block must exist", start >= 0 && end > start);

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "devices = {}; sent = {}; refreshes = 0\n"
                + "noop = function() end\n"
                + "safe = function(fn, ...) if type(fn) ~= 'function' then return false end; local ok, value = pcall(fn, ...); if not ok then return false end; return value end\n"
                + "host = {"
                + "monome_devices = function() return devices end,"
                + "monome_send = function(id,path,args) sent[#sent+1]={id=id,path=path,args=args}; return true end,"
                + "monome_refresh = function() refreshes=refreshes+1; return true end"
                + "}\n",
                "monome-runtime-fixture.lua").call();
        globals.load(boot.substring(start, end) + "\nreturn true",
                "norns-grid-arc-api.lua").call();
        return globals;
    }

    private static void run(Globals globals, String source, String name) {
        LuaValue result = globals.load(source, name).call();
        assertTrue(name + " should return true", result.toboolean());
    }

    @Test public void gridConnectLedBoundsCallbacksAndRemovalFollowNornsApi() throws Exception {
        Globals globals = runtime();
        run(globals,
                "devices={{id=21,type='grid',serial='g1',name='Gridster',cols=16,rows=8}}\n"
                + "local added, removed, deviceKey, portKey = 0, 0, 0, 0\n"
                + "grid.add=function(d) added=added+1; assert(d.id==21) end\n"
                + "grid.remove=function(d) removed=removed+1; assert(d.id==21) end\n"
                + "local port=grid.connect(1)\n"
                + "assert(port.device.id==21 and port.cols==16 and port.rows==8 and port.available)\n"
                + "grid.devices[21].key=function(x,y,z) deviceKey=deviceKey+1; assert(x==2 and y==3 and z==1) end\n"
                + "port.key=function(x,y,z) portKey=portKey+1; assert(x==2 and y==3 and z==1) end\n"
                + "_norns.grid_key(21,2,3,1)\n"
                + "assert(deviceKey==1 and portKey==1 and added==1)\n"
                + "assert(port:led(1,1,5))\n"
                + "assert(sent[#sent].path=='/grid/led/level/set' and sent[#sent].args[1]==0 and sent[#sent].args[2]==0 and sent[#sent].args[3]==5)\n"
                + "assert(port:led(1,1,3,true) and sent[#sent].args[3]==8)\n"
                + "assert(not port:led(0,1,5) and not port:led(17,1,5))\n"
                + "assert(port:all(4) and sent[#sent].path=='/grid/led/level/all')\n"
                + "assert(port:rotation(180) and port:intensity(7) and port:tilt_enable(1,true))\n"
                + "devices={}\n"
                + "grid.update_devices()\n"
                + "assert(removed==1 and port.device==nil and port.available==false and port.name=='Gridster g1')\n"
                + "return true",
                "grid-lua-runtime-contract.lua");
    }

    @Test public void arcLedRingBoundsCallbacksAndRemovalFollowNornsApi() throws Exception {
        Globals globals = runtime();
        run(globals,
                "devices={{id=31,type='arc',serial='a1',name='Arc',rings=2}}\n"
                + "local added, removed, delta, key = 0, 0, 0, 0\n"
                + "arc.add=function(d) added=added+1; assert(d.id==31) end\n"
                + "arc.remove=function(d) removed=removed+1; assert(d.id==31) end\n"
                + "local port=arc.connect(1)\n"
                + "assert(port.device.id==31 and port.device.rings==2 and port.available)\n"
                + "assert(port:led(2,64,9))\n"
                + "assert(sent[#sent].path=='/ring/set' and sent[#sent].args[1]==1 and sent[#sent].args[2]==63 and sent[#sent].args[3]==9)\n"
                + "assert(not port:led(3,1,5) and not port:led(1,65,5))\n"
                + "assert(port:all(6) and sent[#sent].path=='/ring/all')\n"
                + "arc.devices[31].delta=function(r,d) delta=delta+1; assert(r==2 and d==-3) end\n"
                + "port.delta=function(r,d) delta=delta+1; assert(r==2 and d==-3) end\n"
                + "arc.devices[31].key=function(r,z) key=key+1; assert(r==1 and z==1) end\n"
                + "_norns.arc_delta(31,2,-3); _norns.arc_key(31,1,1)\n"
                + "assert(delta==2 and key==1 and added==1)\n"
                + "devices={}\n"
                + "arc.update_devices()\n"
                + "assert(removed==1 and port.device==nil and port.available==false and port.name=='Arc a1')\n"
                + "return true",
                "arc-lua-runtime-contract.lua");
    }

    @Test public void previouslyUnassignedGridAndArcDevicesClaimFreedPortsOnHotplug() throws Exception {
        Globals globals = runtime();
        run(globals,
                "devices={"
                + "{id=1,type='grid',serial='g1',name='Grid',cols=8,rows=8},"
                + "{id=2,type='grid',serial='g2',name='Grid',cols=8,rows=8},"
                + "{id=3,type='grid',serial='g3',name='Grid',cols=8,rows=8},"
                + "{id=4,type='grid',serial='g4',name='Grid',cols=8,rows=8},"
                + "{id=5,type='grid',serial='g5',name='Grid',cols=8,rows=8},"
                + "{id=11,type='arc',serial='a1',name='Arc',rings=2},"
                + "{id=12,type='arc',serial='a2',name='Arc',rings=2},"
                + "{id=13,type='arc',serial='a3',name='Arc',rings=2},"
                + "{id=14,type='arc',serial='a4',name='Arc',rings=2},"
                + "{id=15,type='arc',serial='a5',name='Arc',rings=2}}\n"
                + "grid.update_devices(); arc.update_devices()\n"
                + "assert(grid.devices[5].port==nil and arc.devices[15].port==nil)\n"
                + "devices={"
                + "{id=2,type='grid',serial='g2',name='Grid',cols=8,rows=8},"
                + "{id=3,type='grid',serial='g3',name='Grid',cols=8,rows=8},"
                + "{id=4,type='grid',serial='g4',name='Grid',cols=8,rows=8},"
                + "{id=5,type='grid',serial='g5',name='Grid',cols=8,rows=8},"
                + "{id=12,type='arc',serial='a2',name='Arc',rings=2},"
                + "{id=13,type='arc',serial='a3',name='Arc',rings=2},"
                + "{id=14,type='arc',serial='a4',name='Arc',rings=2},"
                + "{id=15,type='arc',serial='a5',name='Arc',rings=2}}\n"
                + "grid.update_devices(); arc.update_devices()\n"
                + "assert(grid.devices[5].port~=nil and grid.vports[grid.devices[5].port].device.id==5)\n"
                + "assert(arc.devices[15].port~=nil and arc.vports[arc.devices[15].port].device.id==15)\n"
                + "return true",
                "monome-hotplug-vport-reassignment.lua");
    }
}
