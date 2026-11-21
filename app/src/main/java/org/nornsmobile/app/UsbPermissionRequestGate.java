package org.nornsmobile.app;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Correlates Android USB permission broadcasts with the exact request that
 * created them. Device IDs can be reused after detach, so ID alone is unsafe.
 */
final class UsbPermissionRequestGate<T> {
    static final class Request<T> {
        final int token;
        final T value;
        Request(int token, T value) {
            this.token = token;
            this.value = value;
        }
    }

    private final Map<Integer, Request<T>> pending = new HashMap<>();
    private int nextToken = 1;

    synchronized Request<T> begin(int deviceId, T value) {
        int token = allocateToken();
        Request<T> request = new Request<>(token, value);
        pending.put(deviceId, request);
        return request;
    }

    synchronized Request<T> consume(int deviceId, int token) {
        Request<T> current = pending.get(deviceId);
        if (current == null || token == 0 || current.token != token) return null;
        pending.remove(deviceId);
        return current;
    }

    synchronized boolean cancel(int deviceId) {
        return pending.remove(deviceId) != null;
    }

    synchronized boolean cancel(int deviceId, int token) {
        Request<T> current = pending.get(deviceId);
        if (current == null || current.token != token) return false;
        pending.remove(deviceId);
        return true;
    }

    synchronized void clear() {
        pending.clear();
    }

    synchronized boolean isPending(int deviceId) {
        return pending.containsKey(deviceId);
    }

    private int allocateToken() {
        Set<Integer> active = new HashSet<>();
        for (Request<T> request : pending.values()) active.add(request.token);
        for (long attempts = 0; attempts < Integer.MAX_VALUE; attempts++) {
            int candidate = nextToken++;
            if (nextToken <= 0) nextToken = 1;
            if (candidate > 0 && !active.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("No USB permission request tokens available");
    }
}
