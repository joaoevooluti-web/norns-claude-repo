package org.nornsmobile.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SuperColliderBridgeTest {

    @Test public void readbackWaitPumpsRepliesAndPreservesUnmatchedMessages() {
        CountDownLatch waiter = new CountDownLatch(1);
        List<SuperColliderOsc.Reply> deferred = new ArrayList<>();
        SuperColliderOsc.Reply unrelated =
                new SuperColliderOsc.Reply("/status", null, null, null);
        AtomicInteger polls = new AtomicInteger();

        boolean completed = SuperColliderBridge.pumpUntil(waiter, 100,
                () -> {
                    if (polls.getAndIncrement() == 0) return unrelated;
                    waiter.countDown();
                    return null;
                },
                deferred::add);

        assertTrue(completed);
        assertEquals(1, deferred.size());
        assertSame(unrelated, deferred.get(0));
    }

    @Test public void readbackWaitTimesOutWhenNoServerReplyArrives() {
        CountDownLatch waiter = new CountDownLatch(1);
        boolean completed = SuperColliderBridge.pumpUntil(
                waiter, 10, () -> null, reply -> { });
        assertFalse(completed);
        assertEquals(1, waiter.getCount());
    }

    @Test public void rejectsNullOscPacket() {
        assertFalse(SuperColliderBridge.sendPacket(null));
    }

    @Test public void rejectsEmptyOscPacket() {
        assertFalse(SuperColliderBridge.sendPacket(new byte[0]));
    }

    @Test public void rejectsPacketLargerThanUdpDatagramLimit() {
        assertFalse(SuperColliderBridge.sendPacket(new byte[65508]));
    }
}
