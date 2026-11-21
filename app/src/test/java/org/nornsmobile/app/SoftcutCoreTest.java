package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SoftcutCoreTest {
    @Test public void bufferLengthTracksLazyAllocationAndGrowth() {
        SoftcutCore core = new SoftcutCore(4);
        assertEquals(0, core.bufferLength(1));
        core.bufferRead(1, new float[]{0.1f, 0.2f, 0.3f}, 4, 4, 0, 0, -1);
        assertEquals(3, core.bufferLength(1));
        core.bufferRead(1, new float[]{0.4f, 0.5f, 0.6f, 0.7f}, 4, 4, 0, 1, -1);
        assertTrue(core.bufferLength(1) >= 5);
    }

    @Test public void recordingPreparationReservesRequestedBufferCapacity() {
        SoftcutCore core = new SoftcutCore(48000);
        int capacity = core.ensureBufferLength(2, 48001);
        assertTrue(capacity >= 48001);
        assertEquals(capacity, core.bufferLength(2));
        float[] snapshot = core.snapshotBuffer(2, 0, -1);
        assertEquals(capacity, snapshot.length);
        for (float sample : snapshot) assertEquals(0f, sample, 0f);
    }

    @Test public void continuousRecordingAdvancesJavaCursorAndWritesSequentialFrames() {
        SoftcutCore core = new SoftcutCore(48000);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        float[] input = new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        core.render(new float[8], input.length, 48000, input);
        assertEquals(4.0 / 48000.0, core.position(1), 1e-9);
        assertArrayEquals(input, core.snapshotBuffer(1, 0, 4.0 / 48000.0), 0.00001f);
    }

    @Test public void reverseRecordingAdvancesCursorBackwardAndWritesInReverseOrder() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0f, 0f, 0f, 0f}, 4, 4, 0, 0, -1);
        core.command(1, "loop", 1);
        core.command(1, "loop_start", 0);
        core.command(1, "loop_end", 1.0);
        core.command(1, "position", 0.5);
        core.command(1, "rate", -1);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        core.render(new float[4], 2, 4, new float[]{0.25f, 0.5f});
        assertEquals(0.0, core.position(1), 0.0001);
        assertArrayEquals(new float[]{0f, 0.5f, 0.25f, 0f},
                core.snapshotBuffer(1, 0, -1), 0.0001f);
    }

    @Test public void nativeReadbackWritesExactBufferRegionAndGrowsBuffer() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.1f, 0.2f}, 4, 4, 0, 0, -1);
        core.writeBufferRegion(1, 3, new float[]{-0.5f, 0.75f, 0.25f});
        assertArrayEquals(new float[]{0.1f, 0.2f, 0f, -0.5f, 0.75f, 0.25f},
                java.util.Arrays.copyOf(core.snapshotBuffer(1, 0, -1), 6), 0.00001f);
    }

    @Test public void readbackKeepsTwoNativeMirrorBuffersIndependent() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.1f, 0.2f}, 4, 4, 0, 0, -1);
        core.bufferRead(2, new float[]{0.7f, 0.8f}, 4, 4, 0, 0, -1);
        core.writeBufferRegion(1, 1, new float[]{0.3f, 0.4f});
        core.writeBufferRegion(2, 0, new float[]{-0.5f, -0.6f});
        assertArrayEquals(new float[]{0.1f, 0.3f, 0.4f},
                core.snapshotBuffer(1, 0, 0.75), 0.00001f);
        assertArrayEquals(new float[]{-0.5f, -0.6f},
                core.snapshotBuffer(2, 0, 0.5), 0.00001f);
    }

    @Test public void zeroDurationReadIsNoOp() {
        SoftcutCore core = new SoftcutCore(48000);
        core.bufferRead(1, new float[]{0.1f, 0.2f, 0.3f}, 48000, 48000, 0, 0, 0);
        assertEquals(0, core.snapshotBuffer(1, 0, -1).length);
    }

    @Test public void zeroDurationClearDoesNotEraseBuffer() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 4, 4, 0, 0, -1);
        core.clearBuffer(1, 0, 0);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f},
                core.snapshotBuffer(1, 0, -1), 0.00001f);
    }

    @Test public void zeroDurationCopyDoesNotCopyEntireRemainder() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 4, 4, 0, 0, -1);
        core.copyBuffer(1, 2, 0, 0, 0, 0, 0, false);
        assertEquals(0, core.snapshotBuffer(2, 0, -1).length);
    }

    @Test public void negativeDurationMeansRemainderOfSource() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 4, 4, 0, 0, -1);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f},
                core.snapshotBuffer(1, 0, -1), 0.00001f);
    }

    @Test public void defaultRecordingBufferGrowsPastTenSeconds() {
        SoftcutCore core = new SoftcutCore(10);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        float[] output = new float[2 * 120];
        float[] input = new float[120];
        for (int i = 0; i < input.length; i++) input[i] = 0.25f;
        core.render(output, 120, 10, input);
        assertTrue("recording buffer should grow beyond its initial 10 seconds",
                core.snapshotBuffer(1, 0, -1).length > 100);
    }

    @Test public void legacyFilterToggleActuallyBypassesPreFilter() {
        SoftcutCore core = new SoftcutCore(48000);
        core.bufferRead(1, new float[]{0.25f, 0.25f, 0.25f, 0.25f}, 48000, 48000, 0, 0, -1);
        core.command(1, "pre_filter_fc", 10);
        core.command(1, "pre_filter_lp", 1);
        core.command(1, "pre_filter_dry", 0);
        core.command(1, "filter", 0);
        core.command(1, "loop_end", 4.0 / 48000.0);
        core.command(1, "play", 1);
        float[] output = new float[8];
        core.render(output, 4, 48000);
        assertEquals((float) (0.25 * Math.sqrt(0.5)), output[0], 0.0001f);
        assertEquals((float) (0.25 * Math.sqrt(0.5)), output[2], 0.0001f);
    }
    @Test public void isolatedReverbSendTracksSoftcutWithoutChangingMasterOwnership() {
        SoftcutCore core = new SoftcutCore(48000);
        core.bufferRead(1, new float[]{0.25f, 0.25f, 0.25f, 0.25f},
                48000, 48000, 0, 0, -1);
        core.command(1, "loop", 1);
        core.command(1, "loop_end", 4.0 / 48000.0);
        core.command(1, "pre_filter_dry", 1);
        core.command(1, "pre_filter_lp", 0);
        core.command(1, "level", 1);
        core.command(1, "play", 1);
        assertTrue(core.setMasterLevel(0.5));

        float[] master = new float[4];
        float[] reverbSend = new float[4];
        core.render(master, 2, 48000, null, null, null, reverbSend);

        assertTrue("Softcut must remain audible through its one master output",
                Math.abs(master[0]) > 0f && Math.abs(master[1]) > 0f);
        assertEquals("isolated send is tapped before the Softcut master gain",
                master[0] * 2f, reverbSend[0], 0.00001f);
        assertEquals(master[1] * 2f, reverbSend[1], 0.00001f);
        assertEquals(master[2] * 2f, reverbSend[2], 0.00001f);
        assertEquals(master[3] * 2f, reverbSend[3], 0.00001f);
    }

    @Test public void cutMasterLevelScalesOnlyFinalStereoOutput() {
        SoftcutCore unity = new SoftcutCore(48000);
        SoftcutCore half = new SoftcutCore(48000);
        float[] source = new float[]{0.25f, 0.25f, 0.25f, 0.25f};
        for (SoftcutCore core : new SoftcutCore[]{unity, half}) {
            core.bufferRead(1, source, 48000, 48000, 0, 0, -1);
            core.command(1, "loop", 1);
            core.command(1, "loop_end", 4.0 / 48000.0);
            core.command(1, "pre_filter_dry", 1);
            core.command(1, "pre_filter_lp", 0);
            core.command(1, "play", 1);
        }
        assertTrue(half.setMasterLevel(0.5));
        float[] full = new float[4];
        float[] reduced = new float[4];
        unity.render(full, 2, 48000);
        half.render(reduced, 2, 48000);
        for (int i = 0; i < full.length; i++) assertEquals(full[i] * 0.5f, reduced[i], 0.00001f);
        assertFalse(half.setMasterLevel(Double.NaN));
        assertFalse(half.setMasterLevel(1.01));
    }

    @Test public void cutMasterGainDoesNotAlterInterVoiceCutBus() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.25f, 0.25f}, 4, 4, 0, 0, -1);
        core.setMasterLevel(0.0);
        core.command(1, "loop_end", 0.5);
        core.command(1, "loop", 1);
        core.command(1, "play", 1);
        core.command(2, "buffer", 2);
        core.command(2, "rec_level", 1);
        core.command(2, "pre_level", 0);
        core.command(2, "rec", 1);
        core.command(1, "cut_level_2", 1);
        float[] output = new float[2];
        core.render(output, 1, 4);
        core.render(output, 1, 4);
        assertArrayEquals(new float[]{0f, 0f}, output, 0f);
        assertEquals(0f, core.snapshotBuffer(2, 0, 0.25)[0], 0.0001f);
        assertEquals(0.25f, core.snapshotBuffer(2, 0.25, 0.25)[0], 0.0001f);
    }

    @Test public void adcMasterLevelScalesInputBeforePerVoiceGain() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(0, "adc_level", 0.5);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        core.render(new float[2], 1, 4, new float[]{0.25f});
        assertEquals(0.125f, core.snapshotBuffer(1, 0, 0.25)[0], 0.0001f);
    }


    @Test public void bufferReadResamplesBetweenSampleRates() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0f, 1f}, 2, 4, 0, 0, -1);
        assertArrayEquals(new float[]{0f, 0.5f, 1f, 1f},
                core.snapshotBuffer(1, 0, -1), 0.0001f);
    }

    @Test public void bufferReadHonorsPreserveAndMixGains() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.25f, 0.25f}, 4, 4, 0, 0, -1);
        core.bufferRead(1, new float[]{0.5f, 0.5f}, 4, 4, 0, 0, -1, 0.5, 0.5);
        assertArrayEquals(new float[]{0.375f, 0.375f},
                core.snapshotBuffer(1, 0, -1), 0.0001f);
    }

    @Test public void bufferCopyCanReverseSamples() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 4, 4, 0, 0, -1);
        core.copyBuffer(1, 2, 0, 0, -1, 0, 0, true);
        assertArrayEquals(new float[]{0.4f, 0.3f, 0.2f, 0.1f},
                core.snapshotBuffer(2, 0, -1), 0.0001f);
    }

    @Test public void voiceSyncCopiesPositionAndAppliesOffset() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "position", 0.5);
        core.voiceSync(2, 1, 0.25);
        assertEquals(0.75, core.position(2), 0.0001);
    }

    @Test public void voiceToVoiceRecordingUsesPreviousAudioBlock() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.25f, 0.25f}, 4, 4, 0, 0, -1);
        core.command(1, "loop_end", 0.5);
        core.command(1, "loop", 1);
        core.command(1, "play", 1);
        core.command(2, "buffer", 2);
        core.command(2, "rec_level", 1);
        core.command(2, "pre_level", 0);
        core.command(2, "rec", 1);
        core.command(1, "cut_level_2", 1);
        float[] output = new float[2];
        core.render(output, 1, 4);
        // Voice 2 advances its recording head on the first block (which has
        // no previous voice audio), then records voice 1's first block at
        // sample 1 on the second render call.
        core.render(output, 1, 4);
        assertEquals(0f, core.snapshotBuffer(2, 0, 0.25)[0], 0.0001f);
        assertEquals(0.25f, core.snapshotBuffer(2, 0.25, 0.25)[0], 0.0001f);
    }

    @Test public void disabledVoiceFreezesPlaybackAndRecording() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "enable", 0);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        core.command(1, "play", 1);
        core.render(new float[2], 1, 4, new float[]{0.75f});
        assertEquals(0.0, core.position(1), 0.0001);
        assertEquals(0, core.snapshotBuffer(1, 0, -1).length);
    }

    @Test public void fadeTimeSmoothsPlayStopTransitions() {
        SoftcutCore core = new SoftcutCore(1000);
        core.bufferRead(1, new float[]{1, 1, 1, 1, 1, 1, 1, 1}, 1000, 1000, 0, 0, -1);
        core.command(1, "loop", 1);
        core.command(1, "loop_end", 0.008);
        core.command(1, "pre_filter_dry", 1);
        core.command(1, "pre_filter_lp", 0);
        core.command(1, "fade_time", 0.01);
        core.command(1, "play", 1);
        core.render(new float[10], 5, 1000);
        core.command(1, "play", 0);
        float[] fadeOut = new float[2];
        core.render(fadeOut, 1, 1000);
        assertTrue("fade-out should not cut the signal abruptly", fadeOut[0] > 0.1f);
        core.command(1, "play", 1);
        float[] fadeIn = new float[2];
        core.render(fadeIn, 1, 1000);
        assertTrue("fade-in should remain continuous", fadeIn[0] > 0.1f);
    }

    @Test public void positiveRecordingOffsetReservesSpaceBeyondLoopEnd() {
        SoftcutCore core = new SoftcutCore(4);
        core.bufferRead(1, new float[]{0.1f, 0.1f}, 4, 4, 0, 0, -1);
        core.command(1, "loop", 1);
        core.command(1, "loop_end", 0.5);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec_offset", 0.5);
        core.command(1, "rec", 1);
        core.render(new float[2], 1, 4, new float[]{0.5f});
        assertTrue(core.bufferLength(1) >= 4);
        assertEquals(0.5f, core.snapshotBuffer(1, 0.5, 0.25)[0], 0.0001f);
        assertEquals(0.25, core.position(1), 0.0001);
    }

    @Test public void negativeRecordingOffsetSkipsOutOfBufferWritesThenWritesInRange() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec_offset", -0.25);
        core.command(1, "rec", 1);
        core.render(new float[2], 1, 4, new float[]{0.5f});
        assertEquals(0f, core.snapshotBuffer(1, 0, 0.25)[0], 0.0001f);
        core.render(new float[2], 1, 4, new float[]{0.75f});
        assertEquals(0.75f, core.snapshotBuffer(1, 0, 0.25)[0], 0.0001f);
        assertEquals(0.5, core.position(1), 0.0001);
    }

    @Test public void sixCutMatrixSendsReachOneExclusiveDestinationWriter() {
        SoftcutCore core = new SoftcutCore(4);
        float[] constant = new float[]{0.1f, 0.1f};
        core.bufferRead(1, constant, 4, 4, 0, 0, -1);
        core.bufferRead(2, constant, 4, 4, 0, 0, -1);
        for (int source = 1; source <= 6; source++) {
            core.command(source, "buffer", source == 6 ? 2 : 1);
            core.command(source, "loop", 1);
            core.command(source, "loop_end", 0.5);
            core.command(source, "pre_filter_dry", 1);
            core.command(source, "pre_filter_lp", 0);
            core.command(source, "play", 1);
            core.command(source, "cut_level_6", 1);
        }
        core.command(6, "rec_level", 1);
        core.command(6, "pre_level", 0);
        core.command(6, "rec", 1);
        float[] output = new float[2];
        core.render(output, 1, 4);
        core.render(output, 1, 4);
        assertEquals(0.6f, core.snapshotBuffer(2, 0.25, 0.25)[0], 0.0001f);
    }

    @Test public void recordingOffsetMovesTheWriteHeadWithoutMovingPlaybackHead() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec_offset", 0.25);
        core.command(1, "rec", 1);
        core.render(new float[2], 1, 4, new float[]{0.5f});
        assertEquals(0f, core.snapshotBuffer(1, 0, 0.25)[0], 0.0001f);
        assertEquals(0.5f, core.snapshotBuffer(1, 0.25, 0.25)[0], 0.0001f);
        assertEquals(0.25, core.position(1), 0.0001);
    }

    @Test public void cachedFilterCoefficientsRemainContinuousAcrossAudioBlocks() {
        SoftcutCore whole = new SoftcutCore(48000);
        SoftcutCore split = new SoftcutCore(48000);
        float[] source = new float[128];
        for (int i = 0; i < source.length; i++) source[i] = (i % 16 < 8) ? 0.5f : -0.5f;
        whole.bufferRead(1, source, 48000, 48000, 0, 0, -1);
        split.bufferRead(1, source, 48000, 48000, 0, 0, -1);
        for (SoftcutCore core : new SoftcutCore[]{whole, split}) {
            core.command(1, "loop", 1);
            core.command(1, "loop_end", source.length / 48000.0);
            core.command(1, "pre_filter_fc", 1800);
            core.command(1, "pre_filter_rq", 0.8);
            core.command(1, "pre_filter_lp", 1);
            core.command(1, "pre_filter_dry", 0);
            core.command(1, "play", 1);
        }
        float[] contiguous = new float[128 * 2];
        float[] first = new float[64 * 2];
        float[] second = new float[64 * 2];
        whole.render(contiguous, 128, 48000);
        split.render(first, 64, 48000);
        split.render(second, 64, 48000);
        for (int i = 0; i < 64; i++) {
            assertEquals(contiguous[i * 2], first[i * 2], 0.000001f);
            assertEquals(contiguous[(i + 64) * 2], second[i * 2], 0.000001f);
        }
    }

    @Test public void filterCoefficientCacheRefreshesWhenCutoffChanges() {
        SoftcutCore low = new SoftcutCore(48000);
        SoftcutCore high = new SoftcutCore(48000);
        float[] source = new float[256];
        java.util.Arrays.fill(source, 0.5f);
        for (SoftcutCore core : new SoftcutCore[]{low, high}) {
            core.bufferRead(1, source, 48000, 48000, 0, 0, -1);
            core.command(1, "loop", 1);
            core.command(1, "loop_end", source.length / 48000.0);
            core.command(1, "pre_filter_lp", 1);
            core.command(1, "pre_filter_dry", 0);
            core.command(1, "play", 1);
        }
        low.command(1, "pre_filter_fc", 300);
        high.command(1, "pre_filter_fc", 9000);
        float[] lowOut = new float[128];
        float[] highOut = new float[128];
        low.render(lowOut, 64, 48000);
        high.render(highOut, 64, 48000);
        assertNotEquals(lowOut[40], highOut[40], 0.00001f);
    }


    @Test public void postFilterCutoffModulationTracksPlaybackRate() {
        SoftcutCore fixed = new SoftcutCore(48000);
        SoftcutCore modulated = new SoftcutCore(48000);
        float[] source = new float[512];
        for (int i = 0; i < source.length; i++) source[i] = (i % 8 < 4) ? 0.5f : -0.5f;
        for (SoftcutCore core : new SoftcutCore[]{fixed, modulated}) {
            core.bufferRead(1, source, 48000, 48000, 0, 0, -1);
            core.command(1, "loop", 1);
            core.command(1, "loop_end", source.length / 48000.0);
            core.command(1, "rate", 0.5);
            core.command(1, "pre_filter_dry", 1);
            core.command(1, "pre_filter_lp", 0);
            core.command(1, "post_filter_fc", 4000);
            core.command(1, "post_filter_lp", 1);
            core.command(1, "post_filter_dry", 0);
            core.command(1, "play", 1);
        }
        modulated.command(1, "post_filter_fc_mod", 1);
        float[] fixedOut = new float[256];
        float[] modulatedOut = new float[256];
        fixed.render(fixedOut, 128, 48000);
        modulated.render(modulatedOut, 128, 48000);
        assertNotEquals("post-filter fc_mod should scale cutoff with playback rate",
                fixedOut[80], modulatedOut[80], 0.00001f);
    }

