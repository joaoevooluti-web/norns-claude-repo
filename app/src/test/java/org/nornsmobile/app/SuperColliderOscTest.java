package org.nornsmobile.app;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SuperColliderOscTest {
    @Test public void encodesAddressTagsAndBigEndianArguments() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/n_set", 1001, "freq", 440.5, true);
        assertEquals("/n_set", readString(packet, 0));
        assertEquals(",isfT", readString(packet, 8));
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        assertEquals(1001, data.getInt(16));
        assertEquals("freq", readString(packet, 20));
        assertEquals(440.5f, data.getFloat(28), 0.0001f);
    }

    @Test public void preservesLuaNumbersAsFloatEvenWhenIntegral() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/n_set", Double.valueOf(440.0), Integer.valueOf(7));
        assertEquals(",fi", readString(packet, 8));
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        assertEquals(440.0f, data.getFloat(12), 0.0001f);
        assertEquals(7, data.getInt(16));
    }

    @Test public void expandsFloatArrayAsConsecutiveFloat32Arguments() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/b_setn",
                Integer.valueOf(2), Integer.valueOf(16), Integer.valueOf(3),
                new SuperColliderOsc.FloatArray(new float[]{0.25f, -0.5f, 1.0f}));
        assertEquals(",iiifff", readString(packet, 8));
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        assertEquals(2, data.getInt(16));
        assertEquals(16, data.getInt(20));
        assertEquals(3, data.getInt(24));
        assertEquals(0.25f, data.getFloat(28), 0.00001f);
        assertEquals(-0.5f, data.getFloat(32), 0.00001f);
        assertEquals(1.0f, data.getFloat(36), 0.00001f);
    }

    @Test public void nativeBufferUploadChunkFitsOscDatagramLimit() throws Exception {
        float[] samples = new float[12000];
        byte[] packet = SuperColliderOsc.encode("/b_setn", Integer.valueOf(20001),
                Integer.valueOf(0), Integer.valueOf(samples.length),
                new SuperColliderOsc.FloatArray(samples));
        assertTrue("12k sample upload chunk must fit one UDP OSC datagram", packet.length <= 65507);
    }

    @Test public void rejectsNonFiniteFloatArrayValues() throws Exception {
        try {
            SuperColliderOsc.encode("/b_setn", new SuperColliderOsc.FloatArray(new float[]{0, Float.NaN}));
            fail("expected non-finite float array rejection");
        } catch (IOException expected) { }
    }

    @Test public void encodesSynthDefBlobWithLengthAndPadding() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/d_recv", new SuperColliderOsc.Blob(new byte[]{1, 2, 3}));
        assertEquals("/d_recv", readString(packet, 0));
        assertEquals(",b", readString(packet, 8));
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        assertEquals(3, data.getInt(12));
        assertArrayEquals(new byte[]{1, 2, 3}, new byte[]{packet[16], packet[17], packet[18]});
        assertEquals(0, packet[19]);
    }

    @Test public void rejectsInvalidAddressAndUnsupportedArgument() throws Exception {
        try { SuperColliderOsc.encode("n_set", 1); fail("expected invalid address"); }
        catch (IOException expected) { }
        try { SuperColliderOsc.encode("/x", new Object()); fail("expected unsupported argument"); }
        catch (IOException expected) { }
    }

    @Test public void rejectsNonFiniteFloat() throws Exception {
        try { SuperColliderOsc.encode("/x", Double.NaN); fail("expected non-finite rejection"); }
        catch (IOException expected) { }
    }

    @Test public void decodesDoneFailAndSyncedReplies() throws Exception {
        SuperColliderOsc.Reply done = SuperColliderOsc.decodeReply(SuperColliderOsc.encode("/done", "/d_recv"));
        assertEquals("/done", done.address);
        assertEquals("/d_recv", done.command);
        assertNull(done.message);

        // scsynth acknowledgements such as /b_alloc may include extra values.
        SuperColliderOsc.Reply allocated = SuperColliderOsc.decodeReply(
                SuperColliderOsc.encode("/done", "/b_alloc",
                        Integer.valueOf(20001), Integer.valueOf(1)));
        assertEquals("/done", allocated.address);
        assertEquals("/b_alloc", allocated.command);

        SuperColliderOsc.Reply fail = SuperColliderOsc.decodeReply(
                SuperColliderOsc.encode("/fail", "/d_recv", "SynthDef rejected"));
        assertEquals("/fail", fail.address);
        assertEquals("/d_recv", fail.command);
        assertEquals("SynthDef rejected", fail.message);

        SuperColliderOsc.Reply synced = SuperColliderOsc.decodeReply(
                SuperColliderOsc.encode("/synced", Integer.valueOf(731)));
        assertEquals("/synced", synced.address);
        assertEquals(Integer.valueOf(731), synced.syncId);
    }

    @Test public void decodesBufferReadbackSamples() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/b_setn",
                Integer.valueOf(20001), Integer.valueOf(64), Integer.valueOf(3),
                new SuperColliderOsc.FloatArray(new float[]{0.125f, -0.25f, 0.75f}));
        SuperColliderOsc.Reply reply = SuperColliderOsc.decodeReply(packet);
        assertEquals("/b_setn", reply.address);
        assertEquals(Integer.valueOf(20001), reply.bufferId);
        assertEquals(Integer.valueOf(64), reply.startFrame);
        assertArrayEquals(new float[]{0.125f, -0.25f, 0.75f}, reply.samples, 0.000001f);
    }

    @Test public void readbackReplyChunkFitsNativeQueueAndDecodes() throws Exception {
        float[] samples = new float[750];
        for (int i = 0; i < samples.length; i++) samples[i] = (i - 375) / 375f;
        byte[] packet = SuperColliderOsc.encode("/b_setn", Integer.valueOf(20002),
                Integer.valueOf(4096), Integer.valueOf(samples.length),
                new SuperColliderOsc.FloatArray(samples));
        assertTrue("750-sample reply must fit native bounded reply slot", packet.length <= 4096);
        SuperColliderOsc.Reply reply = SuperColliderOsc.decodeReply(packet);
        assertEquals(Integer.valueOf(20002), reply.bufferId);
        assertEquals(Integer.valueOf(4096), reply.startFrame);
        assertArrayEquals(samples, reply.samples, 0.000001f);
    }

    @Test public void encodesNativeBufferReadbackRequest() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/b_getn",
                Integer.valueOf(20001), Integer.valueOf(1200), Integer.valueOf(750));
        assertEquals("/b_getn", readString(packet, 0));
        assertEquals(",iii", readString(packet, 8));
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        assertEquals(20001, data.getInt(16));
        assertEquals(1200, data.getInt(20));
        assertEquals(750, data.getInt(24));
    }

    @Test public void rejectsMalformedBufferReadbackReplies() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/b_setn",
                Integer.valueOf(20001), Integer.valueOf(0), Integer.valueOf(2),
                new SuperColliderOsc.FloatArray(new float[]{0.1f, 0.2f}));
        byte[] truncated = java.util.Arrays.copyOf(packet, packet.length - 4);
        try { SuperColliderOsc.decodeReply(truncated); fail("expected truncated /b_setn rejection"); }
        catch (IOException expected) { }
    }

    @Test public void rejectsTrailingDataOnDoneAndFailReplies() throws Exception {
        byte[] done = SuperColliderOsc.encode("/done", "/d_recv");
        byte[] doneWithTrailingData = java.util.Arrays.copyOf(done, done.length + 4);
        try { SuperColliderOsc.decodeReply(doneWithTrailingData); fail("expected trailing /done data rejection"); }
        catch (IOException expected) { }

        byte[] failReply = SuperColliderOsc.encode("/fail", "/d_recv", "invalid SynthDef");
        byte[] failWithTrailingData = java.util.Arrays.copyOf(failReply, failReply.length + 4);
        try { SuperColliderOsc.decodeReply(failWithTrailingData); fail("expected trailing /fail data rejection"); }
        catch (IOException expected) { }
    }

    @Test public void rejectsMalformedAndUnknownReplies() throws Exception {
        try { SuperColliderOsc.decodeReply(new byte[]{'/', 'd', 'o', 'n', 'e'}); fail("expected malformed reply"); }
        catch (IOException expected) { }
        try { SuperColliderOsc.decodeReply(SuperColliderOsc.encode("/unknown")); fail("expected unknown reply"); }
        catch (IOException expected) { }
        try { SuperColliderOsc.decodeReply(SuperColliderOsc.encode("/synced", Double.valueOf(4.0))); fail("expected wrong synced type"); }
        catch (IOException expected) { }
    }

    private static String readString(byte[] data, int offset) {
        int end = offset;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }
