package org.nornsmobile.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class KeyboardDeviceStateTest {
    @Test public void sameDeviceRepeatsAreForwardedButSecondKeyboardDoesNotDuplicatePress() {
        KeyboardDeviceState state = new KeyboardDeviceState();
        assertTrue(state.keyDown("A", 10));
        assertTrue(state.keyDown("A", 10));
        assertFalse(state.keyDown("A", 11));
        assertTrue(state.isPressed("A"));
    }

    @Test public void logicalKeyIsReleasedOnlyAfterLastKeyboardKeyUp() {
        KeyboardDeviceState state = new KeyboardDeviceState();
        assertTrue(state.keyDown("SPACE", 1));
        assertFalse(state.keyDown("SPACE", 2));
        assertFalse(state.keyUp("SPACE", 1));
        assertTrue(state.isPressed("SPACE"));
        assertTrue(state.keyUp("SPACE", 2));
        assertFalse(state.isPressed("SPACE"));
        assertFalse(state.keyUp("SPACE", 2));
    }

    @Test public void disconnectReleasesRemovedDeviceHidKeyWithoutReleasingOtherOwner() {
        KeyboardDeviceState state = new KeyboardDeviceState();
        state.keyDown("ENTER", 3);
        state.keyDown("ENTER", 4);

        KeyboardDeviceState.Release release = state.removeDevice(3);

        assertEquals(Collections.emptySet(), release.logicalKeys);
        assertEquals(Collections.singleton("ENTER"), release.keysByDevice.get(3));
        assertTrue(state.isPressed("ENTER"));
        assertTrue(state.keyUp("ENTER", 4));
    }

    @Test public void disconnectLastOwnerReleasesBothHidAndLogicalKey() {
        KeyboardDeviceState state = new KeyboardDeviceState();
        state.keyDown("LEFTSHIFT", 8);

        KeyboardDeviceState.Release release = state.removeDevice(8);

        assertEquals(Collections.singleton("LEFTSHIFT"), release.logicalKeys);
        assertEquals(Collections.singleton("LEFTSHIFT"), release.keysByDevice.get(8));
        assertFalse(state.isPressed("LEFTSHIFT"));
    }

    @Test public void focusLossReturnsEveryLogicalKeyAndEachDevicesHidKeys() {
        KeyboardDeviceState state = new KeyboardDeviceState();
        state.keyDown("A", 1);
        state.keyDown("B", 1);
        state.keyDown("A", 2);
        state.keyDown("ENTER", 2);

        KeyboardDeviceState.Release release = state.releaseAll();

        assertEquals(new java.util.TreeSet<String>(Arrays.asList("A", "B", "ENTER")),
                release.logicalKeys);
        assertEquals(new java.util.TreeSet<String>(Arrays.asList("A", "B")),
                release.keysByDevice.get(1));
        assertEquals(new java.util.TreeSet<String>(Arrays.asList("A", "ENTER")),
                release.keysByDevice.get(2));
        assertFalse(state.isPressed("A"));
        assertFalse(state.isPressed("B"));
        assertFalse(state.isPressed("ENTER"));
        assertTrue(state.releaseAll().logicalKeys.isEmpty());
    }

    @Test public void invalidKeyDoesNotCreateState() {
        KeyboardDeviceState state = new KeyboardDeviceState();
        assertFalse(state.keyDown(null, 1));
        assertFalse(state.keyDown("", 1));
        assertFalse(state.keyUp(null, 1));
        assertFalse(state.isPressed(null));
        assertTrue(state.releaseAll().logicalKeys.isEmpty());
    }
}
