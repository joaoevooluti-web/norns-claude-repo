package org.nornsmobile.app;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class MidiOpenRequestsTest {
    private static final int DEVICE = 17;
    private static final int CAPACITY = 64;

    @Test public void openCallbackFlushesQueuedMessagesInFifoOrder() {
        MidiOpenRequests requests = new MidiOpenRequests();
        long token = requests.begin(DEVICE, CAPACITY);
        assertTrue(token > 0);
        assertTrue(requests.offer(DEVICE, new byte[]{(byte) 0x90, 60, 100}));
        assertTrue(requests.offer(DEVICE, new byte[]{(byte) 0x80, 60, 0}));
        MidiOpenRequests.Completed completed = requests.complete(DEVICE, token);
        assertNotNull(completed);
        assertEquals(2, completed.messages.size());
        assertArrayEquals(new byte[]{(byte) 0x90, 60, 100}, completed.messages.get(0));
        assertArrayEquals(new byte[]{(byte) 0x80, 60, 0}, completed.messages.get(1));
        assertFalse(requests.isOpening(DEVICE));
        assertNull(requests.complete(DEVICE, token));
    }

    @Test public void duplicateOpenDoesNotReplacePendingQueueOrToken() {
        MidiOpenRequests requests = new MidiOpenRequests();
        long token = requests.begin(DEVICE, CAPACITY);
        assertEquals(0L, requests.begin(DEVICE, CAPACITY));
        assertTrue(requests.isCurrent(DEVICE, token));
        assertTrue(requests.offer(DEVICE, new byte[]{(byte) 0x90, 64, 90}));
        assertEquals(1, requests.complete(DEVICE, token).messages.size());
    }

    @Test public void closeBeforeCallbackMakesLateCallbackStale() {
        MidiOpenRequests requests = new MidiOpenRequests();
        long token = requests.begin(DEVICE, CAPACITY);
        requests.offer(DEVICE, new byte[]{(byte) 0x90, 60, 100});
        assertEquals(1, requests.cancel(DEVICE));
        assertFalse(requests.isCurrent(DEVICE, token));
        assertNull(requests.complete(DEVICE, token));
        assertEquals(0, requests.size());
    }

    @Test public void oldCallbackCannotFlushMessagesFromReopenedDevice() {
        MidiOpenRequests requests = new MidiOpenRequests();
        long oldToken = requests.begin(DEVICE, CAPACITY);
        requests.offer(DEVICE, new byte[]{(byte) 0x90, 60, 100});
        requests.cancel(DEVICE);
        long newToken = requests.begin(DEVICE, CAPACITY);
        assertNotEquals(oldToken, newToken);
        requests.offer(DEVICE, new byte[]{(byte) 0x90, 67, 100});
        assertNull(requests.complete(DEVICE, oldToken));
        assertTrue(requests.isCurrent(DEVICE, newToken));
        List<byte[]> messages = requests.complete(DEVICE, newToken).messages;
        assertEquals(1, messages.size());
        assertArrayEquals(new byte[]{(byte) 0x90, 67, 100}, messages.get(0));
    }

    @Test public void failedOpenClearsQueueAndRejectsLateSuccess() {
        MidiOpenRequests requests = new MidiOpenRequests();
        long token = requests.begin(DEVICE, CAPACITY);
        requests.offer(DEVICE, new byte[]{(byte) 0x90, 60, 100});
        assertEquals(1, requests.fail(DEVICE, token));
        assertFalse(requests.isOpening(DEVICE));
        assertNull(requests.complete(DEVICE, token));
        assertEquals(-1, requests.fail(DEVICE, token));
    }

    @Test public void closeAllCancelsEveryPendingOpen() {
        MidiOpenRequests requests = new MidiOpenRequests();
        long first = requests.begin(1, CAPACITY);
        long second = requests.begin(2, CAPACITY);
        requests.offer(1, new byte[]{1, 2, 3});
        requests.offer(2, new byte[]{4, 5, 6});
        assertEquals(2, requests.cancelAll());
        assertFalse(requests.isCurrent(1, first));
        assertFalse(requests.isCurrent(2, second));
        assertEquals(0, requests.size());
    }
}
