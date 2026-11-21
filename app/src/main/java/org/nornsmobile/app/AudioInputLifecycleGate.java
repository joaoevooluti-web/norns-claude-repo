package org.nornsmobile.app;

/**
 * Generation gate for the Android microphone capture thread.
 * A late read/finish from an old session must not publish samples or stop a
 * newer session that reused the AudioInput instance.
 */
final class AudioInputLifecycleGate {
    private long generation;
    private boolean active;

    synchronized long begin() {
        generation++;
        active = true;
        return generation;
    }

    synchronized void stop() {
        generation++;
        active = false;
    }

    synchronized boolean isActive() { return active; }

    synchronized boolean isActive(long token) {
        return active && generation == token;
    }

    synchronized boolean finish(long token) {
        if (generation != token) return false;
        active = false;
        return true;
    }

    synchronized boolean isCurrent(long token) {
        return generation == token;
    }
}
