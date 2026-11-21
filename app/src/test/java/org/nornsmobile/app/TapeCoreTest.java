package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

public class TapeCoreTest {
    @Test public void playbackMixesStereoAndProvidesIndependentSoftcutSend() throws Exception {
        File wav = File.createTempFile("tape-playback", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.5f, -0.25f, 0.25f, 0.75f}, 48000, 2);
            WavReader.StereoData source = WavReader.readStereo(wav);
            TapeCore tape = new TapeCore(48000);
            assertTrue(tape.openPlayback(source));
            tape.playStart();

            float[] master = new float[4];
            float[] send = new float[2];
            tape.render(master, 2, send);

            assertArrayEquals(new float[]{0.5f, -0.25f, 0.25f, 0.75f}, master, 0.0001f);
            assertArrayEquals(new float[]{0.125f, 0.5f}, send, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void pauseAndLoopControlPlaybackTransport() throws Exception {
        File wav = File.createTempFile("tape-transport", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.2f, 0.2f}, 48000, 1);
            TapeCore tape = new TapeCore(48000);
            assertTrue(tape.openPlayback(WavReader.readStereo(wav), wav.getPath()));
            assertEquals(1, tape.getState().playState);
            assertEquals(wav.getPath(), tape.getState().playFile);
            tape.setLoop(true);
            tape.playStart();
            float[] master = new float[6];
            float[] send = new float[3];
            tape.render(master, 3, send);
            assertEquals(0.2f, master[4], 0.0001f);
            tape.playPause(true);
            float[] paused = new float[2];
            tape.render(paused, 1, new float[1]);
            assertArrayEquals(new float[]{0f, 0f}, paused, 0f);
            tape.playPause(false);
            float[] resumed = new float[2];
            tape.render(resumed, 1, new float[1]);
            assertEquals(0.2f, resumed[0], 0.0001f);
            assertEquals(0.2f, resumed[1], 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void recordsFinalStereoBlocksAsAValidWav() throws Exception {
        File wav = File.createTempFile("tape-record", ".wav");
        try {
            TapeCore tape = new TapeCore(48000);
            assertTrue(tape.openRecord(wav));
            assertTrue(tape.recordStart());
            tape.recordBlock(new float[]{0.5f, -0.5f, 0.25f, 0.75f}, 2);
            tape.recordStop();

            long deadline = System.currentTimeMillis() + 3000;
            while (!tape.isRecordFinalized() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue("Tape writer should finalize the WAV header", tape.isRecordFinalized());
            WavReader.StereoData recorded = WavReader.readStereo(wav);
            assertEquals(48000, recorded.sampleRate);
            assertArrayEquals(new float[]{0.5f, -0.5f, 0.25f, 0.75f}, recorded.samples, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }

    @Test public void recordingResamplesToNorns48KhzFormat() throws Exception {
        File wav = File.createTempFile("tape-record-48k", ".wav");
        try {
            TapeCore tape = new TapeCore(44100);
            assertTrue(tape.openRecord(wav));
            assertTrue(tape.recordStart());
            tape.recordBlock(new float[]{0.5f, -0.5f, -0.5f, 0.5f}, 2);
            tape.recordStop();

            long deadline = System.currentTimeMillis() + 3000;
            while (!tape.isRecordFinalized() && System.currentTimeMillis() < deadline)
                Thread.sleep(10);
            assertTrue(tape.isRecordFinalized());
            WavReader.StereoData recorded = WavReader.readStereo(wav);
            assertEquals(48000, recorded.sampleRate);
            assertEquals(6, recorded.samples.length);
            assertArrayEquals(new float[]{
                    0.5f, -0.5f, -0.41875f, 0.41875f, -0.5f, 0.5f
            }, recorded.samples, 0.002f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }


    @Test public void refusesToReplaceDirectoryWithTapeRecording() throws Exception {
        File directory = java.nio.file.Files.createTempDirectory("tape-target-directory").toFile();
        try {
            TapeCore tape = new TapeCore(48000);
            assertFalse(tape.openRecord(directory));
            assertTrue(directory.isDirectory());
        } finally {
            assertTrue(directory.delete() || !directory.exists());
        }
    }


    @Test public void mappedPlaybackKeepsLongStereoSourcesOutOfTheJavaHeap() throws Exception {
        File wav = File.createTempFile("tape-mapped", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.6f, -0.4f, -0.2f, 0.8f}, 48000, 2);
            WavReader.MappedStereoData source = WavReader.mapStereo(wav);
            TapeCore tape = new TapeCore(48000);
            assertTrue(tape.openPlayback(source));
            tape.playStart();
            float[] master = new float[4];
            tape.render(master, 2, new float[2]);
            assertArrayEquals(new float[]{0.6f, -0.4f, -0.2f, 0.8f}, master, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }


    @Test public void stateSnapshotTracksPlaybackPauseLoopAndRecordingPosition() throws Exception {
        File wav = File.createTempFile("tape-state", ".wav");
        File recording = File.createTempFile("tape-state-rec", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.1f, -0.1f, 0.2f, -0.2f}, 48000, 2);
            TapeCore tape = new TapeCore(48000);
            TapeCore.State initial = tape.getState();
            assertEquals(0, initial.playState);
            assertEquals(0, initial.recordState);
            assertEquals(0.0, initial.playLengthSeconds, 0.0);
            assertTrue(initial.loopEnabled);

            assertTrue(tape.openPlayback(WavReader.readStereo(wav), wav.getPath()));
            assertEquals(1, tape.getState().playState);
            assertEquals(wav.getPath(), tape.getState().playFile);
            tape.setLoop(true);
            tape.playStart();
            TapeCore.State playing = tape.getState();
            assertEquals(2, playing.playState);
            assertEquals(2.0 / 48000.0, playing.playLengthSeconds, 1.0 / 48000.0);
            assertTrue(playing.loopEnabled);

            tape.playPause(true);
            assertEquals(3, tape.getState().playState);
            tape.playPause(false);
            assertEquals(2, tape.getState().playState);
            tape.playStop();
            assertEquals(1, tape.getState().playState);

            assertTrue(tape.openRecord(recording));
            assertEquals(1, tape.getState().recordState);
            assertEquals(recording.getPath(), tape.getState().recordFile);
            assertTrue(tape.recordStart());
            tape.recordBlock(new float[]{0.25f, -0.25f, 0.5f, -0.5f}, 2);
            assertEquals(2, tape.getState().recordState);
            assertEquals(2.0 / 48000.0, tape.getState().recordPositionSeconds, 1.0 / 48000.0);
            tape.recordPause(true);
            assertEquals(3, tape.getState().recordState);
            tape.recordPause(false);
            assertEquals(2, tape.getState().recordState);
            tape.recordStop();
            long deadline = System.currentTimeMillis() + 3000;
            while (!tape.isRecordFinalized() && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals(0, tape.getState().recordState);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
            assertTrue(recording.delete() || !recording.exists());
        }
    }

    private static File sourceFile(String path) {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        return file;
    }

    @Test public void transportGenerationPreventsAnOldRenderFromOverwritingStopOrSourceSwap() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(sourceFile("app/src/main/java/org/nornsmobile/app/TapeCore.java").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(source.contains("private volatile long playbackGeneration;"));
        assertTrue(source.contains("long renderGeneration = playbackGeneration;"));
        assertTrue(source.contains("renderGeneration == playbackGeneration"));
        assertTrue(source.contains("playbackPosition = position;"));
        assertTrue(source.contains("playbackGeneration++;"));
        assertTrue(source.contains("renderGeneration == playbackGeneration; i++"));
        assertTrue(source.contains("private final Object playbackMutationLock = new Object();"));
        assertTrue(source.contains("synchronized (playbackMutationLock)"));
        assertTrue(source.contains("if (ended) { playing = false; playPaused = false; }"));
        assertFalse(source.contains("else {\n                    playing = false;\n                    break;\n                }"));
    }

    @Test public void mappedPlaybackCanPauseAndResumeWithoutLosingPosition() throws Exception {
        File wav = File.createTempFile("tape-mapped-pause", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.2f, -0.2f, 0.4f, -0.4f, 0.6f, -0.6f}, 48000, 2);
            TapeCore tape = new TapeCore(48000);
            assertTrue(tape.openPlayback(WavReader.mapStereo(wav)));
            tape.playStart();
            float[] first = new float[2];
            tape.render(first, 1, new float[1]);
            assertArrayEquals(new float[]{0.2f, -0.2f}, first, 0.0001f);

            tape.playPause(true);
            float[] paused = new float[2];
            tape.render(paused, 1, new float[1]);
            assertArrayEquals(new float[]{0f, 0f}, paused, 0f);

            tape.playPause(false);
            float[] resumed = new float[2];
            tape.render(resumed, 1, new float[1]);
            assertArrayEquals(new float[]{0.4f, -0.4f}, resumed, 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }


    @Test public void tapeOutputLevelDoesNotChangeSoftcutSendLevel() throws Exception {
        File wav = File.createTempFile("tape-levels", ".wav");
        try {
            WavWriter.write(wav, new float[]{0.8f, -0.4f}, 48000, 2);
            TapeCore tape = new TapeCore(48000);
            assertTrue(tape.openPlayback(WavReader.mapStereo(wav)));
            tape.setPlaybackLevel(0.25);
            tape.playStart();
            float[] master = new float[2];
            float[] send = new float[1];
            tape.render(master, 1, send);
            assertArrayEquals(new float[]{0.2f, -0.1f}, master, 0.0001f);
            assertEquals(0.2f, send[0], 0.0001f);
        } finally {
            assertTrue(wav.delete() || !wav.exists());
        }
    }


    @Test public void tapeWriterStartFailureDoesNotLeaveAnOpenRecordingSession() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                sourceFile("app/src/main/java/org/nornsmobile/app/TapeCore.java").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(source.contains("catch (Throwable startFailure)"));
        assertTrue(source.contains("finished = true;"));
        assertTrue(source.contains("started = false;"));
        assertTrue(source.contains("worker = null;"));
    }

}
