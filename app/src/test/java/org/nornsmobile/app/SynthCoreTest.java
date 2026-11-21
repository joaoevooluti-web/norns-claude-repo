package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SynthCoreTest {
    @Test public void knownEnginesRemainAvailable() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("PolyPerc"));
        assertEquals(SynthCore.ENGINE_POLYPERC, core.getEngine());
        assertTrue(core.setEngine("TestSine"));
        assertEquals(SynthCore.ENGINE_TESTSINE, core.getEngine());
        assertTrue(core.setEngine("FM"));
        assertEquals(SynthCore.ENGINE_FM, core.getEngine());
        assertTrue(core.setEngine("Pluck"));
        assertEquals(SynthCore.ENGINE_PLUCK, core.getEngine());
        assertTrue(core.setEngine("none"));
        assertEquals(SynthCore.ENGINE_NONE, core.getEngine());
    }

    @Test public void unknownEngineIsSilentInsteadOfFalselyPlayingPolyPerc() {
        SynthCore core = new SynthCore(48000);
        assertFalse(core.setEngine("not-an-engine"));
        assertEquals(SynthCore.ENGINE_NONE, core.getEngine());
        core.command("hz", 440, 0);
        float[] output = new float[512 * 2];
        core.render(output, 512);
        for (float sample : output) assertEquals(0f, sample, 0f);
    }
    @Test public void fmEngineRespondsToNornsStyleControlsAndProducesStereoAudio() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("FM"));
        core.command("amp", 0.5, 0);
        core.command("index", 3.0, 0);
        core.command("ratio", 2.0, 0);
        core.command("pan", -1.0, 0);
        core.command("release", 0.25, 0);
        core.command("hz", 440.0, 0);
        float[] output = new float[1024 * 2];
        core.render(output, 1024);
        boolean leftAudible = false;
        boolean rightAudible = false;
        for (int i = 0; i < 1024; i++) {
            leftAudible |= Math.abs(output[i * 2]) > 0.0001f;
            rightAudible |= Math.abs(output[i * 2 + 1]) > 0.0001f;
            assertTrue(Math.abs(output[i * 2]) <= 1.0f);
            assertTrue(Math.abs(output[i * 2 + 1]) <= 1.0f);
        }
        assertTrue("FM voice should produce left-channel audio", leftAudible);
        assertFalse("full-left pan should keep right channel silent", rightAudible);
    }

    @Test public void pluckEngineProducesDecayingStereoStringAndHonorsPan() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("Pluck"));
        core.command("amp", 0.6, 0);
        core.command("decay", 1.5, 0);
        core.command("damping", 0.25, 0);
        core.command("pan", 1.0, 0);
        core.command("hz", 220.0, 0);
        float[] output = new float[4096 * 2];
        core.render(output, 4096);
        boolean rightAudible = false;
        boolean leftAudible = false;
        for (int i = 0; i < 4096; i++) {
            rightAudible |= Math.abs(output[i * 2 + 1]) > 0.0001f;
            leftAudible |= Math.abs(output[i * 2]) > 0.0001f;
            assertTrue(Math.abs(output[i * 2]) <= 1.0f);
            assertTrue(Math.abs(output[i * 2 + 1]) <= 1.0f);
        }
        assertTrue("Pluck should produce right-channel audio", rightAudible);
        assertFalse("full-right pan should keep left channel silent", leftAudible);
    }

    @Test public void fmEngineDoesNotReplaceUnknownEngines() {
        SynthCore core = new SynthCore(48000);
        assertFalse(core.setEngine("KarplusRings"));
        assertEquals(SynthCore.ENGINE_NONE, core.getEngine());
    }
@Test public void fmEngineProducesAudioAndHonorsVoiceReset() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("FM"));
        core.command("amp", 0.5, 0);
        core.command("ratio", 2.0, 0);
        core.command("index", 3.0, 0);
        core.command("hz", 440, 0);
        float[] output = new float[1024 * 2];
        core.render(output, 1024);
        assertHasSignal(output);
        core.allNotesOff();
        java.util.Arrays.fill(output, 0f);
        core.render(output, 1024);
        assertSilent(output);
    }

@Test public void pluckEngineProducesDecayingAudioAndVoiceReset() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("Pluck"));
        core.command("amp", 0.6, 0);
        core.command("decay", 2.0, 0);
        core.command("damp", 0.3, 0);
        core.command("hz", 220, 0);
        float[] output = new float[1024 * 2];
        core.render(output, 1024);
        assertHasSignal(output);
        core.allNotesOff();
        java.util.Arrays.fill(output, 0f);
        core.render(output, 1024);
        assertSilent(output);
    }

