package org.nornsmobile.app;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Android USB/BLE/virtual MIDI bridge. Ports are exposed with stable IDs for this session. */
final class MidiBridge {
    interface Listener {
        void onMidi(int deviceId, int status, int data1, int data2);
        void onMidiConnection(int deviceId, boolean connected, String reason);
        void onMidiDeviceChanged(Device device, boolean added);
        default void onMidiSystemExclusive(int deviceId, byte[] data) {}
    }

    static final class Device {
        final int id;
        final String name;
        final int inputs;
        final int outputs;
        Device(int id, String name, int inputs, int outputs) {
            this.id = id; this.name = name; this.inputs = inputs; this.outputs = outputs;
        }
    }

    static Device deviceForAndroidPort(int id, String name, int portIndex,
                                       int androidInputPorts, int androidOutputPorts) {
        // Android input ports receive data from Norns; Android output ports send data to Norns.
        return new Device(id, name,
                portIndex < androidOutputPorts ? 1 : 0,
                portIndex < androidInputPorts ? 1 : 0);
    }

    private static final class Connection {
        MidiDevice device;
        MidiInputPort input;
        MidiOutputPort output;
        int portIndex;
    }

    private static final int MAX_PENDING_MESSAGES = 256;
    private final MidiManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MidiManager.DeviceCallback deviceCallback;
    private final Listener listener;
    private volatile boolean closed;
    private final Map<Integer, MidiDeviceInfo> infos = new HashMap<>();
    private final MidiDeviceIdRegistry deviceIds = new MidiDeviceIdRegistry();
    private final MidiPortIdRegistry portIds = new MidiPortIdRegistry();
    private final Map<Integer, Integer> portIndices = new HashMap<>();
    private final java.util.Map<Integer, Connection> connections = new java.util.HashMap<>();
    private final java.util.Map<Integer, MidiStreamParser> parsers = new java.util.HashMap<>();
    private final MidiOpenRequests openingRequests = new MidiOpenRequests();

    MidiBridge(Context context, Listener listener) {
        this.manager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
        this.listener = listener;
        this.deviceCallback = new MidiManager.DeviceCallback() {
            @Override public void onDeviceAdded(MidiDeviceInfo info) {
                synchronized (MidiBridge.this) {
                    if (closed) return;
                    List<Device> current = refresh();
                    int sessionId = deviceIds.sessionIdFor(info.getId());
                    if (sessionId < 0 || MidiBridge.this.listener == null) return;
                    for (Device device : current) {
                        if (portIds.sessionForPort(device.id) == sessionId)
                            MidiBridge.this.notifyDeviceChanged(device, true);
                    }
                }
            }

            @Override public void onDeviceRemoved(MidiDeviceInfo info) {
                synchronized (MidiBridge.this) {
                    if (closed) return;
                    int sessionId = deviceIds.sessionIdFor(info.getId());
                    List<Device> removed = new ArrayList<>();
                    if (sessionId >= 0) {
                        for (Integer logicalId : portIds.idsForSession(sessionId)) {
                            MidiDeviceInfo oldInfo = infos.get(logicalId);
                            Integer index = portIndices.get(logicalId);
                            if (oldInfo != null && index != null)
                                removed.add(describePort(oldInfo, logicalId, index));
                        }
                    }
                    refresh();
                    if (MidiBridge.this.listener != null)
                        for (Device device : removed)
                            MidiBridge.this.notifyDeviceChanged(device, false);
                }
            }

            @Override public void onDeviceStatusChanged(android.media.midi.MidiDeviceStatus status) {
                refresh();
            }
        };
        if (manager != null) {
            manager.registerDeviceCallback(deviceCallback, handler);
            refresh();
        }
    }

