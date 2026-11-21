package org.nornsmobile.app;

/**
 * Norns-compatible peak meter. Mirrors crone's IEC-60268 taper lookup and
 * 300 ms / -60 dB release, with instantaneous attack. Android currently has
 * one ADC channel, so the input meter is mirrored to both logical inputs.
 */
final class AudioVuMeter {
    private static final float[] AMP_POSITION = {
            0.0f, 0.3018581858f, 0.3962114306f, 0.4905646755f,
            0.5335667553f, 0.5708631501f, 0.6081595449f, 0.6454559397f,
            0.6827523345f, 0.7200487293f, 0.7523227322f, 0.7641168878f,
            0.7759110434f, 0.7877051990f, 0.7994993546f, 0.8112935102f,
            0.8230876659f, 0.8348818215f, 0.8466759771f, 0.8584701327f,
            0.8702642883f, 0.8820584439f, 0.8938525995f, 0.9056467551f,
            0.9174409107f, 0.9292350663f, 0.9410292220f, 0.9528233776f,
            0.9646175332f, 0.9764116888f, 0.9882058444f, 1.0f
    };
    private static final double RELEASE_DB = 6.9078;
    private static final double RELEASE_SECONDS = 0.3;

    private float inputPeak;
    private float outputLeftPeak;
    private float outputRightPeak;
    private int releaseFrames = -1;
    private int releaseRate = -1;
    private double releasePole = 0.0;

    void update(float[] monoInput, float[] stereoOutput, int frames, int sampleRate) {
        if (frames <= 0 || sampleRate <= 0) return;
        float input = peak(monoInput, frames, 1, 0);
        float left = peak(stereoOutput, frames, 2, 0);
        float right = peak(stereoOutput, frames, 2, 1);
        if (frames != releaseFrames || sampleRate != releaseRate) {
            double blockSeconds = frames / (double) sampleRate;
            releasePole = Math.exp(-RELEASE_DB * blockSeconds / RELEASE_SECONDS);
            releaseFrames = frames;
            releaseRate = sampleRate;
        }
        inputPeak = slew(inputPeak, input, releasePole);
        outputLeftPeak = slew(outputLeftPeak, left, releasePole);
        outputRightPeak = slew(outputRightPeak, right, releasePole);
    }

    void reset() {
        inputPeak = 0f;
        outputLeftPeak = 0f;
        outputRightPeak = 0f;
    }

    int inputLeft() { return toVu(inputPeak); }
    int inputRight() { return toVu(inputPeak); }
    int outputLeft() { return toVu(outputLeftPeak); }
    int outputRight() { return toVu(outputRightPeak); }

    private static float peak(float[] samples, int frames, int stride, int channel) {
        if (samples == null) return 0f;
        int count = Math.min(frames, Math.max(0, (samples.length - channel + stride - 1) / stride));
        float max = 0f;
        for (int i = 0; i < count; i++) {
            float sample = samples[i * stride + channel];
            if (!Float.isFinite(sample)) continue;
            float magnitude = Math.abs(sample);
            if (magnitude > max) max = magnitude;
        }
        return max;
    }

    private static float slew(float previous, float target, double releasePole) {
        // crone's PeakMeter has zero attack time and a 300 ms release.
        return target >= previous ? target
                : (float) (target + (previous - target) * releasePole);
    }

    private static int toVu(float amplitude) {
        if (!Float.isFinite(amplitude) || amplitude <= 0f) return 0;
        if (amplitude >= 1f) return 64;
        float position = amplitude * (AMP_POSITION.length - 1);
        int index = (int) position;
        float fraction = position - index;
        float tapered = AMP_POSITION[index]
                + (AMP_POSITION[index + 1] - AMP_POSITION[index]) * fraction;
        // crone sends uint8_t(64 * IEC-60268 position) to the Lua callback.
        return Math.max(0, Math.min(64, (int) (64f * tapered)));
    }
}
