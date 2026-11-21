package org.nornsmobile.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

public class WavWriterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void writesValidMonoPcmWaveHeaderAndSamples() throws Exception {
        File file = new File(temporary.getRoot(), "mono.wav");
        WavWriter.write(file, new float[]{-1f, 0f, 1f}, 48000, 1);
        byte[] bytes = read(file);
        assertEquals("RIFF", ascii(bytes, 0));
        assertEquals("WAVE", ascii(bytes, 8));
        assertEquals("fmt ", ascii(bytes, 12));
        assertEquals(1, u16(bytes, 22));
        assertEquals(48000, u32(bytes, 24));
        assertEquals(96000, u32(bytes, 28));
        assertEquals("data", ascii(bytes, 36));
        assertEquals(6, u32(bytes, 40));
        assertEquals(0x8000, u16(bytes, 44));
        assertEquals(0, u16(bytes, 46));
        assertEquals(0x7fff, u16(bytes, 48));
        assertEquals(50, bytes.length);
    }

    @Test public void writesStereoFrameAlignmentAndReplacesExistingFile() throws Exception {
        File file = new File(temporary.getRoot(), "stereo.wav");
        FileOutputStream old = new FileOutputStream(file);
        old.write(new byte[]{1, 2, 3});
        old.close();

        WavWriter.write(file, new float[]{0.25f, -0.25f, 0.5f, -0.5f}, 44100, 2);
        byte[] bytes = read(file);
        assertEquals(2, u16(bytes, 22));
        assertEquals(44100, u32(bytes, 24));
        assertEquals(176400, u32(bytes, 28));
        assertEquals(8, u32(bytes, 40));
        assertEquals(52, bytes.length);
        assertFalse(new File(file.getPath() + ".tmp").exists());
        assertFalse(new File(file.getPath() + ".bak").exists());
    }

    @Test public void rejectsInvalidRateWithoutChangingPreviousRecording() throws Exception {
        File file = new File(temporary.getRoot(), "keep.wav");
        byte[] original = new byte[]{9, 8, 7, 6};
        FileOutputStream out = new FileOutputStream(file);
        out.write(original);
        out.close();

        try {
            WavWriter.write(file, new float[]{0.1f}, 0, 1);
            fail("invalid sample rate must be rejected");
        } catch (IOException expected) {
            assertArrayEquals(original, read(file));
        }
    }

    @Test public void replacesNonFiniteSamplesWithSilenceAndClipsFiniteSamples() throws Exception {
        File file = new File(temporary.getRoot(), "safe.wav");
        WavWriter.write(file, new float[]{Float.NaN, Float.POSITIVE_INFINITY, 2f, -2f}, 48000, 1);
        byte[] bytes = read(file);
        assertEquals(0, u16(bytes, 44));
        assertEquals(0, u16(bytes, 46));
        assertEquals(0x7fff, u16(bytes, 48));
        assertEquals(0x8000, u16(bytes, 50));
    }

    @Test public void rejectsIncompleteStereoFramesAndUnsupportedChannelCounts() throws Exception {
        File file = new File(temporary.getRoot(), "invalid.wav");
        assertInvalid(file, new float[]{0.1f}, 48000, 2);
        assertInvalid(file, new float[]{0.1f}, 48000, 3);
    }

    private static void assertInvalid(File file, float[] samples, int rate, int channels) throws Exception {
        try {
            WavWriter.write(file, samples, rate, channels);
            fail("invalid WAV arguments must be rejected");
        } catch (IOException expected) {
            assertFalse(file.exists());
        }
    }

    private static byte[] read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            return data;
        } finally {
            in.close();
        }
    }

    private static String ascii(byte[] data, int offset) {
        return new String(data, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }
    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
}