package org.nornsmobile.app;

/** Mixes Android's mono ADC monitor signal into the Java stereo master output. */
final class AudioMonitorMix {
    private AudioMonitorMix() {}

    static void apply(float[] monoInput, float[] stereoOutput, double level) {
        if (monoInput == null || stereoOutput == null || !Double.isFinite(level)
                || level <= 0.0) return;
        int frames = Math.min(monoInput.length, stereoOutput.length / 2);
        float gain = (float) Math.min(1.0, level);
        for (int i = 0; i < frames; i++) {
            float sample = monoInput[i] * gain;
            stereoOutput[i * 2] += sample;
            stereoOutput[i * 2 + 1] += sample;
        }
    }
}
