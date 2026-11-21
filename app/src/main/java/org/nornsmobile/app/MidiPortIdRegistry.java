package org.nornsmobile.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable logical IDs for each Android MIDI device port within an app session. */
final class MidiPortIdRegistry {
    private final Map<Long, Integer> idByPort = new HashMap<>();
    private final Map<Integer, Long> portById = new HashMap<>();
    private int nextId = 1;

    synchronized int idFor(int deviceSessionId, int portIndex) {
        if (deviceSessionId <= 0 || portIndex < 0) return -1;
        long key = portKey(deviceSessionId, portIndex);
        Integer existing = idByPort.get(key);
        if (existing != null) return existing;
        if (nextId <= 0) throw new IllegalStateException("MIDI logical port ID space exhausted");
        int id = nextId++;
        idByPort.put(key, id);
        portById.put(id, key);
        return id;
    }

    synchronized int sessionForPort(int logicalId) {
        Long key = portById.get(logicalId);
        return key == null ? -1 : (int) (key >>> 32);
    }

    synchronized List<Integer> idsForSession(int sessionId) {
        List<Integer> result = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : portById.entrySet()) {
            if ((int) (entry.getValue() >>> 32) == sessionId) result.add(entry.getKey());
        }
        Collections.sort(result);
        return result;
    }

    static long portKey(int deviceSessionId, int portIndex) {
        return (((long) deviceSessionId) << 32) | (portIndex & 0xffffffffL);
    }

    synchronized List<Integer> retainPorts(Set<Long> activePortKeys) {
        Set<Long> active = activePortKeys == null
                ? new HashSet<Long>() : new HashSet<>(activePortKeys);
        List<Integer> removed = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : new HashMap<>(portById).entrySet()) {
            if (!active.contains(entry.getValue())) {
                portById.remove(entry.getKey());
                idByPort.remove(entry.getValue());
                removed.add(entry.getKey());
            }
        }
        Collections.sort(removed);
        return removed;
    }

    synchronized int size() { return portById.size(); }
}