@Test public void rejectsFiniteDoubleOutsideOscFloat32Range() throws Exception {
        try { SuperColliderOsc.encode("/x", Double.valueOf(Double.MAX_VALUE)); fail("expected float32 overflow rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("float32")); }
    }

@Test public void rejectsLongValuesOutsideOscInt32Range() throws Exception {
        try { SuperColliderOsc.encode("/x", Long.valueOf(0x1_0000_0000L)); fail("expected int32 overflow rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("int32")); }
    }

@Test public void rejectsMalformedUtf8InSuperColliderReplies() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/done", "/d_recv");
        packet[1] = (byte) 0xc3;
        packet[2] = 0;
        try { SuperColliderOsc.decodeReply(packet); fail("expected invalid UTF-8 rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("UTF-8")); }
    }

@Test public void rejectsEmbeddedNulInSuperColliderStringArguments() throws Exception {
        try { SuperColliderOsc.encode("/x", "bad\0value"); fail("expected NUL string rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("NUL")); }
    }

@Test public void rejectsNonZeroPaddingInDoneBlobArguments() throws Exception {
        byte[] packet = SuperColliderOsc.encode("/done", "/blob",
                new SuperColliderOsc.Blob(new byte[]{7}));
        packet[25] = 1;
        try {
            SuperColliderOsc.decodeReply(packet);
            fail("expected invalid /done blob padding rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("padding"));
        }
    }

}
