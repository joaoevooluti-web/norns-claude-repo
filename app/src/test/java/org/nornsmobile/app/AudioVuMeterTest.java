package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

// [run-ci] Deterministic IEC-60268 meter, release-cache and lifecycle regression coverage.
public class AudioVuMeterTest {
    @Test public void mapsPeakLevelsThroughTheNornsIec60268Taper() {
        AudioVuMeter meter = new AudioVuMeter();
        float[] input = {1f, -0.5f, 0.25f, 0f};
        float[] output = {1f, 0.5f, -0.25f, 0.125f, 0f, 0f, 0f, 0f};
        meter.update(input, output, 4, 48000);
        assertEquals(64, meter.inputLeft());
        assertEquals(64, meter.inputRight());
        assertEquals(64, meter.outputLeft());
        assertTrue(meter.outputRight() > 0);
        assertTrue(meter.outputRight() < meter.outputLeft());
    }

    @Test public void silenceStartsAtZeroAndDoesNotCreateMeterActivity() {
        AudioVuMeter meter = new AudioVuMeter();
        meter.update(new float[128], new float[256], 128, 48000);
        assertEquals(0, meter.inputLeft());
        assertEquals(0, meter.inputRight());
        assertEquals(0, meter.outputLeft());
        assertEquals(0, meter.outputRight());
    }

    @Test public void attackIsImmediateAndReleaseReachesTheFloorAfterThreeHundredMs() {
        AudioVuMeter meter = new AudioVuMeter();
        float[] input = new float[128];
        float[] output = new float[256];
        input[0] = 1f;
        output[0] = 1f;
        output[1] = 1f;
        meter.update(input, output, 128, 48000);
        assertEquals(64, meter.inputLeft());
        assertEquals(64, meter.outputRight());

        java.util.Arrays.fill(input, 0f);
        java.util.Arrays.fill(output, 0f);
        for (int i = 0; i < 113; i++) meter.update(input, output, 128, 48000);
        assertEquals(0, meter.inputLeft());
        assertEquals(0, meter.outputLeft());
        assertEquals(0, meter.outputRight());
    }

    @Test public void invalidSamplesAreIgnoredAndOversAreClamped() {
        AudioVuMeter meter = new AudioVuMeter();
        float[] input = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        float[] output = {2f, Float.NaN, -2f, Float.POSITIVE_INFINITY};
        meter.update(input, output, 3, 48000);
        assertEquals(0, meter.inputLeft());
        assertEquals(64, meter.outputLeft());
        assertEquals(0, meter.outputRight());
    }

    @Test public void resetClearsMeterHistoryBetweenAudioSessions() {
        AudioVuMeter meter = new AudioVuMeter();
        float[] input = new float[128];
        float[] output = new float[256];
        input[0] = 1f;
        output[0] = 1f;
        output[1] = 1f;
        meter.update(input, output, 128, 48000);
        assertEquals(64, meter.inputLeft());
        meter.reset();
        assertEquals(0, meter.inputLeft());
        assertEquals(0, meter.inputRight());
        assertEquals(0, meter.outputLeft());
        assertEquals(0, meter.outputRight());
    }

    @Test public void releaseIsIndependentOfAudioBlockSize() {
        AudioVuMeter smallBlocks = new AudioVuMeter();
        AudioVuMeter largeBlocks = new AudioVuMeter();
        float[] inSmall = new float[128], outSmall = new float[256];
        float[] inLarge = new float[256], outLarge = new float[512];
        inSmall[0] = 1f; outSmall[0] = 1f; outSmall[1] = 1f;
        inLarge[0] = 1f; outLarge[0] = 1f; outLarge[1] = 1f;
        smallBlocks.update(inSmall, outSmall, 128, 48000);
        largeBlocks.update(inLarge, outLarge, 256, 48000);
        java.util.Arrays.fill(inSmall, 0f);
        java.util.Arrays.fill(outSmall, 0f);
        java.util.Arrays.fill(inLarge, 0f);
        java.util.Arrays.fill(outLarge, 0f);
        for (int i = 0; i < 112; i++) smallBlocks.update(inSmall, outSmall, 128, 48000);
        for (int i = 0; i < 56; i++) largeBlocks.update(inLarge, outLarge, 256, 48000);
        assertEquals(smallBlocks.inputLeft(), largeBlocks.inputLeft());
        assertEquals(smallBlocks.outputLeft(), largeBlocks.outputLeft());
    }
}
