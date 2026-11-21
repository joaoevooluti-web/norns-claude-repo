package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Executes production Java/native engine lifecycle with stale OSC replies and generation-specific sync barriers. */
public class EngineNativeLifecycleTest {
    private static String readBoot() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        assertTrue("Missing boot.lua", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Globals runtime() throws Exception {
        String boot = readBoot();
        int finishStart = boot.indexOf("local function engine_finish_load(ok, generation)");
        int finishEnd = boot.indexOf("\nlocal kolor_control_names", finishStart);
        int fallbackStart = boot.indexOf("local function engine_fallback()");
        int fallbackEnd = boot.indexOf("\nlocal function engine_polyperc_note", fallbackStart);
        int pollStart = boot.indexOf("local engine_dispatch_command\n\nlocal function engine_poll_kolor_native(generation, deadline)");
        int pollEnd = boot.indexOf("\nlocal function engine_start_native(callback)", pollStart);
        int startStart = pollEnd;
        int resetStart = boot.indexOf("\nfunction engine._reset_native()", startStart);
        int resetEnd = boot.indexOf("\nfunction engine._start_native(callback)", resetStart);
        int wrapperStart = resetEnd;
        int loadStart = boot.indexOf("\nfunction engine.load(name, cb)", wrapperStart);
        int loadEnd = boot.indexOf("\nlocal function engine_registration_count", loadStart);
        int listStart = boot.indexOf("\nfunction engine.list_commands()", loadEnd);
        int listEnd = boot.indexOf("\n-- ===================================================================== stubs", listStart);
        assertTrue(finishStart >= 0 && finishEnd > finishStart);
        assertTrue(fallbackStart >= 0 && fallbackEnd > fallbackStart);
        assertTrue(pollStart > fallbackEnd && pollEnd > pollStart);
        assertTrue(startStart > pollStart && resetStart > startStart);
        assertTrue(resetEnd > resetStart && loadStart > wrapperStart && loadEnd > loadStart);
        assertTrue(listStart > loadEnd && listEnd > listStart);

        Globals g = JsePlatform.standardGlobals();
        g.load(
                "tasks={} replies={} next_sync=100 sync_sent={}\n"
                + "sched_add=function(_, fn) tasks[#tasks+1]=fn end\n"
                + "engine_kolor_cleanup=function() end\n"
                + "engine={name='none',loaded=false,_native_generation=0,_native_state='java',"
                + "_native_params={},_native_pending={},list_commands=function() return {'hz','amp'} end}\n"
                + "softcut={_engine_cut_level=0}\n"
                + "audio={level_eng_cut=function() return true end}\n"
                + "host={now=function() return 1 end,log=function() end,engine_load=function(name) selected_engine=name end,"
                + "sc_poll_reply=function() if #replies==0 then return nil end; return table.remove(replies,1) end,"
                + "sc_send=function(path,args) sync_sent[#sync_sent+1]=args[1]; return true end}\n"
                + "sc_api={is_running=function() return server_running end,"
                + "load_synthdef=function(name) loaded_synthdef=name; return true end,"
                + "sync=function() next_sync=next_sync+1; return next_sync end,"
                + "new=function(name,controls) return {id=next_sync,name=name} end,"
                + "free=function() return true end}\n"
                + "host.engine_cmd=function() return true end\n"
                + "server_running=true; sc_reply_backlog={}\n",
                "engine-native-lifecycle-host.lua").call();

        String chunk = boot.substring(finishStart, finishEnd)
                + boot.substring(fallbackStart, fallbackEnd)
                + boot.substring(pollStart, pollEnd)
                + boot.substring(startStart, resetStart)
                + boot.substring(resetStart + 1, resetEnd)
                + boot.substring(wrapperStart + 1, loadEnd)
                + boot.substring(listStart + 1, listEnd);
        g.load(chunk, "production-native-engine-lifecycle.lua").call();
        return g;
    }

    @Test public void missingPrivateEngineStateDoesNotResolveAsCommandProxy() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "assert(engine._native_sync_id == nil)\n"
                + "assert(engine._kolor_phase_sync_id == nil)\n"
                + "assert(engine._retired_native_sync_ids == nil)\n"
                + "assert(engine._native_node == nil)\n"
                + "assert(engine._load_callback == nil)\n"
                + "assert(type(engine.hz) == 'function')\n"
                + "return true",
                "engine-private-state-is-not-a-command.lua").call();
        assertTrue("missing private state must not become an engine command closure", result.toboolean());
    }

    @Test public void staleNativeCompletionCannotFinishNewGenerationAfterResetAndScriptSwitch() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "old_callback_calls=0; java_callback_calls=0; new_callback_calls=0\n"
                + "assert(engine.load('TestSine', function() old_callback_calls=old_callback_calls+1 end))\n"
                + "local retired_task=tasks[1]; local retired_sync=engine._native_sync_id\n"
                + "assert(engine._native_state=='loading' and retired_sync~=nil)\n"
                + "-- Script cleanup invalidates the first native load before selecting a Java engine.\n"
                + "engine._reset_native()\n"
                + "assert(engine.load('FM', function(commands) java_callback_calls=java_callback_calls+1; assert(#commands==6) end))\n"
                + "assert(engine._native_state=='java' and java_callback_calls==1)\n"
                + "-- A second script starts a fresh native generation.\n"
                + "assert(engine.load('PolyPerc', function(commands) new_callback_calls=new_callback_calls+1; assert(#commands==7) end))\n"
                + "local current_task=tasks[2]; local current_sync=engine._native_sync_id\n"
                + "assert(current_sync~=nil and current_sync~=retired_sync and engine._native_state=='loading')\n"
                + "replies[#replies+1]={address='/fail',command='/d_recv',message='retired load failed'}\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=retired_sync}\n"
                + "retired_task()\n"
                + "assert(old_callback_calls==0 and java_callback_calls==1 and new_callback_calls==0)\n"
                + "current_task()\n"
                + "assert(engine._native_state=='loading' and new_callback_calls==0)\n"
                + "-- Only the current generation's sync fence may complete the native load.\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=current_sync}\n"
                + "current_task()\n"
                + "assert(engine._native_state=='native' and new_callback_calls==1)\n"
                + "assert(old_callback_calls==0 and java_callback_calls==1)\n"
                + "return true",
                "native-engine-stale-completion-after-script-switch.lua").call();
        assertTrue("retired native completion must not complete the active engine generation", result.toboolean());
    }

    @Test public void simpleDelayKolorTwoPhaseNativeLoadCompletesOnlyAfterBothSyncBarriers() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "kolor_callback_calls=0\n"
                + "assert(engine.load('SimpleDelayKolor', function(commands) kolor_callback_calls=kolor_callback_calls+1; assert(#commands==4) end))\n"
                + "local phase_one=tasks[1]; local sync_one=engine._kolor_phase_sync_id\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=sync_one}\n"
                + "phase_one()\n"
                + "assert(engine._native_state=='loading' and engine._kolor_phase==2 and kolor_callback_calls==0)\n"
                + "local phase_two=tasks[2]; local sync_two=engine._kolor_phase_sync_id\n"
                + "assert(sync_two~=sync_one)\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=sync_two}\n"
                + "phase_two()\n"
                + "assert(engine._native_state=='native' and kolor_callback_calls==1)\n"
                + "return true",
                "simple-delay-kolor-two-phase-native-lifecycle.lua").call();
        assertTrue("SimpleDelayKolor must wait for both generation-specific sync barriers", result.toboolean());
    }

    @Test public void nativeFailureWaitsForItsSyncFenceBeforeCompletingFallbackCallback() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "failure_callback_calls=0\n"
                + "assert(engine.load('TestSine', function(commands) failure_callback_calls=failure_callback_calls+1; assert(#commands==2) end))\n"
                + "local poll=tasks[1]; local sync_id=engine._native_sync_id\n"
                + "replies[#replies+1]={address='/fail',command='/d_recv',message='bad SynthDef'}\n"
                + "poll()\n"
                + "assert(engine._native_state=='loading' and failure_callback_calls==0)\n"
                + "replies[#replies+1]={address='/synced',sync_id=sync_id}\n"
                + "poll()\n"
                + "assert(engine._native_state=='java' and failure_callback_calls==1)\n"
                + "return true",
                "native-engine-failure-awaits-sync-fence.lua").call();
        assertTrue("native load failure must be reported only after its own sync barrier", result.toboolean());
    }

    @Test public void nativeHarmonyAndStratumEnginesCompleteTheirOwnGenerationCallbacks() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "harmony_calls=0; stratum_calls=0\n"
                + "assert(engine.load('32Harmony', function(commands) harmony_calls=harmony_calls+1; assert(#commands==17) end))\n"
                + "local harmony_poll=tasks[1]; local harmony_sync=engine._native_sync_id\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=harmony_sync}\n"
                + "harmony_poll()\n"
                + "assert(engine._native_state=='native' and engine._native_node.name=='Norns32Harmony' and harmony_calls==1)\n"
                + "assert(engine.load('Stratum', function(commands) stratum_calls=stratum_calls+1; assert(#commands==25) end))\n"
                + "local stratum_poll=tasks[2]; local stratum_sync=engine._native_sync_id\n"
                + "assert(stratum_sync~=harmony_sync)\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=stratum_sync}\n"
                + "stratum_poll()\n"
                + "assert(engine._native_state=='native' and engine._native_node.name=='NornsStratum' and stratum_calls==1)\n"
                + "assert(harmony_calls==1)\n"
                + "return true",
                "native-harmony-stratum-engine-lifecycle.lua").call();
        assertTrue("32Harmony and Stratum native loads must complete only their own callbacks", result.toboolean());
    }

    @Test public void javaEnginesAndPolyPercFallbackCompleteWithoutNativePolling() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "server_running=false; fm_calls=0; pluck_calls=0; perc_calls=0; delay_calls=0\n"
                + "assert(engine.load('FM', function(commands) fm_calls=fm_calls+1; assert(#commands==6) end))\n"
                + "assert(engine._native_state=='java' and fm_calls==1)\n"
                + "assert(engine.load('Pluck', function(commands) pluck_calls=pluck_calls+1; assert(#commands==5) end))\n"
                + "assert(engine._native_state=='java' and pluck_calls==1)\n"
                + "assert(engine.load('PolyPerc', function(commands) perc_calls=perc_calls+1; assert(#commands==7) end))\n"
                + "assert(engine._native_state=='java' and perc_calls==1)\n"
                + "assert(engine.load('SimpleDelay', function(commands) delay_calls=delay_calls+1; assert(#commands==1) end))\n"
                + "assert(engine._native_state=='java' and delay_calls==1)\n"

                + "return true",
                "java-engine-load-fallback-lifecycle.lua").call();
        assertTrue("Java engines must complete their callbacks synchronously without native polling", result.toboolean());
    }

    @Test public void simpleDelayKolorRetiredPhaseCannotAdvanceAfterResetAndNewEngineLoad() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "kolor_old_calls=0; perc_new_calls=0\n"
                + "assert(engine.load('SimpleDelayKolor', function() kolor_old_calls=kolor_old_calls+1 end))\n"
                + "local retired_poll=tasks[1]; local retired_sync=engine._kolor_phase_sync_id\n"
                + "assert(retired_sync~=nil and engine._kolor_phase==1)\n"
                + "engine._reset_native()\n"
                + "assert(engine.load('PolyPerc', function(commands) perc_new_calls=perc_new_calls+1; assert(#commands==7) end))\n"
                + "local current_poll=tasks[2]; local current_sync=engine._native_sync_id\n"
                + "replies[#replies+1]={address='/fail',command='/d_recv',message='retired KOLOR phase failed'}\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=retired_sync}\n"
                + "retired_poll()\n"
                + "current_poll()\n"
                + "assert(engine._native_state=='loading' and engine.name=='PolyPerc' and engine._native_node==nil)\n"
                + "assert(engine._kolor_phase==nil and kolor_old_calls==0 and perc_new_calls==0)\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=current_sync}\n"
                + "current_poll()\n"
                + "assert(engine._native_state=='native' and perc_new_calls==1 and kolor_old_calls==0)\n"
                + "return true",
                "simple-delay-kolor-retired-phase-after-reset.lua").call();
        assertTrue("a retired SimpleDelayKolor phase must not advance or fail a newer engine generation", result.toboolean());
    }

    @Test public void duplicateNativeSyncRepliesCannotInvokeLoadCallbackTwice() throws Exception {
        Globals g = runtime();
        LuaValue result = g.load(
                "callback_calls=0\n"
                + "assert(engine.load('PolyPerc', function(commands) callback_calls=callback_calls+1; assert(#commands==7) end))\n"
                + "local poll=tasks[1]; local sync_id=engine._native_sync_id\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=sync_id}\n"
                + "poll()\n"
                + "assert(engine._native_state=='native' and callback_calls==1)\n"
                + "replies[#replies+1]={address='/done',command='/d_recv'}\n"
                + "replies[#replies+1]={address='/synced',sync_id=sync_id}\n"
                + "poll()\n"
                + "assert(engine._native_state=='native' and callback_calls==1)\n"
                + "return true",
                "native-engine-duplicate-sync-is-idempotent.lua").call();
        assertTrue("duplicate /synced replies must not invoke the engine load callback more than once", result.toboolean());
    }

}
