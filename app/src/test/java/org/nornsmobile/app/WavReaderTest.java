package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

public class WavReaderTest {
    @Test public void inspectsWavMetadataWithoutLoadingSamplePayload() throws Exception {
        File wav = File.createTempFile("norns-file-info", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.25f, -0.5f, 0.75f, 0.1f}, 44100, 2);
            WavReader.Info info = WavReader.inspect(wav);
            assertEquals(2, info.channels);
            assertEquals(2L, info.samples);
            assertEquals(44100, info.sampleRate);
            assertEquals(16, info.bitsPerSample);
        } finally { assertTrue(wav.delete() || !wav.exists()); }
    }

    @Test public void rejectsMalformedOrTruncatedWavMetadata() throws Exception {
        File wav = File.createTempFile("norns-file-info-invalid", ".wav");
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(wav);
            try { out.write(new byte[]{'R','I','F','F',40,0,0,0,'W','A','V','E'}); }
            finally { out.close(); }
            try { WavReader.inspect(wav); fail("truncated WAV metadata must be rejected"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        } finally { assertTrue(wav.delete() || !wav.exists()); }
    }

    @Test public void readsMonoPcmAndPreservesSampleRate() throws Exception {
        File wav = File.createTempFile("softcut-mono", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.25f, -0.5f, 0.75f}, 44100, 1);
            WavReader.Data data = WavReader.read(wav, 1);
            assertEquals(44100, data.sampleRate);
            assertArrayEquals(new float[]{0.25f, -0.5f, 0.75f}, data.samples, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void readsStereoDownmixAndIndividualChannels() throws Exception {
        File wav = File.createTempFile("softcut-stereo", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.5f, -0.5f, 0.25f, 0.75f}, 48000, 2);
            WavReader.Data left = WavReader.read(wav, 1);
            WavReader.Data right = WavReader.read(wav, 2);
            WavReader.Data mono = WavReader.read(wav, 0);
            assertArrayEquals(new float[]{0.5f, 0.25f}, left.samples, 0.0001f);
            assertArrayEquals(new float[]{-0.5f, 0.75f}, right.samples, 0.0001f);
            assertArrayEquals(new float[]{0f, 0.5f}, mono.samples, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void rejectsUnavailableSourceChannel() throws Exception {
        File wav = File.createTempFile("softcut-channel", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.1f, 0.2f}, 48000, 1);
            try {
                WavReader.read(wav, 2);
                fail("expected invalid source channel");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("Canal"));
            }
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void rejectsDataChunkThatEndsInsideStereoFrame() throws Exception {
        File wav = File.createTempFile("softcut-truncated-frame", ".wav");
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(wav);
            try {
                out.write(new byte[]{
                        'R','I','F','F', 40,0,0,0, 'W','A','V','E',
                        'f','m','t',' ', 16,0,0,0, 1,0, 2,0,
                        (byte)0x80,(byte)0xbb,0,0, 0,(byte)0xee,2,0, 4,0, 16,0,
                        'd','a','t','a', 3,0,0,0, 1,2,3,0
                });
            } finally { out.close(); }
            try {
                WavReader.read(wav);
                fail("partial stereo frame must be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("quadro"));
            }
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void readsTapeStereoWithoutCollapsingChannels() throws Exception {
        File wav = File.createTempFile("tape-stereo", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.75f, -0.25f, 0.5f, -0.5f}, 48000, 2);
            WavReader.StereoData data = WavReader.readStereo(wav);
            assertEquals(48000, data.sampleRate);
            assertArrayEquals(new float[]{0.75f, -0.25f, 0.5f, -0.5f}, data.samples, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void duplicatesMonoTapeToBothChannels() throws Exception {
        File wav = File.createTempFile("tape-mono", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.25f, -0.75f}, 44100, 1);
            WavReader.StereoData data = WavReader.readStereo(wav);
            assertEquals(44100, data.sampleRate);
            assertArrayEquals(new float[]{0.25f, 0.25f, -0.75f, -0.75f}, data.samples, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }


    @Test public void mapsMonoWavAsDuplicatedStereoTapeSource() throws Exception {
        File wav = File.createTempFile("tape-mapped-mono", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.3f, -0.6f}, 44100, 1);
            WavReader.MappedStereoData mapped = WavReader.mapStereo(wav);
            assertEquals(44100, mapped.sampleRate);
            assertEquals(1, mapped.channels);
            assertEquals(2, mapped.frameCount);
            assertEquals(0.3f, mapped.sample(0, 0), 0.0001f);
            assertEquals(0.3f, mapped.sample(0, 1), 0.0001f);
            assertEquals(-0.6f, mapped.sample(1, 0), 0.0001f);
            assertEquals(-0.6f, mapped.sample(1, 1), 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void tapePlaybackKeepsStereoAndExposesIndependentMonoSend() {
        TapeCore tape = new TapeCore(48000);
        assertTrue(tape.openPlayback(new WavReader.StereoData(
                new float[]{0.8f, -0.4f, 0.2f, 0.6f}, 48000)));
        tape.playStart();
        float[] master = new float[4];
        float[] send = new float[2];
        tape.render(master, 2, send);
        assertArrayEquals(new float[]{0.8f, -0.4f, 0.2f, 0.6f}, master, 0.0001f);
        assertArrayEquals(new float[]{0.2f, 0.4f}, send, 0.0001f);
    }

    @Test public void tapePauseAndStopControlPlaybackPosition() {
        TapeCore tape = new TapeCore(48000);
        assertTrue(tape.openPlayback(new WavReader.StereoData(
                new float[]{0.1f, 0.1f, 0.2f, 0.2f, 0.3f, 0.3f}, 48000)));
        tape.playStart();
        float[] first = new float[2];
        tape.render(first, 1, new float[1]);
        assertArrayEquals(new float[]{0.1f, 0.1f}, first, 0.0001f);
        tape.playPause(true);
        float[] paused = new float[2];
        tape.render(paused, 1, new float[1]);
        assertArrayEquals(new float[]{0f, 0f}, paused, 0.0001f);
        tape.playPause(false);
        float[] resumed = new float[2];
        tape.render(resumed, 1, new float[1]);
        assertArrayEquals(new float[]{0.2f, 0.2f}, resumed, 0.0001f);
        tape.playStop();
        tape.playStart();
        float[] restarted = new float[2];
        tape.render(restarted, 1, new float[1]);
        assertArrayEquals(new float[]{0.1f, 0.1f}, restarted, 0.0001f);
    }

    @Test public void tapeLoopWrapsWithoutChangingStereoBalance() {
        TapeCore tape = new TapeCore(48000);
        assertTrue(tape.openPlayback(new WavReader.StereoData(
                new float[]{0.7f, -0.3f, 0.4f, -0.8f}, 48000)));
        tape.setLoop(true);
        tape.playStart();
        float[] master = new float[8];
        tape.render(master, 4, new float[4]);
        assertArrayEquals(new float[]{
                0.7f, -0.3f, 0.4f, -0.8f, 0.7f, -0.3f, 0.4f, -0.8f
        }, master, 0.0001f);
    }

    @Test public void tapeRecorderFinalizesStereoWavFromAudioBlocks() throws Exception {
        File wav = File.createTempFile("norns-tape-record", ".wav");
        try {
            assertTrue(wav.delete());
            TapeCore tape = new TapeCore(48000);
            assertTrue(tape.openRecord(wav));
            assertTrue(tape.recordStart());
            float[] block = new float[512];
            for (int i = 0; i < 256; i++) {
                block[i * 2] = 0.25f;
                block[i * 2 + 1] = -0.5f;
            }
            tape.recordBlock(block, 256);
            tape.recordStop();
            long deadline = System.currentTimeMillis() + 4000;
            while (!tape.isRecordFinalized() && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertTrue("Tape writer did not finish", tape.isRecordFinalized());
            assertTrue(wav.isFile());
            WavReader.StereoData recorded = WavReader.readStereo(wav);
            assertEquals(48000, recorded.sampleRate);
            assertEquals(512, recorded.samples.length);
            assertEquals(0.25f, recorded.samples[0], 0.0001f);
            assertEquals(-0.5f, recorded.samples[1], 0.0001f);
            assertEquals(0.25f, recorded.samples[510], 0.0001f);
            assertEquals(-0.5f, recorded.samples[511], 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
            File temp = new File(wav.getPath() + ".recording.tmp");
            if (temp.exists()) assertTrue(temp.delete());
            File backup = new File(wav.getPath() + ".bak");
            if (backup.exists()) assertTrue(backup.delete());
        }
    }

}
