package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioInputRingBufferTest {
    @Test public void convertsPcm16AndReadsInOrder() {
        AudioInputRingBuffer ring = new AudioInputRingBuffer(8);
        ring.offer(new short[]{0, 16384, -16384, 32767}, 4);
        float[] out = new float[4];
        ring.read(out, out.length);
        assertArrayEquals(new float[]{0f, 0.5f, -0.5f, 32767f / 32768f}, out, 0.00001f);
        assertEquals(0, ring.availableSamples());
    }

    @Test public void wrapsAcrossEndOfRing() {
        AudioInputRingBuffer ring = new AudioInputRingBuffer(4);
        ring.offer(new short[]{1000, 2000, 3000}, 3);
        float[] first = new float[2];
        ring.read(first, 2);
        ring.offer(new short[]{4000, 5000, 6000}, 3);
        float[] rest = new float[4];
        ring.read(rest, 4);
        assertArrayEquals(new float[]{3000f / 32768f, 4000f / 32768f,
                5000f / 32768f, 6000f / 32768f}, rest, 0.00001f);
    }

    @Test public void overflowDropsNewestSamplesWithoutOverwritingUnreadSlots() {
        AudioInputRingBuffer ring = new AudioInputRingBuffer(4);
        ring.offer(new short[]{1000, 2000, 3000, 4000, 5000, 6000}, 6);
        assertEquals(4, ring.availableSamples());
        float[] out = new float[4];
        ring.read(out, 4);
        assertArrayEquals(new float[]{1000f / 32768f, 2000f / 32768f,
                3000f / 32768f, 4000f / 32768f}, out, 0.00001f);
        assertEquals(0, ring.availableSamples());
    }

    @Test public void underflowZeroFillsAndDoesNotReturnStaleSamples() {
        AudioInputRingBuffer ring = new AudioInputRingBuffer(4);
        ring.offer(new short[]{12000, -12000}, 2);
        float[] out = new float[4];
        ring.read(out, 4);
        assertArrayEquals(new float[]{12000f / 32768f, -12000f / 32768f, 0f, 0f},
                out, 0.00001f);
        ring.read(out, 4);
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, out, 0f);
    }

    @Test public void nullAndEmptyBatchesAreIgnored() {
        AudioInputRingBuffer ring = new AudioInputRingBuffer(4);
        ring.offer(null, 3);
        ring.offer(new short[]{1, 2}, 0);
        assertEquals(0, ring.availableSamples());
    }
    @Test(timeout = 15000) public void concurrentProducerAndConsumerPreserveSampleOrder() throws Exception {
        final int total = 50000;
        final AudioInputRingBuffer ring = new AudioInputRingBuffer(32);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread producer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 1; i <= total && !stop.get(); i++) {
                        while (!stop.get() && ring.availableSamples() >= 32) Thread.yield();
                        if (!stop.get()) ring.offer(new short[]{(short)(i % 30000 + 1)}, 1);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    stop.set(true);
                }
            }
        }, "adc-ring-producer-test");
        Thread consumer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    float[] sample = new float[1];
                    for (int i = 1; i <= total && !stop.get(); i++) {
                        while (!stop.get() && ring.availableSamples() == 0) Thread.yield();
                        if (stop.get()) break;
                        ring.read(sample, 1);
                        assertEquals((i % 30000 + 1) / 32768f, sample[0], 0.000001f);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    stop.set(true);
                }
            }
        }, "adc-ring-consumer-test");
        // Daemon workers ensure a failed assertion or interrupted test cannot
        // strand the Gradle test JVM if a worker regresses into a busy wait.
        producer.setDaemon(true);
        consumer.setDaemon(true);
        producer.start();
        consumer.start();
        try {
            producer.join(3000);
            consumer.join(3000);
        } catch (InterruptedException interrupted) {
            stop.set(true);
            producer.interrupt();
            consumer.interrupt();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (producer.isAlive() || consumer.isAlive()) {
            stop.set(true);
            producer.interrupt();
            consumer.interrupt();
            producer.join(250);
            consumer.join(250);
            fail("SPSC stress workers did not terminate");
        }
        if (failure.get() != null) throw new AssertionError("SPSC stress failed", failure.get());
        assertEquals(0, ring.availableSamples());
    }

}