    synchronized List<Device> refresh() {
        if (closed || manager == null) return Collections.emptyList();
        MidiDeviceInfo[] found = manager.getDevices();
        Set<Integer> androidIds = new HashSet<>();
        Set<Integer> activeSessionIds = new HashSet<>();
        Set<Long> activePortKeys = new HashSet<>();
        Map<Integer, MidiDeviceInfo> refreshed = new HashMap<>();
        Map<Integer, Integer> refreshedIndices = new HashMap<>();
        List<Device> out = new ArrayList<>();
        if (found != null) {
            for (MidiDeviceInfo info : found) {
                int androidId = info.getId();
                androidIds.add(androidId);
                int sessionId = deviceIds.idFor(androidId);
                activeSessionIds.add(sessionId);
                int sendPorts = info.getInputPortCount();
                int receivePorts = info.getOutputPortCount();
                int portCount = Math.max(sendPorts, receivePorts);
                String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
                if (name == null || name.trim().isEmpty()) name = "MIDI device " + androidId;
                for (int portIndex = 0; portIndex < portCount; portIndex++) {
                    int logicalId = portIds.idFor(sessionId, portIndex);
                    activePortKeys.add(MidiPortIdRegistry.portKey(sessionId, portIndex));
                    refreshed.put(logicalId, info);
                    refreshedIndices.put(logicalId, portIndex);
                    String portName = portCount > 1 ? name + " — porta " + (portIndex + 1) : name;
                    out.add(deviceForAndroidPort(logicalId, portName, portIndex,
                            sendPorts, receivePorts));
                }
            }
        }
        deviceIds.retain(androidIds);
        for (Integer removedId : portIds.retainPorts(activePortKeys)) {
            openingRequests.cancel(removedId);
            parsers.remove(removedId);
            closeQuietly(connections.remove(removedId));
            notifyConnection(removedId, false, "porta MIDI removida");
        }
        infos.clear();
        infos.putAll(refreshed);
        portIndices.clear();
        portIndices.putAll(refreshedIndices);
        Collections.sort(out, (left, right) -> Integer.compare(left.id, right.id));
        return out;
    }

    private void notifyConnection(int id, boolean connected, String reason) {
        if (listener == null) return;
        try {
            listener.onMidiConnection(id, connected, reason);
        } catch (RuntimeException error) {
            AppLog.e("MIDI callback de conexão " + id + ": " + error.getMessage());
        }
    }

    private void notifyDeviceChanged(Device device, boolean added) {
        if (listener == null) return;
        try {
            listener.onMidiDeviceChanged(device, added);
        } catch (RuntimeException error) {
            AppLog.e("MIDI callback de dispositivo: " + error.getMessage());
        }
    }

    private void notifyMidi(int id, int status, int data1, int data2) {
        if (listener == null) return;
        try {
            listener.onMidi(id, status, data1, data2);
        } catch (RuntimeException error) {
            AppLog.e("MIDI callback de mensagem " + id + ": " + error.getMessage());
        }
    }

    private void notifyMidiSystemExclusive(int id, byte[] data) {
        if (listener == null) return;
        try {
            listener.onMidiSystemExclusive(id, data);
        } catch (RuntimeException error) {
            AppLog.e("MIDI callback SysEx " + id + ": " + error.getMessage());
        }
    }

