package org.nornsmobile.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** UDP/OSC client for monome serialosc discovery, Grid and Arc devices. */
final class MonomeSerialoscBridge {
    interface Listener {
        void onDeviceAdded(Device device);
        void onDeviceRemoved(Device device);
        void onDeviceInfo(Device device);
        void onGridKey(int deviceId, int x, int y, int state);
        void onGridTilt(int deviceId, int x, int y, int z);
        void onArcDelta(int deviceId, int ring, int delta);
        void onArcKey(int deviceId, int ring, int state);
    }

    static final class Device {
        final int id;
        final String serial;
        final boolean arc;
        volatile String name;
        volatile String host;
        volatile String prefix = "/monome";
        volatile int port;
        volatile int cols;
        volatile int rows;
        volatile int rings;

        Device(int id, String serial, String name, String host, int port) {
            this.id = id;
            this.serial = serial;
            this.name = name;
            this.host = host;
            this.port = port;
            this.arc = isArcType(name);
            this.rings = arcRingCount(name);
        }
    }

    private static final int SERIALOSC_PORT = 12002;
    private static final int MAX_PACKET_BYTES = 65507;
    private final Listener listener;
    private final Map<String, Device> bySerial = new LinkedHashMap<>();
    private final Map<Integer, Device> byId = new LinkedHashMap<>();
    private DatagramSocket socket;
    private Thread receiver;
    private volatile boolean running;
    private String localHost = "127.0.0.1";
    private int localPort;
    private int nextDeviceId = 1;

    MonomeSerialoscBridge(Listener listener) {
        this.listener = listener;
    }

    synchronized boolean start() {
        if (running && socket != null && !socket.isClosed()) return true;
        try {
            socket = new DatagramSocket(0);
            socket.setBroadcast(true);
            localPort = socket.getLocalPort();
            localHost = chooseLocalAddress();
            running = true;
            final DatagramSocket listening = socket;
            receiver = new Thread(() -> receiveLoop(listening), "norns-serialosc");
            receiver.setDaemon(true);
            receiver.start();
        } catch (Exception error) {
            running = false;
            if (socket != null) socket.close();
            socket = null;
            AppLog.e("serialosc: não foi possível iniciar UDP (" + error.getMessage() + ").");
            return false;
        }
        refresh();
        AppLog.i("serialosc: descoberta UDP ativa em " + localHost + ":" + localPort + ".");
        return true;
    }

    synchronized boolean refresh() {
        if (!running || socket == null || socket.isClosed()) return false;
        try {
            byte[] list = OscBridge.encode("/serialosc/list",
                    new Object[]{localHost, Integer.valueOf(localPort)});
            byte[] notify = OscBridge.encode("/serialosc/notify",
                    new Object[]{localHost, Integer.valueOf(localPort)});
            List<InetAddress> broadcasts = chooseBroadcastAddresses();
            if (broadcasts.isEmpty()) broadcasts.add(InetAddress.getByName("255.255.255.255"));
            boolean sent = false;
            for (InetAddress broadcast : broadcasts) {
                sendPacket(socket, list, broadcast, SERIALOSC_PORT);
                sendPacket(socket, notify, broadcast, SERIALOSC_PORT);
                sent = true;
            }
            return sent;
        } catch (Exception error) {
            AppLog.e("serialosc discovery: " + error.getMessage());
            return false;
        }
    }

    synchronized List<Device> devices() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    synchronized boolean send(int deviceId, String path, Object[] args) {
        Device device = byId.get(deviceId);
        if (!running || socket == null || socket.isClosed() || device == null) return false;
        try {
            byte[] data = OscBridge.encode(devicePath(device.prefix, path), args);
            sendPacket(socket, data, InetAddress.getByName(device.host), device.port);
            return true;
        } catch (Exception error) {
            AppLog.e("serialosc envio " + device.name + ": " + error.getMessage());
            return false;
        }
    }

    synchronized void close() {
        running = false;
        DatagramSocket current = socket;
        socket = null;
        if (current != null) current.close();
        Thread thread = receiver;
        receiver = null;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(250); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        bySerial.clear();
        byId.clear();
    }

