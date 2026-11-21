package org.nornsmobile.app;

/** Detects stale Oboe snapshots and bounds corrections of the silent phase witness. */
final class SoftcutPhaseSupervisor {
    static final long STALE_AFTER_NANOS = 180_000_000L;

    static final class SnapshotStatus {
        final boolean fresh;
        final long sequence;
        final long ageNanos;
        SnapshotStatus(boolean fresh, long sequence, long ageNanos) {
            this.fresh = fresh;
            this.sequence = sequence;
            this.ageNanos = ageNanos;
        }
        double ageSeconds() {
            return ageNanos == Long.MAX_VALUE ? Double.POSITIVE_INFINITY
                    : ageNanos / 1_000_000_000.0;
        }
    }

    private boolean initialized;
    private long lastSequence;
    private long lastAdvanceNanos;

    synchronized SnapshotStatus observe(float sequenceValue, long nowNanos) {
        if (Float.isNaN(sequenceValue) || Float.isInfinite(sequenceValue)
                || sequenceValue < 0 || sequenceValue >= 1_000_000.0f) {
            return new SnapshotStatus(false, lastSequence, Long.MAX_VALUE);
        }
        long sequence = Math.round(sequenceValue);
        if (!initialized || sequence != lastSequence) {
            initialized = true;
            lastSequence = sequence;
            lastAdvanceNanos = nowNanos;
        }
        long age = Math.max(0L, nowNanos - lastAdvanceNanos);
        return new SnapshotStatus(age <= STALE_AFTER_NANOS, lastSequence, age);
    }

    synchronized void reset() {
        initialized = false;
        lastSequence = 0;
        lastAdvanceNanos = 0;
    }

    static double reanchorThresholdSeconds(int sampleRate, int blockFrames) {
        if (sampleRate <= 0 || blockFrames <= 0) return Double.POSITIVE_INFINITY;
        return Math.max(2.0 / sampleRate, blockFrames / (double) sampleRate);
    }

    static boolean shouldReanchor(double signedErrorSeconds, int sampleRate, int blockFrames) {
        if (Double.isNaN(signedErrorSeconds) || Double.isInfinite(signedErrorSeconds)) return false;
        return Math.abs(signedErrorSeconds) > reanchorThresholdSeconds(sampleRate, blockFrames);
    }
}
