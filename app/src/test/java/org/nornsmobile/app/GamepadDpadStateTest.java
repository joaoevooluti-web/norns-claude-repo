package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GamepadDpadStateTest {
    @Test public void releasingOneDirectionKeepsOppositeDirectionPressed() {
        GamepadDpadState state = new GamepadDpadState();
        assertEquals(-1f, state.update(4, "dpadx", -1, true), 0f);
        assertEquals(0f, state.update(4, "dpadx", 1, true), 0f);
        assertEquals(1f, state.update(4, "dpadx", -1, false), 0f);
        assertEquals(0f, state.update(4, "dpadx", 1, false), 0f);
    }

    @Test public void repeatedKeyDownAndUpAreIdempotent() {
        GamepadDpadState state = new GamepadDpadState();
        assertEquals(1f, state.update(2, "dpady", 1, true), 0f);
        assertEquals(1f, state.update(2, "dpady", 1, true), 0f);
        assertEquals(0f, state.update(2, "dpady", 1, false), 0f);
        assertEquals(0f, state.update(2, "dpady", 1, false), 0f);
    }

    @Test public void devicesKeepIndependentDpadStateAndDisconnectClearsOnlyOne() {
        GamepadDpadState state = new GamepadDpadState();
        assertEquals(-1f, state.update(1, "dpadx", -1, true), 0f);
        assertEquals(1f, state.update(2, "dpadx", 1, true), 0f);
        state.removeDevice(1);
        assertEquals(0f, state.update(1, "dpadx", -1, false), 0f);
        assertEquals(1f, state.update(2, "dpadx", 1, true), 0f);
        assertEquals(0f, state.update(2, "dpadx", 1, false), 0f);
    }

    @Test public void clearReleasesEveryDeviceAndAxis() {
        GamepadDpadState state = new GamepadDpadState();
        assertEquals(-1f, state.update(1, "dpadx", -1, true), 0f);
        assertEquals(1f, state.update(2, "dpady", 1, true), 0f);
        state.clear();
        assertEquals(0f, state.update(1, "dpadx", -1, false), 0f);
        assertEquals(0f, state.update(2, "dpady", 1, false), 0f);
        assertEquals(1f, state.update(2, "dpady", 1, true), 0f);
    }

    @Test public void invalidUpdatesDoNotCreateDirectionalState() {
        GamepadDpadState state = new GamepadDpadState();
        assertEquals(0f, state.update(1, "dpadx", 0, true), 0f);
        assertEquals(0f, state.update(1, null, -1, true), 0f);
        assertEquals(0f, state.update(1, "dpadx", -1, false), 0f);
    }
}
