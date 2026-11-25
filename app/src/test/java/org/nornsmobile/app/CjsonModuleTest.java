package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Módulo cjson (lib/cjson.lua e lib/cjson/safe.lua): a API do lua-cjson 2.1.0 em Lua puro. Os casos ficam em
 * src/test/resources/lua/cjson_spec.lua; cada grupo roda num runtime novo, numa thread de pilha grande
 * (a profundidade padrão do módulo é 1000 níveis, e a thread de scripts do app também tem pilha de 8 MB).
 */
public class CjsonModuleTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class Outcome {
        final int cases;
        final List<String> failures = new ArrayList<String>();

        Outcome(int cases) { this.cases = cases; }
    }

    private static Outcome runGroup(final String name) throws Exception {
        final Outcome[] outcome = new Outcome[1];
        final Throwable[] crash = new Throwable[1];
        Thread worker = new Thread(null, new Runnable() {
            @Override public void run() {
                try {
                    Globals g = JsePlatform.standardGlobals();
                    LuaValue cjson = g.load(read("app/src/main/assets/lua/lib/cjson.lua"), "@lua/lib/cjson.lua").call();
                    g.get("package").get("loaded").set("cjson", cjson);
                    LuaValue safe = g.load(read("app/src/main/assets/lua/lib/cjson/safe.lua"),
                            "@lua/lib/cjson/safe.lua").call();
                    LuaValue groups = g.load(read("app/src/test/resources/lua/cjson_spec.lua"), "@cjson_spec.lua")
                            .call(cjson, safe);
                    LuaValue cases = groups.get(name);
                    assertFalse("grupo inexistente no cjson_spec.lua: " + name, cases.isnil());
                    Outcome result = new Outcome(cases.length());
                    for (int i = 1; i <= cases.length(); i++) {
                        LuaValue one = cases.get(i);
                        try {
                            one.get("run").call();
                        } catch (LuaError e) {
                            result.failures.add(one.get("name").tojstring() + ": " + e.getMessage());
                        }
                    }
                    outcome[0] = result;
                } catch (Throwable t) {
                    crash[0] = t;
                }
            }
        }, "cjson-spec", 64L * 1024L * 1024L);
        worker.start();
        worker.join();
        if (crash[0] != null) throw new AssertionError("o grupo " + name + " não chegou a rodar", crash[0]);
        return outcome[0];
    }

    private static void assertGroupPasses(String name, int minimumCases) throws Exception {
        Outcome outcome = runGroup(name);
        assertTrue("o grupo " + name + " tem " + outcome.cases + " casos; esperados pelo menos " + minimumCases,
                outcome.cases >= minimumCases);
        StringBuilder message = new StringBuilder();
        for (String failure : outcome.failures) message.append("\n  - ").append(failure);
        assertTrue("cjson/" + name + " falhou em " + outcome.failures.size() + " de " + outcome.cases
                + " casos:" + message, outcome.failures.isEmpty());
    }

    @Test public void encodeFollowsLuaCjson() throws Exception { assertGroupPasses("encode", 17); }

    @Test public void numbersAreFormattedLikePercentDot14gWithoutLuajFormat() throws Exception {
        assertGroupPasses("numbers", 5);
    }

    @Test public void decodeFollowsLuaCjsonIncludingErrorPositions() throws Exception { assertGroupPasses("decode", 9); }

    @Test public void roundTripsKeepTheData() throws Exception { assertGroupPasses("roundtrip", 2); }

    @Test public void optionsAndInstancesAreIndependent() throws Exception { assertGroupPasses("options", 5); }

    @Test public void safeModuleReturnsNilAndMessage() throws Exception { assertGroupPasses("safe", 3); }

    private static Globals bootResolver(String initialCpath) throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("local loaded = package.loaded");
        int end = boot.indexOf("local included = {}", start);
        assertTrue("boot module resolver must exist", start >= 0 && end > start);

        Globals g = JsePlatform.standardGlobals();
        LuaTable assets = new LuaTable();
        assets.set("lua/lib/cjson.lua", LuaValue.valueOf(read("app/src/main/assets/lua/lib/cjson.lua")));
        assets.set("lua/lib/cjson/safe.lua", LuaValue.valueOf(read("app/src/main/assets/lua/lib/cjson/safe.lua")));
        g.set("assets", assets);
        if (initialCpath != null) g.get("package").set("cpath", LuaValue.valueOf(initialCpath));
        g.load("host = {read_asset = function(path) return assets[path] end, read_file = function() return nil end}\n"
                + "norns = {state = {path = ''}}\n"
                + "_path = {code = ''}\n", "cjson-host-fixture.lua").call();
        g.load(boot.substring(start, end), "boot-module-resolver.lua").call();
        return g;
    }

    /** O require real do boot.lua resolve "cjson" e "cjson.safe" nos assets (pontos viram barras em lua/lib/). */
    @Test public void bootRequireLoadsCjsonAndCjsonSafeFromTheBundledAssets() throws Exception {
        Globals g = bootResolver(null);
        LuaValue result = g.load(
                "assert(type(package.cpath) == 'string', 'package.cpath precisa existir depois do boot')\n"
                + "package.cpath = package.cpath .. ';/home/we/dust/code/x/?.so'\n"
                + "local cjson = require('cjson')\n"
                + "assert(cjson.encode({1, 2, 3}) == '[1,2,3]')\n"
                + "assert(require('cjson') == cjson, 'o require precisa guardar o módulo')\n"
                + "local safe = require('cjson.safe')\n"
                + "assert(safe ~= cjson and safe._NAME == 'cjson.safe')\n"
                + "local value, err = safe.decode('[')\n"
                + "assert(value == nil and type(err) == 'string')\n"
                + "assert(rawequal(safe.null, cjson.null))\n"
                + "assert(package.loaded['cjson.safe'] == safe)\n"
                + "return true\n", "cjson-boot-require.lua").call();
        assertTrue(result.toboolean());
    }

    @Test public void bootKeepsAnExistingPackageCpath() throws Exception {
        Globals g = bootResolver("/custom/?.so");
        assertEquals("/custom/?.so", g.get("package").get("cpath").tojstring());
    }
}
