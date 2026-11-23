package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Behavioral lifecycle tests execute the production loader, callback dispatch and teardown. */
public class NornsScriptLifecycleTest {
    private static String readBoot() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        assertTrue("Missing boot.lua", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Globals lifecycleRuntime() throws Exception {
        String boot = readBoot();
        int menuStart = boot.indexOf("-- Menu callback ownership mirrors norns:");
        int menuEnd = boot.indexOf("-- ===================================================================== require / include", menuStart);
        int stripStart = boot.indexOf("local function strip_bom(src)");
        int stripEnd = boot.indexOf("\nend", stripStart);
        int keyStart = boot.indexOf("function _norns.key(n, z)");
        int cleanupStart = boot.indexOf("function _norns.cleanup()");
        int cleanupEnd = boot.indexOf("function _norns.load_script(path)", cleanupStart);
        int loadStart = cleanupEnd;
        int loadEnd = boot.indexOf("-- ---- parâmetros: ponte com a interface", loadStart);
        int scriptApiStart = boot.indexOf("norns.script.load = function(filename)");
        int scriptApiEnd = boot.indexOf("\nreturn true", scriptApiStart);
        assertTrue(menuStart >= 0 && menuEnd > menuStart);
        assertTrue(stripStart >= 0 && stripEnd > stripStart);
        assertTrue(keyStart >= 0 && cleanupStart > keyStart && cleanupEnd > cleanupStart);
        assertTrue(loadStart >= 0 && loadEnd > loadStart);
        assertTrue(scriptApiStart >= 0 && scriptApiEnd > scriptApiStart);

        Globals g = JsePlatform.standardGlobals();
        g.load(
                "noop=function() end\n"
                + "safe=function(fn, ...) local ok, err = pcall(fn, ...); if not ok then host.log(tostring(err), true) end; return ok end\n"
                + "loaded={}\n timers={}\n"
                + "norns={state={},script={},enc={},encoders={},crow={init=function() end}}\n"
                + "_path={code='/scripts/',data='/data/'}\n"
                + "deferred_engine_callbacks={}\n"
                + "engine={name='none',_reset_native=function() engine_resets=(engine_resets or 0)+1 end,"
                + "_start_native=function(done) engine_starts=(engine_starts or 0)+1; if done then "
                + "if defer_engine_start then deferred_engine_callbacks[#deferred_engine_callbacks+1]=done else done() end end end}\n"
                + "softcut={_reset_native=function() softcut_resets=(softcut_resets or 0)+1 end,"
                + "_start_native=function() softcut_starts=(softcut_starts or 0)+1 end}\n"
                + "params={clear=function() params_clears=(params_clears or 0)+1 end}\n"
                + "audio={add_params=function() audio_adds=(audio_adds or 0)+1 end}\n"
                + "add_system_params=function() system_params=(system_params or 0)+1 end\n"
                + "host={now=function() return 1 end,wake_at=function() end,mkdir=function() end,"
                + "engine_load=function(name) engine_loaded=name end,params_changed=function() params_changes=(params_changes or 0)+1 end,"
                + "log=function(message) last_log=tostring(message) end,"
                + "read_file=function(path) return script_sources[path] end}\n"
                + "script_sources={"
                + "['/scripts/one.lua']='engine.name=\"One\"; init=function() one_init=(one_init or 0)+1 end; redraw=function() one_redraw=(one_redraw or 0)+1 end; key=function(n,z) one_key=(one_key or 0)+n+z end; enc=function(n,d) one_enc=(one_enc or 0)+d end; cleanup=function() one_cleanup=(one_cleanup or 0)+1 end',"
                + "['/scripts/two.lua']='engine.name=\"Two\"; init=function() two_init=(two_init or 0)+1 end; redraw=function() two_redraw=(two_redraw or 0)+1 end; key=function(n,z) two_key=(two_key or 0)+n+z end; enc=function(n,d) two_enc=(two_enc or 0)+d end; cleanup=function() two_cleanup=(two_cleanup or 0)+1 end',"
                + "['/scripts/meta.lua']=[[-- metadata title\n-- author: test\r\nengine.name='Ignored']],"
                + "['/scripts/no-meta.lua']=[[engine.name='NoMeta']],"
                + "['/scripts/broken.lua']='engine.name=\"Broken\"; init=function() broken_init=(broken_init or 0)+1; error(\"init failed\") end; key=function() broken_key=(broken_key or 0)+1 end; cleanup=function() broken_cleanup=(broken_cleanup or 0)+1 end'}\n"
                + "serial={cleanup=function() end}; poll={clear_all=function() end};"
                + "clock={cleanup=function() end}; metro={free_all=function() end}; midi={cleanup=function() end};"
                + "grid={cleanup=function() end}; arc={cleanup=function() end}; hid={cleanup=function() end}\n"
                + "sc={}; _norns={}\n",
                "norns-lifecycle-fixture.lua").call();

        g.load(boot.substring(stripStart, stripEnd + 4)
                .replace("local function strip_bom", "function strip_bom"),
                "production-strip-bom.lua").call();
        // Keep menu_mode, encoder_last_time and production dispatchers in one
        // Lua chunk so their lexical upvalues match the real boot.lua VM.
        g.load(boot.substring(menuStart, menuEnd)
                + boot.substring(keyStart, cleanupStart)
                + boot.substring(cleanupStart, cleanupEnd)
                + boot.substring(loadStart, loadEnd)
                + boot.substring(scriptApiStart, scriptApiEnd + "\nreturn true".length()),
                "production-script-lifecycle.lua").call();
        return g;
    }

    @Test public void scriptCleanupCallbackRunsOnlyOnceAcrossRepeatedTeardown() throws Exception {
        String boot = readBoot();
        int start = boot.indexOf("function _norns.cleanup()");
        int end = boot.indexOf("function _norns.load_script(path)", start);
        assertTrue("production cleanup implementation must exist", start >= 0 && end > start);
        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "noop = function() end\n"
                + "safe = function(fn) local ok, err = pcall(fn); if not ok then error(err) end end\n"
                + "cleanup_count = 0; teardown_count = 0\n"
                + "cleanup = function() cleanup_count = cleanup_count + 1; assert(teardown_count == 0) end\n"
                + "local retire = function() teardown_count = teardown_count + 1 end\n"
                + "loaded = {}\n"
                + "norns = {menu={set=retire,toggle=retire}, crow={init=retire}}\n"
                + "serial = {cleanup=retire}; engine = {_reset_native=retire}; softcut = {_reset_native=retire}\n"
                + "poll = {clear_all=retire}; clock = {cleanup=retire}; metro = {free_all=retire}\n"
                + "midi = {cleanup=retire}; grid = {cleanup=retire}; arc = {cleanup=retire}; hid = {cleanup=retire}\n"
                + "host = {wake_at=retire}; _norns = {}\n",
                "norns-script-cleanup-host.lua").call();
        globals.load(boot.substring(start, end), "production-norns-cleanup.lua").call();
        globals.load(
                "_norns.cleanup()\n"
                + "assert(cleanup_count == 1)\n"
                + "assert(cleanup == noop)\n"
                + "_norns.cleanup()\n"
                + "assert(cleanup_count == 1)\n"
                + "assert(teardown_count > 0)\n"
                + "return true",
                "norns-script-cleanup-once.lua").call();
    }

