package org.nornsmobile.app;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class UsbLifecycleGateTest {
    private static void await(CountDownLatch latch) {
        try {
            assertTrue("barrier timed out", latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test public void permissionGrantWinningRaceIsClosedBeforeCloseReturns() throws Exception {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final AtomicInteger pending = new AtomicInteger(1);
        final AtomicInteger opens = new AtomicInteger();
        final AtomicBoolean portOpen = new AtomicBoolean(false);
        final CountDownLatch grantEntered = new CountDownLatch(1);
        final CountDownLatch allowGrantToFinish = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);

        Thread grant = new Thread(() -> gate.runIfActive(() -> {
            if (pending.getAndSet(0) == 1) {
                opens.incrementAndGet();
                portOpen.set(true);
                grantEntered.countDown();
                await(allowGrantToFinish);
            }
            return true;
        }, false), "usb-permission-grant");
        grant.start();
        await(grantEntered);

        Thread close = new Thread(() -> {
            closeStarted.countDown();
            gate.runSerialized(() -> {
                pending.set(0);
                portOpen.set(false);
            });
            closeFinished.countDown();
        }, "usb-close-after-grant");
        close.start();
        await(closeStarted);
        assertEquals("close must serialize behind an in-flight permission completion",
                1L, closeFinished.getCount());

        allowGrantToFinish.countDown();
        grant.join(2000);
        close.join(2000);
        assertFalse(grant.isAlive());
        assertFalse(close.isAlive());
        assertEquals(1, opens.get());
        assertFalse("close must leave no reopened port", portOpen.get());
        assertEquals(0, pending.get());
    }

    @Test public void closeWinningRaceConsumesPendingPermissionBeforeLateGrant() throws Exception {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final AtomicInteger pending = new AtomicInteger(1);
        final AtomicInteger opens = new AtomicInteger();
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch allowClose = new CountDownLatch(1);
        final CountDownLatch grantStarted = new CountDownLatch(1);

        Thread close = new Thread(() -> gate.runSerialized(() -> {
            closeEntered.countDown();
            await(allowClose);
            pending.set(0);
        }), "usb-close-before-grant");
        close.start();
        await(closeEntered);

        Thread grant = new Thread(() -> {
            grantStarted.countDown();
            gate.runIfActive(() -> {
                if (pending.getAndSet(0) == 1) opens.incrementAndGet();
                return true;
            }, false);
        }, "usb-late-permission-grant");
        grant.start();
        await(grantStarted);
        assertEquals(0, opens.get());
        allowClose.countDown();
        close.join(2000);
        grant.join(2000);
        assertFalse(close.isAlive());
        assertFalse(grant.isAlive());
        assertEquals("late permission must not recreate a closed port", 0, opens.get());
        assertEquals(0, pending.get());
    }

    @Test public void shutdownRejectsPermissionCompletionAfterPendingStateIsCleared() {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final AtomicInteger pending = new AtomicInteger(1);
        final AtomicInteger opens = new AtomicInteger();
        assertTrue(gate.isActive());
        gate.shutdown(() -> pending.set(0));
        assertFalse(gate.isActive());
        assertFalse(gate.runIfActive(() -> {
            if (pending.getAndSet(0) == 1) opens.incrementAndGet();
            return true;
        }, false));
        assertEquals(0, opens.get());
        assertEquals(0, pending.get());
        gate.shutdown(() -> fail("shutdown cleanup must run only once"));
    }

    @Test public void serializedPortCleanupRemainsAvailableAfterBridgeShutdown() {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final AtomicInteger cleanups = new AtomicInteger();
        assertTrue(gate.isActive());
        gate.shutdown(() -> {});
        gate.runSerialized(cleanups::incrementAndGet);
        assertEquals("shutdown blocks new opens but still permits deterministic port cleanup",
                1, cleanups.get());
    }


    @Test public void attachAndDetachBroadcastsAreSerializedInArrivalOrder() throws Exception {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final java.util.List<String> events = java.util.Collections.synchronizedList(
                new java.util.ArrayList<String>());
        final CountDownLatch attachEntered = new CountDownLatch(1);
        final CountDownLatch releaseAttach = new CountDownLatch(1);
        final CountDownLatch detachStarted = new CountDownLatch(1);
        final CountDownLatch detachFinished = new CountDownLatch(1);

        Thread attach = new Thread(() -> gate.runIfActive(() -> {
            events.add("attach-start");
            attachEntered.countDown();
            await(releaseAttach);
            events.add("attach-end");
        }), "usb-attach-broadcast");
        attach.start();
        await(attachEntered);

        Thread detach = new Thread(() -> {
            detachStarted.countDown();
            gate.runIfActive(() -> events.add("detach"));
            detachFinished.countDown();
        }, "usb-detach-broadcast");
        detach.start();
        await(detachStarted);
        assertEquals("detach must wait for the in-flight attach transition",
                1L, detachFinished.getCount());

        releaseAttach.countDown();
        attach.join(2000);
        detach.join(2000);
        assertFalse(attach.isAlive());
        assertFalse(detach.isAlive());
        assertEquals(java.util.Arrays.asList("attach-start", "attach-end", "detach"), events);
        assertEquals(0L, detachFinished.getCount());
    }

    @Test public void lateAttachAndDetachBroadcastsAreIgnoredAfterShutdown() {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final AtomicInteger attachNotifications = new AtomicInteger();
        final AtomicInteger detachNotifications = new AtomicInteger();
        gate.shutdown(() -> {});
        gate.runIfActive(attachNotifications::incrementAndGet);
        gate.runIfActive(detachNotifications::incrementAndGet);
        assertEquals(0, attachNotifications.get());
        assertEquals(0, detachNotifications.get());
    }

    @Test public void shutdownRejectsPermissionWhileCleanupRunsOutsideLifecycleMonitor() throws Exception {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final CountDownLatch releaseCleanup = new CountDownLatch(1);
        final CountDownLatch shutdownFinished = new CountDownLatch(1);
        final AtomicInteger permissionOpens = new AtomicInteger();
        final AtomicInteger serializedCloses = new AtomicInteger();
        Thread shutdown = new Thread(() -> {
            gate.shutdown(() -> {
                cleanupEntered.countDown();
                await(releaseCleanup);
            });
            shutdownFinished.countDown();
        }, "usb-shutdown-cleanup");
        shutdown.start();
        await(cleanupEntered);
        assertFalse("permission completion must be rejected as soon as shutdown marks inactive",
                gate.runIfActive(() -> { permissionOpens.incrementAndGet(); return true; }, false));
        gate.runSerialized(serializedCloses::incrementAndGet);
        assertEquals(0, permissionOpens.get());
        assertEquals("port close remains available while shutdown cleanup is in progress",
                1, serializedCloses.get());
        assertEquals(1L, shutdownFinished.getCount());
        releaseCleanup.countDown();
        shutdown.join(2000);
        assertFalse(shutdown.isAlive());
        assertEquals(0L, shutdownFinished.getCount());
    }

    @Test public void concurrentShutdownCallersWaitForTheSameCleanupBarrier() throws Exception {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final CountDownLatch releaseCleanup = new CountDownLatch(1);
        final CountDownLatch firstFinished = new CountDownLatch(1);
        final CountDownLatch secondStarted = new CountDownLatch(1);
        final CountDownLatch secondFinished = new CountDownLatch(1);
        final AtomicInteger cleanups = new AtomicInteger();
        Thread first = new Thread(() -> {
            gate.shutdown(() -> {
                cleanups.incrementAndGet();
                cleanupEntered.countDown();
                await(releaseCleanup);
            });
            firstFinished.countDown();
        }, "usb-shutdown-owner");
        first.start();
        await(cleanupEntered);

        Thread second = new Thread(() -> {
            secondStarted.countDown();
            gate.shutdown(() -> cleanups.incrementAndGet());
            secondFinished.countDown();
        }, "usb-shutdown-waiter");
        second.start();
        await(secondStarted);
        assertEquals("a concurrent shutdown caller must not report completion early",
                1L, secondFinished.getCount());
        assertEquals(1L, firstFinished.getCount());
        assertEquals(1, cleanups.get());

        releaseCleanup.countDown();
        first.join(2000);
        second.join(2000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(0L, firstFinished.getCount());
        assertEquals(0L, secondFinished.getCount());
        assertEquals("shutdown cleanup must execute exactly once", 1, cleanups.get());
        assertFalse(gate.isActive());
    }

    @Test public void shutdownCanBeReenteredByItsCleanupOwnerWithoutDeadlock() {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final AtomicInteger cleanups = new AtomicInteger();
        gate.shutdown(() -> {
            cleanups.incrementAndGet();
            gate.shutdown(cleanups::incrementAndGet);
        });
        assertEquals(1, cleanups.get());
        assertFalse(gate.isActive());
    }

    @Test public void failedShutdownCleanupStillReleasesConcurrentWaiters() throws Exception {
        final UsbLifecycleGate gate = new UsbLifecycleGate();
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final CountDownLatch releaseFailure = new CountDownLatch(1);
        final CountDownLatch waiterFinished = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            try {
                gate.shutdown(() -> {
                    cleanupEntered.countDown();
                    await(releaseFailure);
                    throw new IllegalStateException("simulated USB cleanup failure");
                });
                fail("cleanup exception must propagate to its owner");
            } catch (IllegalStateException expected) {
                assertEquals("simulated USB cleanup failure", expected.getMessage());
            }
        }, "usb-shutdown-failing-owner");
        owner.start();
        await(cleanupEntered);
        Thread waiter = new Thread(() -> {
            gate.shutdown(() -> fail("cleanup must not be retried by a waiter"));
            waiterFinished.countDown();
        }, "usb-shutdown-after-failure");
        waiter.start();
        assertEquals(1L, waiterFinished.getCount());
        releaseFailure.countDown();
        owner.join(2000);
        waiter.join(2000);
        assertFalse(owner.isAlive());
        assertFalse(waiter.isAlive());
        assertEquals(0L, waiterFinished.getCount());
        assertFalse(gate.isActive());
    }


}

