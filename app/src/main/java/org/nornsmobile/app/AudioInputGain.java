package org.nornsmobile.app;

/** Applies upstream audio.level_adc gain to captured mono ADC frames before onset detection and audio routing. */
final class AudioInputGain {
    private AudioInputGain() {}

    static void apply(float[] input, double level) {
        if (input == null || !Double.isFinite(level)) return;
        float gain = (float) Math.max(0.0, Math.min(1.0, level));
        if (gain == 1.0f) return;
        for (int i = 0; i < input.length; i++) input[i] *= gain;
    }
}
