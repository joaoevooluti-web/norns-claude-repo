package org.nornsmobile.app;

/**
 * Lock-free single-producer/single-consumer PCM ring.
 * The producer only writes free slots and advances writeIndex; the consumer
 * alone advances readIndex. A full ring drops incoming samples rather than
 * overwriting slots that the consumer may currently be reading.
 */
final class AudioInputRingBuffer {
    private final float[] samples;
    private final int capacity;
    private volatile long writeIndex;
    private volatile long readIndex;

    AudioInputRingBuffer(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity must be >= 2");
        this.capacity = capacity;
        this.samples = new float[capacity];
    }

    void offer(short[] source, int count) {
        if (source == null || count <= 0) return;
        long write = writeIndex;
        long read = readIndex;
        long occupied = write - read;
        if (occupied < 0 || occupied >= capacity) return;
        int accepted = (int)Math.min(Math.min((long)count, source.length), capacity - occupied);
        for (int i = 0; i < accepted; i++) {
            samples[(int)((write + i) % capacity)] = source[i] / 32768f;
        }
        // Publish only after all accepted samples in this batch are visible.
        writeIndex = write + accepted;
    }

    void read(float[] destination, int count) {
        if (destination == null || count <= 0) return;
        int length = Math.min(count, destination.length);
        long read = readIndex;
        long write = writeIndex;
        long available = write - read;
        if (available < 0) available = 0;
        if (available > capacity) available = capacity;
        int copied = (int)Math.min(length, available);
        for (int i = 0; i < copied; i++) {
            destination[i] = samples[(int)((read + i) % capacity)];
        }
        for (int i = copied; i < length; i++) destination[i] = 0f;
        readIndex = read + copied;
    }

    int availableSamples() {
        long available = writeIndex - readIndex;
        return (int)Math.max(0L, Math.min(capacity, available));
    }
}
