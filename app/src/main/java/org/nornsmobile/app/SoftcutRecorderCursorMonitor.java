package org.nornsmobile.app;

/** Measures the two native BufWr cursors independently from the six playback phase witnesses. */
final class SoftcutRecorderCursorMonitor {
    static final class Snapshot {
        final boolean valid;
        final boolean recording;
        final boolean recoveryRequired;
        final boolean reanchorAllowed;
        final long samples;
        final double lastErrorSeconds;
        final double maxAbsErrorSeconds;
        final double rmsErrorSeconds;
        final double thresholdSeconds;

        Snapshot(boolean valid, boolean recording, boolean recoveryRequired,
                 boolean reanchorAllowed, long samples, double lastErrorSeconds,
                 double maxAbsErrorSeconds, double rmsErrorSeconds, double thresholdSeconds) {
            this.valid = valid;
            this.recording = recording;
            this.recoveryRequired = recoveryRequired;
            this.reanchorAllowed = reanchorAllowed;
            this.samples = samples;
            this.lastErrorSeconds = lastErrorSeconds;
            this.maxAbsErrorSeconds = maxAbsErrorSeconds;
            this.rmsErrorSeconds = rmsErrorSeconds;
            this.thresholdSeconds = thresholdSeconds;
        }
    }

    private long samples;
    private double lastErrorSeconds;
    private double maxAbsErrorSeconds;
    private double sumSquaredErrorSeconds;

    synchronized Snapshot record(double javaPosition, double nativePosition,
                                 double loopStart, double loopEnd, boolean looping,
                                 boolean recording, int sampleRate, int blockFrames) {
        double threshold = SoftcutPhaseSupervisor.reanchorThresholdSeconds(sampleRate, blockFrames);
        double error = SoftcutPhaseDiagnostics.signedErrorSeconds(
                javaPosition, nativePosition, loopStart, loopEnd, looping);
        if (!finite(error) || !finite(threshold)) {
            return snapshot(false, recording, false, false, threshold);
        }
        samples++;
        lastErrorSeconds = error;
        maxAbsErrorSeconds = Math.max(maxAbsErrorSeconds, Math.abs(error));
        sumSquaredErrorSeconds += error * error;
        boolean required = Math.abs(error) > threshold;
        return snapshot(true, recording, required, required && !recording, threshold);
    }

    synchronized Snapshot snapshot() {
        return snapshot(false, false, false, false, Double.POSITIVE_INFINITY);
    }

    synchronized void reset() {
        samples = 0;
        lastErrorSeconds = 0;
        maxAbsErrorSeconds = 0;
        sumSquaredErrorSeconds = 0;
    }

    private Snapshot snapshot(boolean valid, boolean recording, boolean required,
                              boolean allowed, double threshold) {
        double rms = samples == 0 ? 0 : Math.sqrt(sumSquaredErrorSeconds / samples);
        return new Snapshot(valid, recording, required, allowed, samples,
                lastErrorSeconds, maxAbsErrorSeconds, rms, threshold);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
