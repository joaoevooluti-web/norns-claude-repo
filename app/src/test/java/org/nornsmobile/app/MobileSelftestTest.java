package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * mobile_selftest: o módulo lib/nornsmobile_selftest.lua e o script que o desenha. As checagens rodam sobre o
 * código de produção: io/os do boot.lua, o shell POSIX, util, cjson, paramset, clock e metro reais, com um
 * sistema de arquivos em memória e um relógio determinístico (o mesmo recorte de boot.lua que os outros testes usam).
 * Só a engine e a tela são falsas.
 */
public class MobileSelftestTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final String FIXTURE = ""
            + "now = 0\n"
            + "logs = {}\n"
            + "host = _host\n"
            + "host.now = function() return now end\n"
            + "host.wake_at = function() end\n"
            + "host.log = function(message) logs[#logs + 1] = tostring(message) end\n"
            + "_path = {dust = '/dust/', code = '/dust/code/', data = '/dust/data/', audio = '/dust/audio/'}\n"
            + "safe = function(fn, ...) local ok, result = pcall(fn, ...); return ok, result end\n"
            + "noop = function() end\n"
            + "midi = nil\n"
            + "print = function(...)\n"
            + "  local t = {}\n"
            + "  for i = 1, select('#', ...) do t[i] = tostring((select(i, ...))) end\n"
            + "  logs[#logs + 1] = table.concat(t, '\\t')\n"
            + "end\n"
            + "engine = {name = 'PolyPerc', calls = {},\n"
            + "  list_commands = function() return {'hz', 'amp', 'pw', 'release', 'cutoff', 'gain', 'pan'} end}\n"
            + "for _, name in ipairs({'hz', 'amp', 'pw', 'release', 'cutoff', 'gain', 'pan'}) do\n"
            + "  engine[name] = function(v) engine.calls[#engine.calls + 1] = name .. '=' .. tostring(v) end\n"
            + "end\n"
            + "screen_log = {}\n"
            + "screen = {}\n"
            + "for _, name in ipairs({'clear', 'aa', 'font_size', 'level', 'move', 'update'}) do\n"
            + "  screen[name] = function() end\n"
            + "end\n"
            + "screen.text = function(s) screen_log[#screen_log + 1] = s end\n"
            + "screen.text_extents = function(s) return #s * 5 end\n";

    private static final String DRIVER = ""
            + "function advance(seconds)\n"
            + "  local steps = math.floor(seconds / 0.01 + 0.5)\n"
            + "  for _ = 1, steps do\n"
            + "    now = now + 0.01\n"
            + "    tick()\n"
            + "  end\n"
            + "end\n"
            + "function run_suite(customize)\n"
            + "  local selftest = require('nornsmobile_selftest')\n"
            + "  local suite = selftest.new(_G)\n"
            + "  if customize then customize(suite, selftest) end\n"
            + "  local done = nil\n"
            + "  done_calls = 0\n"
            + "  local started = suite:run({on_done = function(r) done = r; done_calls = done_calls + 1 end})\n"
            + "  local guard = 0\n"
            + "  while done == nil and guard < 6000 do\n"
            + "    advance(0.01)\n"
            + "    guard = guard + 1\n"
            + "  end\n"
            + "  last_results = done\n"
            + "  return suite, done, started\n"
            + "end\n"
            + "function log_has(prefix)\n"
            + "  for i = 1, #logs do\n"
            + "    if string.sub(logs[i], 1, #prefix) == prefix then return true end\n"
            + "  end\n"
            + "  return false\n"
            + "end\n"
            + "function wait_for_log(prefix, seconds)\n"
            + "  local deadline = now + seconds\n"
            + "  while now < deadline do\n"
            + "    if log_has(prefix) then return true end\n"
            + "    advance(0.01)\n"
            + "  end\n"
            + "  return log_has(prefix)\n"
            + "end\n";

    /** Resultado de uma rodada, indexado pelo id da checagem. */
    private static final class Outcome {
        final Map<String, String> status = new LinkedHashMap<String, String>();
        final Map<String, String> detail = new LinkedHashMap<String, String>();

        Outcome(LuaValue results) {
            assertTrue("a rodada não terminou (nil)", results.istable());
            for (int i = 1; i <= results.length(); i++) {
                LuaValue r = results.get(i);
                String id = r.get("id").tojstring();
                status.put(id, r.get("status").tojstring());
                detail.put(id, r.get("detail").isnil() ? "" : r.get("detail").tojstring());
            }
        }

        int count(String wanted) {
            int n = 0;
            for (String s : status.values()) if (s.equals(wanted)) n++;
            return n;
        }

        String with(String wanted) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : status.entrySet()) {
                if (e.getValue().equals(wanted)) sb.append(e.getKey()).append(": ").append(detail.get(e.getKey())).append("\n");
            }
            return sb.toString();
        }

        void assertStatus(String id, String wanted) {
            assertEquals(id + " (" + detail.get(id) + ")", wanted, status.get(id));
        }
    }

    /** Runtime de produção sobre um sistema de arquivos falso e um relógio virtual. */
    private static final class Runtime {
        final Globals g = JsePlatform.standardGlobals();
        final LuaValue fs;

        Runtime(boolean formatFix) throws Exception {
            if (formatFix) NornsStringFormat.install(g);
            installRequire();
            fs = g.load(read("app/src/test/resources/lua/fake_host_fs.lua"), "fake_host_fs.lua").call();
            g.set("fs", fs);
            g.set("_host", fs.get("host"));
            g.load(FIXTURE, "selftest-fixture.lua").call();

            String boot = read("app/src/main/assets/lua/boot.lua");
            int ioStart = boot.indexOf("local FileR = {}");
            int ioEnd = boot.indexOf("luajava = nil", ioStart);
            assertTrue("boot.lua must keep the io/os block", ioStart >= 0 && ioEnd > ioStart);
            g.load(boot.substring(ioStart, ioEnd), "production-io-os.lua").call();

            g.load("util = require 'util'\n"
                    + "tab = require 'tabutil'\n"
                    + "controlspec = require 'controlspec'\n"
                    + "paramset = require 'paramset'\n"
                    + "formatters = require 'formatters'\n", "production-libs.lua").call();

            int schedStart = boot.indexOf("local timers = {}");
            int schedEnd = boot.indexOf("-- ===================================================================== SuperCollider", schedStart);
            int tick = boot.indexOf("function _norns.tick()");
            int tickEnd = boot.indexOf("\nend", tick);
            assertTrue("boot.lua must keep the scheduler", schedStart >= 0 && schedEnd > schedStart && tick > 0 && tickEnd > tick);
            String tickSource = boot.substring(tick, tickEnd + 4).replace("function _norns.tick()", "function tick()");
            g.load(boot.substring(schedStart, schedEnd) + "\n" + tickSource, "production-scheduler.lua").call();

            g.load("params = paramset.new()\n"
                    + "params:add_number('clock_tempo', 'tempo', 1, 300, 120)\n"
                    + "params:set_action('clock_tempo', function(v) clock.set_tempo(v) end)\n", "params-fixture.lua").call();
            g.load(DRIVER, "selftest-driver.lua").call();
        }

        private void installRequire() {
            final LuaTable loaded = g.get("package").get("loaded").checktable();
            g.set("require", new OneArgFunction() {
                @Override public LuaValue call(LuaValue requested) {
                    String name = requested.checkjstring();
                    LuaValue cached = loaded.get(name);
                    if (!cached.isnil()) return cached;
                    String rel = name.startsWith("lib/") ? name.substring(4) : name;
                    String path = "app/src/main/assets/lua/lib/" + rel.replace('.', '/') + ".lua";
                    File file = new File(path);
                    if (!file.isFile()) file = new File(path.substring(4));
                    if (!file.isFile()) throw new LuaError("module '" + name + "' not found");
                    String source;
                    try {
                        source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new LuaError(e.toString());
                    }
                    loaded.set(name, LuaValue.TRUE);
                    LuaValue result = g.load(source, "@lua/lib/" + rel + ".lua").call();
                    if (!result.isnil()) loaded.set(name, result);
                    return loaded.get(name);
                }
            });
        }

        LuaValue lua(String code) {
            return g.load(code, "test.lua").call();
        }

        /** Roda a suíte (com ajustes opcionais, em Lua: suite, selftest) e avança o relógio até ela terminar. */
        Outcome run(String customize) {
            LuaValue fn = customize == null ? LuaValue.NIL
                    : g.load("return function(suite, selftest) " + customize + " end", "customize.lua").call();
            Varargs v = g.get("run_suite").invoke(fn);
            return new Outcome(v.arg(2));
        }

        Outcome run() { return run(null); }

        String fsList(String path) {
            LuaValue v = fs.get("list").call(LuaValue.valueOf(path));
            return v.isnil() ? null : v.tojstring();
        }
    }

    // ------------------------------------------------------------------ o módulo sobre o runtime de produção

    @Test public void everyCheckPassesOnTheProductionRuntime() throws Exception {
        Runtime rt = new Runtime(true);
        Outcome o = rt.run();
        assertEquals("checagens que falharam:\n" + o.with("fail"), 0, o.count("fail"));
        assertEquals("checagens puladas:\n" + o.with("skip"), 0, o.count("skip"));
        assertTrue("o módulo deveria ter pelo menos 40 checagens, tem " + o.status.size(), o.status.size() >= 40);
        for (Map.Entry<String, String> e : o.status.entrySet()) {
            if (e.getValue().equals("limit")) {
                assertTrue(e.getKey() + " é limite mas não é uma limitação conhecida", e.getKey().startsWith("limite/"));
            } else {
                assertEquals(e.getKey() + ": " + o.detail.get(e.getKey()), "ok", e.getValue());
            }
        }
        String[] groups = {"format/", "cjson/", "shell/", "paths/", "engine/", "params/", "tempo/", "lua/", "screen/", "limite/"};
        for (String group : groups) {
            boolean found = false;
            for (String id : o.status.keySet()) if (id.startsWith(group)) found = true;
            assertTrue("grupo sem checagens: " + group, found);
        }
        for (String id : new String[]{"tempo/sleep", "tempo/sync", "tempo/cancel", "tempo/metro", "tempo/metro-stop"}) {
            o.assertStatus(id, "ok");
        }
    }

    @Test public void formatChecksFailOnStockLuaJAndPassWithTheFormatFix() throws Exception {
        Outcome stock = new Runtime(false).run();
        for (String id : new String[]{"format/float", "format/int", "format/hex", "format/str", "format/tostring"}) {
            stock.assertStatus(id, "fail");
        }
        Outcome fixed = new Runtime(true).run();
        for (String id : new String[]{"format/float", "format/int", "format/hex", "format/expo", "format/str",
                "format/misc", "format/erros", "format/tostring"}) {
            fixed.assertStatus(id, "ok");
        }
    }

    @Test public void knownLimitationsAreReportedAsLimitsNotFailures() throws Exception {
        Outcome o = new Runtime(true).run();
        for (String id : new String[]{"limite/concat", "limite/math.type", "limite/tointeger", "limite/table.move",
                "limite/utf8", "limite/pack", "limite/operadores"}) {
            o.assertStatus(id, "limit");
        }
        assertTrue(o.detail.get("limite/concat").contains("string.format"));
    }

    @Test public void theSuiteCanRunAgainAndLeavesNoFilesBehind() throws Exception {
        Runtime rt = new Runtime(true);
        Outcome first = rt.run();
        String afterFirst = rt.fsList("/dust/data/mobile_selftest");
        Outcome second = rt.run();
        String afterSecond = rt.fsList("/dust/data/mobile_selftest");
        assertEquals(first.status, second.status);
        assertTrue("sobraram arquivos da primeira rodada: " + afterFirst, afterFirst == null || afterFirst.isEmpty());
        assertTrue("sobraram arquivos da segunda rodada: " + afterSecond, afterSecond == null || afterSecond.isEmpty());
    }

    @Test public void noFileSurvivesAnywhereInDust() throws Exception {
        Runtime rt = new Runtime(true);
        rt.run();
        String snapshot = rt.lua("return fs.snapshot()").tojstring();
        assertFalse("o selftest não pode deixar arquivos: " + snapshot, snapshot.contains("="));
    }

    // ------------------------------------------------------------------ o executor

    @Test public void asyncCheckThatNeverFinishesTimesOutAndTheRunContinues() throws Exception {
        Runtime rt = new Runtime(true);
        Outcome o = rt.run(""
                + "suite.checks[#suite.checks + 1] = {id = 'x/parado', group = 'x', name = 'parado', async = true, known = false,\n"
                + "  timeout = 1, watchdog = 'metro', start = function(finish) end}\n"
                + "suite.checks[#suite.checks + 1] = {id = 'x/depois', group = 'x', name = 'depois', known = false,\n"
                + "  run = function() return true, 'rodou' end}\n");
        o.assertStatus("x/parado", "fail");
        assertTrue(o.detail.get("x/parado"), o.detail.get("x/parado").contains("sem resposta em 1 s"));
        o.assertStatus("x/depois", "ok");
        assertEquals("rodou", o.detail.get("x/depois"));
    }

    @Test public void finishCalledTwiceOrAfterTheTimeoutIsIgnored() throws Exception {
        Runtime rt = new Runtime(true);
        rt.lua("late_finish = nil");
        Outcome o = rt.run(""
                + "suite.checks = {}\n"
                + "suite.checks[1] = {id = 'x/duas', group = 'x', name = 'duas', async = true, known = false, timeout = 1,\n"
                + "  watchdog = 'metro', start = function(finish) finish(true, 'primeira'); finish(false, 'segunda') end}\n"
                + "suite.checks[2] = {id = 'x/tarde', group = 'x', name = 'tarde', async = true, known = false, timeout = 1,\n"
                + "  watchdog = 'metro', start = function(finish) late_finish = finish end}\n");
        assertEquals(2, o.status.size());
        o.assertStatus("x/duas", "ok");
        assertEquals("primeira", o.detail.get("x/duas"));
        o.assertStatus("x/tarde", "fail");
        assertEquals(1, rt.lua("return done_calls").toint());
        // O resultado já foi registrado: uma chamada tardia não muda nem duplica nada e não reabre a rodada.
        rt.lua("late_finish(true, 'atrasada'); advance(0.5)");
        assertEquals(2, rt.lua("return #last_results").toint());
        assertEquals(1, rt.lua("return done_calls").toint());
        assertEquals("fail", rt.lua("return last_results[2].status").tojstring());
    }

    @Test public void checksThatThrowOrReturnNothingBecomeFailuresAndKnownOnesBecomeLimits() throws Exception {
        Runtime rt = new Runtime(true);
        Outcome o = rt.run(""
                + "suite.checks = {\n"
                + "  {id = 'x/erro', group = 'x', name = 'erro', known = false, run = function() error('boom') end},\n"
                + "  {id = 'x/nada', group = 'x', name = 'nada', known = false, run = function() end},\n"
                + "  {id = 'x/falha', group = 'x', name = 'falha', known = false, run = function() return false, 'detalhe' end},\n"
                + "  {id = 'x/limite', group = 'x', name = 'limite', known = true, run = function() return false, 'faltou' end},\n"
                + "  {id = 'x/passou', group = 'x', name = 'passou', known = true, run = function() return true end},\n"
                + "  {id = 'x/pulou', group = 'x', name = 'pulou', known = false, run = function() return 'skip', 'sem engine' end},\n"
                + "  {id = 'x/assinc', group = 'x', name = 'assinc', async = true, known = false, timeout = 1, watchdog = 'metro',\n"
                + "    start = function(finish) error('falhou ao iniciar') end},\n"
                + "  {id = 'x/pulou2', group = 'x', name = 'pulou2', async = true, known = false, timeout = 1, watchdog = 'metro',\n"
                + "    start = function(finish) finish('skip', 'fonte externa') end},\n"
                + "}\n");
        o.assertStatus("x/erro", "fail");
        assertTrue(o.detail.get("x/erro"), o.detail.get("x/erro").contains("boom"));
        o.assertStatus("x/nada", "fail");
        assertTrue(o.detail.get("x/nada").contains("sem resultado"));
        o.assertStatus("x/falha", "fail");
        assertEquals("detalhe", o.detail.get("x/falha"));
        o.assertStatus("x/limite", "limit");
        o.assertStatus("x/passou", "ok");
        o.assertStatus("x/pulou", "skip");
        o.assertStatus("x/assinc", "fail");
        assertTrue(o.detail.get("x/assinc"), o.detail.get("x/assinc").contains("falhou ao iniciar"));
        o.assertStatus("x/pulou2", "skip");
        assertEquals(8, o.status.size());
    }

    @Test public void aDeadClockCannotHangTheRun() throws Exception {
        Runtime rt = new Runtime(true);
        rt.lua("clock.run = function() return 1 end");
        Outcome o = rt.run();
        for (String id : new String[]{"tempo/sleep", "tempo/sync", "tempo/cancel"}) {
            o.assertStatus(id, "fail");
            assertTrue(o.detail.get(id), o.detail.get(id).contains("sem resposta"));
        }
        o.assertStatus("tempo/metro", "ok");
        o.assertStatus("format/float", "ok");
    }

    @Test public void aDeadMetroCannotHangTheRun() throws Exception {
        Runtime rt = new Runtime(true);
        rt.lua("metro.init = function() return {start = function() end, stop = function() end, free = function() end} end");
        Outcome o = rt.run();
        o.assertStatus("tempo/metro", "fail");
        assertTrue(o.detail.get("tempo/metro"), o.detail.get("tempo/metro").contains("sem resposta"));
        o.assertStatus("tempo/metro-stop", "fail");
        o.assertStatus("tempo/sleep", "ok");
    }

    @Test public void aRunInProgressRefusesASecondRun() throws Exception {
        Runtime rt = new Runtime(true);
        LuaValue second = rt.lua(""
                + "local selftest = require('nornsmobile_selftest')\n"
                + "local suite = selftest.new(_G)\n"
                + "local first = suite:run({})\n"
                + "local again = suite:run({})\n"
                + "advance(20)\n"
                + "return {first = first, again = again, running = suite.running}");
        assertTrue(second.get("first").toboolean());
        assertFalse(second.get("again").toboolean());
        assertFalse("a rodada deveria ter terminado", second.get("running").toboolean());
    }

    @Test public void clockSyncIsSkippedWhenTheClockIsExternal() throws Exception {
        Runtime rt = new Runtime(true);
        rt.lua("clock.get_source = function() return 'midi' end");
        Outcome o = rt.run();
        o.assertStatus("tempo/sync", "skip");
        assertTrue(o.detail.get("tempo/sync").contains("midi"));
        assertEquals(0, o.count("fail"));
    }

    @Test public void metroSlotsAreReleasedAfterEveryRun() throws Exception {
        Runtime rt = new Runtime(true);
        for (int i = 0; i < 12; i++) rt.run();
        LuaValue used = rt.lua("local n = 0; for id = 1, metro.num_script_metros do if metro[id] ~= nil then n = n + 1 end end; return n");
        assertEquals("os metros do selftest precisam ser liberados (o norns tem só 30)", 0, used.toint());
        Outcome o = rt.run();
        assertEquals(o.with("fail"), 0, o.count("fail"));
    }

    // ------------------------------------------------------------------ resultados e relatório

    @Test public void summarySortAndReportOrderFailuresFirstWithoutStringFormat() throws Exception {
        Runtime rt = new Runtime(false);
        LuaValue out = rt.lua(""
                + "local selftest = require('nornsmobile_selftest')\n"
                + "local results = {\n"
                + "  {id = 'a/ok1', group = 'a', name = 'ok1', status = 'ok'},\n"
                + "  {id = 'a/limite', group = 'a', name = 'limite', status = 'limit', detail = 'LuaJ 5.2'},\n"
                + "  {id = 'b/falha', group = 'b', name = 'falha', status = 'fail', detail = 'obtido 1, esperado 2'},\n"
                + "  {id = 'b/ok2', group = 'b', name = 'ok2', status = 'ok', detail = '3 casos'},\n"
                + "  {id = 'c/pulou', group = 'c', name = 'pulou', status = 'skip', detail = 'sem engine'},\n"
                + "  {id = 'c/falha2', group = 'c', name = 'falha2', status = 'fail'},\n"
                + "}\n"
                + "local sorted = selftest.sorted(results)\n"
                + "local ids = {}\n"
                + "for i = 1, #sorted do ids[i] = sorted[i].id end\n"
                + "local counts = selftest.summary(results)\n"
                + "string.format = function() error('o relatório não pode usar string.format') end\n"
                + "local lines = selftest.report(results)\n"
                + "return {ids = table.concat(ids, ','), ok = counts.ok, fail = counts.fail, limit = counts.limit,\n"
                + "  skip = counts.skip, total = counts.total, lines = table.concat(lines, '|')}");
        assertEquals("b/falha,c/falha2,a/limite,c/pulou,a/ok1,b/ok2", out.get("ids").tojstring());
        assertEquals(2, out.get("ok").toint());
        assertEquals(2, out.get("fail").toint());
        assertEquals(1, out.get("limit").toint());
        assertEquals(1, out.get("skip").toint());
        assertEquals(6, out.get("total").toint());
        assertEquals("selftest: ok=2 falha=2 limite=1 pulado=1"
                + "|selftest FALHA b/falha: obtido 1, esperado 2"
                + "|selftest FALHA c/falha2"
                + "|selftest limite a/limite: LuaJ 5.2"
                + "|selftest pulado c/pulou: sem engine"
                + "|selftest ok a/ok1"
                + "|selftest ok b/ok2: 3 casos", out.get("lines").tojstring());
    }

    @Test public void detailsAreOneLineAndTruncated() throws Exception {
        Runtime rt = new Runtime(true);
        Outcome o = rt.run(""
                + "suite.checks = {{id = 'x/longo', group = 'x', name = 'longo', known = false,\n"
                + "  run = function() return false, string.rep('a', 400) .. '\\nfim' end}}\n");
        String detail = o.detail.get("x/longo");
        assertTrue(detail, detail.length() <= 160);
        assertTrue(detail, detail.endsWith("..."));
        assertFalse(detail, detail.contains("\n"));
    }

    // ------------------------------------------------------------------ o script

    @Test public void scriptRunsOnTheProductionRuntimeAndDrawsTheResult() throws Exception {
        Runtime rt = new Runtime(true);
        rt.lua(read("app/src/main/assets/dust/code/mobile_selftest/mobile_selftest.lua"));
        assertEquals("PolyPerc", rt.lua("return engine.name").tojstring());

        rt.lua("init()");
        assertTrue(rt.lua("return log_has('selftest: iniciando ')").toboolean());
        assertFalse("a rodada assíncrona ainda não acabou", rt.lua("return log_has('selftest: ok=')").toboolean());
        // K3 durante a rodada é ignorada.
        rt.lua("logs = {}; key(3, 1)");
        assertFalse(rt.lua("return log_has('selftest: iniciando ')").toboolean());

        assertTrue("a rodada deveria terminar", rt.lua("return wait_for_log('selftest: ok=', 30)").toboolean());
        String summary = rt.lua("for i = 1, #logs do if string.sub(logs[i], 1, 13) == 'selftest: ok=' then return logs[i] end end").tojstring();
        assertTrue(summary, summary.matches("selftest: ok=\\d+ falha=0 limite=7 pulado=0"));
        assertTrue(rt.lua("return log_has('selftest limite limite/concat')").toboolean());
        assertTrue(rt.lua("return log_has('selftest ok tempo/metro')").toboolean());

        rt.lua("screen_log = {}; redraw()");
        String screen = rt.lua("return table.concat(screen_log, '|')").tojstring();
        assertTrue(screen, screen.startsWith("selftest  ok "));
        assertTrue(screen, screen.contains("falha 0"));
        assertTrue(screen, screen.contains("limite 7"));
        assertTrue(screen, screen.contains("E2 rola"));

        // E2 rola a lista e a linha selecionada muda.
        rt.lua("screen_log = {}; redraw()");
        String before = rt.lua("return screen_log[3]").tojstring();
        rt.lua("enc(2, 1); screen_log = {}; redraw()");
        String after = rt.lua("return screen_log[3]").tojstring();
        assertFalse("E2 deveria mudar a linha selecionada: " + before, before.equals(after));
        rt.lua("enc(2, -50); enc(2, 500); redraw()");

        // K2 toca a nota de teste; K3 repete tudo.
        rt.lua("key(2, 1)");
        assertEquals("hz=440", rt.lua("return engine.calls[#engine.calls]").tojstring());
        assertTrue(rt.lua("return log_has('selftest: nota de teste enviada')").toboolean());
        rt.lua("logs = {}; key(3, 1)");
        assertTrue(rt.lua("return log_has('selftest: iniciando ')").toboolean());
        assertTrue(rt.lua("return wait_for_log('selftest: ok=', 30)").toboolean());
        String again = rt.lua("for i = 1, #logs do if string.sub(logs[i], 1, 13) == 'selftest: ok=' then return logs[i] end end").tojstring();
        assertEquals(summary, again);
    }

    @Test public void scriptShowsFailuresFirstWhenTheFormatFixIsMissing() throws Exception {
        Runtime rt = new Runtime(false);
        rt.lua(read("app/src/main/assets/dust/code/mobile_selftest/mobile_selftest.lua"));
        rt.lua("init()");
        assertTrue(rt.lua("return wait_for_log('selftest: ok=', 30)").toboolean());
        rt.lua("screen_log = {}; redraw()");
        String screen = rt.lua("return table.concat(screen_log, '|')").tojstring();
        assertFalse(screen, screen.contains("falha 0"));
        assertTrue(screen, screen.contains("> FALHA format/"));
        assertTrue(rt.lua("return log_has('selftest FALHA format/float')").toboolean());
    }

    @Test public void scriptFileKeepsTheDocumentedContract() throws Exception {
        String script = read("app/src/main/assets/dust/code/mobile_selftest/mobile_selftest.lua");
        assertTrue(script.startsWith("-- mobile_selftest\n"));
        assertTrue(script.contains("engine.name = \"PolyPerc\""));
        assertTrue(script.contains("require \"nornsmobile_selftest\""));
        assertTrue(script.contains("function init()"));
        assertTrue(script.contains("function key(n, z)"));
        assertTrue(script.contains("function enc(n, d)"));
        assertTrue(script.contains("function redraw()"));
        assertFalse("o script não pode chamar string.format (ele existe para testá-lo)", script.contains("string.format("));
        String module = read("app/src/main/assets/lua/lib/nornsmobile_selftest.lua");
        assertFalse("o módulo só pode citar string.format dentro das checagens",
                module.replaceAll("env\\.string\\.format", "").replaceAll("\"[^\"\\n]*string\\.format[^\"\\n]*\"", "")
                        .replaceAll("--[^\\n]*", "").contains("string.format("));
    }
}
