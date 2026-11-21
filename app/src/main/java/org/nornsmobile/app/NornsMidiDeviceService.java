package org.nornsmobile.app;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;

import java.io.IOException;

/**
 * Virtual MIDI 1.0 output exposed to other Android applications.
 * The service is output-only and keeps physical MIDI connections independent.
 *
 * The Android MIDI service binds this component when another app opens the
 * device. Lua scripts send bytes through sendMessage(), which forwards them
 * to every client connected to the virtual device output port.
 */
public final class NornsMidiDeviceService extends MidiDeviceService {
    private static volatile NornsMidiDeviceService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppLog.i("MIDI virtual: serviço NMMO iniciado; saída declarada pelo Android.");
        AppLog.i("MIDI virtual: portas de saída com receptores conectados="
                + (getOutputPortReceivers() == null ? 0 : getOutputPortReceivers().length));
    }

    @Override
    public void onDestroy() {
        AppLog.i("MIDI virtual: serviço NMMO encerrado.");
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        // This device is an output-only MIDI source. Clients receive from its output port.
        AppLog.i("MIDI virtual: Android consultou as portas de entrada do serviço (0; saída-only).");
        return new MidiReceiver[0];
    }

    static boolean sendMessage(byte[] data, int offset, int count, long timestamp) {
        NornsMidiDeviceService service = instance;
        if (service == null || data == null || count <= 0) return false;

        MidiReceiver[] receivers = service.getOutputPortReceivers();
        if (receivers == null || receivers.length == 0) return false;

        boolean delivered = false;
        for (MidiReceiver receiver : receivers) {
            if (receiver == null) continue;
            try {
                receiver.send(data, offset, count, timestamp);
                delivered = true;
            } catch (IOException | IllegalStateException error) {
                AppLog.e("MIDI virtual envio: " + error.getMessage());
            }
        }
        return delivered;
    }
}
