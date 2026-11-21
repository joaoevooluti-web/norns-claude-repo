package org.nornsmobile.app;

/** Measures native-vs-Java Softcut phase error without changing either engine state. */
final class SoftcutPhaseDiagnostics {
    static final class Snapshot {
        final long samples;
        final double lastErrorSeconds;
        final double maxAbsErrorSeconds;
        final double rmsErrorSeconds;
        Snapshot(long samples, double lastErrorSeconds, double maxAbsErrorSeconds,
                 double rmsErrorSeconds) {
            this.samples=samples; this.lastErrorSeconds=lastErrorSeconds;
            this.maxAbsErrorSeconds=maxAbsErrorSeconds; this.rmsErrorSeconds=rmsErrorSeconds;
        }
    }
    private long samples;
    private double lastErrorSeconds, maxAbsErrorSeconds, sumSquaredErrorSeconds;

    static double signedErrorSeconds(double javaPosition, double nativePosition,
                                     double loopStart, double loopEnd, boolean looping) {
        if (!finite(javaPosition) || !finite(nativePosition)) return Double.NaN;
        double error=nativePosition-javaPosition;
        double length=loopEnd-loopStart;
        if (looping && finite(loopStart) && finite(loopEnd) && length>0)
            error=Math.IEEEremainder(error,length);
        return error;
    }
    synchronized Snapshot record(double javaPosition, double nativePosition,
                                 double loopStart, double loopEnd, boolean looping) {
        double error=signedErrorSeconds(javaPosition,nativePosition,loopStart,loopEnd,looping);
        if (finite(error)) {
            samples++; lastErrorSeconds=error;
            maxAbsErrorSeconds=Math.max(maxAbsErrorSeconds,Math.abs(error));
            sumSquaredErrorSeconds+=error*error;
        }
        return snapshotLocked();
    }
    synchronized Snapshot snapshot() { return snapshotLocked(); }
    synchronized void reset() {
        samples=0; lastErrorSeconds=0; maxAbsErrorSeconds=0; sumSquaredErrorSeconds=0;
    }
    private Snapshot snapshotLocked() {
        return new Snapshot(samples,lastErrorSeconds,maxAbsErrorSeconds,
                samples==0?0:Math.sqrt(sumSquaredErrorSeconds/samples));
    }
    private static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }
}
