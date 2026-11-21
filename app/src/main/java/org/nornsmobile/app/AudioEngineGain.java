package org.nornsmobile.app;

/** Applies the upstream audio.level_eng gain to Java SynthCore output before routing to Tape/Softcut. */
final class AudioEngineGain {
    private AudioEngineGain() { }

    static void apply(float[] samples, double level) {
        if (samples == null || !Double.isFinite(level)) return;
        float gain = (float)Math.max(0.0, Math.min(1.0, level));
        if (gain == 1.0f) return;
        for (int i = 0; i < samples.length; i++) samples[i] *= gain;
    }
}