    private void receiveLoop(DatagramSocket listening) {
        byte[] buffer = new byte[MAX_PACKET_BYTES];
        while (running && !listening.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                listening.receive(packet);
                Object[] decoded = OscBridge.decode(packet.getData(), packet.getLength());
                if (decoded == null || decoded.length < 2
                        || !(decoded[0] instanceof String) || !(decoded[1] instanceof Object[])) continue;
                handleMessage(listening, (String) decoded[0], (Object[]) decoded[1],
                        packet.getAddress().getHostAddress(), packet.getPort());
            } catch (SocketException closed) {
                if (running) AppLog.e("serialosc socket: " + closed.getMessage());
                break;
            } catch (IOException error) {
                if (running) AppLog.e("serialosc receive: " + error.getMessage());
            } catch (RuntimeException error) {
                if (running) AppLog.e("serialosc decode: " + error.getMessage());
            }
        }
    }

    private synchronized void handleMessage(DatagramSocket listening, String path, Object[] args,
                                             String sourceHost, int sourcePort) {
        // Serialize dispatch with close()/restart and reject packets from an old receiver socket.
        if (!running || socket != listening || listening.isClosed()) return;
        if ("/serialosc/device".equals(path) && args.length >= 3
                && args[0] instanceof String && args[1] instanceof String
                && args[2] instanceof Number) {
            registerDevice((String) args[0], (String) args[1], sourceHost,
                    ((Number) args[2]).intValue());
            return;
        }
        if ("/serialosc/add".equals(path)) {
            if (args.length >= 3 && args[0] instanceof String && args[1] instanceof String
                    && args[2] instanceof Number) {
                registerDevice((String) args[0], (String) args[1], sourceHost,
                        ((Number) args[2]).intValue());
            }
            // /serialosc/notify is one-shot: renew it whenever the daemon reports a change.
            refresh();
            return;
        }
        if ("/serialosc/remove".equals(path)) {
            if (args.length > 0 && args[0] instanceof String) removeDevice((String) args[0]);
            refresh();
            return;
        }

        Device device = findDevice(sourceHost, sourcePort, path);
        if (device == null) return;
        if ("/sys/size".equals(path) && args.length >= 2
                && args[0] instanceof Number && args[1] instanceof Number) {
            device.cols = Math.max(0, ((Number) args[0]).intValue());
            device.rows = Math.max(0, ((Number) args[1]).intValue());
            notifyListener("device info", () -> listener.onDeviceInfo(device));
            return;
        }
        if ("/sys/prefix".equals(path) && args.length > 0 && args[0] instanceof String) {
            device.prefix = normalizePrefix((String) args[0]);
            notifyListener("device info", () -> listener.onDeviceInfo(device));
            return;
        }
        if (path.endsWith("/grid/key") && args.length >= 3
                && args[0] instanceof Number && args[1] instanceof Number && args[2] instanceof Number) {
            int x = ((Number) args[0]).intValue();
            int y = ((Number) args[1]).intValue();
            int state = ((Number) args[2]).intValue();
            int maxCols = device.cols > 0 ? Math.min(128, device.cols) : 128;
            int maxRows = device.rows > 0 ? Math.min(64, device.rows) : 64;
            if (x >= 0 && x < maxCols && y >= 0 && y < maxRows && (state == 0 || state == 1))
                notifyListener("grid key", () -> listener.onGridKey(device.id, x + 1, y + 1, state));
        } else if (path.endsWith("/tilt") && args.length >= 4
                && args[0] instanceof Number && args[1] instanceof Number
                && args[2] instanceof Number && args[3] instanceof Number) {
            // serialosc includes a sensor index; norns' grid.tilt callback exposes only x/y/z.
            int sensor = ((Number) args[0]).intValue();
            int x = ((Number) args[1]).intValue();
            int y = ((Number) args[2]).intValue();
            int z = ((Number) args[3]).intValue();
            if (sensor >= 0 && sensor < 16 && x >= -32768 && x <= 32767
                    && y >= -32768 && y <= 32767 && z >= -32768 && z <= 32767)
                notifyListener("grid tilt", () -> listener.onGridTilt(device.id, x, y, z));
        } else if (path.endsWith("/enc/delta") && args.length >= 2
                && args[0] instanceof Number && args[1] instanceof Number) {
            int ring = ((Number) args[0]).intValue();
            int delta = ((Number) args[1]).intValue();
            if (ring >= 0 && ring < Math.max(1, device.rings) && delta >= -32768 && delta <= 32767)
                notifyListener("arc delta", () -> listener.onArcDelta(device.id, ring + 1, delta));
        } else if (path.endsWith("/enc/key") && args.length >= 2
                && args[0] instanceof Number && args[1] instanceof Number) {
            int ring = ((Number) args[0]).intValue();
            int state = ((Number) args[1]).intValue();
            if (ring >= 0 && ring < Math.max(1, device.rings) && (state == 0 || state == 1))
                notifyListener("arc key", () -> listener.onArcKey(device.id, ring + 1, state));
        }
    }

    private void notifyListener(String action, Runnable callback) {
        if (listener == null) return;
        try {
            callback.run();
        } catch (RuntimeException error) {
            AppLog.e("serialosc callback " + action + ": " + error.getMessage());
        }
    }

    private synchronized void registerDevice(String serial, String name, String host, int port) {
        if (serial == null || serial.trim().isEmpty() || port < 1 || port > 65535) return;
        Device device = bySerial.get(serial);
        boolean added = device == null;
        if (added) {
            device = new Device(nextDeviceId++, serial, name, host, port);
            bySerial.put(serial, device);
            byId.put(device.id, device);
        } else {
            device.name = name;
            device.host = host;
            device.port = port;
        }
        try {
            sendControl(device, "/sys/host", localHost);
            sendControl(device, "/sys/port", Integer.valueOf(localPort));
            // Preserve the device's saved prefix; /sys/info reports it back to us.
            sendControl(device, "/sys/info", localHost, Integer.valueOf(localPort));
        } catch (IOException error) {
            AppLog.e("serialosc configuração " + name + ": " + error.getMessage());
        }
        if (added) {
            final Device registered = device;
            notifyListener("device added", () -> listener.onDeviceAdded(registered));
        }
    }

    private synchronized void removeDevice(String serial) {
        Device removed = bySerial.remove(serial);
        if (removed == null) return;
        byId.remove(removed.id);
        notifyListener("device removed", () -> listener.onDeviceRemoved(removed));
    }

    private synchronized Device findDevice(String host, int sourcePort, String path) {
        for (Device device : byId.values()) {
            if (device.host.equals(host) && device.port == sourcePort) return device;
        }
        for (Device device : byId.values()) {
            if (device.host.equals(host) && path.startsWith(device.prefix + "/")) return device;
        }
        return null;
    }

    private void sendControl(Device device, String path, Object... args) throws IOException {
        DatagramSocket current = socket;
        if (current == null || current.isClosed()) throw new IOException("socket fechado");
        byte[] data = OscBridge.encode(path, args);
        sendPacket(current, data, InetAddress.getByName(device.host), device.port);
    }

    private static void sendPacket(DatagramSocket socket, byte[] data,
                                   InetAddress address, int port) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES)
            throw new IOException("pacote OSC inválido");
        socket.send(new DatagramPacket(data, data.length, address, port));
    }

    static boolean isArcType(String type) {
        return type != null && type.toLowerCase(Locale.US).contains("arc");
    }

    static int arcRingCount(String type) {
        if (!isArcType(type)) return 0;
        String lower = type.toLowerCase(Locale.US);
        for (int rings : new int[]{4, 2, 1}) {
            String digits = lower.replaceAll("[^0-9]+", " ").trim();
            if (digits.matches(".*(?:^|\\s)" + rings + "(?:$|\\s).*")) return rings;
        }
        return 4;
    }

    static int arcLedIndex(double radians) {
        if (Double.isNaN(radians) || Double.isInfinite(radians)) return 0;
        double turn = radians % (Math.PI * 2.0);
        if (turn < 0) turn += Math.PI * 2.0;
        return ((int) Math.floor((turn / (Math.PI * 2.0)) * 64.0 + 0.5)) % 64;
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) return "/monome";
        String value = prefix.trim();
        return value.charAt(0) == '/' ? value : "/" + value;
    }

    static String prefixedPath(String prefix, String path) {
        String normalizedPrefix = normalizePrefix(prefix);
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("OSC path vazia");
        String suffix = path.trim();
        if (suffix.charAt(0) != '/') suffix = "/" + suffix;
        if (suffix.startsWith(normalizedPrefix + "/") || suffix.equals(normalizedPrefix)) return suffix;
        return normalizedPrefix + suffix;
    }

    static String devicePath(String prefix, String path) {
        if (path != null && path.startsWith("/sys/")) return path;
        return prefixedPath(prefix, path);
    }

    private static String chooseLocalAddress() {
        NetworkTarget target = chooseNetworkTarget();
        return target == null ? OscBridge.localIpv4Address() : target.host;
    }

    private static List<InetAddress> chooseBroadcastAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.US);
                if (!(name.startsWith("wlan") || name.contains("wifi")
                        || name.startsWith("eth") || name.startsWith("en"))) continue;
                for (InterfaceAddress entry : network.getInterfaceAddresses()) {
                    InetAddress address = entry.getAddress();
                    InetAddress broadcast = entry.getBroadcast();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress() && broadcast != null
                            && !addresses.contains(broadcast)) addresses.add(broadcast);
                }
            }
        } catch (Exception ignored) {}
        return addresses;
    }

    private static final class NetworkTarget {
        final String host;
        final int priority;
        NetworkTarget(String host, int priority) { this.host = host; this.priority = priority; }
    }

    private static NetworkTarget chooseNetworkTarget() {
        NetworkTarget selected = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.US);
                boolean wifi = name.startsWith("wlan") || name.contains("wifi");
                boolean ethernet = name.startsWith("eth") || name.startsWith("en");
                // Discovery broadcasts are sent only on these LAN interfaces. Select the
                // advertised reply address from the same class of interface, never cellular/VPN.
                if (!wifi && !ethernet) continue;
                int priority = wifi ? 0 : 1;
                for (InterfaceAddress entry : network.getInterfaceAddresses()) {
                    InetAddress address = entry.getAddress();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || entry.getBroadcast() == null) continue;
                    NetworkTarget candidate = new NetworkTarget(address.getHostAddress(), priority);
                    if (selected == null || candidate.priority < selected.priority) selected = candidate;
                }
            }
        } catch (Exception ignored) {}
        return selected;
    }
}