@Test public void engineSendRecordsOnlyThePreSoftcutEngineSource() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "level_slew_time", 0);
        core.command(1, "engine_level", 1);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        float[] engine = new float[]{0.25f, -0.5f, 0.75f};
        core.render(new float[6], 3, 4, null, engine);
        assertArrayEquals(engine, core.snapshotBuffer(1, 0, 0.75), 0.00001f);
    }

@Test public void engineSendIsSilentByDefaultAndCanBeMuted() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "level_slew_time", 0);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        core.render(new float[4], 2, 4, null, new float[]{0.5f, 0.5f});
        assertArrayEquals(new float[]{0f, 0f}, core.snapshotBuffer(1, 0, 0.5), 0f);
        core.command(1, "engine_level", 0);
        core.render(new float[4], 2, 4, null, new float[]{0.25f, 0.25f});
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f},
                core.snapshotBuffer(1, 0, 1.0), 0f);
    }

@Test public void engineSendGainIsIndependentAcrossSoftcutVoices() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "buffer", 1);
        core.command(2, "buffer", 2);
        for (int voice = 1; voice <= 2; voice++) {
            core.command(voice, "level_slew_time", 0);
            core.command(voice, "rec_level", 1);
            core.command(voice, "pre_level", 0);
            core.command(voice, "rec", 1);
        }
        core.command(1, "engine_level", 0.5);
        core.command(2, "engine_level", 1.5);
        core.render(new float[4], 2, 4, null, new float[]{0.5f, 0.5f});
        assertArrayEquals(new float[]{0.25f, 0.25f},
                core.snapshotBuffer(1, 0, 0.5), 0.00001f);
        assertArrayEquals(new float[]{0.75f, 0.75f},
                core.snapshotBuffer(2, 0, 0.5), 0.00001f);
    }

@Test public void tapeSendRecordsOnlyTheDedicatedTapeInputBus() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "level_slew_time", 0);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        float[] tape = new float[]{0.2f, -0.4f, 0.6f};
        float[] master = new float[6];
        core.command(0, "tape_level", 1);
        core.render(master, 3, 4, null, null, tape);
        assertArrayEquals(tape, core.snapshotBuffer(1, 0, 0.75), 0.00001f);
        assertArrayEquals(new float[6], master, 0f);
    }

@Test public void tapeSendIsMutedByDefaultAndGainIsIndependent() {
        SoftcutCore core = new SoftcutCore(4);
        core.command(1, "level_slew_time", 0);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        float[] tape = new float[]{0.5f, 0.5f};
        core.render(new float[4], 2, 4, null, null, tape);
        assertArrayEquals(new float[]{0f, 0f}, core.snapshotBuffer(1, 0, 0.5), 0f);
        core.command(0, "tape_level", 0.5);
        core.render(new float[4], 2, 4, null, null, tape);
        assertArrayEquals(new float[]{0f, 0f, 0.25f, 0.25f},
                core.snapshotBuffer(1, 0, 1.0), 0.00001f);
    }

}
