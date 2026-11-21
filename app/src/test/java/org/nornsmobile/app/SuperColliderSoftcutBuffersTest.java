package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SuperColliderSoftcutBuffersTest {
    @Test public void recorderRecoveryIsRefusedUntilNativeServerIsReady() {
        SuperColliderSoftcutBuffers.reset();
        assertFalse(SuperColliderSoftcutBuffers.isRecording(1));
        assertFalse(SuperColliderSoftcutBuffers.isRecording(2));
        assertFalse(SuperColliderSoftcutBuffers.reanchorRecordingCursor(1, 101, 1.0));
        assertFalse(SuperColliderSoftcutBuffers.reanchorRecordingCursor(2, 102, 2.0));
        assertFalse(SuperColliderSoftcutBuffers.reanchorRecordingCursor(1, 101, Double.NaN));
        assertFalse(SuperColliderSoftcutBuffers.isReanchorPending(1));
        assertFalse(SuperColliderSoftcutBuffers.isReanchorPending(2));
    }

    @Test public void tracksLuaBufferFreeAndReallocation() {
        SuperColliderSoftcutBuffers.reset();
        assertFalse(SuperColliderSoftcutBuffers.needsFullSnapshot(1, 1));
        SuperColliderSoftcutBuffers.noteNativeBufferFreed(20001);
        assertTrue(SuperColliderSoftcutBuffers.needsFullSnapshot(1, 1));
        SuperColliderSoftcutBuffers.noteNativeBufferAllocated(20001, 64);
        assertFalse(SuperColliderSoftcutBuffers.needsFullSnapshot(1, 64));
        assertTrue(SuperColliderSoftcutBuffers.needsFullSnapshot(1, 65));
        assertTrue(SuperColliderSoftcutBuffers.needsFullSnapshot(2, 64));
    }

    @Test public void nativeChannelBuffersHaveDistinctStableIds() {
        int ch1 = SuperColliderSoftcutBuffers.nativeBufferIdForChannel(1);
        int ch2 = SuperColliderSoftcutBuffers.nativeBufferIdForChannel(2);
        assertEquals(20001, ch1);
        assertEquals(20002, ch2);
        assertNotEquals(ch1, ch2);
        assertEquals(-1, SuperColliderSoftcutBuffers.nativeBufferIdForChannel(0));
        assertEquals(-1, SuperColliderSoftcutBuffers.nativeBufferIdForChannel(3));
    }

    @Test public void nativeIsolationProbePatternsAreDistinctAndDeterministic() {
        float[] ch1 = SuperColliderSoftcutBuffers.createIsolationProbePattern(1);
        float[] ch2 = SuperColliderSoftcutBuffers.createIsolationProbePattern(2);
        assertEquals(64, ch1.length);
        assertEquals(64, ch2.length);
        assertEquals(SuperColliderSoftcutBuffers.sampleFingerprint(ch1),
                SuperColliderSoftcutBuffers.sampleFingerprint(
                        SuperColliderSoftcutBuffers.createIsolationProbePattern(1)));
        assertNotEquals(SuperColliderSoftcutBuffers.sampleFingerprint(ch1),
                SuperColliderSoftcutBuffers.sampleFingerprint(ch2));
        for (float sample : ch1) assertTrue(sample > 0.0f);
        for (float sample : ch2) assertTrue(sample < 0.0f);
        assertEquals(0, SuperColliderSoftcutBuffers.createIsolationProbePattern(0).length);
    }

    @Test public void bufWrIsolationPatternsMatchTheTwoServerWriterValues() {
        float[] ch1 = SuperColliderSoftcutBuffers.createBufWrIsolationPattern(1);
        float[] ch2 = SuperColliderSoftcutBuffers.createBufWrIsolationPattern(2);
        assertEquals(64, ch1.length);
        assertEquals(64, ch2.length);
        for (float sample : ch1) assertEquals(0.25f, sample, 0.0f);
        for (float sample : ch2) assertEquals(-0.375f, sample, 0.0f);
        assertNotEquals(SuperColliderSoftcutBuffers.sampleFingerprint(ch1),
                SuperColliderSoftcutBuffers.sampleFingerprint(ch2));
        assertEquals(0, SuperColliderSoftcutBuffers.createBufWrIsolationPattern(0).length);
    }

    @Test public void recordingReadbackIsClampedToTheConfiguredWindow() {
        int[] ch1 = SuperColliderSoftcutBuffers.intersectRecordingWindow(
                1440000, 72192, 1440000, 1512000);
        assertArrayEquals(new int[]{1440000, 72000}, ch1);

        int[] ch2 = SuperColliderSoftcutBuffers.intersectRecordingWindow(
                1439808, 72192, 1439808, 1512000);
        assertArrayEquals(new int[]{1439808, 72192}, ch2);
    }

    @Test public void recordingReadbackUsesOnlyTheOverlap() {
        assertArrayEquals(new int[]{200, 300},
                SuperColliderSoftcutBuffers.intersectRecordingWindow(100, 400, 200, 500));
        assertArrayEquals(new int[]{300, 200},
                SuperColliderSoftcutBuffers.intersectRecordingWindow(300, 400, 100, 500));
    }

    @Test public void recordingReadbackRejectsAnEmptyOrUnknownWindow() {
        assertArrayEquals(new int[]{-1, 0},
                SuperColliderSoftcutBuffers.intersectRecordingWindow(100, 20, 200, 300));
        assertArrayEquals(new int[]{-1, 0},
                SuperColliderSoftcutBuffers.intersectRecordingWindow(100, 20, -1, -1));
        assertArrayEquals(new int[]{-1, 0},
                SuperColliderSoftcutBuffers.intersectRecordingWindow(-1, 20, 0, 100));
    }

    @Test public void readbackFingerprintUsesActualFloatPayload() {
        float[] first = new float[]{0.0f, 0.25f, -0.5f, 0.75f};
        float[] same = new float[]{0.0f, 0.25f, -0.5f, 0.75f};
        float[] different = new float[]{0.0f, -0.25f, 0.5f, -0.75f};
        assertEquals(SuperColliderSoftcutBuffers.sampleFingerprint(first),
                SuperColliderSoftcutBuffers.sampleFingerprint(same));
        assertNotEquals(SuperColliderSoftcutBuffers.sampleFingerprint(first),
                SuperColliderSoftcutBuffers.sampleFingerprint(different));
        assertNotEquals(SuperColliderSoftcutBuffers.sampleFingerprint(first),
                SuperColliderSoftcutBuffers.sampleFingerprint(new float[]{0.0f, 0.25f, -0.5f}));
    }

    @Test public void distinctChannelPatternsProduceDistinctReadbackFingerprints() {
        // A channel-isolation validation must use different source patterns; a shared
        // mono input recorded simultaneously is not evidence of shared buffer storage.
        float[] ch1 = new float[]{0.0f, 0.25f, 0.5f, 0.75f};
        float[] ch2 = new float[]{0.0f, -0.25f, -0.5f, -0.75f};
        assertNotEquals(SuperColliderSoftcutBuffers.sampleFingerprint(ch1),
                SuperColliderSoftcutBuffers.sampleFingerprint(ch2));
    }

    @Test public void stopIsQueuedOnlyOnceWhilePending() {
        assertTrue(SuperColliderSoftcutBuffers.shouldQueueStop(true, false, false));
        assertTrue(SuperColliderSoftcutBuffers.shouldQueueStop(false, true, false));
        assertFalse(SuperColliderSoftcutBuffers.shouldQueueStop(true, false, true));
        assertFalse(SuperColliderSoftcutBuffers.shouldQueueStop(false, true, true));
        assertFalse(SuperColliderSoftcutBuffers.shouldQueueStop(false, false, false));
    }

    @Test public void confirmedReadbackAdvancesInBoundedChunks() {
        assertEquals(750, SuperColliderSoftcutBuffers.nextReadbackOffset(0, 24000));
        assertEquals(1500, SuperColliderSoftcutBuffers.nextReadbackOffset(750, 24000));
        assertEquals(24000, SuperColliderSoftcutBuffers.nextReadbackOffset(23250, 24000));
        assertEquals(-1, SuperColliderSoftcutBuffers.nextReadbackOffset(24000, 24000));
        assertEquals(-1, SuperColliderSoftcutBuffers.nextReadbackOffset(-1, 24000));
    }
}