@Test public void harmonyFallbackRemainsFiniteAtControlBounds() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("32Harmony"));
        core.command("rate1In", 20000, 0);
        core.command("rate2In", 20000, 0);
        core.command("rootIn", 20000, 0);
        core.command("range1In", 9, 0);
        core.command("range2In", 9, 0);
        core.command("attackIn", 0.001, 0);
        core.command("decayIn", 30, 0);
        core.command("lpfCutoffIn", 20000, 0);
        core.command("hpfCutoffIn", 20000, 0);
        core.command("quantAmtIn", 0, 0);
        core.command("step0In", 32, 0);
        core.command("step1In", 32, 0);
        core.command("step2In", 32, 0);
        core.command("step3In", 32, 0);
        core.command("step4In", 32, 0);
        core.command("freqMultIn", 32, 0);
        core.command("saturationAmtIn", 300, 0);
        float[] output = new float[4096 * 2];
        core.render(output, 4096);
        for (float sample : output)
            assertTrue("bounded Harmony controls must not produce NaN/Infinity", Float.isFinite(sample));
    }

@Test public void harmonyJavaFallbackProducesStereoAudioAndCanBeReset() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("32Harmony"));
        core.command("rate1In", 1000, 0);
        core.command("rate2In", 800, 0);
        core.command("rootIn", 55, 0);
        core.command("range1In", 3, 0);
        core.command("range2In", 5, 0);
        core.command("attackIn", 0.02, 0);
        core.command("decayIn", 1.5, 0);
        core.command("lpfCutoffIn", 3000, 0);
        core.command("hpfCutoffIn", 80, 0);
        core.command("quantAmtIn", 0.7, 0);
        core.command("step0In", 1, 0);
        core.command("step1In", 1.122462, 0);
        core.command("step2In", 1.259921, 0);
        core.command("step3In", 1.498307, 0);
        core.command("step4In", 1.681793, 0);
        core.command("freqMultIn", 1, 0);
        core.command("saturationAmtIn", 1.5, 0);
        float[] output = new float[48000 * 2];
        core.render(output, 48000);
        double left = 0, right = 0;
        for (int i = 0; i < output.length; i += 2) {
            left += Math.abs(output[i]);
            right += Math.abs(output[i + 1]);
        }
        assertTrue("32Harmony fallback must produce left output", left > 0.1);
        assertTrue("32Harmony fallback must produce right output", right > 0.1);
        for (float sample : output)
            assertTrue("32Harmony output must remain finite", Float.isFinite(sample));
        assertTrue(core.setEngine("none"));
        java.util.Arrays.fill(output, 0f);
        core.render(output, 512);
        for (float sample : output) assertEquals(0f, sample, 0f);
    }
    private static void assertHasSignal(float[] output) {
        boolean audible = false;
        for (float sample : output) {
            assertTrue("synth output must remain finite", Float.isFinite(sample));
            audible |= Math.abs(sample) > 0.00001f;
        }
        assertTrue("synth should produce audible output", audible);
    }

    private static void assertSilent(float[] output) {
        for (float sample : output) {
            assertTrue("synth should be silent after allNotesOff", Math.abs(sample) <= 0.000001f);
        }
    }
    @Test public void simpleDelayIsARecognizedSilentOnsetEngineWithBoundedThreshold() {
        SynthCore core = new SynthCore(48000);
        assertTrue(core.setEngine("SimpleDelay"));
        assertEquals(SynthCore.ENGINE_SIMPLE_DELAY, core.getEngine());
        assertEquals(-60.0, core.getSimpleDelayThresholdDb(), 0.0);
        core.command("threshold", -24.0, 0);
        assertEquals(-24.0, core.getSimpleDelayThresholdDb(), 0.0);
        core.command("threshold", 12.0, 0);
        assertEquals(0.0, core.getSimpleDelayThresholdDb(), 0.0);
        core.command("threshold", -120.0, 0);
        assertEquals(-90.0, core.getSimpleDelayThresholdDb(), 0.0);
        float[] output = new float[512 * 2];
        core.render(output, 512);
        for (float sample : output) assertEquals("SimpleDelay is an input detector, not an output synth", 0f, sample, 0f);
    }


    @Test public void simpleDelayKolorIsExplicitlyUnavailableAndNeverAliasesToAnotherEngine() {
        SynthCore core = new SynthCore(48000);
        assertFalse("Kolor must not be reported as implemented before its sample engine exists",
                core.setEngine("SimpleDelayKolor"));
        assertEquals(SynthCore.ENGINE_NONE, core.getEngine());
        core.command("kolorplay", 1, 0);
        float[] output = new float[1024 * 2];
        core.render(output, 1024);
        assertSilent(output);
    }


}
