package org.nornsmobile.app;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class UsbPortCallbackGateTest {
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("callback gate timed out", latch.await(2, TimeUnit.SECONDS));
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && thread.getState() != Thread.State.BLOCKED) {
            Thread.yield();
        }
        assertEquals("competing callback must be waiting for the callback lock",
                Thread.State.BLOCKED, thread.getState());
    }

    @Test public void concurrentReadsSerializeAndCloseDrainsBothBeforeReleasingPort() throws Exception {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch releaseSecond = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumActive = new AtomicInteger();

        Thread first = new Thread(() -> gate.dispatch(() -> true, () -> {
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstEntered.countDown();
            try { await(releaseFirst); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new AssertionError(e);
            } finally { active.decrementAndGet(); }
        }), "usb-read-callback-1");
        first.start();
        await(firstEntered);

        Thread second = new Thread(() -> gate.dispatch(() -> true, () -> {
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            secondEntered.countDown();
            try { await(releaseSecond); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new AssertionError(e);
            } finally { active.decrementAndGet(); }
        }), "usb-read-callback-2");
        second.start();
        awaitBlocked(second);
        assertEquals("callbacks for one port must never overlap", 1, maximumActive.get());

        releaseFirst.countDown();
        await(secondEntered);
        Thread closer = new Thread(() -> gate.close(closeFinished::countDown), "usb-close-drain");
        closer.start();
        awaitBlocked(closer);
        assertEquals("close cannot release the interface while a callback is active",
                1L, closeFinished.getCount());

        releaseSecond.countDown();
        first.join(2000); second.join(2000); closer.join(2000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(1, maximumActive.get());
        assertEquals(0L, closeFinished.getCount());
        assertFalse("late read delivery must be rejected after close",
                gate.dispatch(() -> true, () -> fail("late callback reached Lua")));
    }

    @Test public void oldPortReadIsRejectedAfterSameIdIsReplaced() {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final Object oldPort = new Object();
        final Object replacement = new Object();
        final AtomicReference<Object> currentPort = new AtomicReference<>(oldPort);
        final AtomicInteger delivered = new AtomicInteger();

        assertTrue(gate.dispatch(() -> currentPort.get() == oldPort, delivered::incrementAndGet));
        currentPort.set(replacement);
        assertFalse("old reader must not deliver bytes into the replacement session",
                gate.dispatch(() -> currentPort.get() == oldPort, delivered::incrementAndGet));
        assertTrue(gate.dispatch(() -> currentPort.get() == replacement, delivered::incrementAndGet));
        assertEquals(2, delivered.get());
    }

    @Test public void callbackFailureDoesNotLeavePortGateLockedOrActive() {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        try {
            gate.dispatch(() -> true, () -> { throw new IllegalStateException("listener failure"); });
            fail("listener exception should propagate to the caller");
        } catch (IllegalStateException expected) {
            assertEquals("listener failure", expected.getMessage());
        }
        final AtomicInteger delivered = new AtomicInteger();
        assertTrue(gate.dispatch(() -> true, delivered::incrementAndGet));
        assertEquals(1, delivered.get());
        gate.close(() -> {});
        assertFalse(gate.dispatch(() -> true, delivered::incrementAndGet));
        assertEquals(1, delivered.get());
    }
    @Test public void failedCloseCleanupStillClosesGateExactlyOnce() {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final AtomicInteger cleanupCalls = new AtomicInteger();
        try {
            gate.close(() -> {
                cleanupCalls.incrementAndGet();
                throw new IllegalStateException("release failed");
            });
            fail("cleanup failure should propagate");
        } catch (IllegalStateException expected) {
            assertEquals("release failed", expected.getMessage());
        }
        gate.close(cleanupCalls::incrementAndGet);
        assertEquals("close cleanup must not be retried after gate is marked inactive", 1,
                cleanupCalls.get());
        assertFalse(gate.dispatch(() -> true, () -> fail("closed gate dispatched callback")));
    }

    @Test public void inFlightWriteHoldsPortLockUntilCloseAndLaterWritesAreRejected() throws Exception {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            synchronized (gate.lock) {
                if (!gate.isActive()) return;
                writeEntered.countDown();
                try { await(releaseWrite); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new AssertionError(e);
                }
            }
        }, "usb-write-in-flight");
        writer.start();
        await(writeEntered);
        Thread closer = new Thread(() -> gate.close(closeFinished::countDown), "usb-close-after-write");
        closer.start();
        awaitBlocked(closer);
        assertEquals(1L, closeFinished.getCount());
        releaseWrite.countDown();
        writer.join(2000); closer.join(2000);
        assertFalse(writer.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(0L, closeFinished.getCount());
        final AtomicInteger writes = new AtomicInteger();
        synchronized (gate.lock) {
            if (gate.isActive()) writes.incrementAndGet();
        }
        assertEquals("no new write may start after close owns the port", 0, writes.get());
    }

    @Test public void multipleConcurrentWritesSerializeAndCloseDrainsQueuedWriters() throws Exception {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseWriters = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumActive = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        Runnable write = () -> {
            synchronized (gate.lock) {
                if (!gate.isActive()) return;
                int concurrent = active.incrementAndGet();
                maximumActive.accumulateAndGet(concurrent, Math::max);
                writes.incrementAndGet();
                firstEntered.countDown();
                try { await(releaseWriters); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new AssertionError(e);
                } finally { active.decrementAndGet(); }
            }
        };
        Thread first = new Thread(write, "usb-write-1");
        Thread second = new Thread(write, "usb-write-2");
        Thread third = new Thread(write, "usb-write-3");
        first.start(); await(firstEntered); second.start(); third.start();
        awaitBlocked(second); awaitBlocked(third);
        final CountDownLatch closeFinished = new CountDownLatch(1);
        Thread closer = new Thread(() -> gate.close(closeFinished::countDown), "usb-close-multiple-writes");
        closer.start(); awaitBlocked(closer);
        assertEquals(1, active.get()); assertEquals(1L, closeFinished.getCount());
        releaseWriters.countDown();
        first.join(2000); second.join(2000); third.join(2000); closer.join(2000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertFalse(third.isAlive()); assertFalse(closer.isAlive());
        assertEquals("bulkTransfer calls for one port must never overlap", 1, maximumActive.get());
        int acceptedWrites = writes.get();
        Thread lateWriter = new Thread(write, "usb-write-after-close");
        lateWriter.start(); lateWriter.join(2000);
        assertFalse(lateWriter.isAlive());
        assertEquals("no write may be accepted after close", acceptedWrites, writes.get());
        assertEquals(0L, closeFinished.getCount());
    }

    @Test public void closeDuringBlockingReadDiscardsBytesReturnedAfterClose() throws Exception {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);
        final AtomicInteger delivered = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        Thread reader = new Thread(() -> {
            // Model bulkTransfer: the blocking read is outside the callback lock.
            readEntered.countDown();
            try { await(releaseRead); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new AssertionError(e);
            }
            byte[] lateBytes = new byte[] { 1, 2, 3 };
            boolean accepted = gate.dispatch(() -> true, () -> delivered.addAndGet(lateBytes.length));
            assertFalse("bytes returned after close must be discarded", accepted);
        }, "usb-blocked-bulk-read");
        reader.start();
        await(readEntered);
        Thread closer = new Thread(() -> gate.close(() -> {
            released.incrementAndGet();
            closeFinished.countDown();
        }), "usb-close-during-bulk-read");
        closer.start();
        await(closeFinished);
        assertEquals("close must release the interface without waiting for blocked bulkTransfer",
                1, released.get());
        releaseRead.countDown();
        reader.join(2000);
        closer.join(2000);
        assertFalse(reader.isAlive());
        assertFalse(closer.isAlive());
        assertEquals("late bytes must never reach listener/Lua", 0, delivered.get());
        assertFalse(gate.dispatch(() -> true, () -> delivered.incrementAndGet()));
    }

    @Test public void permissionGrantCloseAndShutdownRacesNeverReopenClosedSession() throws Exception {
        final UsbLifecycleGate lifecycle = new UsbLifecycleGate();
        final AtomicInteger pending = new AtomicInteger(1);
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch grantEntered = new CountDownLatch(1);
        final CountDownLatch releaseGrant = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);
        Thread grant = new Thread(() -> lifecycle.runIfActive(() -> {
            if (pending.compareAndSet(1, 0)) {
                opens.incrementAndGet();
                grantEntered.countDown();
                try { await(releaseGrant); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new AssertionError(e);
                }
            }
            return true;
        }, false), "usb-grant-vs-close");
        grant.start();
        await(grantEntered);
        Thread close = new Thread(() -> {
            lifecycle.runSerialized(() -> { pending.set(0); closes.incrementAndGet(); });
            closeFinished.countDown();
        }, "usb-close-vs-grant");
        close.start();
        assertEquals("close must serialize behind an in-flight permission completion",
                1L, closeFinished.getCount());
        releaseGrant.countDown();
        grant.join(2000);
        close.join(2000);
        assertFalse(grant.isAlive());
        assertFalse(close.isAlive());
        assertEquals(1, opens.get());
        assertEquals(1, closes.get());
        assertEquals(0, pending.get());

        final AtomicInteger lateOpens = new AtomicInteger();
        lifecycle.shutdown(() -> pending.set(0));
        assertFalse(lifecycle.runIfActive(() -> {
            lateOpens.incrementAndGet();
            return true;
        }, false));
        assertEquals("shutdown must reject all later permission completions", 0, lateOpens.get());
    }

}
