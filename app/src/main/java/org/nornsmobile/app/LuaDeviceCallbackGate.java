package org.nornsmobile.app;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Session-aware callback admission for device IDs reused by Android after hotplug. */
final class LuaDeviceCallbackGate {
    static final class Session {
        final int deviceId;
        final long token;
        final String identity;
        boolean closing;
        boolean announced;
        Session(int deviceId, long token, String identity) { this.deviceId = deviceId; this.token = token; this.identity = identity; }
    }

    private final AtomicLong nextToken = new AtomicLong(1L);
    private final Map<Integer, Session> active = new HashMap<Integer, Session>();
    private final Map<Long, Session> sessions = new HashMap<Long, Session>();

    synchronized Session beginIfAbsent(int deviceId) { return beginIfAbsent(deviceId, null); }

    synchronized Session beginIfAbsent(int deviceId, String identity) {
        Session current = active.get(deviceId);
        if (current != null && (current.identity == null || identity == null || current.identity.equals(identity))) return current;
        if (current != null) { current.closing = true; active.remove(deviceId); }
        long token = nextToken.getAndIncrement();
        if (token <= 0L) { nextToken.set(2L); token = 1L; }
        Session session = new Session(deviceId, token, identity);
        sessions.put(token, session);
        active.put(deviceId, session);
        return session;
    }

    synchronized Session begin(int deviceId, long token) {
        if (token <= 0L) return null;
        Session existing = sessions.get(token);
        if (existing != null) return existing.deviceId == deviceId ? existing : null;
        Session session = new Session(deviceId, token, null);
        sessions.put(token, session);
        active.put(deviceId, session);
        return session;
    }

    synchronized Session current(int deviceId) { return active.get(deviceId); }

    synchronized boolean matchesIdentity(int deviceId, long token, String identity) {
        Session session = sessions.get(token);
        return session != null && session.deviceId == deviceId
                && (session.identity == null || identity == null || session.identity.equals(identity));
    }

    synchronized boolean acceptsOrderedTerminal(int deviceId, long token) {
        Session session = sessions.get(token);
        return session != null && session.deviceId == deviceId && session.closing;
    }

    synchronized boolean markAnnounced(Session session) {
        if (session == null || session.closing || active.get(session.deviceId) != session
                || session.announced) return false;
        session.announced = true;
        return true;
    }

    synchronized Session beginClose(int deviceId) {
        Session session = active.remove(deviceId);
        if (session != null) session.closing = true;
        return session;
    }

    synchronized Session beginClose(int deviceId, long token) {
        Session session = sessions.get(token);
        if (session == null || session.deviceId != deviceId || session.closing) return null;
        if (active.get(deviceId) == session) active.remove(deviceId);
        session.closing = true;
        return session;
    }

    synchronized boolean acceptsTerminal(int deviceId, long token) {
        Session session = sessions.get(token);
        return session != null && session.deviceId == deviceId && session.closing
                && active.get(deviceId) == null;
    }

    synchronized boolean accepts(int deviceId, long token, boolean release) {
        Session session = sessions.get(token);
        if (session == null || session.deviceId != deviceId) return false;
        if (session.closing) return release;
        return active.get(deviceId) == session;
    }

    synchronized void finishClose(int deviceId, long token) {
        Session session = sessions.get(token);
        if (session == null || session.deviceId != deviceId || !session.closing) return;
        sessions.remove(token);
    }

    synchronized void clear() { active.clear(); sessions.clear(); }
}
