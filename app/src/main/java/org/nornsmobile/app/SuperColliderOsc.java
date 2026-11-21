package org.nornsmobile.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

/** OSC 1.0 encoder used by the embedded SuperCollider server. */
final class SuperColliderOsc {
    static final class Blob {
        final byte[] data;
        Blob(byte[] data) { if (data == null) throw new IllegalArgumentException("null OSC blob"); this.data = data; }
    }
    /** Expands to consecutive OSC float32 arguments (used by /b_setn). */
    static final class FloatArray {
        final float[] values;
        FloatArray(float[] values) {
            if (values == null) throw new IllegalArgumentException("null OSC float array");
            this.values = values.clone();
        }
    }
    private SuperColliderOsc() { }
    static byte[] encode(String address, Object... args) throws IOException {
        if (address == null || !address.startsWith("/") || address.indexOf('\0') >= 0) throw new IOException("Invalid OSC address");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, address);
        StringBuilder tags = new StringBuilder(",");
        if (args != null) for (Object arg : args) {
            if (arg instanceof Boolean) tags.append(((Boolean)arg).booleanValue() ? 'T' : 'F');
            else if (arg instanceof Blob) tags.append('b');
            else if (arg instanceof FloatArray) {
                for (float value : ((FloatArray)arg).values) {
                    if (Float.isNaN(value) || Float.isInfinite(value)) throw new IOException("Non-finite OSC float array value");
                    tags.append('f');
                }
            }
            else if (arg instanceof Number) {
                double v = ((Number)arg).doubleValue();
                if (Double.isNaN(v) || Double.isInfinite(v)) throw new IOException("Non-finite OSC number");
                boolean integer = arg instanceof Byte || arg instanceof Short || arg instanceof Integer || arg instanceof Long;
                if (integer && (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE))
                    throw new IOException("OSC int32 argument out of range");
                if (!integer && Float.isInfinite((float) v))
                    throw new IOException("OSC float32 argument out of range");
                tags.append(integer ? 'i' : 'f');
            } else if (arg instanceof String) {
                if (((String) arg).indexOf('\0') >= 0) throw new IOException("OSC string argument contains NUL");
                tags.append('s');
            }
            else throw new IOException("Unsupported OSC argument type");
        }
        writeString(out, tags.toString());
        if (args != null) for (Object arg : args) {
            if (arg instanceof Boolean) continue;
            if (arg instanceof Blob) { byte[] b=((Blob)arg).data; writeInt(out,b.length); out.write(b,0,b.length); pad(out); }
            else if (arg instanceof FloatArray) {
                for (float value : ((FloatArray)arg).values) writeInt(out, Float.floatToIntBits(value));
            }
            else if (arg instanceof Number) { double v=((Number)arg).doubleValue(); boolean i=arg instanceof Byte || arg instanceof Short || arg instanceof Integer || arg instanceof Long; writeInt(out,i?(int)v:Float.floatToIntBits((float)v)); }
            else writeString(out,(String)arg);
        }
        if (out.size() > 65507) throw new IOException("OSC packet exceeds UDP datagram limit");
        return out.toByteArray();
    }
    static final class Reply {
        final String address;
        final String command;
        final String message;
        final Integer syncId;
        final Integer bufferId;
        final Integer startFrame;
        final float[] samples;
        Reply(String address, String command, String message, Integer syncId) {
            this(address, command, message, syncId, null, null, null);
        }
        Reply(String address, String command, String message, Integer syncId,
              Integer bufferId, Integer startFrame, float[] samples) {
            this.address = address;
            this.command = command;
            this.message = message;
            this.syncId = syncId;
            this.bufferId = bufferId;
            this.startFrame = startFrame;
            this.samples = samples;
        }
    }

    /** Decodes the server acknowledgements needed by SynthDef loading and /sync. */
    static Reply decodeReply(byte[] packet) throws IOException {
        if (packet == null || packet.length < 8 || (packet.length & 3) != 0)
            throw new IOException("Invalid OSC reply packet");
        Cursor cursor = new Cursor();
        String address = readPaddedString(packet, cursor);
        String tags = readPaddedString(packet, cursor);
        if (tags.length() < 1 || tags.charAt(0) != ',')
            throw new IOException("Invalid OSC reply type tag");
        if ("/done".equals(address)) {
            // scsynth may append command-specific values to /done (for example
            // /b_alloc includes the allocated buffer id). Consume those values
            // instead of rejecting a valid acknowledgement.
            if (tags.length() < 2 || tags.charAt(0) != ',' || tags.charAt(1) != 's')
                throw new IOException("Unexpected /done arguments");
            String command = readPaddedString(packet, cursor);
            for (int i = 2; i < tags.length(); i++) {
                char type = tags.charAt(i);
                switch (type) {
                    case 'i': case 'f': case 'c': case 'r': case 'm':
                        readInt(packet, cursor);
                        break;
                    case 'T': case 'F': case 'N': case 'I':
                        break;
                    case 's': case 'S':
                        readPaddedString(packet, cursor);
                        break;
                    case 'b': {
                        int length = readInt(packet, cursor);
                        if (length < 0 || length > packet.length - cursor.offset)
                            throw new IOException("Invalid /done blob length");
                        int padded = (length + 3) & ~3;
                        if (padded > packet.length - cursor.offset)
                            throw new IOException("Truncated /done blob");
                        for (int padding = length; padding < padded; padding++) {
                            if (packet[cursor.offset + padding] != 0)
                                throw new IOException("Invalid /done blob padding");
                        }
                        cursor.offset += padded;
                        break;
                    }
                    default:
                        throw new IOException("Unsupported /done argument type: " + type);
                }
            }
            if (cursor.offset != packet.length) throw new IOException("Unexpected trailing /done data");
            return new Reply(address, command, null, null);
        }
        if ("/fail".equals(address)) {
            if (!",ss".equals(tags)) throw new IOException("Unexpected /fail arguments");
            String command = readPaddedString(packet, cursor);
            String message = readPaddedString(packet, cursor);
            if (cursor.offset != packet.length) throw new IOException("Unexpected trailing /fail data");
            return new Reply(address, command, message, null);
        }
        if ("/synced".equals(address)) {
            if (!",i".equals(tags) || packet.length - cursor.offset != 4)
                throw new IOException("Unexpected /synced arguments");
            int value = ((packet[cursor.offset] & 0xff) << 24)
                    | ((packet[cursor.offset + 1] & 0xff) << 16)
                    | ((packet[cursor.offset + 2] & 0xff) << 8)
                    | (packet[cursor.offset + 3] & 0xff);
            return new Reply(address, null, null, Integer.valueOf(value));
        }
        if ("/b_setn".equals(address)) {
            if (tags.length() < 4 || !tags.startsWith(",iii"))
                throw new IOException("Unexpected /b_setn reply arguments");
            int buffer = readInt(packet, cursor);
            int start = readInt(packet, cursor);
            int count = readInt(packet, cursor);
            if (buffer < 0 || start < 0 || count < 0 || count > 12000
                    || tags.length() != 4 + count
                    || packet.length - cursor.offset != count * 4)
                throw new IOException("Invalid /b_setn reply dimensions");
            float[] samples = new float[count];
            for (int i = 0; i < count; i++) {
                int bits = readInt(packet, cursor);
                float value = Float.intBitsToFloat(bits);
                if (Float.isNaN(value) || Float.isInfinite(value))
                    throw new IOException("Non-finite /b_setn sample");
                samples[i] = value;
            }
            return new Reply(address, null, null, null,
                    Integer.valueOf(buffer), Integer.valueOf(start), samples);
        }
        throw new IOException("Unsupported OSC reply: " + address);
    }

    private static int readInt(byte[] packet, Cursor cursor) throws IOException {
        if (cursor.offset < 0 || packet.length - cursor.offset < 4)
            throw new IOException("Truncated OSC int32");
        int value = ((packet[cursor.offset] & 0xff) << 24)
                | ((packet[cursor.offset + 1] & 0xff) << 16)
                | ((packet[cursor.offset + 2] & 0xff) << 8)
                | (packet[cursor.offset + 3] & 0xff);
        cursor.offset += 4;
        return value;
    }

    private static final class Cursor { int offset; }

    private static String readPaddedString(byte[] packet, Cursor cursor) throws IOException {
        if (cursor.offset < 0 || cursor.offset >= packet.length) throw new IOException("Truncated OSC string");
        int end = cursor.offset;
        while (end < packet.length && packet[end] != 0) end++;
        if (end == packet.length) throw new IOException("Unterminated OSC string");
        final String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(packet, cursor.offset, end - cursor.offset)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("Invalid UTF-8 in OSC string", invalidUtf8);
        }
        int next = (end + 4) & ~3;
        if (next > packet.length) throw new IOException("Truncated OSC string padding");
        for (int i = end; i < next; i++) if (packet[i] != 0) throw new IOException("Invalid OSC string padding");
        cursor.offset = next;
        return value;
    }

    private static void writeString(ByteArrayOutputStream o,String s) { byte[] b=s.getBytes(StandardCharsets.UTF_8); o.write(b,0,b.length); o.write(0); pad(o); }
    private static void pad(ByteArrayOutputStream o) { while ((o.size() & 3) != 0) o.write(0); }
    private static void writeInt(ByteArrayOutputStream o,int v) { o.write((v>>>24)&255); o.write((v>>>16)&255); o.write((v>>>8)&255); o.write(v&255); }
}
