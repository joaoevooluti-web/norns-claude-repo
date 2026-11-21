package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SimpleOnsetDetectorTest {
    private static float[] block(float value, int frames) {
        float[] samples = new float[frames];
        java.util.Arrays.fill(samples, value);
        return samples;
    }

    @Test public void thresholdCrossingEmitsOnceUntilSignalFallsBelowHysteresis() {
        SimpleOnsetDetector detector = new SimpleOnsetDetector();
        assertFalse(detector.process(block(0f, 100), 100, 1000, -20));
        assertTrue(detector.process(block(0.2f, 100), 100, 1000, -20));
        assertFalse("sustained signal must not retrigger", detector.process(block(0.2f, 100), 100, 1000, -20));
        assertFalse("signal above the release hysteresis must remain latched",
                detector.process(block(0.08f, 100), 100, 1000, -20));
        assertFalse(detector.process(block(0f, 100), 100, 1000, -20));
    }

    @Test public void enforcesTwoHundredMillisecondMinimumGapAfterRearming() {
        SimpleOnsetDetector detector = new SimpleOnsetDetector();
        assertTrue(detector.process(block(0.5f, 100), 100, 1000, -20));
        assertFalse(detector.process(block(0f, 50), 50, 1000, -20));
        assertFalse("only 100 ms elapsed",
                detector.process(block(0.5f, 50), 50, 1000, -20));
        assertFalse(detector.process(block(0f, 100), 100, 1000, -20));
        assertTrue("onset may retrigger after the 200 ms gap",
                detector.process(block(0.5f, 50), 50, 1000, -20));
    }

    @Test public void ignoresNonFiniteInputSamplesAndRecovers() {
        SimpleOnsetDetector detector = new SimpleOnsetDetector();
        float[] invalid = block(Float.NaN, 100);
        invalid[10] = Float.POSITIVE_INFINITY;
        assertFalse(detector.process(invalid, 100, 1000, -20));
        assertTrue(detector.process(block(0.5f, 100), 100, 1000, -20));
    }

    @Test public void resetAllowsAnImmediateNewOnsetAndInvalidBlocksAreIgnored() {
        SimpleOnsetDetector detector = new SimpleOnsetDetector();
        assertFalse(detector.process(null, 100, 1000, -20));
        assertFalse(detector.process(block(0.5f, 100), 101, 1000, -20));
        assertTrue(detector.process(block(0.5f, 100), 100, 1000, -20));
        detector.reset();
        assertTrue(detector.process(block(0.5f, 100), 100, 1000, -20));
    }

    @Test public void thresholdUsesDecibelsAndClampsInvalidValuesSafely() {
        SimpleOnsetDetector detector = new SimpleOnsetDetector();
        assertFalse(detector.process(block(0.05f, 100), 100, 1000, -20));
        assertTrue(detector.process(block(0.2f, 100), 100, 1000, -20));
        detector.reset();
        assertFalse(detector.process(block(0.2f, 100), 100, 1000, -6));
        detector.reset();
        assertTrue(detector.process(block(0.2f, 100), 100, 1000, Double.NaN));
    }
}
