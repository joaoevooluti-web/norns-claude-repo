package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioPollMetricsTest {
    @Test public void reportsMonoInputStereoOutputAndPitch() {
        AudioPollMetrics metrics = new AudioPollMetrics();
        metrics.setPitchAnalysisEnabled(true);
        int frames = 2048;
        int rate = 48000;
        float[] input = new float[frames];
        float[] output = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            input[i] = (float)(0.5 * Math.sin(2 * Math.PI * 440 * i / rate));
            output[i * 2] = 0.5f;
            output[i * 2 + 1] = 0.25f;
        }
        metrics.update(input, output, frames, rate, 10_000_000L);
        assertEquals(0.3535, metrics.value("amp_in_l"), 0.01);
        assertEquals(metrics.value("amp_in_l"), metrics.value("amp_in_r"), 0.0001);
        assertEquals(0.5, metrics.value("amp_out_l"), 0.0001);
        assertEquals(0.25, metrics.value("amp_out_r"), 0.0001);
        assertEquals(440, metrics.value("pitch_in_l"), 20);
        assertEquals(metrics.value("pitch_in_l"), metrics.value("pitch_in_r"), 0.0001);
        assertTrue(metrics.value("cpu_avg") > 0);
        assertTrue(metrics.value("cpu_peak") > 0);
    }

    @Test public void pitchAnalysisIsOptInAndOffClearsThePreviousEstimate() {
        AudioPollMetrics metrics = new AudioPollMetrics();
        float[] tone = new float[2048];
        for (int i = 0; i < tone.length; i++)
            tone[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / 48000));
        float[] output = new float[4096];

        metrics.update(tone, output, tone.length, 48000, 1_000_000L);
        assertEquals(-1, metrics.value("pitch_in_l"), 0);

        metrics.setPitchAnalysisEnabled(true);
        metrics.update(tone, output, tone.length, 48000, 1_000_000L);
        assertEquals(440, metrics.value("pitch_in_l"), 20);

        metrics.setPitchAnalysisEnabled(false);
        assertEquals(-1, metrics.value("pitch_in_l"), 0);
    }

    @Test public void silenceHasNoDetectedPitchAndUnknownPollReturnsUnavailable() {
        AudioPollMetrics metrics = new AudioPollMetrics();
        float[] silence = new float[2048];
        metrics.update(silence, new float[4096], 2048, 48000, 1_000_000L);
        assertEquals(-1, metrics.value("pitch_in_l"), 0);
        assertEquals(-1, metrics.value("not_a_poll"), 0);
        assertEquals(0, metrics.value("amp_in_l"), 0);
    }
}