    private static Device describePort(MidiDeviceInfo info, int logicalId, int portIndex) {
        if (info == null) return null;
        String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name == null || name.trim().isEmpty()) name = "MIDI device " + info.getId();
        int count = Math.max(info.getInputPortCount(), info.getOutputPortCount());
        String portName = count > 1 ? name + " — porta " + (portIndex + 1) : name;
        // Keep hotplug removal metadata identical to refresh(): Android output ports
        // deliver MIDI to Norns, while Android input ports receive MIDI from Norns.
        return deviceForAndroidPort(logicalId, portName, portIndex,
                info.getInputPortCount(), info.getOutputPortCount());
    }

    void shutdown() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            closeAll();
        }
        if (manager != null) {
            try { manager.unregisterDeviceCallback(deviceCallback); }
            catch (RuntimeException ignored) {}
        }
    }

    synchronized List<Device> devices() { return refresh(); }

    boolean open(final int id) {
        if (manager == null) { AppLog.e("MIDI: serviço Android indisponível"); return false; }
        final MidiDeviceInfo info;
        final long token;
        synchronized (this) {
            if (closed) return false;
            if (!infos.containsKey(id)) {
                AppLog.e("MIDI: dispositivo " + id + " não encontrado");
                return false;
            }
            if (connections.containsKey(id)) {
                notifyConnection(id, true, null);
                return true;
            }
            if (openingRequests.isOpening(id)) return true;
            info = infos.get(id);
            token = openingRequests.begin(id, MAX_PENDING_MESSAGES);
            if (token == 0L) return true;
        }

        try {
            synchronized (this) {
                if (closed || !openingRequests.isCurrent(id, token)) return true;
                manager.openDevice(info, new MidiManager.OnDeviceOpenedListener() {
            @Override public void onDeviceOpened(MidiDevice device) {
                if (device == null) {
                    failOpen(id, token, "Android MidiManager retornou dispositivo nulo");
                    return;
                }

                Connection connection = new Connection();
                connection.device = device;
                try {
                    MidiDeviceInfo openedInfo = device.getInfo();
                    final int portIndex;
                    synchronized (MidiBridge.this) {
                        Integer selected = portIndices.get(id);
                        if (selected == null) {
                            closeQuietly(connection);
                            failOpen(id, token, "porta MIDI removida durante a abertura");
                            return;
                        }
                        portIndex = selected;
                    }
                    connection.portIndex = portIndex;
                    if (openedInfo.getInputPortCount() > portIndex) {
                        connection.input = device.openInputPort(portIndex);
                    }
                    if (openedInfo.getOutputPortCount() > portIndex) {
                        connection.output = device.openOutputPort(portIndex);
                        if (connection.output != null) {
                            connection.output.connect(new MidiReceiver() {
                                @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
                                    synchronized (MidiBridge.this) {
                                        if (connections.get(id) != connection) return;
                                    }
                                    parse(id, connection, data, offset, count);
                                }
                            });
                        }
                    }

                    synchronized (MidiBridge.this) {
                        if (!openingRequests.isCurrent(id, token)) {
                            closeQuietly(connection);
                            return; // fechamento ou nova abertura ocorreu antes do callback
                        }

                        MidiOpenRequests.Completed completed = openingRequests.complete(id, token);
                        if (completed == null) {
                            closeQuietly(connection);
                            return;
                        }
                        Connection old = connections.put(id, connection);
                        closeQuietly(old);
                        parsers.put(id, new MidiStreamParser());

                        List<byte[]> waiting = completed.messages;
                        if (connection.input != null) {
                            for (byte[] message : waiting) sendToConnection(id, connection, message);
                        } else if (!waiting.isEmpty()) {
                            AppLog.e("MIDI: dispositivo " + id
                                    + " não possui porta de entrada; " + waiting.size()
                                    + " mensagem(ns) pendente(s) descartada(s).");
                        }
                        if (completed.droppedCount > 0) {
                            AppLog.e("MIDI: fila de abertura do dispositivo " + id
                                    + " descartou " + completed.droppedCount
                                    + " mensagem(ns) por limite de " + MAX_PENDING_MESSAGES + ".");
                        }
                    }
                    synchronized (MidiBridge.this) {
                        // A close/remove may race with the asynchronous Android open callback.
                        if (connections.get(id) != connection) return;
                        notifyConnection(id, true, null);
                    }
                    String name = openedInfo.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
                    AppLog.i("MIDI conectado: " + (name == null ? ("dispositivo " + id) : name)
                            + (connection.input == null ? " (sem porta de entrada para envio)" : ""));
                } catch (Exception e) {
                    closeQuietly(connection);
                    failOpen(id, token, "falha ao configurar portas: " + e.getMessage());
                }
            }
                }, handler);
            }
            return true;
        } catch (RuntimeException error) {
            failOpen(id, token, "não foi possível solicitar abertura: " + error.getMessage());
            return false;
        }
    }

    private synchronized void failOpen(int id, long token, String reason) {
        int discarded = openingRequests.fail(id, token);
        if (discarded < 0) return;
        AppLog.e("MIDI: " + reason + " (dispositivo " + id
                + (discarded > 0 ? ", " + discarded + " mensagem(ns) pendente(s) descartada(s)" : "")
                + ").");
        notifyConnection(id, false, reason);
    }

    private void parse(final int id, final Connection source,
                       byte[] data, int offset, int count) {
        final MidiStreamParser parser;
        synchronized (this) {
            if (connections.get(id) != source) return;
            MidiStreamParser existing = parsers.get(id);
            if (existing == null) return;
            parser = existing;
        }
        parser.feed(data, offset, count, new MidiStreamParser.Listener() {
            @Override public void onMessage(int status, int data1, int data2) {
                synchronized (MidiBridge.this) {
                    if (connections.get(id) == source)
                        notifyMidi(id, status, data1, data2);
                }
            }
            @Override public void onSystemExclusive(byte[] message) {
                synchronized (MidiBridge.this) {
                    if (connections.get(id) == source)
                        notifyMidiSystemExclusive(id, message.clone());
                }
            }
        });
    }

    static byte[] encodeMessage(int status, int data1, int data2) {
        if (status < 0x80 || status > 0xff) return new byte[0];
        int kind = status & 0xf0;
        final int length;
        if (status >= 0xf8 || status == 0xf6 || status == 0xf7) length = 1;
        else if (status == 0xf1 || status == 0xf3
                || (status < 0xf0 && (kind == 0xc0 || kind == 0xd0))) length = 2;
        else if (status == 0xf2 || status < 0xf0) length = 3;
        else length = 1;
        if (length == 1) return new byte[]{(byte) status};
        if (data1 < 0 || data1 > 127) return new byte[0];
        if (length == 2) return new byte[]{(byte) status, (byte) data1};
        if (data2 < 0 || data2 > 127) return new byte[0];
        return new byte[]{(byte) status, (byte) data1, (byte) data2};
    }

    synchronized boolean send(int id, int status, int data1, int data2) {
        if (closed) return false;
        byte[] bytes = encodeMessage(status, data1, data2);
        if (bytes.length == 0) return false;
        return sendEncoded(id, bytes);
    }

    synchronized boolean sendBytes(int id, byte[] message) {
        if (closed || message == null || message.length == 0 || message.length > 65536) return false;
        return sendEncoded(id, message.clone());
    }

    private boolean sendEncoded(int id, byte[] bytes) {
        // ID 0 is reserved for the app's own Android virtual MIDI output.
        if (id == 0) {
            boolean accepted = NornsMidiDeviceService.sendMessage(bytes, 0, bytes.length, System.nanoTime());
            if (!accepted) AppLog.i("MIDI virtual: nenhum aplicativo está conectado à saída.");
            return accepted;
        }

        Connection connection = connections.get(id);
        if (connection != null) return sendToConnection(id, connection, bytes);

        // Lua can send immediately after midi.connect(); Android opens asynchronously.
        return openingRequests.offer(id, bytes);
    }

    private boolean sendToConnection(int id, Connection connection, byte[] bytes) {
        if (connection.input == null) return false;
        try {
            connection.input.send(bytes, 0, bytes.length);
            return true;
        } catch (IOException e) {
            AppLog.e("MIDI envio dispositivo " + id + ": " + e.getMessage());
        } catch (RuntimeException e) {
            AppLog.e("MIDI porta do dispositivo " + id + " deixou de aceitar mensagens: " + e.getMessage());
        }
        return false;
    }

    synchronized void close(int id) {
        openingRequests.cancel(id);
        parsers.remove(id);
        closeQuietly(connections.remove(id));
    }

    synchronized void closeAll() {
        openingRequests.cancelAll();
        for (Connection connection : connections.values()) closeQuietly(connection);
        connections.clear();
        parsers.clear();
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try { if (connection.output != null) connection.output.close(); } catch (Exception ignored) {}
        try { if (connection.input != null) connection.input.close(); } catch (Exception ignored) {}
        try { if (connection.device != null) connection.device.close(); } catch (Exception ignored) {}
    }
}
