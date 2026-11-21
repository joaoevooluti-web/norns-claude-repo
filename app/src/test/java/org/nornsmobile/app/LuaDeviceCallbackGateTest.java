package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LuaDeviceCallbackGateTest {
    @Test public void staleQueuedEventCannotEnterReplacementSession() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session old = gate.beginIfAbsent(42);
        assertSame(old, gate.beginIfAbsent(42));
        assertSame(old, gate.beginClose(42));
        LuaDeviceCallbackGate.Session replacement = gate.beginIfAbsent(42);
        assertNotEquals(old.token, replacement.token);
        assertFalse(gate.accepts(42, old.token, false));
        assertTrue(gate.accepts(42, replacement.token, false));
        gate.finishClose(42, old.token);
        assertTrue(gate.accepts(42, replacement.token, false));
    }

    @Test public void queuedReleaseIsAllowedDuringCloseButOrdinaryEventIsRejected() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session session = gate.beginIfAbsent(9);
        assertSame(session, gate.beginClose(9));
        assertFalse(gate.accepts(9, session.token, false));
        assertTrue(gate.accepts(9, session.token, true));
        gate.finishClose(9, session.token);
        assertFalse(gate.accepts(9, session.token, true));
    }

    @Test public void closeWithoutSessionAndWrongDeviceTokenAreRejected() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        assertNull(gate.beginClose(10));
        LuaDeviceCallbackGate.Session session = gate.beginIfAbsent(10);
        assertFalse(gate.accepts(11, session.token, true));
        gate.clear();
        assertFalse(gate.accepts(10, session.token, false));
    }

    @Test public void replacementDoesNotEraseOldPendingReleaseBeforeItsRemovalRuns() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session old = gate.beginIfAbsent(5);
        gate.beginClose(5);
        LuaDeviceCallbackGate.Session current = gate.beginIfAbsent(5);
        assertTrue(gate.accepts(5, old.token, true));
        assertTrue(gate.accepts(5, current.token, false));
        gate.finishClose(5, old.token);
        assertFalse(gate.accepts(5, old.token, true));
        assertTrue(gate.accepts(5, current.token, false));
    }
    @Test public void oldUsbDisconnectCannotRemoveReplacementSession() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session old = gate.begin(77, 1001L);
        assertNotNull(old);
        assertSame(old, gate.beginClose(77, 1001L));
        LuaDeviceCallbackGate.Session replacement = gate.begin(77, 1002L);
        assertNotNull(replacement);
        assertFalse(gate.accepts(77, 1001L, false));
        assertFalse("old terminal callback must not disconnect the replacement",
                gate.acceptsTerminal(77, 1001L));
        assertTrue(gate.accepts(77, 1002L, false));
        gate.finishClose(77, 1001L);
        assertTrue(gate.accepts(77, 1002L, false));
    }

    @Test public void staleUsbDataTokenIsRejectedAfterNewConnectionIsAnnounced() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        gate.begin(8, 21L);
        gate.beginClose(8, 21L);
        gate.begin(8, 22L);
        assertFalse(gate.accepts(8, 21L, false));
        assertTrue(gate.accepts(8, 22L, false));
    }

    @Test public void terminalDisconnectIsDeliveredWhenNoReplacementExists() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session session = gate.begin(3, 300L);
        assertNotNull(session);
        assertNotNull(gate.beginClose(3, 300L));
        assertTrue(gate.acceptsTerminal(3, 300L));
        gate.finishClose(3, 300L);
        assertFalse(gate.acceptsTerminal(3, 300L));
    }

    @Test public void HIDAnnouncementIsIdempotentWithinOneConnection() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session session = gate.beginIfAbsent(12);
        assertTrue(gate.markAnnounced(session));
        assertFalse(gate.markAnnounced(session));
        assertSame(session, gate.beginIfAbsent(12));
        assertFalse(gate.markAnnounced(gate.current(12)));
    }

    @Test public void oneTransportTokenCannotBeClaimedByAnotherDeviceId() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        assertNotNull(gate.begin(4, 900L));
        assertNull("a token identifies exactly one connection and device",
                gate.begin(5, 900L));
        assertTrue(gate.accepts(4, 900L, false));
        assertFalse(gate.accepts(5, 900L, false));
    }

    @Test public void explicitCloseInvalidatesQueuedDataBeforeTransportCleanup() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session session = gate.begin(66, 6600L);
        assertNotNull(session);
        assertFalse(gate.accepts(66, 6600L, false) == false);
        assertNotNull(gate.beginClose(66, 6600L));
        assertFalse("queued data is dropped as soon as Lua requests close",
                gate.accepts(66, 6600L, false));
        assertTrue("only release callbacks may drain while closing",
                gate.accepts(66, 6600L, true));
        gate.finishClose(66, 6600L);
        assertFalse(gate.accepts(66, 6600L, true));
    }

    @Test public void sameDeviceIdWithSimilarButDifferentDescriptorCreatesReplacementSession() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        String oldDescriptor = "usb-vendor-1234-product-5678-interface-00";
        String replacementDescriptor = "usb-vendor-1234-product-5678-interface-01";
        LuaDeviceCallbackGate.Session old = gate.beginIfAbsent(42, oldDescriptor);
        assertTrue(gate.markAnnounced(old));
        assertTrue(gate.matchesIdentity(42, old.token, oldDescriptor));
        assertFalse("same model/interface sibling must not inherit the old session",
                gate.matchesIdentity(42, old.token, replacementDescriptor));
        LuaDeviceCallbackGate.Session replacement = gate.beginIfAbsent(42, replacementDescriptor);
        assertNotEquals(old.token, replacement.token);
        assertFalse(gate.accepts(42, old.token, false));
        assertTrue(gate.acceptsOrderedTerminal(42, old.token));
        assertTrue(gate.accepts(42, replacement.token, false));
        gate.finishClose(42, old.token);
        assertTrue(gate.accepts(42, replacement.token, false));
    }

    @Test public void callbackCapturedForOldDescriptorCannotEnterReplacementSession() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session captured = gate.beginIfAbsent(15, "descriptor-old");
        long capturedToken = captured.token;
        gate.beginClose(15);
        LuaDeviceCallbackGate.Session replacement = gate.beginIfAbsent(15, "descriptor-new");
        assertTrue(gate.matchesIdentity(15, capturedToken, "descriptor-old"));
        assertFalse(gate.matchesIdentity(15, replacement.token, "descriptor-old"));
        assertFalse(gate.accepts(15, capturedToken, false));
        assertTrue(gate.matchesIdentity(15, replacement.token, "descriptor-new"));
        assertTrue(gate.accepts(15, replacement.token, false));
    }
    @Test public void delayedOldIdCallbacksCannotEnterReplacementAfterDescriptorReuse() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session old = gate.beginIfAbsent(77, "usb:keyboard-A");
        assertTrue(gate.matchesIdentity(77, old.token, "usb:keyboard-A"));
        assertSame(old, gate.beginClose(77));
        LuaDeviceCallbackGate.Session replacement = gate.beginIfAbsent(77, "usb:gamepad-B");
        assertFalse(gate.accepts(77, old.token, false));
        assertFalse(gate.matchesIdentity(77, old.token, "usb:gamepad-B"));
        assertTrue(gate.matchesIdentity(77, replacement.token, "usb:gamepad-B"));
        assertTrue(gate.accepts(77, replacement.token, false));
        gate.finishClose(77, old.token);
        assertTrue(gate.accepts(77, replacement.token, false));
    }

    @Test public void keyboardGamepadAndMouseCallbacksQueuedBeforeVmRestartAreRejected() {
        LuaDeviceCallbackGate gate = new LuaDeviceCallbackGate();
        LuaDeviceCallbackGate.Session beforeStop = gate.beginIfAbsent(88, "input-device");
        long keyboardToken = beforeStop.token;
        long gamepadToken = beforeStop.token;
        long mouseToken = beforeStop.token;
        assertSame(beforeStop, gate.beginClose(88));
        gate.clear(); // LuaHost.stopInternal invalidates every queued session.
        LuaDeviceCallbackGate.Session afterRestart = gate.beginIfAbsent(88, "input-device");
        assertNotEquals(beforeStop.token, afterRestart.token);
        assertFalse(gate.accepts(88, keyboardToken, false));
        assertFalse(gate.accepts(88, gamepadToken, false));
        assertFalse(gate.accepts(88, mouseToken, false));
        assertTrue(gate.accepts(88, afterRestart.token, false));
    }

}
