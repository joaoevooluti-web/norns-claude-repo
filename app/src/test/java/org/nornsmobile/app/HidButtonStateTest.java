package org.nornsmobile.app;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class HidButtonStateTest {
    @Test public void buttonPressAndReleaseAreIdempotent() {
        HidButtonState state = new HidButtonState();
        assertTrue(state.press(2, 0x110));
        assertFalse(state.press(2, 0x110));
        assertTrue(state.release(2, 0x110));
        assertFalse(state.release(2, 0x110));
    }

    @Test public void disconnectReturnsOnlyButtonsOwnedByThatDevice() {
        HidButtonState state = new HidButtonState();
        state.press(1, 0x110);
        state.press(1, 0x111);
        state.press(2, 0x110);
        HidButtonState.Release release = state.removeDevice(1);
        assertEquals(2, release.buttonsByDevice.get(1).size());
        assertTrue(release.buttonsByDevice.get(1).contains(0x110));
        assertTrue(release.buttonsByDevice.get(1).contains(0x111));
        assertTrue(state.release(2, 0x110));
    }

    @Test public void focusLossSnapshotsEveryPressedButtonByDevice() {
        HidButtonState state = new HidButtonState();
        state.press(3, 0x110);
        state.press(4, 0x112);
        HidButtonState.Release release = state.releaseAll();
        assertEquals(Collections.singleton(0x110), release.buttonsByDevice.get(3));
        assertEquals(Collections.singleton(0x112), release.buttonsByDevice.get(4));
        assertTrue(state.releaseAll().buttonsByDevice.isEmpty());
    }

    @Test public void invalidDeviceOrCodeIsIgnored() {
        HidButtonState state = new HidButtonState();
        assertFalse(state.press(-1, 0x110));
        assertFalse(state.press(1, -1));
        assertFalse(state.release(1, 0x110));
        assertTrue(state.releaseAll().buttonsByDevice.isEmpty());
    }

    @Test public void concurrentDuplicatePressAndReleaseProduceOneStateTransition() throws Exception {
        final HidButtonState state = new HidButtonState();
        final int workers = 16;
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(workers);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger pressed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try { start.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }
                    if (state.press(7, 0x110)) pressed.incrementAndGet();
                }
            }, "hid-press-race-" + i);
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) thread.join(2000);
        for (Thread thread : threads) assertFalse("press worker must finish", thread.isAlive());
        assertEquals("duplicate concurrent press is one transition", 1, pressed.get());

        final java.util.concurrent.CountDownLatch releaseReady = new java.util.concurrent.CountDownLatch(workers);
        final java.util.concurrent.CountDownLatch releaseStart = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override public void run() {
                    releaseReady.countDown();
                    try { releaseStart.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }
                    if (state.release(7, 0x110)) released.incrementAndGet();
                }
            }, "hid-release-race-" + i);
            threads[i].start();
        }
        releaseReady.await();
        releaseStart.countDown();
        for (Thread thread : threads) thread.join(2000);
        for (Thread thread : threads) assertFalse("release worker must finish", thread.isAlive());
        assertEquals("duplicate concurrent release is one transition", 1, released.get());
        assertFalse(state.release(7, 0x110));
    }

    @Test public void concurrentDeviceDetachDoesNotReleaseAnotherControllersButtons() throws Exception {
        final HidButtonState state = new HidButtonState();
        state.press(21, 0x110);
        state.press(22, 0x111);
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<HidButtonState.Release> detached =
                new java.util.concurrent.atomic.AtomicReference<HidButtonState.Release>();
        Thread detach = new Thread(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            detached.set(state.removeDevice(21));
        }, "hid-hotplug-detach");
        Thread otherInput = new Thread(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            assertTrue(state.press(22, 0x112));
        }, "hid-other-controller-input");
        detach.start(); otherInput.start();
        assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        detach.join(2000); otherInput.join(2000);
        assertFalse(detach.isAlive()); assertFalse(otherInput.isAlive());
        assertEquals(Collections.singleton(0x110), detached.get().buttonsByDevice.get(21));
        assertTrue(state.release(22, 0x111));
        assertTrue(state.release(22, 0x112));
        assertTrue(state.releaseAll().buttonsByDevice.isEmpty());
    }

    @Test public void detachAndFocusLossRaceNeverLoseOrDuplicateHeldButtonReleases() throws Exception {
        final HidButtonState state = new HidButtonState();
        state.press(61, 0x110);
        state.press(62, 0x111);
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<HidButtonState.Release> detached =
                new java.util.concurrent.atomic.AtomicReference<HidButtonState.Release>();
        final java.util.concurrent.atomic.AtomicReference<HidButtonState.Release> focusLost =
                new java.util.concurrent.atomic.AtomicReference<HidButtonState.Release>();
        Thread detach = new Thread(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            detached.set(state.removeDevice(61));
        }, "hid-detach-vs-focus-loss");
        Thread focus = new Thread(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            focusLost.set(state.releaseAll());
        }, "hid-focus-loss-vs-detach");
        detach.start(); focus.start();
        assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        detach.join(2000); focus.join(2000);
        assertFalse(detach.isAlive()); assertFalse(focus.isAlive());
        int releases = 0;
        for (java.util.Map<Integer, java.util.Set<Integer>> snapshot :
                new java.util.Map[]{detached.get().buttonsByDevice, focusLost.get().buttonsByDevice}) {
            for (java.util.Set<Integer> codes : snapshot.values()) releases += codes.size();
        }
        assertEquals("each held button must be released exactly once across both lifecycle paths", 2, releases);
        assertTrue(state.releaseAll().buttonsByDevice.isEmpty());
    }

}
