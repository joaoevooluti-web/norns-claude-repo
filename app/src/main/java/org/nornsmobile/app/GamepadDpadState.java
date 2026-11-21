package org.nornsmobile.app;

import java.util.HashMap;
import java.util.Map;

/** Tracks digital D-pad keys independently so one key-up cannot cancel its opposite key. */
final class GamepadDpadState {
    private static final class AxisState {
        boolean negative;
        boolean positive;
        float value() {
            if (negative == positive) return 0f;
            return negative ? -1f : 1f;
        }
    }

    private final Map<Integer, Map<String, AxisState>> devices =
            new HashMap<Integer, Map<String, AxisState>>();

    synchronized float update(int deviceId, String axis, int direction, boolean pressed) {
        if (axis == null || (direction != -1 && direction != 1)) return 0f;
        Map<String, AxisState> axes = devices.get(deviceId);
        if (axes == null) {
            axes = new HashMap<String, AxisState>();
            devices.put(deviceId, axes);
        }
        AxisState state = axes.get(axis);
        if (state == null) {
            state = new AxisState();
            axes.put(axis, state);
        }
        if (direction < 0) state.negative = pressed;
        else state.positive = pressed;
        float value = state.value();
        if (!state.negative && !state.positive) {
            axes.remove(axis);
            if (axes.isEmpty()) devices.remove(deviceId);
        }
        return value;
    }

    synchronized void removeDevice(int deviceId) {
        devices.remove(deviceId);
    }

    synchronized void clear() {
        devices.clear();
    }
}
