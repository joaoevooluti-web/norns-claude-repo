package org.nornsmobile.app;

/**
 * Java fallback for the norns SimpleDelay engine's Coyote onset detector.
 * It emits one onset on a threshold crossing and enforces the source engine's
 * 200 ms minimum gap. This is intentionally an input detector, not an audio voice.
 */
final class SimpleOnsetDetector {
    private boolean aboveThreshold;
    private long samplesSinceTrigger = Long.MAX_VALUE;

    boolean process(float[] input, int frames, int sampleRate, double thresholdDb) {
        if (input == null || frames <= 0 || frames > input.length || sampleRate <= 0) return false;

        double db = thresholdDb;
        if (Double.isNaN(db) || Double.isInfinite(db)) db = -60.0;
        db = Math.max(-90.0, Math.min(0.0, db));
        double threshold = Math.pow(10.0, db / 20.0);

        double sumSquares = 0.0;
        int validSamples = 0;
        for (int i = 0; i < frames; i++) {
            float sample = input[i];
            if (Float.isNaN(sample) || Float.isInfinite(sample)) continue;
            sumSquares += (double) sample * sample;
            validSamples++;
        }
        double rms = validSamples == 0 ? 0.0 : Math.sqrt(sumSquares / validSamples);

        if (samplesSinceTrigger < Long.MAX_VALUE - frames) samplesSinceTrigger += frames;
        if (aboveThreshold) {
            if (rms < threshold * 0.7) aboveThreshold = false;
            return false;
        }

        long minimumGap = Math.max(1L, sampleRate / 5L);
        if (rms >= threshold && samplesSinceTrigger >= minimumGap) {
            aboveThreshold = true;
            samplesSinceTrigger = 0L;
            return true;
        }
        return false;
    }

    void reset() {
        aboveThreshold = false;
        samplesSinceTrigger = Long.MAX_VALUE;
    }
}
