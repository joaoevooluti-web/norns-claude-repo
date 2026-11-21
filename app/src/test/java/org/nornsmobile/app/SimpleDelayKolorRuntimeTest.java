package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class SimpleDelayKolorRuntimeTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Globals runtime() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String start = "local kolor_control_names = {";
        String end = "local function engine_fallback()";
        int first = boot.indexOf(start);
        int last = boot.indexOf(end, first);
        assertTrue("production Kolor command helpers must exist", first >= 0 && last > first);
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine = {_native_state='native', _kolor_nodes={}, _kolor_voice_banks={}, "
                + "_kolor_loaded={}, _kolor_load_queue={}}\n"
                + "_path={dust='/safe-root/'}; sc_reply_backlog = {}; sent_packets = {}; created_nodes = {}; logs = {}; "
                + "reply_queue = {}; freed_buffers = {}; freed_nodes = {}\n"
                + "host = {resolve_sample_path=function(path) "
                + "if string.sub(path,1,6)=='/safe/' or string.sub(path,1,11)=='/safe-root/' then return path end end, "
                + "log=function(message,iserr) logs[#logs+1]=tostring(message) end, "
                + "now=function() return 1 end, "
                + "sc_poll_reply=function() if #reply_queue==0 then return nil end; "
                + "return table.remove(reply_queue,1) end}\n"
                + "sc_api = {int=function(v) return v end, "
                + "send=function(path,...) sent_packets[#sent_packets+1]={path=path,args={...}}; return true end, "
                + "sync=function() return 77 end, "
                + "buffer_free=function(id) freed_buffers[#freed_buffers+1]=id; return true end, "
                + "set=function(node,controls) last_set=controls; return true end, "
                + "new=function(name,controls) created_nodes[#created_nodes+1]={name=name,controls=controls}; "
                + "return 900+#created_nodes end, free=function(id) freed_nodes[#freed_nodes+1]=id; return true end}\n"
                + "sched_add=function(when,fn) scheduled=fn end\n",
                "simple-delay-kolor-host.lua").call();
        globals.load(boot.substring(first, last)
                + "\nkolor_sample_test = engine_kolor_sample\n"
                + "kolor_play_test = engine_kolor_play\n"
                + "kolor_cleanup_test = engine_kolor_cleanup\n",
                "simple-delay-kolor-production-helpers.lua").call();
        return globals;
    }

    @Test public void sampleBanksUseCanonicalSandboxedWavPathsAndWaitForServerSync() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "assert(kolor_sample_test(1,'/home/we/dust/code/kolor/samples/kick.wav'))\n"
                + "assert(#sent_packets==1 and sent_packets[1].path=='/b_allocRead')\n"
                + "assert(sent_packets[1].args[1]==30001 and sent_packets[1].args[2]=='/safe-root/code/kolor/samples/kick.wav')\n"
                + "assert(engine._kolor_loading.sync_id==77 and engine._kolor_loaded[1]==nil)\n"
                + "reply_queue={{address='/synced',sync_id=77}}; scheduled()\n"
                + "assert(engine._kolor_loaded[1]==true and engine._kolor_loading==nil)\n"
                + "assert(not kolor_sample_test(13,'/safe/out-of-range.wav'))\n"
                + "assert(not kolor_sample_test(2,'/outside/escape.wav'))\n"
                + "assert(#sent_packets==1)\n"
                + "return true", "kolor-safe-bank-load.lua").call();
        assertTrue("WAV bank must load only after sandbox validation and /synced", result.toboolean());
    }

    @Test public void failedBufferReadIsNotMarkedLoadedEvenWhenSyncReplyAlsoArrives() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "assert(kolor_sample_test(2,'/safe/bad.wav'))\n"
                + "reply_queue={{address='/fail',command='/b_allocRead',message='invalid WAV'},"
                + "{address='/synced',sync_id=77}}; scheduled()\n"
                + "assert(engine._kolor_loaded[2]~=true and engine._kolor_loading==nil)\n"
                + "assert(#freed_buffers==1 and freed_buffers[1]==30002)\n"
                + "return true", "kolor-failed-bank-load.lua").call();
        assertTrue("a failed b_allocRead must remain unavailable", result.toboolean());
    }

    @Test public void kolorplayMapsAllFortyFourControlsToTheCorrectVoiceAndBank() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "engine._kolor_loaded[3]=true\n"
                + "local args={2,12.5}\n"
                + "for i=1,44 do args[#args+1]=i/10 end\n"
                + "args[47]=3.5; args[48]=3; args[49]=0.25\n"
                + "assert(kolor_play_test(args))\n"
                + "assert(#created_nodes==1 and created_nodes[1].name=='NornsKolorPlayer')\n"
                + "local c=created_nodes[1].controls\n"
                + "assert(c.sampleBufnum==30003 and c.currentTime==12.5)\n"
                + "assert(c.ampMin==0.1 and c.ampMax==0.2 and c.ampLFOMin==0.3 and c.ampLFOMax==0.4)\n"
                + "assert(c.delayFeedbackLFOMax==4.4 and c.lfolfo==3.5 and c.secondsPerBeat==0.25)\n"
                + "assert(c.t_trig==1 and c.t_gate==1)\n"
                + "assert(not kolor_play_test({2,12.5}))\n"
                + "return true", "kolor-play-control-map.lua").call();
        assertTrue("kolorplay must map all upstream controls to the correct sample player", result.toboolean());
    }

    @Test public void nativeStartupUsesTwoCorrelatedSyncBarriersBeforeCreatingDelayNode() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String start = "local function engine_poll_kolor_native(generation, deadline)";
        String end = "local function engine_poll_native(generation, deadline)";
        int first = boot.indexOf(start);
        int last = boot.indexOf(end, first);
        assertTrue("production native startup poller must exist", first >= 0 && last > first);
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine={_native_generation=7,_native_state='loading',name='SimpleDelayKolor',"
                + "_kolor_phase=1,_kolor_phase_sync_id=11,_native_pending={},"
                + "_kolor_pending={{name='kolorsample',args={1,'/safe/a.wav'}},"
                + "{name='kolorplay',args={1,2}}},_kolor_delay=0.03,_kolor_volume=0.5}\n"
                + "sc_reply_backlog={}; reply_queue={}; loaded_defs={}; replayed={}; fallback=false; "
                + "finished=nil; created_node=nil; loaded_engine=nil; sync_calls=0\n"
                + "host={now=function() return 1 end,log=function() end,"
                + "engine_load=function(name) loaded_engine=name; return true end,"
                + "sc_poll_reply=function() if #reply_queue==0 then return nil end; "
                + "return table.remove(reply_queue,1) end}\n"
                + "sc_api={load_synthdef=function(name) loaded_defs[#loaded_defs+1]=name; return true end,"
                + "sync=function() sync_calls=sync_calls+1; return 22 end,"
                + "new=function(name,controls) created_node={name=name,controls=controls}; return 901 end}\n"
                + "sched_add=function(when,fn) scheduled=fn end\n"
                + "engine_fallback=function() fallback=true end\n"
                + "engine_finish_load=function(ok) finished=ok end\n"
                + "engine_dispatch_command=function(name) replayed[#replayed+1]=name end\n"
                + "engine_kolor_sample=function() replayed[#replayed+1]='kolorsample' end\n"
                + "engine_kolor_play=function() replayed[#replayed+1]='kolorplay' end\n",
                "kolor-native-startup-host.lua").call();
        globals.load(boot.substring(first, last)
                + "\nkolor_poll_test=engine_poll_kolor_native\n",
                "kolor-native-startup-poller.lua").call();
        LuaValue result = globals.load(
                "reply_queue={{address='/synced',sync_id=11}}; kolor_poll_test(7,10)\n"
                + "assert(engine._kolor_phase==2 and engine._kolor_phase_sync_id==22)\n"
                + "assert(loaded_defs[1]=='NornsKolorDelay.scsyndef' and created_node==nil)\n"
                + "assert(type(scheduled)=='function' and engine._native_state=='loading')\n"
                + "reply_queue={{address='/done',command='/d_recv'},"
                + "{address='/synced',sync_id=22}}; scheduled()\n"
                + "assert(engine._native_state=='native' and loaded_engine=='none')\n"
                + "assert(created_node.name=='NornsKolorDelay' and created_node.controls.volume==0.5)\n"
                + "assert(finished==true and fallback==false and engine._kolor_phase==nil)\n"
                + "assert(replayed[1]=='kolorsample' and replayed[2]=='kolorplay')\n"
                + "return true", "kolor-native-startup-two-phase.lua").call();
        assertTrue("both SynthDefs must be synchronized before the delay node and queued commands are activated",
                result.toboolean());
    }

    @Test public void cleanupFreesLoadedAndInFlightBanksAndAllNativeNodes() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "engine._kolor_nodes={[1]=901}; engine._kolor_delay_node=902\n"
                + "engine._kolor_loaded={[3]=true}; engine._kolor_loading={slot=2,sync_id=99}\n"
                + "engine._kolor_load_queue={{slot=4,path='/safe/pending.wav'}}\n"
                + "kolor_cleanup_test()\n"
                + "assert(#freed_nodes==2 and freed_nodes[1]==901 and freed_nodes[2]==902)\n"
                + "assert(#freed_buffers==2)\n"
                + "assert((freed_buffers[1]==30003 or freed_buffers[2]==30003))\n"
                + "assert((freed_buffers[1]==30002 or freed_buffers[2]==30002))\n"
                + "assert(next(engine._kolor_nodes)==nil and next(engine._kolor_loaded)==nil)\n"
                + "assert(engine._kolor_loading==nil and #engine._kolor_load_queue==0)\n"
                + "return true", "kolor-native-cleanup.lua").call();
        assertTrue("switching engines must release active and in-flight Kolor resources", result.toboolean());
    }

    @Test public void existingVoiceNodeIsReusedForSubsequentKolorplayCalls() throws Exception {
        Globals globals = runtime();
        LuaValue result = globals.load(
                "engine._kolor_loaded[1]=true\n"
                + "local args={1,1}; for i=1,44 do args[#args+1]=0.5 end; args[47]=1; args[48]=1; args[49]=0.5\n"
                + "assert(kolor_play_test(args)); assert(#created_nodes==1)\n"
                + "assert(kolor_play_test(args)); assert(#created_nodes==1 and last_set.sampleBufnum==30001)\n"
                + "return true", "kolor-player-reuse.lua").call();
        assertTrue("each of the twelve voice nodes should be reused", result.toboolean());
    }
}
