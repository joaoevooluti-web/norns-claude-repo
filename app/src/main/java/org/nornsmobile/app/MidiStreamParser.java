package org.nornsmobile.app;

import java.io.ByteArrayOutputStream;

/** Stateful MIDI byte-stream parser with running status, fragmented packets and realtime interleaving. */
final class MidiStreamParser {
    interface Listener {
        void onMessage(int status, int data1, int data2);
        default void onSystemExclusive(byte[] data) {}
    }

    private static final int MAX_SYSEX_BYTES = 65536;

    private int runningStatus;
    private int currentStatus;
    private int expectedData;
    private int dataCount;
    private int data1;
    private boolean inSysEx;
    private boolean discardSysEx;
    private ByteArrayOutputStream sysEx;

    synchronized void reset() {
        runningStatus = currentStatus = expectedData = dataCount = data1 = 0;
        inSysEx = false;
        discardSysEx = false;
        sysEx = null;
    }

    synchronized void feed(byte[] data, int offset, int count, Listener listener) {
        if (data == null || listener == null || offset < 0 || count < 0
                || offset > data.length - count) return;
        int end = offset + count;
        for (int i = offset; i < end; i++) {
            int value = data[i] & 0xff;
            if (value >= 0xf8) {
                listener.onMessage(value, 0, 0); // realtime may interleave any message
                continue;
            }
            if (inSysEx) {
                if (value == 0xf7) {
                    if (!discardSysEx && sysEx != null) {
                        sysEx.write(value);
                        listener.onSystemExclusive(sysEx.toByteArray());
                    }
                    inSysEx = false;
                    discardSysEx = false;
                    sysEx = null;
                } else if ((value & 0x80) != 0) {
                    inSysEx = false;
                    discardSysEx = false;
                    sysEx = null;
                    acceptStatus(value);
                } else if (!discardSysEx && sysEx != null) {
                    if (sysEx.size() >= MAX_SYSEX_BYTES - 1) {
                        discardSysEx = true;
                        sysEx = null;
                    } else {
                        sysEx.write(value);
                    }
                }
                continue;
            }
            if ((value & 0x80) != 0) {
                acceptStatus(value);
                if (expectedData == 0 && currentStatus != 0) {
                    listener.onMessage(currentStatus, 0, 0);
                    currentStatus = 0;
                }
                continue;
            }
            if (currentStatus == 0) {
                if (runningStatus == 0) continue;
                currentStatus = runningStatus;
                expectedData = dataLength(currentStatus);
                dataCount = 0;
            }
            if (dataCount == 0) data1 = value;
            dataCount++;
            if (dataCount >= expectedData) {
                int status = currentStatus;
                int first = data1;
                int second = expectedData == 2 ? value : 0;
                listener.onMessage(status, first, second);
                currentStatus = 0;
                dataCount = 0;
                data1 = 0;
                if (status < 0xf0) {
                    // next data byte may use MIDI running status
                    expectedData = dataLength(status);
                } else {
                    expectedData = 0;
                }
            }
        }
    }

    private void acceptStatus(int status) {
        dataCount = 0;
        data1 = 0;
        currentStatus = 0;
        if (status == 0xf0) {
            inSysEx = true;
            discardSysEx = false;
            sysEx = new ByteArrayOutputStream();
            sysEx.write(status);
            runningStatus = 0;
            expectedData = 0;
        } else if (status == 0xf7) {
            inSysEx = false;
            runningStatus = 0;
            expectedData = 0;
        } else if (status >= 0xf0) {
            runningStatus = 0;
            expectedData = dataLength(status);
            currentStatus = status;
        } else {
            runningStatus = status;
            expectedData = dataLength(status);
            currentStatus = status;
        }
    }

    private static int dataLength(int status) {
        if (status >= 0xf0) {
            if (status == 0xf1 || status == 0xf3) return 1;
            if (status == 0xf2) return 2;
            return 0;
        }
        int kind = status & 0xf0;
        return kind == 0xc0 || kind == 0xd0 ? 1 : 2;
    }
}
