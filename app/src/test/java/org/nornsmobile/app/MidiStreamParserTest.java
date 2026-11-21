package org.nornsmobile.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class MidiStreamParserTest {
    private static final class Message {
        final int status, a, b;
        Message(int status, int a, int b) { this.status=status; this.a=a; this.b=b; }
    }

    private static List<Message> feed(MidiStreamParser parser, byte[]... packets) {
        final List<Message> messages = new ArrayList<>();
        MidiStreamParser.Listener listener = (status, a, b) -> messages.add(new Message(status, a, b));
        for (byte[] packet : packets) parser.feed(packet, 0, packet.length, listener);
        return messages;
    }

    @Test public void parsesCompleteChannelMessagesAndRunningStatus() {
        MidiStreamParser parser = new MidiStreamParser();
        List<Message> messages = feed(parser, new byte[]{
                (byte)0x90, 60, 100, 61, 101, (byte)0x80, 60, 0});
        assertEquals(3, messages.size());
        assertEquals(0x90, messages.get(0).status);
        assertEquals(60, messages.get(0).a);
        assertEquals(100, messages.get(0).b);
        assertEquals(0x90, messages.get(1).status);
        assertEquals(61, messages.get(1).a);
        assertEquals(101, messages.get(1).b);
        assertEquals(0x80, messages.get(2).status);
    }

    @Test public void completesMessagesAcrossAndroidReceiverPackets() {
        MidiStreamParser parser = new MidiStreamParser();
        List<Message> messages = feed(parser,
                new byte[]{(byte)0x90, 64},
                new byte[]{(byte)0xf8, 90},
                new byte[]{(byte)0x80, 64, 0});
        assertEquals(3, messages.size());
        assertEquals(0xf8, messages.get(0).status);
        assertEquals(0x90, messages.get(1).status);
        assertEquals(64, messages.get(1).a);
        assertEquals(90, messages.get(1).b);
        assertEquals(0x80, messages.get(2).status);
    }

    @Test public void realtimeMessagesDoNotCancelRunningStatusOrSysex() {
        MidiStreamParser parser = new MidiStreamParser();
        List<Message> messages = feed(parser, new byte[]{
                (byte)0x90, 60, (byte)0xf8, 100,
                (byte)0xf0, 1, 2, (byte)0xf8, 3, (byte)0xf7,
                (byte)0x90, 61, 90});
        assertEquals(4, messages.size());
        assertEquals(0xf8, messages.get(0).status);
        assertEquals(0x90, messages.get(1).status);
        assertEquals(0xf8, messages.get(2).status);
        assertEquals(0x90, messages.get(3).status);
        assertEquals(61, messages.get(3).a);
    }

    @Test public void systemResetRealtimeBytePreservesChannelRunningStatus() {
        MidiStreamParser parser = new MidiStreamParser();
        List<Message> messages = feed(parser, new byte[]{
                (byte)0x90, 60, 100, (byte)0xff, 61, 101});
        assertEquals(3, messages.size());
        assertEquals(0x90, messages.get(0).status);
        assertEquals(0xff, messages.get(1).status);
        assertEquals(0x90, messages.get(2).status);
        assertEquals(61, messages.get(2).a);
        assertEquals(101, messages.get(2).b);
    }

    @Test public void parsesProgramChangeAndSystemCommonMessages() {
        MidiStreamParser parser = new MidiStreamParser();
        List<Message> messages = feed(parser, new byte[]{
                (byte)0xc2, 10, (byte)0xf2, 1, 2, (byte)0xf6});
        assertEquals(3, messages.size());
        assertEquals(0xc2, messages.get(0).status);
        assertEquals(10, messages.get(0).a);
        assertEquals(0, messages.get(0).b);
        assertEquals(0xf2, messages.get(1).status);
        assertEquals(1, messages.get(1).a);
        assertEquals(2, messages.get(1).b);
        assertEquals(0xf6, messages.get(2).status);
    }

    @Test public void resetClearsRunningStatusAndFragmentedMessage() {
        MidiStreamParser parser = new MidiStreamParser();
        List<Message> messages = feed(parser, new byte[]{(byte)0x90, 60});
        assertTrue(messages.isEmpty());
        parser.reset();
        messages = feed(parser, new byte[]{100});
        assertTrue(messages.isEmpty());
        messages = feed(parser, new byte[]{(byte)0x90, 61, 100});
        assertEquals(1, messages.size());
        assertEquals(61, messages.get(0).a);
    }


    @Test public void forwardsFragmentedSysExIncludingRealtimeInterleaving() {
        MidiStreamParser parser = new MidiStreamParser();
        final List<Message> messages = new ArrayList<>();
        final List<byte[]> sysex = new ArrayList<>();
        MidiStreamParser.Listener listener = new MidiStreamParser.Listener() {
            @Override public void onMessage(int status, int a, int b) {
                messages.add(new Message(status, a, b));
            }
            @Override public void onSystemExclusive(byte[] data) { sysex.add(data); }
        };
        parser.feed(new byte[]{(byte)0xf0, 0x7d, 1}, 0, 3, listener);
        parser.feed(new byte[]{(byte)0xf8, 2, (byte)0xf7}, 0, 3, listener);
        assertEquals(1, messages.size());
        assertEquals(0xf8, messages.get(0).status);
        assertEquals(1, sysex.size());
        assertArrayEquals(new byte[]{(byte)0xf0, 0x7d, 1, 2, (byte)0xf7}, sysex.get(0));
    }

    @Test public void discardsOversizedSysExAndRecoversAfterTerminator() {
        MidiStreamParser parser = new MidiStreamParser();
        final List<byte[]> sysex = new ArrayList<>();
        final List<Message> messages = new ArrayList<>();
        MidiStreamParser.Listener listener = new MidiStreamParser.Listener() {
            @Override public void onMessage(int status, int a, int b) {
                messages.add(new Message(status, a, b));
            }
            @Override public void onSystemExclusive(byte[] data) { sysex.add(data); }
        };
        byte[] oversized = new byte[65540];
        oversized[0] = (byte)0xf0;
        java.util.Arrays.fill(oversized, 1, oversized.length - 1, (byte)1);
        oversized[oversized.length - 1] = (byte)0xf7;
        parser.feed(oversized, 0, oversized.length, listener);
        parser.feed(new byte[]{(byte)0x90, 60, 100}, 0, 3, listener);
        assertTrue(sysex.isEmpty());
        assertEquals(1, messages.size());
        assertEquals(0x90, messages.get(0).status);
    }

    @Test public void invalidRangesAreIgnored() {
        MidiStreamParser parser = new MidiStreamParser();
        final int[] count = {0};
        parser.feed(new byte[]{(byte)0x90, 60, 100}, 2, 5,
                (status, a, b) -> count[0]++);
        assertEquals(0, count[0]);
    }
}
