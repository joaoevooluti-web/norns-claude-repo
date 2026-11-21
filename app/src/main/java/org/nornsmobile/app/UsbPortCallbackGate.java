package org.nornsmobile.app;

final class UsbPortCallbackGate {
    final Object lock = new Object();
    private boolean active = true;

    boolean isActive() {
        synchronized (lock) { return active; }
    }

    boolean dispatch(java.util.function.BooleanSupplier current, Runnable callback) {
        synchronized (lock) {
            if (!active || !current.getAsBoolean()) return false;
            callback.run();
            return true;
        }
    }

    void close(Runnable cleanup) {
        synchronized (lock) {
            if (!active) return;
            active = false;
            cleanup.run();
        }
    }
}
