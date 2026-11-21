package org.nornsmobile.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MidiPortIdRegistryTest {
    @Test public void eachPhysicalPortGetsItsOwnStableLogicalId() {
        MidiPortIdRegistry ids = new MidiPortIdRegistry();
        int first = ids.idFor(12, 0);
        int second = ids.idFor(12, 1);
        int third = ids.idFor(12, 2);
        assertTrue(first > 0);
        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertEquals(first, ids.idFor(12, 0));
        assertEquals(12, ids.sessionForPort(second));
        assertEquals(Arrays.asList(first, second, third), ids.idsForSession(12));
    }

    @Test public void portsOfDifferentDevicesNeverCollide() {
        MidiPortIdRegistry ids = new MidiPortIdRegistry();
        int a = ids.idFor(1, 0);
        int b = ids.idFor(2, 0);
        assertNotEquals(a, b);
        assertEquals(1, ids.sessionForPort(a));
        assertEquals(2, ids.sessionForPort(b));
    }

    @Test public void removingDeviceRetiresOnlyItsPorts() {
        MidiPortIdRegistry ids = new MidiPortIdRegistry();
        int a0 = ids.idFor(1, 0);
        int a1 = ids.idFor(1, 1);
        int b0 = ids.idFor(2, 0);
        List<Integer> removed = ids.retainPorts(Collections.singleton(MidiPortIdRegistry.portKey(2, 0)));
        assertEquals(2, removed.size());
        assertTrue(removed.contains(a0));
        assertTrue(removed.contains(a1));
        assertEquals(-1, ids.sessionForPort(a0));
        assertEquals(2, ids.sessionForPort(b0));
        assertEquals(b0, ids.idFor(2, 0));
        assertEquals(4, ids.idFor(3, 0));
    }

    @Test public void shrinkingPortCountRetiresOnlyRemovedIndexes() {
        MidiPortIdRegistry ids = new MidiPortIdRegistry();
        int port0 = ids.idFor(7, 0);
        int port1 = ids.idFor(7, 1);
        int port2 = ids.idFor(7, 2);
        int other = ids.idFor(8, 0);

        List<Integer> removed = ids.retainPorts(new java.util.HashSet<>(Arrays.asList(
                MidiPortIdRegistry.portKey(7, 0),
                MidiPortIdRegistry.portKey(7, 1),
                MidiPortIdRegistry.portKey(8, 0))));

        assertEquals(Collections.singletonList(port2), removed);
        assertEquals(7, ids.sessionForPort(port1));
        assertEquals(-1, ids.sessionForPort(port2));
        assertEquals(other, ids.idFor(8, 0));
        assertNotEquals(port2, ids.idFor(7, 2));
    }

    @Test public void invalidDeviceOrPortDoesNotAllocateAnId() {
        MidiPortIdRegistry ids = new MidiPortIdRegistry();
        assertEquals(-1, ids.idFor(0, 0));
        assertEquals(-1, ids.idFor(1, -1));
        assertEquals(0, ids.size());
    }
}
