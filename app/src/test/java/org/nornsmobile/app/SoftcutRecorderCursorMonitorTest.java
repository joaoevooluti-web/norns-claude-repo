package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SoftcutRecorderCursorMonitorTest {
    private static final int RATE = 48000;
    private static final int BLOCK = 64;
    private static final double START = 0.0;
    private static final double END = 8.0;

    @Test public void tracksContinuousRecordingAtPositiveRate() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        SoftcutRecorderCursorMonitor.Snapshot a =
                monitor.record(1.000, 1.0005, START, END, true, true, RATE, BLOCK);
        SoftcutRecorderCursorMonitor.Snapshot b =
                monitor.record(1.033, 1.0335, START, END, true, true, RATE, BLOCK);
        SoftcutRecorderCursorMonitor.Snapshot c =
                monitor.record(1.066, 1.0665, START, END, true, true, RATE, BLOCK);
        assertTrue(a.valid && b.valid && c.valid);
        assertFalse(c.recoveryRequired);
        assertFalse(c.reanchorAllowed);
        assertEquals(3, c.samples);
        assertEquals(0.0005, c.lastErrorSeconds, 1e-9);
    }

    @Test public void tracksContinuousRecordingAtNegativeRateAndLoopWrap() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        monitor.record(0.020, 0.0205, START, END, true, true, RATE, BLOCK);
        SoftcutRecorderCursorMonitor.Snapshot wrapped =
                monitor.record(7.990, 7.9905, START, END, true, true, RATE, BLOCK);
        assertTrue(wrapped.valid);
        assertFalse(wrapped.recoveryRequired);
        assertEquals(2, wrapped.samples);
    }

    @Test public void driftDuringRecordingRequestsRecoveryButNeverReanchors() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        SoftcutRecorderCursorMonitor.Snapshot sample =
                monitor.record(2.0, 2.01, START, END, true, true, RATE, BLOCK);
        assertTrue(sample.recoveryRequired);
        assertFalse(sample.reanchorAllowed);
        assertTrue(sample.recording);
        assertTrue(sample.maxAbsErrorSeconds > sample.thresholdSeconds);
    }

    @Test public void confirmedIdleAllowsCursorRecovery() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        SoftcutRecorderCursorMonitor.Snapshot sample =
                monitor.record(2.0, 2.01, START, END, true, false, RATE, BLOCK);
        assertTrue(sample.recoveryRequired);
        assertTrue(sample.reanchorAllowed);
        assertFalse(sample.recording);
    }

    @Test public void recoveryClearsAfterTheCursorConverges() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        assertTrue(monitor.record(2.0, 2.01, START, END, true, false, RATE, BLOCK).recoveryRequired);
        SoftcutRecorderCursorMonitor.Snapshot recovered =
                monitor.record(2.02, 2.0202, START, END, true, false, RATE, BLOCK);
        assertFalse(recovered.recoveryRequired);
        assertFalse(recovered.reanchorAllowed);
        assertTrue(recovered.maxAbsErrorSeconds > Math.abs(recovered.lastErrorSeconds));
    }

    @Test public void writeCursorComparisonIncludesRecOffset() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        double javaHeadSeconds = 3.0;
        double recOffsetSeconds = -0.00015;
        double javaWriteCursor = javaHeadSeconds + recOffsetSeconds;
        SoftcutRecorderCursorMonitor.Snapshot sample =
                monitor.record(javaWriteCursor, javaWriteCursor, 2.0, 4.0,
                        true, true, RATE, BLOCK);
        assertTrue(sample.valid);
        assertEquals(0.0, sample.lastErrorSeconds, 1e-12);
        assertFalse(sample.recoveryRequired);
        assertFalse(sample.reanchorAllowed);
    }

    @Test public void invalidPositionsNeverAuthorizeRecovery() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        SoftcutRecorderCursorMonitor.Snapshot invalid =
                monitor.record(Double.NaN, 1, START, END, true, false, RATE, BLOCK);
        assertFalse(invalid.valid);
        assertFalse(invalid.reanchorAllowed);
        assertEquals(0, invalid.samples);
    }

    @Test public void resetClearsAccumulatedRecordingStatistics() {
        SoftcutRecorderCursorMonitor monitor = new SoftcutRecorderCursorMonitor();
        monitor.record(1, 1.01, START, END, true, true, RATE, BLOCK);
        monitor.reset();
        SoftcutRecorderCursorMonitor.Snapshot reset = monitor.snapshot();
        assertEquals(0, reset.samples);
        assertEquals(0.0, reset.maxAbsErrorSeconds, 0.0);
    }
}
