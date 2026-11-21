package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class Engine32HarmonyCommandTest {
    @Test
    public void nativeCommandsAreQueuedDuringLoadAndAppliedToNamedSynthControls() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        if (!file.isFile()) throw new AssertionError("Missing boot.lua");
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String startMarker = "engine_dispatch_command = function(name, ...)";
        String endMarker = "engine = setmetatable({name =";
        int start = boot.indexOf(startMarker);
        int end = boot.indexOf(endMarker, start);
        if (start < 0 || end <= start) throw new AssertionError("Could not isolate engine command dispatcher");

        Globals globals = JsePlatform.standardGlobals();
        globals.load(
                "engine = {name = '32Harmony', loaded = true, _native_state = 'loading', "
                + "_native_node = 77, _native_pending = {}, _native_params = {harmony = {}}}\n"
                + "applied = {}\n"
                + "sc_api = {set = function(node, controls) "
                + "assert(node == 77); applied[#applied + 1] = controls; return true end}\n"
                + "host = {engine_cmd = function() error('32Harmony must not use Java fallback') end}\n",
                "engine-32harmony-fixture.lua").call();
        assertNotNull(globals.load(boot.substring(start, end), "engine-32harmony-dispatch.lua").call());
        globals.load(
                "local names = {'rate1In','rate2In','rootIn','range1In','range2In',"
                + "'attackIn','decayIn','lpfCutoffIn','hpfCutoffIn','quantAmtIn',"
                + "'step0In','step1In','step2In','step3In','step4In','freqMultIn','saturationAmtIn'}\n"
                + "for i = 1, #names do "
                + "assert(engine_dispatch_command(names[i], i) == true); "
                + "assert(engine._native_pending[i].name == names[i]); "
                + "assert(engine._native_pending[i].a == i) end\n"
                + "assert(engine_dispatch_command('unknownControl', 1) == false)\n"
                + "engine._native_state = 'native'\n"
                + "engine._native_pending = {}\n"
                + "for i = 1, #names do "
                + "assert(engine_dispatch_command(names[i], i * 2) == true); "
                + "local control = applied[i]; "
                + "assert(control[names[i]] == i * 2) end\n"
                + "assert(#applied == #names)\n",
                "engine-32harmony-command-contract.lua").call();
    }

    @Test
    public void compiled32HarmonySourceKeepsEveryLuaControlAndBothSixteenVoiceLayers() throws Exception {
        File file = new File("tools/compile-softcut-synthdef.scd");
        if (!file.isFile()) file = new File("../tools/compile-softcut-synthdef.scd");
        if (!file.isFile()) throw new AssertionError("Missing SynthDef compiler source");
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int start = source.indexOf("SynthDef(\\Norns32Harmony");
        int end = source.indexOf("}).writeDefFile", start);
        assertTrue("Norns32Harmony SynthDef must be compiled by CI", start >= 0 && end > start);
        String synthDef = source.substring(start, end);

        String[] controls = {
                "rate1In", "rate2In", "rootIn", "range1In", "range2In",
                "attackIn", "decayIn", "lpfCutoffIn", "hpfCutoffIn", "quantAmtIn",
                "step0In", "step1In", "step2In", "step3In", "step4In",
                "freqMultIn", "saturationAmtIn"
        };
        for (String control : controls) {
            assertTrue("SynthDef is missing Lua control " + control,
                    synthDef.contains(control + " ="));
        }
        assertTrue("Left harmony layer must contain 16 voices",
                synthDef.contains("left = Mix.fill(16"));
        assertTrue("Right harmony layer must contain 16 voices",
                synthDef.contains("right = Mix.fill(16"));
        assertTrue("SynthDef must write stereo output", synthDef.contains("Out.ar(out, signal)"));
    }


    @Test
    public void javaEngineRegistersAndRenders32HarmonyWithoutFallingBackToPolyPerc() {
        SynthCore synth = new SynthCore(48000);
        assertTrue(synth.setEngine("32Harmony"));
        assertEquals(SynthCore.ENGINE_32HARMONY, synth.getEngine());
        synth.command("rate1In", 20000, 0);
        synth.command("rate2In", 20000, 0);
        synth.command("rootIn", 110, 0);
        synth.command("range1In", 4, 0);
        synth.command("range2In", 5, 0);
        synth.command("attackIn", 0.001, 0);
        synth.command("decayIn", 1.0, 0);
        synth.command("lpfCutoffIn", 12000, 0);
        synth.command("hpfCutoffIn", 20, 0);
        synth.command("quantAmtIn", 1, 0);
        synth.command("step0In", 1, 0);
        synth.command("step1In", 1.122, 0);
        synth.command("step2In", 1.26, 0);
        synth.command("step3In", 1.5, 0);
        synth.command("step4In", 1.682, 0);
        synth.command("freqMultIn", 1, 0);
        synth.command("saturationAmtIn", 1, 0);
        float[] output = new float[48000 * 2];
        synth.render(output, 48000);
        boolean audible = false;
        for (float sample : output) {
            assertTrue(Float.isFinite(sample));
            if (Math.abs(sample) > 1e-6f) audible = true;
        }
        assertTrue("32Harmony should render finite audible stereo output", audible);
        synth.allNotesOff();
    }

    @Test
    public void unknownJavaEngineRemainsSilentInsteadOfSelectingPolyPerc() {
        SynthCore synth = new SynthCore(48000);
        assertFalse(synth.setEngine("not-an-engine"));
        assertEquals(SynthCore.ENGINE_NONE, synth.getEngine());
    }

}
