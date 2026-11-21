package org.nornsmobile.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tracks physical keyboard ownership of logical keys.
 * A logical Norns key is released only after its last physical owner releases it.
 */
final class KeyboardDeviceState {
    static final class Release {
        final Set<String> logicalKeys;
        final Map<Integer, Set<String>> keysByDevice;

        Release(Set<String> logicalKeys, Map<Integer, Set<String>> keysByDevice) {
            this.logicalKeys = Collections.unmodifiableSet(logicalKeys);
            this.keysByDevice = Collections.unmodifiableMap(keysByDevice);
        }
    }

    private final Map<String, Set<Integer>> devicesByKey =
            new HashMap<String, Set<Integer>>();

    /** Returns true when this event should be forwarded to the aggregate Norns keyboard. */
    synchronized boolean keyDown(String key, int deviceId) {
        if (key == null || key.length() == 0) return false;
        Set<Integer> owners = devicesByKey.get(key);
        if (owners == null) {
            owners = new HashSet<Integer>();
            devicesByKey.put(key, owners);
        }
        boolean alreadyOwned = owners.contains(deviceId);
        boolean firstOwner = owners.isEmpty();
        owners.add(deviceId);
        // Repeats from the owning keyboard remain visible; a second keyboard's
        // initial down must not duplicate the aggregate logical press.
        return firstOwner || alreadyOwned;
    }

    /** Returns true only when this key has no remaining physical owner. */
    synchronized boolean keyUp(String key, int deviceId) {
        if (key == null) return false;
        Set<Integer> owners = devicesByKey.get(key);
        if (owners == null || !owners.remove(deviceId)) return false;
        if (!owners.isEmpty()) return false;
        devicesByKey.remove(key);
        return true;
    }

    /** Removes one keyboard, preserving logical keys still held by other keyboards. */
    synchronized Release removeDevice(int deviceId) {
        Set<String> deviceKeys = new TreeSet<String>();
        Set<String> released = new TreeSet<String>();
        for (Map.Entry<String, Set<Integer>> entry : devicesByKey.entrySet()) {
            if (entry.getValue().remove(deviceId)) deviceKeys.add(entry.getKey());
            if (entry.getValue().isEmpty()) released.add(entry.getKey());
        }
        for (String key : released) devicesByKey.remove(key);
        Map<Integer, Set<String>> byDevice = new HashMap<Integer, Set<String>>();
        byDevice.put(deviceId, deviceKeys);
        return new Release(released, byDevice);
    }

    /** Clears every keyboard and returns all HID and aggregate releases to dispatch. */
    synchronized Release releaseAll() {
        Map<Integer, Set<String>> byDevice = new HashMap<Integer, Set<String>>();
        Set<String> allKeys = new TreeSet<String>();
        for (Map.Entry<String, Set<Integer>> entry : devicesByKey.entrySet()) {
            allKeys.add(entry.getKey());
            for (Integer deviceId : entry.getValue()) {
                Set<String> keys = byDevice.get(deviceId);
                if (keys == null) {
                    keys = new TreeSet<String>();
                    byDevice.put(deviceId, keys);
                }
                keys.add(entry.getKey());
            }
        }
        devicesByKey.clear();
        return new Release(allKeys, byDevice);
    }

    synchronized boolean isPressed(String key) {
        Set<Integer> owners = devicesByKey.get(key);
        return owners != null && !owners.isEmpty();
    }
}
