package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioEngineGainTest {
    @Test public void unityGainPreservesEngineSamples() {
        float[] samples = new float[]{-0.9f, -0.2f, 0f, 0.3f, 0.8f};
        float[] expected = samples.clone();
        AudioEngineGain.apply(samples, 1.0);
        assertArrayEquals(expected, samples, 0f);
    }

    @Test public void halfGainScalesEngineWithoutClippingOrTouchingOtherSources() {
        float[] engine = new float[]{-1.4f, 0.8f, 0.4f, -0.2f};
        float[] unrelated = new float[]{0.7f, -0.6f};
        float[] unrelatedBefore = unrelated.clone();
        AudioEngineGain.apply(engine, 0.5);
        assertArrayEquals(new float[]{-0.7f, 0.4f, 0.2f, -0.1f}, engine, 0.000001f);
        assertArrayEquals(unrelatedBefore, unrelated, 0f);
    }

    @Test public void zeroGainMutesOnlyEngine() {
        float[] samples = new float[]{-0.7f, 0.4f, 0.9f};
        AudioEngineGain.apply(samples, 0.0);
        assertArrayEquals(new float[]{0f, 0f, 0f}, samples, 0f);
    }

    @Test public void invalidGainDoesNotCorruptSamples() {
        float[] samples = new float[]{-0.5f, 0.75f};
        float[] expected = samples.clone();
        AudioEngineGain.apply(samples, Double.NaN);
        AudioEngineGain.apply(samples, Double.POSITIVE_INFINITY);
        assertArrayEquals(expected, samples, 0f);
    }
}
