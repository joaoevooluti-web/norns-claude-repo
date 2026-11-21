package org.nornsmobile.app;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class MidiMessageQueueTest {
    private static final int CAPACITY = 64;

    @Test public void drainsMessagesInFifoOrder() {
        MidiMessageQueue queue = new MidiMessageQueue(CAPACITY);
        assertTrue(queue.offer(new byte[]{(byte) 0x90, 60, 100}));
        assertTrue(queue.offer(new byte[]{(byte) 0x80, 60, 0}));
        List<byte[]> drained = queue.drain();
        assertEquals(2, drained.size());
        assertArrayEquals(new byte[]{(byte) 0x90, 60, 100}, drained.get(0));
        assertArrayEquals(new byte[]{(byte) 0x80, 60, 0}, drained.get(1));
        assertEquals(0, queue.size());
    }

    @Test public void copiesMessagesWhenQueued() {
        MidiMessageQueue queue = new MidiMessageQueue(CAPACITY);
        byte[] source = new byte[]{(byte) 0x90, 64, 90};
        assertTrue(queue.offer(source));
        source[1] = 1;
        assertArrayEquals(new byte[]{(byte) 0x90, 64, 90}, queue.drain().get(0));
    }

    @Test public void boundedQueueDropsNewestOrdinaryMessageWithoutReordering() {
        MidiMessageQueue queue = new MidiMessageQueue(33);
        for (int i = 0; i < 33; i++) assertTrue(queue.offer(new byte[]{1, (byte) i}));
        assertFalse(queue.offer(new byte[]{2, 99}));
        assertEquals(1, queue.getDroppedCount());
        List<byte[]> drained = queue.drain();
        assertEquals(33, drained.size());
        assertArrayEquals(new byte[]{1, 0}, drained.get(0));
        assertArrayEquals(new byte[]{1, 32}, drained.get(32));
    }

    @Test public void noteOffOverflowReplacesBurstWithPanicAndPreservesRelease() {
        MidiMessageQueue queue = new MidiMessageQueue(33);
        for (int i = 0; i < 33; i++) assertTrue(queue.offer(new byte[]{(byte) 0x90, (byte) i, 100}));

        byte[] noteOff = new byte[]{(byte) 0x80, 60, 0};
        assertTrue(queue.offer(noteOff));

        List<byte[]> drained = queue.drain();
        assertEquals(33, drained.size());
        for (int channel = 0; channel < 16; channel++) {
            byte[] sustainOff = drained.get(channel * 2);
            byte[] allNotesOff = drained.get(channel * 2 + 1);
            assertArrayEquals(new byte[]{(byte) (0xB0 | channel), 64, 0}, sustainOff);
            assertArrayEquals(new byte[]{(byte) (0xB0 | channel), 123, 0}, allNotesOff);
        }
        assertArrayEquals(noteOff, drained.get(32));
        assertEquals(33, queue.getDroppedCount());
    }

    @Test public void zeroVelocityNoteOnIsTreatedAsNoteOffForOverflowSafety() {
        MidiMessageQueue queue = new MidiMessageQueue(33);
        for (int i = 0; i < 33; i++) assertTrue(queue.offer(new byte[]{1, (byte) i}));
        byte[] noteOff = new byte[]{(byte) 0x92, 60, 0};
        assertTrue(queue.offer(noteOff));
        List<byte[]> drained = queue.drain();
        assertEquals(33, drained.size());
        assertArrayEquals(noteOff, drained.get(32));
    }

    @Test public void clearDiscardsQueuedMessages() {
        MidiMessageQueue queue = new MidiMessageQueue(CAPACITY);
        queue.offer(new byte[]{1});
        queue.offer(new byte[]{2});
        assertEquals(2, queue.clear());
        assertEquals(0, queue.size());
        assertTrue(queue.drain().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCapacityTooSmallForPanicRecovery() {
        new MidiMessageQueue(32);
    }
}
