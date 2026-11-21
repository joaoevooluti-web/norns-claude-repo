package org.nornsmobile.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

public class MidiDeviceIdRegistryTest {
    @Test public void idsRemainStableWhenAndroidEnumerationOrderChanges() {
        MidiDeviceIdRegistry registry = new MidiDeviceIdRegistry();
        int first = registry.idFor(42);
        int second = registry.idFor(7);
        assertEquals(first, registry.idFor(42));
        assertEquals(second, registry.idFor(7));
        assertEquals(first, registry.sessionIdFor(42));
        assertEquals(second, registry.sessionIdFor(7));
        registry.retain(new HashSet<Integer>(Arrays.asList(7, 42)));
        assertEquals(first, registry.idFor(42));
        assertEquals(second, registry.idFor(7));
        assertNotEquals(first, second);
    }

    @Test public void removedDeviceRetiresOnlyItsSessionId() {
        MidiDeviceIdRegistry registry = new MidiDeviceIdRegistry();
        int removed = registry.idFor(100);
        int retained = registry.idFor(200);
        List<Integer> retired = registry.retain(Collections.singleton(200));
        assertEquals(Collections.singletonList(removed), retired);
        assertEquals(-1, registry.sessionIdFor(100));
        assertEquals(retained, registry.sessionIdFor(200));
        assertEquals(1, registry.size());
        assertEquals(retained, registry.idFor(200));
        int replacement = registry.idFor(300);
        assertTrue(replacement > retained);
        assertNotEquals(removed, replacement);
    }

    @Test public void emptyRefreshRetiresAllDevices() {
        MidiDeviceIdRegistry registry = new MidiDeviceIdRegistry();
        registry.idFor(1);
        registry.idFor(2);
        List<Integer> retired = registry.retain(Collections.<Integer>emptySet());
        assertEquals(2, retired.size());
        assertEquals(0, registry.size());
    }
}
