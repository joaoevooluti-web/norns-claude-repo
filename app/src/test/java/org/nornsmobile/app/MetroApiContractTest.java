package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MetroApiContractTest {
    @Test public void scriptMetrosRespectTheNornsThirtySlotLimit() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("metro = {num_metros = 36, num_script_metros = 30}"));
        assertTrue(boot.contains("while id <= metro.num_script_metros and metro_list[id] ~= nil do id = id + 1 end"));
        assertTrue(boot.contains("if id > metro.num_script_metros then"));
        assertTrue(boot.contains("metro.init: limite de 30 temporizadores de script atingido"));
        assertTrue(boot.contains("metro_list[id] = m"));
    }

    @Test public void metroModuleExposesInstancesAndPublicConstructor() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function metro.new(id)"));
        assertTrue(boot.contains("id > metro.num_metros"));
        assertTrue(boot.contains("id = id, time = 1, count = -1, init_stage = 1"));
        assertTrue(boot.contains("if type(key) == \"number\" then return metro_list[key] end"));
    }

    @Test public void zeroCountCannotAccidentallyCreateAnInfiniteMetro() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("local function fire()");
        int end = boot.indexOf("sched_add(m.next_t, fire)", start);
        assertTrue(start >= 0 && end > start);
        String fire = boot.substring(start, end);
        assertTrue(fire.contains("cnt ~= nil and cnt >= 0"));
        assertTrue(fire.contains("stage >= math.max(1, cnt)"));
        assertTrue(fire.contains("m.is_running = false"));
    }

    @Test public void metroFreeAndFreeAllRetireScheduledCallbacks() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function Metro:stop()"));
        assertTrue(boot.contains("self.gen = (self.gen or 0) + 1"));
        assertTrue(boot.contains("function Metro:free()"));
        assertTrue(boot.contains("function metro.free(metro_or_id)"));
        assertTrue(boot.contains("function metro.free_all()"));
        assertTrue(boot.contains("for i = 1, #pending do pending[i]:free() end"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
