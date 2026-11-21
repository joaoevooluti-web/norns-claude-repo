package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioInputGainTest {
    @Test public void unityPreservesCapturedInput() {
        float[] input = {0.25f, -0.5f, 1f};
        AudioInputGain.apply(input, 1.0);
        assertArrayEquals(new float[]{0.25f, -0.5f, 1f}, input, 0f);
    }

    @Test public void zeroMutesAdcInput() {
        float[] input = {0.25f, -0.5f, 1f};
        AudioInputGain.apply(input, 0.0);
        assertArrayEquals(new float[]{0f, 0f, 0f}, input, 0f);
    }

    @Test public void intermediateGainScalesEveryFrame() {
        float[] input = {0.25f, -0.5f, 1f};
        AudioInputGain.apply(input, 0.4);
        assertArrayEquals(new float[]{0.1f, -0.2f, 0.4f}, input, 1e-6f);
    }

    @Test public void invalidGainLeavesInputUntouchedAndOutOfRangeIsClamped() {
        float[] invalid = {0.25f, -0.5f};
        AudioInputGain.apply(invalid, Double.NaN);
        assertArrayEquals(new float[]{0.25f, -0.5f}, invalid, 0f);
        float[] clamped = {0.25f, -0.5f};
        AudioInputGain.apply(clamped, 2.0);
        assertArrayEquals(new float[]{0.25f, -0.5f}, clamped, 0f);
    }

    @Test public void nullAndEmptyBuffersAreSafe() {
        AudioInputGain.apply(null, 0.5);
        float[] empty = new float[0];
        AudioInputGain.apply(empty, 0.5);
        assertEquals(0, empty.length);
    }
}
