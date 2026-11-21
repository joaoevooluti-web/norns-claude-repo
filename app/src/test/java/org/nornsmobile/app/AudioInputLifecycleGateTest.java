package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioInputLifecycleGateTest {
    @Test public void activeSessionCanFinishNormally() {
        AudioInputLifecycleGate gate = new AudioInputLifecycleGate();
        long session = gate.begin();
        assertTrue(gate.isActive());
        assertTrue(gate.isActive(session));
        assertTrue(gate.finish(session));
        assertFalse(gate.isActive());
        assertFalse(gate.isActive(session));
        assertTrue(gate.isCurrent(session));
    }

    @Test public void stopInvalidatesLateReadAndFinish() {
        AudioInputLifecycleGate gate = new AudioInputLifecycleGate();
        long session = gate.begin();
        gate.stop();
        assertFalse(gate.isActive(session));
        assertFalse(gate.finish(session));
        assertFalse(gate.isCurrent(session));
    }

    @Test public void oldSessionCannotStopReplacement() {
        AudioInputLifecycleGate gate = new AudioInputLifecycleGate();
        long oldSession = gate.begin();
        gate.stop();
        long replacement = gate.begin();
        assertFalse(gate.isActive(oldSession));
        assertFalse(gate.finish(oldSession));
        assertTrue(gate.isActive());
        assertTrue(gate.isActive(replacement));
        assertTrue(gate.isCurrent(replacement));
    }

    @Test public void finishingOldSessionDoesNotChangeCurrentGeneration() {
        AudioInputLifecycleGate gate = new AudioInputLifecycleGate();
        long oldSession = gate.begin();
        long replacement = gate.begin();
        assertFalse(gate.finish(oldSession));
        assertTrue(gate.isActive(replacement));
    }
}
