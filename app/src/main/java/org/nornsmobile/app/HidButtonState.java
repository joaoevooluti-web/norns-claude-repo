package org.nornsmobile.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Tracks pressed mouse buttons by Android input-device ID for reliable HID key-up. */
final class HidButtonState {
    static final class Release {
        final Map<Integer, Set<Integer>> buttonsByDevice;
        Release(Map<Integer, Set<Integer>> buttonsByDevice) {
            this.buttonsByDevice = Collections.unmodifiableMap(buttonsByDevice);
        }
    }

    private final Map<Integer, Set<Integer>> buttons = new HashMap<Integer, Set<Integer>>();

    synchronized boolean press(int deviceId, int buttonCode) {
        if (deviceId < 0 || buttonCode < 0) return false;
        Set<Integer> pressed = buttons.get(deviceId);
        if (pressed == null) {
            pressed = new TreeSet<Integer>();
            buttons.put(deviceId, pressed);
        }
        return pressed.add(buttonCode);
    }

    synchronized boolean release(int deviceId, int buttonCode) {
        Set<Integer> pressed = buttons.get(deviceId);
        if (pressed == null || !pressed.remove(buttonCode)) return false;
        if (pressed.isEmpty()) buttons.remove(deviceId);
        return true;
    }

    synchronized Release removeDevice(int deviceId) {
        Map<Integer, Set<Integer>> removed = new HashMap<Integer, Set<Integer>>();
        Set<Integer> pressed = buttons.remove(deviceId);
        if (pressed != null) removed.put(deviceId, new TreeSet<Integer>(pressed));
        return new Release(removed);
    }

    synchronized Release releaseAll() {
        Map<Integer, Set<Integer>> snapshot = new HashMap<Integer, Set<Integer>>();
        for (Map.Entry<Integer, Set<Integer>> entry : buttons.entrySet()) {
            snapshot.put(entry.getKey(), new TreeSet<Integer>(entry.getValue()));
        }
        buttons.clear();
        return new Release(snapshot);
    }
}
