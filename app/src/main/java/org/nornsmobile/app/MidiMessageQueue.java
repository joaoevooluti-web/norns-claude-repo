package org.nornsmobile.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded FIFO for MIDI messages emitted while Android is asynchronously opening a port. */
final class MidiMessageQueue {
    private static final int MIDI_CHANNELS = 16;
    private static final int PANIC_MESSAGES_PER_CHANNEL = 2;
    private static final int PANIC_MESSAGE_COUNT = MIDI_CHANNELS * PANIC_MESSAGES_PER_CHANNEL;

    private final int capacity;
    private final ArrayDeque<byte[]> messages = new ArrayDeque<>();
    private int droppedCount;

    MidiMessageQueue(int capacity) {
        if (capacity < PANIC_MESSAGE_COUNT + 1) {
            throw new IllegalArgumentException("capacity must leave room for MIDI panic messages");
        }
        this.capacity = capacity;
    }

    /**
     * Copies the message. Ordinary overflow drops the newest message. If a Note Off
     * would overflow the queue, discard the incomplete opening burst and replace it
     * with sustain-off + all-notes-off for every channel, followed by that Note Off.
     * This bounds memory while preferring silence over potentially stuck notes.
     */
    boolean offer(byte[] message) {
        if (message == null || message.length == 0) return false;
        byte[] copy = message.clone();
        if (messages.size() >= capacity) {
            if (!isNoteOff(copy)) {
                droppedCount++;
                return false;
            }

            droppedCount += messages.size();
            messages.clear();
            for (int channel = 0; channel < MIDI_CHANNELS; channel++) {
                int controlStatus = 0xB0 | channel;
                messages.addLast(new byte[]{(byte) controlStatus, 64, 0});
                messages.addLast(new byte[]{(byte) controlStatus, 123, 0});
            }
            messages.addLast(copy);
            return true;
        }
        messages.addLast(copy);
        return true;
    }

    private static boolean isNoteOff(byte[] message) {
        if (message.length < 3) return false;
        int status = message[0] & 0xFF;
        int kind = status & 0xF0;
        return kind == 0x80 || (kind == 0x90 && (message[2] & 0x7F) == 0);
    }

    List<byte[]> drain() {
        List<byte[]> drained = new ArrayList<>(messages.size());
        while (!messages.isEmpty()) drained.add(messages.removeFirst());
        return drained;
    }

    int clear() {
        int discarded = messages.size();
        messages.clear();
        return discarded;
    }

    int size() { return messages.size(); }
    int getDroppedCount() { return droppedCount; }
}
