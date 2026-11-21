package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioOutputGainTest {
    @Test public void unityGainPreservesSamples() {
        float[] samples = new float[]{-0.75f, -0.1f, 0f, 0.25f, 0.9f};
        float[] expected = samples.clone();
        AudioOutputGain.apply(samples, 1.0);
        assertArrayEquals(expected, samples, 0.000001f);
    }

    @Test public void halfGainScalesBothChannelsAndMuteZerosOutput() {
        float[] samples = new float[]{-0.8f, 0.6f, 0.4f, -0.2f};
        AudioOutputGain.apply(samples, 0.5);
        assertArrayEquals(new float[]{-0.4f, 0.3f, 0.2f, -0.1f}, samples, 0.000001f);
        AudioOutputGain.apply(samples, 0.0);
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, samples, 0f);
    }

    @Test public void gainIsBoundedAndOutputCannotClip() {
        float[] samples = new float[]{-2f, 0.5f, 2f};
        AudioOutputGain.apply(samples, 4.0);
        assertArrayEquals(new float[]{-1f, 0.5f, 1f}, samples, 0.000001f);
    }

    @Test public void invalidGainDoesNotCorruptSamples() {
        float[] samples = new float[]{-0.5f, 0.75f};
        float[] expected = samples.clone();
        AudioOutputGain.apply(samples, Double.NaN);
        AudioOutputGain.apply(samples, Double.POSITIVE_INFINITY);
        assertArrayEquals(expected, samples, 0f);
    }
}
