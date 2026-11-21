package org.nornsmobile.app;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks ports removed from the live map whose transport cleanup is still in
 * progress. Same-ID opens must wait until the old USB interfaces are released.
 */
final class UsbPortCloseGate {
    private final Set<Integer> closing = new HashSet<>();

    synchronized boolean beginClose(int deviceId) {
        return closing.add(deviceId);
    }

    synchronized boolean isClosing(int deviceId) {
        return closing.contains(deviceId);
    }

    synchronized boolean canOpen(int deviceId) {
        return !closing.contains(deviceId);
    }

    synchronized void finishClose(int deviceId) {
        closing.remove(deviceId);
        notifyAll();
    }

    synchronized void awaitIdle() {
        boolean interrupted = false;
        while (!closing.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
