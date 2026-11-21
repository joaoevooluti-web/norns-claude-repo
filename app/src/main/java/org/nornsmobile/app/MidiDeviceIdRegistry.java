package org.nornsmobile.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps Android's transient device IDs to stable positive IDs for one app session. */
final class MidiDeviceIdRegistry {
    private final Map<Integer, Integer> sessionByAndroid = new HashMap<>();
    private final Map<Integer, Integer> androidBySession = new HashMap<>();
    private int nextSessionId = 1;

    synchronized int idFor(int androidDeviceId) {
        Integer existing = sessionByAndroid.get(androidDeviceId);
        if (existing != null) return existing;
        int assigned = nextSessionId++;
        sessionByAndroid.put(androidDeviceId, assigned);
        androidBySession.put(assigned, androidDeviceId);
        return assigned;
    }

    synchronized int sessionIdFor(int androidDeviceId) {
        Integer id = sessionByAndroid.get(androidDeviceId);
        return id == null ? -1 : id;
    }

    /** Retains currently connected Android IDs and returns retired session IDs. */
    synchronized List<Integer> retain(Set<Integer> connectedAndroidIds) {
        Set<Integer> connected = connectedAndroidIds == null
                ? new HashSet<Integer>() : new HashSet<>(connectedAndroidIds);
        List<Integer> removed = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : new HashMap<>(sessionByAndroid).entrySet()) {
            if (!connected.contains(entry.getKey())) {
                sessionByAndroid.remove(entry.getKey());
                androidBySession.remove(entry.getValue());
                removed.add(entry.getValue());
            }
        }
        return removed;
    }

    synchronized int size() { return sessionByAndroid.size(); }
}
