package org.nornsmobile.app;

/** Applies the user DAC gain to the Java output after the record tap. */
final class AudioOutputGain {
    private AudioOutputGain() { }

    static void apply(float[] samples, double level) {
        if (samples == null || Double.isNaN(level) || Double.isInfinite(level)) return;
        float gain = (float) Math.max(0.0, Math.min(1.0, level));
        for (int i = 0; i < samples.length; i++) {
            float value = samples[i] * gain;
            if (value > 1f) value = 1f;
            else if (value < -1f) value = -1f;
            samples[i] = value;
        }
    }
}