    @Test public void loadDispatchCleanupAndReloadFollowTheCompleteScriptLifecycle() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "assert(norns.script.load('one.lua'))\n"
                + "assert(one_init==1 and one_redraw==1)\n"
                + "assert(engine_loaded=='One' and engine_starts==1)\n"
                + "_norns.key(2,1); _norns.enc(1,3)\n"
                + "assert(one_key==3 and one_enc==3)\n"
                + "local retired_key, retired_enc = key, enc\n"
                + "assert(norns.script.load('two.lua'))\n"
                + "assert(one_cleanup==1)\n"
                + "assert(two_init==1 and two_redraw==1 and engine_loaded=='Two')\n"
                + "assert(engine_starts==2 and engine_resets>=3)\n"
                + "_norns.key(3,1); _norns.enc(2,-4)\n"
                + "assert(two_key==4 and two_enc==-4)\n"
                + "assert(one_key==3 and one_enc==3)\n"
                + "-- A retained function from generation one may still be called by a stale producer.\n"
                + "-- Its closure must remain bound to generation one and must not dispatch into generation two.\n"
                + "retired_key(7,1); retired_enc(1,7)\n"
                + "assert(one_key==11 and one_enc==10)\n"
                + "assert(two_key==4 and two_enc==-4)\n"
                + "assert(norns.rerun())\n"
                + "assert(two_cleanup==1 and two_init==2 and two_redraw==2)\n"
                + "assert(engine_starts==3 and engine_resets>=5)\n"
                + "assert(norns.script.clear())\n"
                + "assert(two_cleanup==2)\n"
                + "assert(norns.state.name=='' and norns.state.path=='')\n"
                + "_norns.key(1,1); _norns.enc(1,1)\n"
                + "assert(two_key==4 and two_enc==-4)\n"
                + "return true",
                "norns-complete-load-init-callback-cleanup-reload.lua").call();
        assertTrue("complete Norns script lifecycle must return true", result.toboolean());
    }

    @Test public void initFailureCleansPartialScriptAndReturnsFailure() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "assert(norns.script.load('broken.lua') == false)\n"
                + "assert(broken_init == 1 and broken_cleanup == 1)\n"
                + "assert(engine_resets >= 2)\n"
                + "_norns.key(1,1); _norns.enc(1,1)\n"
                + "assert(broken_key == nil)\n"
                + "_norns.cleanup()\n"
                + "assert(broken_cleanup == 1)\n"
                + "return true",
                "norns-init-failure-cleanup.lua").call();
        assertTrue("failed init must retire partial script state", result.toboolean());
    }

    @Test public void scriptLoadWithoutFilenameReloadsCurrentScript() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "assert(norns.script.load('one.lua'))\n"
                + "assert(one_init==1 and norns.state.shortname=='one')\n"
                + "assert(norns.script.load())\n"
                + "assert(one_cleanup==1 and one_init==2 and engine_starts==2)\n"
                + "assert(norns.script.load(''))\n"
                + "assert(one_cleanup==2 and one_init==3 and engine_starts==3)\n"
                + "assert(norns.script.clear())\n"
                + "assert(not norns.script.load())\n"
                + "return true",
                "norns-script-load-current-file.lua").call();
        assertTrue("script.load without a filename must reload only an active script", result.toboolean());
    }

    @Test public void scriptRunIsPublicAndIdempotentForEachLoadedGeneration() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "assert(norns.script.run() == false)\n"
                + "assert(norns.script.load('one.lua'))\n"
                + "assert(one_init == 1 and engine_starts == 1)\n"
                + "assert(norns.script.run() == false)\n"
                + "assert(one_init == 1 and engine_starts == 1)\n"
                + "assert(norns.script.load('two.lua'))\n"
                + "assert(one_cleanup == 1 and two_init == 1 and engine_starts == 2)\n"
                + "assert(norns.script.run() == false)\n"
                + "assert(two_init == 1 and engine_starts == 2)\n"
                + "_norns.cleanup()\n"
                + "assert(norns.script.run() == false)\n"
                + "return true",
                "norns-script-run-idempotent.lua").call();
        assertTrue("script.run must run only once per loaded generation", result.toboolean());
    }

    @Test public void scriptMetadataReadsLeadingCommentBlockAndHandlesMissingFiles() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "local meta=norns.script.metadata('meta.lua')\n"
                + "assert(#meta==2 and meta[1]=='metadata title' and meta[2]=='author: test')\n"
                + "local no_meta=norns.script.metadata('no-meta.lua')\n"
                + "assert(#no_meta==1 and no_meta[1]=='no script information')\n"
                + "local missing=norns.script.metadata('missing.lua')\n"
                + "assert(#missing==0)\n"
                + "return true",
                "norns-script-metadata.lua").call();
        assertTrue("metadata must parse only the initial comment block without executing scripts", result.toboolean());
    }

    @Test public void delayedEngineCompletionCannotStartSoftcutAfterScriptCleanupOrReload() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "defer_engine_start=true\n"
                + "assert(norns.script.load('one.lua'))\n"
                + "assert(engine_starts==1 and softcut_starts==nil)\n"
                + "local retired_completion=deferred_engine_callbacks[1]\n"
                + "_norns.cleanup()\n"
                + "retired_completion()\n"
                + "assert(softcut_starts==nil)\n"
                + "assert(norns.script.load('two.lua'))\n"
                + "assert(engine_starts==2 and softcut_starts==nil)\n"
                + "local current_completion=deferred_engine_callbacks[2]\n"
                + "current_completion()\n"
                + "assert(softcut_starts==1)\n"
                + "_norns.cleanup()\n"
                + "current_completion()\n"
                + "assert(softcut_starts==1)\n"
                + "return true",
                "norns-stale-engine-completion-after-cleanup-and-reload.lua").call();
        assertTrue("stale engine completion must not start Softcut for a retired script", result.toboolean());
    }

    @Test public void lateCallbacksAfterCleanupCannotReachRetiredScript() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "assert(norns.script.load('one.lua'))\n"
                + "_norns.key(2,1); _norns.enc(1,3)\n"
                + "assert(one_key == 3 and one_enc == 3)\n"
                + "_norns.cleanup()\n"
                + "assert(one_cleanup == 1)\n"
                + "_norns.key(9,1); _norns.enc(1,9)\n"
                + "_norns.cleanup()\n"
                + "assert(one_key == 3 and one_enc == 3 and one_cleanup == 1)\n"
                + "return true",
                "norns-late-callback-after-cleanup.lua").call();
        assertTrue("late callbacks must be inert after cleanup", result.toboolean());
    }

    @Test public void repeatedReloadsRetireEachScriptGenerationExactlyOnce() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "assert(norns.script.load('one.lua'))\n"
                + "for generation = 1, 5 do\n"
                + "  assert(norns.rerun())\n"
                + "  assert(one_cleanup == generation)\n"
                + "  assert(one_init == generation + 1)\n"
                + "  _norns.key(1,1); _norns.enc(1,1)\n"
                + "end\n"
                + "assert(one_key == 10 and one_enc == 5)\n"
                + "assert(norns.script.clear())\n"
                + "assert(one_cleanup == 6)\n"
                + "_norns.key(1,1); _norns.enc(1,1)\n"
                + "assert(one_key == 10 and one_enc == 5)\n"
                + "return true",
                "norns-repeated-script-reload.lua").call();
        assertTrue("repeated reloads must isolate script generations", result.toboolean());
    }

    @Test public void loaderAppliesKnownScriptCompatPatchBeforeTheScriptRuns() throws Exception {
        Globals g = lifecycleRuntime();
        LuaValue result = g.load(
                "script_sources['/scripts/circlish.lua'] = [[\n"
                + "engine.name = 'PolyPerc'\n"
                + "function noteForCircle(circle) return 65 end\n"
                + "function handleCircleBurst(circle)\n"
                + "  local note = noteForCircle(circle)\n"
                + "  engine.hz(note)\n"
                + "end\n"
                + "init = function() end\n"
                + "]]\n"
                + "script_sources['/scripts/plain.lua'] = [[\n"
                + "engine.name = 'PolyPerc'\n"
                + "function play(note) engine.hz(note) end\n"
                + "init = function() end\n"
                + "]]\n"
                + "engine.hz = function(value) last_hz = value end\n"
                + "require = function(name) assert(name == 'musicutil'); "
                + "return {note_num_to_freq = function(n) return 440 * 2 ^ ((n - 69) / 12) end} end\n"
                // circles passa a nota MIDI (65) a engine.hz: o loader deve entregar Hz ao engine.
                + "assert(norns.script.load('circlish.lua'))\n"
                + "assert(string.find(last_log, 'Compat: circles', 1, true), 'patch deve ser registrado no log')\n"
                + "handleCircleBurst({})\n"
                + "assert(math.abs(last_hz - 349.2282314) < 0.0001, 'esperava Hz, recebeu ' .. tostring(last_hz))\n"
                // Script sem a assinatura do circles continua intacto.
                + "assert(norns.script.load('plain.lua'))\n"
                + "play(65)\n"
                + "assert(last_hz == 65, 'script comum nao pode ser alterado')\n"
                + "return true",
                "norns-compat-patch-on-load.lua").call();
        assertTrue("known-script compat patch must run in the production loader", result.toboolean());
    }
}
