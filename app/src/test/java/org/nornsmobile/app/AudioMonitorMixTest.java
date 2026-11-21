package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioMonitorMixTest {
    @Test public void mixesMonoInputToBothOutputChannelsAtRequestedLevel() {
        float[] input = {0.25f, -0.5f, 1f};
        float[] output = new float[6];
        AudioMonitorMix.apply(input, output, 0.5);
        assertArrayEquals(new float[]{0.125f,0.125f,-0.25f,-0.25f,0.5f,0.5f}, output, 1e-6f);
    }

    @Test public void zeroLevelLeavesOutputUntouched() {
        float[] output = {0.1f,0.2f,0.3f,0.4f};
        AudioMonitorMix.apply(new float[]{1f,1f}, output, 0);
        assertArrayEquals(new float[]{0.1f,0.2f,0.3f,0.4f}, output, 0);
    }

    @Test public void absentOrShortInputAndNonFiniteGainAreSafe() {
        float[] output = new float[4];
        AudioMonitorMix.apply(null, output, 1);
        AudioMonitorMix.apply(new float[]{0.5f}, output, Double.NaN);
        AudioMonitorMix.apply(new float[]{0.5f}, output, 1);
        assertArrayEquals(new float[]{0.5f,0.5f,0,0}, output, 1e-6f);
    }

    @Test public void clampsGainAndNeverWritesPastEitherBuffer() {
        float[] output = new float[2];
        AudioMonitorMix.apply(new float[]{0.2f,0.4f}, output, 8);
        assertArrayEquals(new float[]{0.2f,0.2f}, output, 1e-6f);
    }
}
