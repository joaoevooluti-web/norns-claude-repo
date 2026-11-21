package org.nornsmobile.app;

/**
 * Serializes USB permission completion, open/close and shutdown transitions.
 * Actions execute while holding the same monitor used by the production bridge.
 */
final class UsbLifecycleGate {
    interface Operation<T> { T run(); }

    final Object lock = new Object();
    private boolean active = true;
    private boolean shutdownStarted;
    private boolean shutdownComplete;
    private Thread shutdownOwner;

    <T> T runIfActive(Operation<T> operation, T inactiveValue) {
        synchronized (lock) {
            if (!active) return inactiveValue;
            return operation.run();
        }
    }

    void runIfActive(Runnable operation) {
        synchronized (lock) {
            if (active) operation.run();
        }
    }

    void runSerialized(Runnable operation) {
        synchronized (lock) { operation.run(); }
    }

    void shutdown(Runnable cleanup) {
        synchronized (lock) {
            if (shutdownStarted) {
                // Cleanup may re-enter shutdown on its own thread. Do not deadlock
                // that owner, but make every other caller wait for full teardown.
                if (shutdownOwner == Thread.currentThread()) return;
                boolean interrupted = false;
                while (!shutdownComplete) {
                    try { lock.wait(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return;
            }
            shutdownStarted = true;
            shutdownOwner = Thread.currentThread();
            active = false;
        }
        // Never run transport cleanup while holding the lifecycle monitor:
        // close waits for per-port callback gates, whose listeners may re-enter close.
        try {
            cleanup.run();
        } finally {
            synchronized (lock) {
                shutdownComplete = true;
                shutdownOwner = null;
                lock.notifyAll();
            }
        }
    }

    boolean isActive() {
        synchronized (lock) { return active; }
    }
}
