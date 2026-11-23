package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Patches de compatibilidade de código-fonte aplicados por {@code _norns.load_script} a scripts conhecidos.
 *
 * <p>Regressão coberta: o circles (JakeCarter) repassa a nota MIDI direto a {@code engine.hz()}, que espera Hz.
 * O PolyPerc tocava ~60-70 Hz, as escalas quase não alteravam o som e só a saída MIDI/crow seguia a escala.
 */
public class ScriptSourcePatchTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** Carrega somente o bloco de produção entre os marcadores compat-patches do boot.lua. */
    private static Globals patchRuntime() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("-- >>> compat-patches");
        int end = boot.indexOf("-- <<< compat-patches", start);
        assertTrue("boot.lua must keep the compat-patches block", start >= 0 && end > start);
        Globals g = JsePlatform.standardGlobals();
        g.load("_norns = {}\ncompat_logs = {}\n"
                        + "host = {log = function(message, is_error) compat_logs[#compat_logs + 1] = tostring(message) end}\n",
                "compat-patches-fixture.lua").call();
        g.load(boot.substring(start, end), "production-compat-patches.lua").call();
        return g;
    }

    private static String patch(Globals g, String source) {
        return g.get("_norns").get("patch_script_source")
                .call(LuaValue.valueOf(source), LuaValue.valueOf("test/script.lua")).tojstring();
    }

    private static final String CIRCLES_LIKE = ""
            + "engine.name = 'PolyPerc'\n"
            + "function noteForCircle(circle) return 65 end\n"
            + "function handleCircleBurst(circle)\n"
            + "  local note = noteForCircle(circle)\n"
            + "  engine.pw(0.5)\n"
            + "  engine.hz(note)\n"
            + "end\n";

    @Test public void circlesMidiNoteIsConvertedToHzBeforeReachingTheEngine() throws Exception {
        Globals g = patchRuntime();
        String patched = patch(g, CIRCLES_LIKE);
        assertTrue(patched.contains("engine.hz(require(\"musicutil\").note_num_to_freq(note))"));
        assertFalse("the MIDI note number must no longer reach engine.hz", patched.contains("engine.hz(note)"));
        // A troca é na própria linha: mensagens de erro continuam apontando para a linha original.
        assertEquals(CIRCLES_LIKE.split("\n", -1).length, patched.split("\n", -1).length);
        assertEquals(1, g.get("compat_logs").length());
        assertTrue(g.get("compat_logs").get(1).tojstring().contains("circles"));
    }

    @Test public void patchIsIdempotentAndLeavesEveryOtherScriptUntouched() throws Exception {
        Globals g = patchRuntime();
        String once = patch(g, CIRCLES_LIKE);
        assertEquals("patching twice must not convert the frequency a second time", once, patch(g, once));

        // Upstream já corrigido: a assinatura existe, mas a chamada antiga não. Nada a fazer.
        String fixedUpstream = CIRCLES_LIKE.replace("engine.hz(note)",
                "engine.hz(music_util.note_num_to_freq(note))");
        assertEquals(fixedUpstream, patch(g, fixedUpstream));

        // Outro script cuja variável `note` já está em Hz: não pode ser alterado.
        String otherScript = "engine.name = 'PolyPerc'\nfunction play(note) engine.hz(note) end\n";
        assertEquals(otherScript, patch(g, otherScript));

        // Assinatura do circles, mas a chamada aparece duas vezes: ambíguo, não mexe.
        String ambiguous = CIRCLES_LIKE.replace("  engine.hz(note)\n", "  engine.hz(note)\n  engine.hz(note)\n");
        assertEquals(ambiguous, patch(g, ambiguous));

        // Outro engine: o valor passado a engine.hz não é assunto do PolyPerc.
        String otherEngine = CIRCLES_LIKE.replace("PolyPerc", "Other");
        assertEquals(otherEngine, patch(g, otherEngine));

        assertTrue(g.get("_norns").get("patch_script_source")
                .call(LuaValue.NIL, LuaValue.valueOf("x")).isnil());
        // Só o primeiro patch (circles-like) registrou log; os demais casos não aplicaram nada.
        assertEquals(1, g.get("compat_logs").length());
    }

    /**
     * Roda o circles fixado com as bibliotecas Lua reais do app (util, controlspec, paramset, musicutil) e
     * confirma que o synth recebe a frequência da MESMA nota quantizada pela escala que a saída MIDI envia.
     */
    @Test public void pinnedCirclesAudioPlaysTheSameQuantizedNotesThatMidiReceives() throws Exception {
        final String circles = read("app/build/compatibility-scripts/circles/circles.lua");
        final String libCircles = read("app/build/compatibility-scripts/circles/lib/libCircles.lua");
        final String mathHelpers = read("app/build/compatibility-scripts/circles/lib/math_helpers.lua");
        final String utilSource = read("app/src/main/assets/lua/lib/util.lua");
        final String controlspecSource = read("app/src/main/assets/lua/lib/controlspec.lua");
        final String paramsetSource = read("app/src/main/assets/lua/lib/paramset.lua");
        final String musicutilSource = read("app/src/main/assets/lua/lib/musicutil.lua");

        String patched = patch(patchRuntime(), circles);
        assertNotEquals("the compat patch must match the pinned circles fixture; if upstream fixed "
                + "engine.hz, retire the rule in boot.lua", circles, patched);

        final Globals g = JsePlatform.standardGlobals();
        final LuaTable loaded = g.get("package").get("loaded").checktable();
        g.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                LuaValue cached = loaded.get(name);
                if (!cached.isnil()) return cached;
                LuaValue module;
                if ("ui".equals(name)) {
                    module = g.load("return {Message={new=function() return {redraw=function() end} end}}",
                            "ui-fixture.lua").call();
                } else {
                    String source;
                    if ("util".equals(name)) source = utilSource;
                    else if ("controlspec".equals(name)) source = controlspecSource;
                    else if ("paramset".equals(name)) source = paramsetSource;
                    else if ("musicutil".equals(name)) source = musicutilSource;
                    else throw new AssertionError("Unexpected module in circles: " + name);
                    module = g.load(source, "@bundled/" + name + ".lua").call();
                }
                loaded.set(name, module);
                return module;
            }
        });
        final LuaValue mathHelpersModule = g.load(mathHelpers, "@upstream/circles/lib/math_helpers.lua").call();
        g.set("include", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requested) {
                String name = requested.checkjstring();
                if ("lib/math_helpers".equals(name)) return mathHelpersModule;
                if ("lib/libCircles".equals(name)) return g.load(libCircles, "@upstream/circles/lib/libCircles.lua").call();
                throw new AssertionError("Unexpected circles include: " + name);
            }
        });
        // musicutil usa o global `util`, como no norns real.
        g.set("util", g.get("require").call(LuaValue.valueOf("util")));
        g.set("controlspec", g.get("require").call(LuaValue.valueOf("controlspec")));
        g.set("paramset", g.get("require").call(LuaValue.valueOf("paramset")));
        g.set("params", g.get("paramset").get("new").call());
        g.load(
                "audio_hz = nil\n"
                + "midi_note = nil\n"
                + "engine = setmetatable({}, {__index = function(self, name) "
                + "return function(value) if name == 'hz' then audio_hz = value end end end})\n"
                + "clock = {transport = {}, run = function() return 1 end, sync = function() end}\n"
                + "midi = {vports = {{name = 'virtual'}}, to_msg = function() return {} end, "
                + "connect = function() return {note_on = function(self, n) midi_note = n end, "
                + "note_off = function() end} end}\n"
                + "local noop_index = setmetatable({}, {__index = function() return function() end end})\n"
                + "crow = setmetatable({ii = setmetatable({jf = noop_index, pullup = function() end}, {}), "
                + "output = setmetatable({}, {__index = function() return {execute = function() end} end})}, {})\n"
                + "screen = noop_index\n"
                + "tab = {save = function() end, load = function() return nil end}\n",
                "circles-audio-vs-midi-fixture.lua").call();

        g.load(patched, "@upstream/circles/circles.lua").call();
        g.get("init").call();
        // Como o app (norns.script.run): aplica as ações dos parâmetros após o init.
        g.load("params:bang()", "circles-params-bang.lua").call();

        double majorAtRight = Double.NaN;
        double pentatonicAtRight = Double.NaN;
        final int[] modes = {1, 2, 5, 12}; // Major, Natural Minor, Dorian, Minor Pentatonic
        final int[] positions = {5, 30, 64, 90, 125};
        for (int mode : modes) {
            for (int x : positions) {
                Varargs result = g.load(
                        "params:set('scale_mode', " + mode + ")\n"
                        + "params:set('circles_output', 2)\n"
                        + "midi_note = nil\n"
                        + "handleCircleBurst({x = " + x + ", y = 32, r = 12})\n"
                        + "local sent_to_midi = midi_note\n"
                        + "params:set('circles_output', 1)\n"
                        + "audio_hz = nil\n"
                        + "handleCircleBurst({x = " + x + ", y = 32, r = 12})\n"
                        + "return sent_to_midi, audio_hz",
                        "circles-audio-vs-midi.lua").invoke();
                String where = "scale mode " + mode + ", x=" + x;
                assertTrue("MIDI must receive a note: " + where, result.arg(1).isnumber());
                assertTrue("engine.hz must be called: " + where, result.arg(2).isnumber());
                double midiNote = result.arg(1).checkdouble();
                double hz = result.arg(2).checkdouble();
                assertTrue("engine.hz must receive Hz, not a MIDI note number (" + hz + "): " + where, hz > 200.0);
                double expected = 440.0 * Math.pow(2.0, (midiNote - 69.0) / 12.0);
                assertEquals("audio must play the note MIDI receives: " + where, expected, hz, expected * 1e-9);
                if (x == 125 && mode == 1) majorAtRight = hz;
                if (x == 125 && mode == 12) pentatonicAtRight = hz;
            }
        }
        assertTrue("changing the scale must change the synth pitch",
                Math.abs(majorAtRight - pentatonicAtRight) / majorAtRight > 0.01);
    }
}
