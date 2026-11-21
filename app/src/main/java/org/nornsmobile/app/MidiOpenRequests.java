package org.nornsmobile.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tokenized MIDI open lifecycle; stale Android callbacks cannot flush a newer request. */
final class MidiOpenRequests {
    static final class Completed {
        final List<byte[]> messages;
        final int droppedCount;
        Completed(List<byte[]> messages, int droppedCount) {
            this.messages = messages;
            this.droppedCount = droppedCount;
        }
    }

    private static final class Pending {
        final long token;
        final MidiMessageQueue queue;
        Pending(long token, int capacity) {
            this.token = token;
            this.queue = new MidiMessageQueue(capacity);
        }
    }

    private final Map<Integer, Pending> pending = new HashMap<>();
    private long nextToken = 1L;

    /** Returns 0 when a request is already pending. */
    synchronized long begin(int deviceId, int capacity) {
        if (pending.containsKey(deviceId)) return 0L;
        long token = nextToken++;
        if (nextToken == 0L) nextToken = 1L;
        pending.put(deviceId, new Pending(token, capacity));
        return token;
    }

    synchronized boolean isCurrent(int deviceId, long token) {
        Pending request = pending.get(deviceId);
        return request != null && request.token == token;
    }

    synchronized boolean isOpening(int deviceId) {
        return pending.containsKey(deviceId);
    }

    synchronized boolean offer(int deviceId, byte[] message) {
        Pending request = pending.get(deviceId);
        return request != null && request.queue.offer(message);
    }

    /** Null means the callback belongs to a closed or superseded request. */
    synchronized Completed complete(int deviceId, long token) {
        Pending request = pending.get(deviceId);
        if (request == null || request.token != token) return null;
        pending.remove(deviceId);
        return new Completed(request.queue.drain(), request.queue.getDroppedCount());
    }

    /** Returns -1 for stale callbacks; otherwise the number of discarded messages. */
    synchronized int fail(int deviceId, long token) {
        Pending request = pending.get(deviceId);
        if (request == null || request.token != token) return -1;
        pending.remove(deviceId);
        return request.queue.clear();
    }

    synchronized int cancel(int deviceId) {
        Pending request = pending.remove(deviceId);
        return request == null ? 0 : request.queue.clear();
    }

    synchronized int cancelAll() {
        int discarded = 0;
        for (Pending request : pending.values()) discarded += request.queue.clear();
        pending.clear();
        return discarded;
    }

    synchronized int size() { return pending.size(); }
}
