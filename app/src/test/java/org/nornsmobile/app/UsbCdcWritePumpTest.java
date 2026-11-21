package org.nornsmobile.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class UsbCdcWritePumpTest {
    @Test public void partialTransfersPreserveOffsetsAndSplitLargePayloads() {
        final List<Integer> offsets = new ArrayList<Integer>();
        final List<Integer> lengths = new ArrayList<Integer>();
        byte[] payload = new byte[40000];
        assertTrue(UsbCdcWritePump.write(payload, (offset, length) -> {
            offsets.add(offset); lengths.add(length);
            return Math.min(length, 7000);
        }, () -> true));
        assertEquals(Arrays.asList(0, 7000, 14000, 21000, 28000, 35000), offsets);
        assertEquals(Arrays.asList(16384, 16384, 16384, 16384, 12000, 5000), lengths);
    }

    @Test public void transientZeroProgressBackpressureIsRetriedWithoutLosingBytes() {
        final int[] calls = {0};
        final int[] lastOffset = {-1};
        assertTrue(UsbCdcWritePump.write(new byte[32], (offset, length) -> {
            calls[0]++;
            lastOffset[0] = offset;
            return calls[0] == 1 || calls[0] == 2 ? 0 : length;
        }, () -> true));
        assertEquals(3, calls[0]);
        assertEquals(0, lastOffset[0]);
    }

    @Test public void persistentBackpressureIsBoundedAndFails() {
        final int[] calls = {0};
        assertFalse(UsbCdcWritePump.write(new byte[8], (offset, length) -> {
            calls[0]++; return 0;
        }, () -> true));
        assertEquals(3, calls[0]);
    }

    @Test public void transferFailureAndOversizedTransferAreRejected() {
        assertFalse(UsbCdcWritePump.write(new byte[8], (offset, length) -> -1, () -> true));
        assertFalse(UsbCdcWritePump.write(new byte[8], (offset, length) -> length + 1, () -> true));
        assertFalse(UsbCdcWritePump.write(new byte[65537], (offset, length) -> length, () -> true));
    }

    @Test public void closeBetweenChunksStopsFurtherUsbWrites() {
        final AtomicBoolean open = new AtomicBoolean(true);
        final int[] calls = {0};
        assertFalse(UsbCdcWritePump.write(new byte[40000], (offset, length) -> {
            calls[0]++;
            open.set(false);
            return Math.min(length, 1000);
        }, open::get));
        assertEquals(1, calls[0]);
    }

    @Test public void invalidInputsAreRejectedBeforeCallingTransport() {
        final int[] calls = {0};
        assertFalse(UsbCdcWritePump.write(null, (offset, length) -> { calls[0]++; return length; }, () -> true));
        assertFalse(UsbCdcWritePump.write(new byte[0], (offset, length) -> { calls[0]++; return length; }, () -> true));
        assertFalse(UsbCdcWritePump.write(new byte[1], null, () -> true));
        assertFalse(UsbCdcWritePump.write(new byte[1], (offset, length) -> { calls[0]++; return length; }, null));
        assertEquals(0, calls[0]);
    }
}
