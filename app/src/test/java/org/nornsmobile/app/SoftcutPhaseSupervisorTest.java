package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SoftcutPhaseSupervisorTest {
    @Test public void advancingCallbackSequenceStaysFresh() {
        SoftcutPhaseSupervisor supervisor = new SoftcutPhaseSupervisor();
        assertTrue(supervisor.observe(10, 1_000_000_000L).fresh);
        SoftcutPhaseSupervisor.SnapshotStatus next = supervisor.observe(11, 1_040_000_000L);
        assertTrue(next.fresh);
        assertEquals(0.0, next.ageSeconds(), 0.0);
    }

    @Test public void unchangedSequenceEventuallyBecomesStale() {
        SoftcutPhaseSupervisor supervisor = new SoftcutPhaseSupervisor();
        assertTrue(supervisor.observe(42, 1_000_000_000L).fresh);
        assertTrue(supervisor.observe(42, 1_100_000_000L).fresh);
        SoftcutPhaseSupervisor.SnapshotStatus stale = supervisor.observe(42, 1_181_000_000L);
        assertFalse(stale.fresh);
        assertEquals(0.181, stale.ageSeconds(), 1e-9);
    }

    @Test public void invalidSequenceIsNeverFresh() {
        SoftcutPhaseSupervisor supervisor = new SoftcutPhaseSupervisor();
        assertFalse(supervisor.observe(Float.NaN, 1_000_000_000L).fresh);
        assertFalse(supervisor.observe(-1, 1_100_000_000L).fresh);
    }

    @Test public void resetStartsANewFreshnessEpoch() {
        SoftcutPhaseSupervisor supervisor = new SoftcutPhaseSupervisor();
        assertTrue(supervisor.observe(7, 1_000_000_000L).fresh);
        supervisor.reset();
        assertTrue(supervisor.observe(7, 1_200_000_000L).fresh);
    }

    @Test public void thresholdUsesOneScBlockOrTwoFrames() {
        assertEquals(64.0 / 48000.0,
                SoftcutPhaseSupervisor.reanchorThresholdSeconds(48000, 64), 1e-12);
        assertEquals(2.0 / 48000.0,
                SoftcutPhaseSupervisor.reanchorThresholdSeconds(48000, 1), 1e-12);
    }

    @Test public void reanchorRequiresFiniteErrorBeyondThreshold() {
        assertFalse(SoftcutPhaseSupervisor.shouldReanchor(0.001, 48000, 64));
        assertTrue(SoftcutPhaseSupervisor.shouldReanchor(0.002, 48000, 64));
        assertTrue(SoftcutPhaseSupervisor.shouldReanchor(-0.002, 48000, 64));
        assertFalse(SoftcutPhaseSupervisor.shouldReanchor(Double.NaN, 48000, 64));
        assertFalse(SoftcutPhaseSupervisor.shouldReanchor(1, 0, 64));
    }
}
