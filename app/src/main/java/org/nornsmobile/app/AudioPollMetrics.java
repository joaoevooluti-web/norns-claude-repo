package org.nornsmobile.app;

/** Lightweight system poll metrics for the Norns Lua poll module. Input is mono on Android. */
final class AudioPollMetrics {
    private static final int HISTORY_SIZE = 2048;
    private final float[] inputHistory = new float[HISTORY_SIZE];
    private int historyWrite;
    private int historyCount;
    private int sampleRate = 48000;

    private volatile double ampIn;
    private volatile double ampOutLeft;
    private volatile double ampOutRight;
    private volatile double cpuAverage;
    private volatile double cpuPeak;
    private volatile boolean pitchAnalysisEnabled;
    private long peakWindowStartNanos;

    synchronized void update(float[] input, float[] output, int frames,
                             int rate, long renderNanos) {
        if (frames <= 0 || rate <= 0) return;
        sampleRate = rate;

        double inputSum = 0;
        int inputCount = input == null ? 0 : Math.min(frames, input.length);
        for (int i = 0; i < inputCount; i++) {
            float sample = input[i];
            inputSum += sample * sample;
            if (pitchAnalysisEnabled) {
                inputHistory[historyWrite] = sample;
                historyWrite = (historyWrite + 1) % HISTORY_SIZE;
                if (historyCount < HISTORY_SIZE) historyCount++;
            }
        }
        ampIn = inputCount == 0 ? 0 : Math.sqrt(inputSum / inputCount);

        double leftSum = 0;
        double rightSum = 0;
        int outputFrames = output == null ? 0 : Math.min(frames, output.length / 2);
        for (int i = 0; i < outputFrames; i++) {
            double left = output[i * 2];
            double right = output[i * 2 + 1];
            leftSum += left * left;
            rightSum += right * right;
        }
        ampOutLeft = outputFrames == 0 ? 0 : Math.sqrt(leftSum / outputFrames);
        ampOutRight = outputFrames == 0 ? 0 : Math.sqrt(rightSum / outputFrames);

        double blockNanos = frames * 1_000_000_000.0 / rate;
        double load = blockNanos <= 0 ? 0 : Math.max(0, renderNanos) * 100.0 / blockNanos;
        load = Math.min(100.0, load);
        cpuAverage = cpuAverage == 0 ? load : cpuAverage * 0.9 + load * 0.1;
        long now = System.nanoTime();
        if (peakWindowStartNanos == 0 || now - peakWindowStartNanos >= 1_000_000_000L) {
            peakWindowStartNanos = now;
            cpuPeak = load;
        } else if (load > cpuPeak) {
            cpuPeak = load;
        }
    }

    synchronized void setPitchAnalysisEnabled(boolean enabled) {
        if (pitchAnalysisEnabled == enabled) return;
        pitchAnalysisEnabled = enabled;
        historyWrite = 0;
        historyCount = 0;
        java.util.Arrays.fill(inputHistory, 0f);
    }

    double value(String name) {
        if (name == null) return -1;
        switch (name) {
            case "amp_in_l":
            case "amp_in_r": return ampIn;
            case "amp_out_l": return ampOutLeft;
            case "amp_out_r": return ampOutRight;
            case "cpu_avg": return cpuAverage;
            case "cpu_peak": return cpuPeak;
            case "pitch_in_l":
            case "pitch_in_r": return pitchAnalysisEnabled ? estimatePitch() : -1;
            default: return -1;
        }
    }

    private synchronized double estimatePitch() {
        int count = historyCount;
        if (count < 1024) return -1;
        float[] samples = new float[count];
        int start = (historyWrite - count + HISTORY_SIZE) % HISTORY_SIZE;
        double mean = 0;
        for (int i = 0; i < count; i++) {
            float value = inputHistory[(start + i) % HISTORY_SIZE];
            samples[i] = value;
            mean += value;
        }
        mean /= count;

        double energy = 0;
        for (int i = 0; i < count; i++) {
            samples[i] -= (float) mean;
            energy += samples[i] * samples[i];
        }
        if (energy < 1.0e-7) return -1;

        int minLag = Math.max(2, sampleRate / 1000);
        int maxLag = Math.min(count / 2, sampleRate / 50);
        if (maxLag <= minLag) return -1;
        int bestLag = -1;
        double bestCorrelation = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double cross = 0;
            double energyA = 0;
            double energyB = 0;
            int length = count - lag;
            for (int i = 0; i < length; i++) {
                double a = samples[i];
                double b = samples[i + lag];
                cross += a * b;
                energyA += a * a;
                energyB += b * b;
            }
            double denominator = Math.sqrt(energyA * energyB);
            double correlation = denominator <= 1.0e-12 ? 0 : cross / denominator;
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestLag = lag;
            }
        }
        return bestLag > 0 && bestCorrelation >= 0.25
                ? sampleRate / (double) bestLag : -1;
    }
}
