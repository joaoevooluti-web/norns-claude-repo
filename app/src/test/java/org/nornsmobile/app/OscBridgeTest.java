package org.nornsmobile.app;

import org.junit.Test;
import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class OscBridgeTest {
    @Test public void roundTripsNornsOscDestinationMessageTypes() throws Exception {
        byte[] packet = OscBridge.encode("cut/level", new Object[]{
                Integer.valueOf(12), Float.valueOf(0.75f), "voice", Boolean.TRUE,
                Boolean.FALSE, null});
        Object[] decoded = OscBridge.decode(packet, packet.length);
        assertNotNull(decoded);
        assertEquals("/cut/level", decoded[0]);
        Object[] args = (Object[]) decoded[1];
        assertEquals(6, args.length);
        assertEquals(Integer.valueOf(12), args[0]);
        assertEquals(Float.valueOf(0.75f), args[1]);
        assertEquals("voice", args[2]);
        assertEquals(Boolean.TRUE, args[3]);
        assertEquals(Boolean.FALSE, args[4]);
        assertNull(args[5]);
    }

    @Test public void rejectsTruncatedOrMalformedOscPackets() throws Exception {
        byte[] valid = OscBridge.encode("/test", new Object[]{Integer.valueOf(1)});
        assertNull(OscBridge.decode(valid, valid.length - 1));
        assertNull(OscBridge.decode(new byte[]{'/', 'x', 'x', 'x'}, 4));
        byte[] invalidTags = OscBridge.encode("/test", new Object[]{Integer.valueOf(1)});
        invalidTags[9] = 'x';
        assertNull(OscBridge.decode(invalidTags, invalidTags.length));
    }

    @Test public void decodesMultipleMessagesAndNestedBundles() throws Exception {
        byte[] first = OscBridge.encode("/one", new Object[]{Integer.valueOf(1)});
        byte[] second = OscBridge.encode("/two", new Object[]{Float.valueOf(0.5f)});
        byte[] inner = OscBridge.encodeBundle(new byte[][]{first, second}, 1L);
        byte[] outer = OscBridge.encodeBundle(new byte[][]{inner, first}, 1L);
        java.util.List<Object[]> messages = OscBridge.decodeAll(outer, outer.length);
        assertNotNull(messages);
        assertEquals(3, messages.size());
        assertEquals("/one", messages.get(0)[0]);
        assertEquals("/two", messages.get(1)[0]);
        assertEquals("/one", messages.get(2)[0]);
    }

    @Test public void enforcesMaximumBundleNestingAndMessageCount() throws Exception {
        byte[] message = OscBridge.encode("/bounded", new Object[0]);
        byte[] nested = message;
        for (int i = 0; i < 8; i++) {
            nested = OscBridge.encodeBundle(new byte[][]{nested}, 1L);
        }
        assertNotNull("eight nested bundle levels remain valid",
                OscBridge.decodeAll(nested, nested.length));
        nested = OscBridge.encodeBundle(new byte[][]{nested}, 1L);
        assertNull("the ninth nested level must be rejected",
                OscBridge.decodeAll(nested, nested.length));

        byte[][] many = new byte[257][];
        for (int i = 0; i < many.length; i++) many[i] = message;
        byte[] oversized = OscBridge.encodeBundle(many, 1L);
        assertNull("bundle message count must remain bounded",
                OscBridge.decodeAll(oversized, oversized.length));
    }

    @Test public void rejectsMalformedBundleElementLength() throws Exception {
        byte[] message = OscBridge.encode("/one", new Object[]{Integer.valueOf(1)});
        byte[] bundle = OscBridge.encodeBundle(new byte[][]{message}, 1L);
        bundle[16] = 0x7f;
        assertNull(OscBridge.decodeAll(bundle, bundle.length));
    }

    @Test public void roundTripsInt64Float64AndBlobPayloads() throws Exception {
        byte[] payload = new byte[]{0, 1, 2, (byte) 0xff, 42};
        byte[] packet = OscBridge.encode("/extended", new Object[]{
                Long.valueOf(0x123456789abcdefL), Double.valueOf(Math.PI), payload});
        Object[] decoded = OscBridge.decode(packet, packet.length);
        assertNotNull(decoded);
        Object[] values = (Object[]) decoded[1];
        assertEquals(Long.valueOf(0x123456789abcdefL), values[0]);
        assertEquals(Double.valueOf(Math.PI), values[1]);
        assertArrayEquals(payload, (byte[]) values[2]);
    }

    @Test public void schedulesFutureBundleTimetagsAndInheritsParentForImmediateChildren() throws Exception {
        long due = System.currentTimeMillis() + 5000L;
        long ntpSeconds = due / 1000L + 2208988800L;
        long fraction = ((due % 1000L) << 32) / 1000L;
        long timetag = (ntpSeconds << 32) | (fraction & 0xffffffffL);
        byte[] first = OscBridge.encode("/future", new Object[]{Integer.valueOf(1)});
        byte[] nestedImmediate = OscBridge.encodeBundle(new byte[][]{first}, 1L);
        byte[] outer = OscBridge.encodeBundle(new byte[][]{nestedImmediate}, timetag);
        java.util.List<OscBridge.ScheduledMessage> decoded =
                OscBridge.decodeScheduled(outer, outer.length, System.currentTimeMillis());
        assertNotNull(decoded);
        assertEquals(1, decoded.size());
        assertEquals("/future", decoded.get(0).path);
        assertTrue(decoded.get(0).dueTimeMillis >= due - 2L);
        assertTrue(decoded.get(0).dueTimeMillis <= due + 2L);
    }

    @Test public void unixMillisecondTimetagRoundTripsThroughNtpFormat() {
        long unixMillis = 1794000123456L;
        long ntp = OscBridge.unixMillisToNtp(unixMillis);
        assertTrue(Math.abs(unixMillis - OscBridge.ntpToUnixMillis(ntp, unixMillis)) <= 1L);
    }

    @Test public void resolvesNtpEraAfterThe2036SecondsRollover() {
        long unixMillis = 2208988800000L; // 2040-01-01 UTC
        long ntp = OscBridge.unixMillisToNtp(unixMillis);
        assertTrue(Math.abs(unixMillis - OscBridge.ntpToUnixMillis(ntp, unixMillis)) <= 1L);
    }

    @Test public void rejectsInvalidNtpBundleTimetagsInsteadOfTreatingThemAsImmediate() throws Exception {
        byte[] message = OscBridge.encode("/invalid-time", new Object[0]);
        byte[] bundle = OscBridge.encodeBundle(new byte[][]{message}, 0L);
        assertNull(OscBridge.decodeScheduled(bundle, bundle.length, System.currentTimeMillis()));
    }

    @Test public void immediateBundleDoesNotDelayItsMessages() throws Exception {
        byte[] message = OscBridge.encode("/now", new Object[0]);
        byte[] bundle = OscBridge.encodeBundle(new byte[][]{message}, 1L);
        java.util.List<OscBridge.ScheduledMessage> decoded =
                OscBridge.decodeScheduled(bundle, bundle.length, System.currentTimeMillis());
        assertNotNull(decoded);
        assertEquals(0L, decoded.get(0).dueTimeMillis);
    }

    @Test public void acceptsSlashlessNornsPathAndAddsCanonicalSlashOnSend() throws Exception {
        byte[] packet = OscBridge.encode("remote/key", new Object[]{Integer.valueOf(3), Integer.valueOf(1)});
        Object[] decoded = OscBridge.decode(packet, packet.length);
        assertNotNull(decoded);
        assertEquals("/remote/key", decoded[0]);
    }

    @Test public void honorsDeclaredOscPacketLengthDespiteLargerBackingBuffer() throws Exception {
        byte[] valid = OscBridge.encode("/length", new Object[]{Integer.valueOf(9)});
        byte[] backingBuffer = java.util.Arrays.copyOf(valid, valid.length + 8);
        assertNotNull(OscBridge.decode(backingBuffer, valid.length));
        assertNull(OscBridge.decode(backingBuffer, valid.length + 4));
    }

    @Test public void rejectsMalformedUtf8InOscAddressAndStringArguments() throws Exception {
        byte[] invalidAddress = OscBridge.encode("/ok", new Object[0]);
        invalidAddress[1] = (byte) 0xc3;
        invalidAddress[2] = 0;
        assertNull(OscBridge.decode(invalidAddress, invalidAddress.length));

        byte[] invalidArgument = OscBridge.encode("/ok", new Object[]{"x"});
        int stringStart = 8;
        invalidArgument[stringStart] = (byte) 0xc3;
        invalidArgument[stringStart + 1] = 0;
        assertNull(OscBridge.decode(invalidArgument, invalidArgument.length));
    }

    @Test public void rejectsEmbeddedNulInOscAddressAndStringArguments() throws Exception {
        try { OscBridge.encode("/bad\0path", new Object[0]); fail("expected NUL address rejection"); }
        catch (IOException expected) { }
        try { OscBridge.encode("/valid", new Object[]{"bad\0value"}); fail("expected NUL string rejection"); }
        catch (IOException expected) { }
    }

    @Test public void rejectsTrailingDataAfterTheLastOscArgument() throws Exception {
        byte[] valid = OscBridge.encode("/trailing", new Object[]{Integer.valueOf(1)});
        byte[] withTrailingData = java.util.Arrays.copyOf(valid, valid.length + 4);
        assertNull(OscBridge.decode(withTrailingData, withTrailingData.length));
    }

    @Test public void rejectsNonZeroOscStringAndBlobPadding() throws Exception {
        byte[] stringPacket = OscBridge.encode("/a", new Object[0]);
        stringPacket[3] = 1;
        assertNull(OscBridge.decode(stringPacket, stringPacket.length));

        byte[] blobPacket = OscBridge.encode("/b", new Object[]{new byte[]{7}});
        blobPacket[15] = 1;
        assertNull(OscBridge.decode(blobPacket, blobPacket.length));
    }

    @Test public void oscScheduledCallbacksAreInvalidatedByCloseAndListenerFailuresAreContained() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/OscBridge.java");
        assertTrue(source.contains("if (!running || socket != listening) return;"));
        assertTrue(source.contains("scheduler.shutdownNow();"));
        assertTrue(source.contains("listener = null;"));
        assertTrue(source.contains("socket.close();"));
        assertTrue(source.contains("catch (RuntimeException error)"));
        assertTrue(source.contains("OSC callback: "));
        assertTrue(source.indexOf("if (!running || socket != listening) return;")
                < source.indexOf("callback.onMessage("));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void rejectsEmptyPath() {
        try {
            OscBridge.encode("", new Object[0]);
            fail("empty OSC address should be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }
}
