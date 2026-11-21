package org.nornsmobile.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.util.Enumeration;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** OSC 1.1 UDP transport: messages, bounded nested/timetagged bundles and common scalar/blob types. */
final class OscBridge {
    interface Listener { void onMessage(String path, Object[] args, String host, int port); }
    private static final long NTP_UNIX_EPOCH_SECONDS = 2208988800L;
    private static final int MAX_BUNDLE_MESSAGES = 256;
    private static final int MAX_BUNDLE_DEPTH = 8;

    static final class ScheduledMessage {
        final String path;
        final Object[] args;
        /** Unix epoch milliseconds; 0 means immediate. */
        final long dueTimeMillis;
        ScheduledMessage(String path, Object[] args, long dueTimeMillis) {
            this.path = path; this.args = args; this.dueTimeMillis = dueTimeMillis;
        }
    }

    private DatagramSocket socket;
    private volatile boolean running;
    private Listener listener;
    private ScheduledExecutorService scheduler;
    private AtomicInteger scheduledCount = new AtomicInteger();

    synchronized void listen(int port, Listener cb) throws SocketException {
        if (port < 1 || port > 65535) throw new SocketException("OSC port out of range");
        close();
        final DatagramSocket listening = new DatagramSocket(port);
        socket = listening;
        listener = cb;
        running = true;
        scheduledCount = new AtomicInteger();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "norns-osc-timetags");
            thread.setDaemon(true);
            return thread;
        });
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[65507];
            while (running && socket == listening && !listening.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    listening.receive(packet);
                    List<ScheduledMessage> decoded = decodeScheduled(
                            packet.getData(), packet.getLength(), System.currentTimeMillis());
                    if (decoded == null) continue;
                    for (ScheduledMessage message : decoded) {
                        final String sourceHost = packet.getAddress().getHostAddress();
                        final int sourcePort = packet.getPort();
                        final long delay = message.dueTimeMillis <= 0 ? 0
                                : Math.max(0, message.dueTimeMillis - System.currentTimeMillis());
                        ScheduledExecutorService executor;
                        synchronized (OscBridge.this) {
                            if (!running || socket != listening) break;
                            executor = scheduler;
                        }
                        if (executor == null) break;
                        AtomicInteger counter = scheduledCount;
                        if (counter.incrementAndGet() > 1024) {
                            counter.decrementAndGet();
                            AppLog.e("OSC: limite de 1024 mensagens agendadas atingido; pacote excedente descartado.");
                            continue;
                        }
                        try {
                            executor.schedule(() -> {
                                try {
                                    Listener callback;
                                    synchronized (OscBridge.this) {
                                        if (!running || socket != listening) return;
                                        callback = listener;
                                    }
                                    // Do not execute script callbacks while holding the transport lock.
                                    if (callback != null) {
                                        try {
                                            callback.onMessage(message.path, message.args, sourceHost, sourcePort);
                                        } catch (RuntimeException error) {
                                            AppLog.e("OSC callback: " + error.getMessage());
                                        }
                                    }
                                } finally {
                                    counter.decrementAndGet();
                                }
                            }, delay, TimeUnit.MILLISECONDS);
                        } catch (RuntimeException rejected) {
                            counter.decrementAndGet();
                            if (running && socket == listening)
                                AppLog.e("OSC schedule: " + rejected.getMessage());
                        }
                    }
                } catch (IOException error) {
                    if (running && socket == listening)
                        AppLog.e("OSC receive: " + error.getMessage());
                } catch (RuntimeException error) {
                    if (running && socket == listening)
                        AppLog.e("OSC dispatch: " + error.getMessage());
                }
            }
        }, "norns-osc-udp");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void send(String host, int port, String path, Object[] args) throws IOException {
        if (host == null || host.trim().isEmpty()) throw new IOException("OSC destination host is empty");
        if (port < 1 || port > 65535) throw new IOException("OSC destination port out of range");
        byte[] data = encode(path, args);
        if (data.length > 65507) throw new IOException("OSC datagram exceeds UDP payload limit");
        DatagramSocket sender = new DatagramSocket();
        try {
            sender.setBroadcast(true);
            sender.send(new DatagramPacket(data, data.length, InetAddress.getByName(host), port));
        } finally {
            sender.close();
        }
    }

    static byte[] encode(String path, Object[] args) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("OSC path is empty");
        if (path.indexOf('\0') >= 0) throw new IOException("OSC path contains NUL");
        String address = path.charAt(0) == '/' ? path : "/" + path;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, address);
        Object[] values = args == null ? new Object[0] : args;
        StringBuilder tags = new StringBuilder(",");
        for (Object value : values) {
            if (value == null) tags.append('N');
            else if (value instanceof Boolean) tags.append(((Boolean) value).booleanValue() ? 'T' : 'F');
            else if (value instanceof Byte || value instanceof Short || value instanceof Integer) tags.append('i');
            else if (value instanceof Long) tags.append('h');
            else if (value instanceof Float) tags.append('f');
            else if (value instanceof Double) tags.append('d');
            else if (value instanceof byte[]) tags.append('b');
            else if (value instanceof Character) tags.append('c');
            else {
                String text = String.valueOf(value);
                if (text.indexOf('\0') >= 0) throw new IOException("OSC string argument contains NUL");
                tags.append('s');
            }
        }
        writeString(out, tags.toString());
        for (Object value : values) {
            if (value == null || value instanceof Boolean) continue;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer)
                writeInt(out, ((Number) value).intValue());
            else if (value instanceof Long) writeLong(out, ((Long) value).longValue());
            else if (value instanceof Float) writeInt(out, Float.floatToIntBits(((Float) value).floatValue()));
            else if (value instanceof Double) writeLong(out, Double.doubleToLongBits(((Double) value).doubleValue()));
            else if (value instanceof byte[]) writeBlob(out, (byte[]) value);
            else if (value instanceof Character) writeInt(out, (int) ((Character) value).charValue());
            else writeString(out, String.valueOf(value));
        }
        byte[] packet = out.toByteArray();
        if (packet.length > 65507) throw new IOException("OSC datagram exceeds UDP payload limit");
        return packet;
    }

    synchronized void sendBundle(String host, int port, byte[][] messages, long timetag) throws IOException {
        if (host == null || host.trim().isEmpty()) throw new IOException("OSC destination host is empty");
        if (port < 1 || port > 65535) throw new IOException("OSC destination port out of range");
        byte[] data = encodeBundle(messages, timetag);
        DatagramSocket sender = new DatagramSocket();
        try {
            sender.setBroadcast(true);
            sender.send(new DatagramPacket(data, data.length, InetAddress.getByName(host), port));
        } finally {
            sender.close();
        }
    }

    static String localIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress candidate = address.getAddress();
                    if (candidate instanceof Inet4Address && !candidate.isLoopbackAddress()
                            && !candidate.isLinkLocalAddress()) return candidate.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    synchronized void close() {
        running = false;
        listener = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        writeInt(out, (int) (value >>> 32));
        writeInt(out, (int) value);
    }
    private static void writeBlob(ByteArrayOutputStream out, byte[] bytes) {
        writeInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
        while ((out.size() & 3) != 0) out.write(0);
    }
    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 255); o.write((v >>> 16) & 255);
        o.write((v >>> 8) & 255); o.write(v & 255);
    }
    private static void writeString(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(b, 0, b.length); o.write(0);
        while ((o.size() & 3) != 0) o.write(0);
    }

    /** Encode an OSC bundle with an NTP timetag and already encoded child messages. */
    static byte[] encodeBundle(byte[][] messages, long timetag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, "#bundle");
        writeLong(out, timetag);
        if (messages != null) {
            for (byte[] message : messages) {
                if (message == null || message.length == 0) continue;
                if ((message.length & 3) != 0) throw new IOException("OSC bundle element must be 4-byte aligned");
                writeInt(out, message.length);
                out.write(message, 0, message.length);
            }
        }
        byte[] packet = out.toByteArray();
        if (packet.length > 65507) throw new IOException("OSC bundle exceeds UDP payload limit");
        return packet;
    }

    static List<Object[]> decodeAll(byte[] data, int length) {
        List<ScheduledMessage> scheduled = decodeScheduled(data, length, System.currentTimeMillis());
        if (scheduled == null) return null;
        List<Object[]> messages = new ArrayList<>(scheduled.size());
        for (ScheduledMessage item : scheduled) messages.add(new Object[]{item.path, item.args});
        return messages;
    }

    static List<ScheduledMessage> decodeScheduled(byte[] data, int length, long nowMillis) {
        return decodeScheduled(data, length, 0, new int[]{0}, 0L, nowMillis);
    }

    private static List<ScheduledMessage> decodeScheduled(byte[] data, int length, int depth,
                                                           int[] total, long parentDue,
                                                           long nowMillis) {
        if (data == null || length < 4 || length > data.length
                || depth > MAX_BUNDLE_DEPTH || total[0] > MAX_BUNDLE_MESSAGES) return null;
        boolean bundle = length >= 16
                && data[0] == '#' && data[1] == 'b' && data[2] == 'u' && data[3] == 'n'
                && data[4] == 'd' && data[5] == 'l' && data[6] == 'e' && data[7] == 0;
        if (!bundle) {
            Object[] decoded = decode(data, length);
            if (decoded == null || ++total[0] > MAX_BUNDLE_MESSAGES) return null;
            long due = parentDue;
            List<ScheduledMessage> one = new ArrayList<>(1);
            one.add(new ScheduledMessage((String) decoded[0], (Object[]) decoded[1], due));
            return one;
        }

        long timetag = readLong(data, 8);
        long ownDue = ntpToUnixMillis(timetag, nowMillis);
        if (ownDue < 0L) return null; // Invalid/out-of-range NTP values are not immediate.
        if (ownDue == 0L) ownDue = parentDue; // OSC immediate timetag inherits parent.
        else if (parentDue > 0L) ownDue = Math.max(parentDue, ownDue);
        int position = 16;
        List<ScheduledMessage> result = new ArrayList<>();
        while (position < length) {
            if (position + 4 > length) return null;
            int[] cursor = {position};
            int elementLength = readInt(data, cursor);
            position = cursor[0];
            if (elementLength <= 0 || (elementLength & 3) != 0
                    || elementLength > length - position) return null;
            List<ScheduledMessage> nested = decodeScheduled(
                    Arrays.copyOfRange(data, position, position + elementLength),
                    elementLength, depth + 1, total, ownDue, nowMillis);
            if (nested == null) return null;
            result.addAll(nested);
            if (result.size() > MAX_BUNDLE_MESSAGES) return null;
            position += elementLength;
        }
        return result;
    }

    static long unixMillisToNtp(long unixMillis) {
        if (unixMillis <= 0L) return 1L;
        long seconds = unixMillis / 1000L + NTP_UNIX_EPOCH_SECONDS;
        long fraction = ((unixMillis % 1000L) << 32) / 1000L;
        return (seconds << 32) | (fraction & 0xffffffffL);
    }

    static long ntpToUnixMillis(long timetag) {
        return ntpToUnixMillis(timetag, System.currentTimeMillis());
    }


    /** Resolve the 32-bit NTP seconds field to the era nearest the supplied clock. */
    static long ntpToUnixMillis(long timetag, long referenceMillis) {
        if (timetag == 1L) return 0L; // OSC immediate
        if (timetag == 0L || referenceMillis < 0L) return -1L;
        final long eraSeconds = 0x100000000L;
        long seconds32 = (timetag >>> 32) & 0xffffffffL;
        long fraction = timetag & 0xffffffffL;
        long referenceNtpSeconds = referenceMillis / 1000L + NTP_UNIX_EPOCH_SECONDS;
        long referenceEra = Math.floorDiv(referenceNtpSeconds, eraSeconds);
        long bestUnixSeconds = Long.MIN_VALUE;
        long bestDistance = Long.MAX_VALUE;
        for (long era = referenceEra - 1L; era <= referenceEra + 1L; era++) {
            long ntpSeconds = era * eraSeconds + seconds32;
            long unixSeconds = ntpSeconds - NTP_UNIX_EPOCH_SECONDS;
            if (unixSeconds < 0L || unixSeconds > 253402300799L) continue;
            long distance = Math.abs(unixSeconds - referenceMillis / 1000L);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestUnixSeconds = unixSeconds;
            }
        }
        if (bestUnixSeconds == Long.MIN_VALUE) return -1L;
        return bestUnixSeconds * 1000L + ((fraction * 1000L) >>> 32);
    }

    static Object[] decode(byte[] data, int length) {
        if (data == null || length < 8 || length > data.length) return null;
        // DatagramPacket reuses a larger receive buffer; never let its unused tail
        // satisfy reads beyond the packet's actual length.
        byte[] packet = Arrays.copyOf(data, length);
        try {
            int[] position = {0};
            String path = readString(packet, length, position);
            String tags = readString(packet, length, position);
            if (path == null || path.isEmpty() || tags == null || !tags.startsWith(",")) return null;
            ArrayList<Object> values = new ArrayList<>();
            for (int i = 1; i < tags.length(); i++) {
                char type = tags.charAt(i);
                if (type == 'i' || type == 'f') {
                    if (position[0] + 4 > length) return null;
                    int bits = readInt(packet, position);
                    if (type == 'i') values.add(Integer.valueOf(bits));
                    else values.add(Float.valueOf(Float.intBitsToFloat(bits)));
                } else if (type == 'h' || type == 'd') {
                    if (position[0] + 8 > length) return null;
                    long bits = readLong(packet, position[0]);
                    position[0] += 8;
                    if (type == 'h') values.add(Long.valueOf(bits));
                    else values.add(Double.valueOf(Double.longBitsToDouble(bits)));
                } else if (type == 's') {
                    String value = readString(packet, length, position);
                    if (value == null) return null;
                    values.add(value);
                } else if (type == 'b') {
                    if (position[0] + 4 > length) return null;
                    int size = readInt(packet, position);
                    if (size < 0 || size > length - position[0]) return null;
                    values.add(Arrays.copyOfRange(packet, position[0], position[0] + size));
                    int payloadEnd = position[0] + size;
                    int next = (payloadEnd + 3) & ~3;
                    if (next > length) return null;
                    for (int padding = payloadEnd; padding < next; padding++)
                        if (packet[padding] != 0) return null;
                    position[0] = next;
                } else if (type == 'c') {
                    if (position[0] + 4 > length) return null;
                    values.add(Character.valueOf((char) readInt(packet, position)));
                } else if (type == 'T') values.add(Boolean.TRUE);
                else if (type == 'F') values.add(Boolean.FALSE);
                else if (type == 'N') values.add(null);
                else return null;
            }
            if (position[0] != length) return null;
            return new Object[]{path, values.toArray()};
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static int readInt(byte[] data, int[] position) {
        int p = position[0];
        if (p < 0 || p > data.length - 4) throw new IndexOutOfBoundsException();
        int value = ((data[p] & 255) << 24) | ((data[p + 1] & 255) << 16)
                | ((data[p + 2] & 255) << 8) | (data[p + 3] & 255);
        position[0] = p + 4;
        return value;
    }
    private static long readLong(byte[] data, int offset) {
        if (offset < 0 || offset > data.length - 8) throw new IndexOutOfBoundsException();
        long high = ((long) readInt(data, new int[]{offset})) & 0xffffffffL;
        long low = ((long) readInt(data, new int[]{offset + 4})) & 0xffffffffL;
        return (high << 32) | low;
    }
    private static String readString(byte[] data, int length, int[] position) {
        int start = position[0];
        if (start < 0 || start >= length) return null;
        int end = start;
        while (end < length && data[end] != 0) end++;
        if (end >= length) return null;
        final String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, start, end - start)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            return null;
        }
        int next = (end + 4) & ~3;
        if (next > length) return null;
        for (int padding = end; padding < next; padding++)
            if (data[padding] != 0) return null;
        position[0] = next;
        return value;
    }
}