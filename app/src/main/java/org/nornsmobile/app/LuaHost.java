package org.nornsmobile.app;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.InputDevice;
import android.view.MotionEvent;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Executa scripts norns (Lua) numa thread dedicada. Todo o estado Lua pertence a essa thread;
 * eventos (teclas, encoders, temporizadores, parâmetros) são enfileirados nela.
 * A API norns em si (clock, metro, params, ...) está em assets/lua/boot.lua.
 */
final class LuaHost {

    interface Listener {
        void onState(boolean running, String scriptName);

        void onParams(String data);
    }

    // operações do objeto _host
    private static final int H_LOG = 0;
    private static final int H_NOW = 1;
    private static final int H_WAKE_AT = 2;
    private static final int H_READ_ASSET = 3;
    private static final int H_READ_FILE = 4;
    private static final int H_WRITE_FILE = 5;
    private static final int H_FILE_EXISTS = 6;
    private static final int H_MKDIR = 7;
    private static final int H_LIST_DIR = 8;
    private static final int H_REMOVE = 9;
    private static final int H_RENAME = 10;
    private static final int H_ENGINE_LOAD = 11;
    private static final int H_ENGINE_CMD = 12;
    private static final int H_PARAMS_CHANGED = 13;
    private static final int H_MIDI_DEVICES = 14;
    private static final int H_MIDI_OPEN = 15;
    private static final int H_MIDI_SEND = 16;
    private static final int H_MIDI_CLOSE = 17;
    private static final int H_OSC_SEND = 18;
    private static final int H_OSC_LISTEN = 19;
    private static final int H_OSC_CLOSE = 20;
    private static final int H_SOFTCUT = 21;
    private static final int H_SOFTCUT_BUFFER = 22;
    private static final int H_SOFTCUT_BUFFER_CLEAR = 23;
    private static final int H_SOFTCUT_POSITION = 24;
    private static final int H_SOFTCUT_BUFFER_COPY = 25;
    private static final int H_SOFTCUT_BUFFER_WRITE = 26;
    private static final int H_SOFTCUT_BUFFER_RENDER = 27;
    private static final int H_SOFTCUT_SYNC = 28;
    private static final int H_SC_SEND = 29;
    private static final int H_SC_LOAD_SYNTHDEF = 30;
    private static final int H_SC_AVAILABLE = 31;
    private static final int H_SC_POLL_REPLY = 32;
    private static final int H_SC_SOFTCUT_READY = 33;
    private static final int H_SC_SOFTCUT_READBACK = 34;
    private static final int H_SC_SOFTCUT_RECORD_START = 35;
    private static final int H_SC_SOFTCUT_READBACK_SECONDS = 36;
    private static final int H_SC_SOFTCUT_RECORD_STOP = 37;
    private static final int H_SC_SOFTCUT_OUTPUT = 38;
    private static final int H_SC_SOFTCUT_DIAGNOSTICS = 39;
    private static final int H_SC_SOFTCUT_PHASE_SAMPLE = 40;
    private static final int H_SC_SOFTCUT_RECORDER_SAMPLE = 41;
    private static final int H_SC_SOFTCUT_RECORDER_REANCHOR = 42;
    private static final int H_APP_INFO = 43;
    private static final int H_SC_SOFTCUT_BUFFER_ISOLATION_PROBE = 44;
    private static final int H_MIDI_SEND_BYTES = 45;
    private static final int H_POLL_VALUE = 46;
    private static final int H_OSC_SEND_BUNDLE = 47;
    private static final int H_USB_SERIAL_DEVICES = 48;
    private static final int H_USB_SERIAL_OPEN = 49;
    private static final int H_USB_SERIAL_WRITE = 50;
    private static final int H_USB_SERIAL_CLOSE = 51;
    private static final int H_MONOME_DEVICES = 52;
    private static final int H_MONOME_SEND = 53;
    private static final int H_MONOME_REFRESH = 54;
    private static final int H_TAPE_PLAY_OPEN = 55;
    private static final int H_TAPE_PLAY_START = 56;
    private static final int H_TAPE_PLAY_PAUSE = 57;
    private static final int H_TAPE_PLAY_STOP = 58;
    private static final int H_TAPE_PLAY_LOOP = 59;
    private static final int H_TAPE_RECORD_OPEN = 60;
    private static final int H_TAPE_RECORD_START = 61;
    private static final int H_TAPE_RECORD_PAUSE = 62;
    private static final int H_TAPE_RECORD_STOP = 63;
    private static final int H_TAPE_LEVEL = 64;
    private static final int H_TAPE_LEVEL_CUT = 65;
    private static final int H_RESOLVE_SAMPLE_PATH = 66;
    private static final int H_AUDIO_FILE_INFO = 67;
    private static final int H_AUDIO_LEVEL_DAC = 68;
    private static final int H_AUDIO_PITCH_ON = 69;
    private static final int H_AUDIO_PITCH_OFF = 70;
    private static final int H_AUDIO_LEVEL_MONITOR = 71;
    private static final int H_AUDIO_LEVEL_ADC = 72;
    private static final int H_AUDIO_MONITOR_MODE = 73;
    private static final int H_TAPE_STATE = 74;
    private static final int H_DISK_FREE_BYTES = 75;
    private static final int H_AUDIO_FX = 76;
    private static final int H_AUDIO_LEVEL_ENG = 77;
    private static final int H_AUDIO_LEVEL_CUT = 78;
    private static final int H_SC_PLUGIN_EXISTS = 79;
    private static final int H_COPY = 80;
    private static final int H_REMOVE_ALL = 81;

    private static final String[] HOST_NAMES = {
            "log", "now", "wake_at", "read_asset", "read_file", "write_file", "file_exists",
            "mkdir", "list_dir", "remove", "rename", "engine_load", "engine_cmd", "params_changed",
            "midi_devices", "midi_open", "midi_send", "midi_close", "osc_send", "osc_listen", "osc_close",
            "softcut", "softcut_buffer", "softcut_buffer_clear", "softcut_position", "softcut_buffer_copy", "softcut_buffer_write", "softcut_buffer_render", "softcut_sync", "sc_send", "sc_load_synthdef", "sc_available", "sc_poll_reply", "sc_softcut_ready", "sc_softcut_readback", "sc_softcut_record_start", "sc_softcut_readback_seconds", "sc_softcut_record_stop",
            "sc_softcut_output", "sc_softcut_diagnostics", "sc_softcut_phase_sample",
            "sc_softcut_recorder_sample", "sc_softcut_recorder_reanchor", "app_info",
            "sc_softcut_buffer_isolation_probe", "midi_send_bytes", "poll_value", "osc_send_bundle",
            "usb_serial_devices", "usb_serial_open", "usb_serial_write", "usb_serial_close",
            "monome_devices", "monome_send", "monome_refresh",
            "tape_play_open", "tape_play_start", "tape_play_pause", "tape_play_stop",
            "tape_play_loop", "tape_record_open", "tape_record_start", "tape_record_pause",
            "tape_record_stop", "tape_level", "tape_level_cut", "resolve_sample_path", "audio_file_info",
            "audio_level_dac", "audio_pitch_on", "audio_pitch_off", "audio_level_monitor",
            "audio_level_adc", "audio_monitor_mode", "tape_state", "disk_free_bytes", "audio_fx",
            "audio_level_eng", "audio_level_cut", "sc_plugin_exists", "copy", "remove_all"
    };

    // operações do objeto screen (a ordem define o código de cada função)
    private static final String[] SCREEN_NAMES = {
            "clear", "update", "level", "aa", "line_width", "line_cap", "line_join", "miter_limit",
            "move", "line", "move_rel", "line_rel", "curve", "curve_rel", "arc", "rect", "circle",
            "pixel", "close", "stroke", "fill", "text", "text_right", "text_center", "text_extents",
            "font_face", "font_size", "save", "restore", "translate", "rotate", "current_point",
            "blend_mode", "export_screenshot", "display_png", "update_default", "peek", "poke",
            "load_png", "create_image", "display_image", "display_image_region", "draw_to", "free_image",
            "text_trim", "text_rotate", "text_center_rotate"
    };

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final Dust dust;
    private final ScreenSurface screen;
    private final AudioEngine audio;
    private final Listener listener;
    private final MidiBridge midi;
    private final UsbCdcSerialBridge usbSerial;
    private final MonomeSerialoscBridge monome;
    private final OscBridge osc = new OscBridge();
    private final long t0 = System.nanoTime();
    private final HashMap<String, String> assetCache = new HashMap<String, String>();
    private final Object wakeLock = new Object();
    // Accessed from Activity dispatchKeyEvent (main thread); Lua callbacks remain serialized.
    private final KeyboardDeviceState keyboardDeviceState = new KeyboardDeviceState();
    private final HashMap<String, Float> gamepadAxisValues = new HashMap<String, Float>();
    private final GamepadDpadState gamepadDpadState = new GamepadDpadState();
    private final Set<String> pressedGamepadButtons = new HashSet<String>();
    private final HashMap<Integer, float[]> mousePositions = new HashMap<Integer, float[]>();
    private final HidButtonState mouseButtonState = new HidButtonState();
    private final LuaDeviceCallbackGate hidCallbackGate = new LuaDeviceCallbackGate();
    private final LuaDeviceCallbackGate usbCallbackGate = new LuaDeviceCallbackGate();

    private volatile ScheduledThreadPoolExecutor exec;
    private volatile Globals globals;
    private volatile boolean running = false;
    // Invalidates queued callbacks when a Lua VM is stopped or replaced.
    private volatile long vmGeneration = 1L;
    private String scriptName = "";
    private ScheduledFuture<?> pendingWake;
    private boolean paramsPending = false;
    private volatile boolean superColliderStarted = false;
    private File superColliderSynthDefDir;
    private File superColliderPluginDir;

    LuaHost(Context ctx, Dust dust, ScreenSurface screen, AudioEngine audio, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.dust = dust;
        this.screen = screen;
        this.audio = audio;
        this.listener = listener;
        this.audio.setSoftcutRenderListener(new AudioEngine.SoftcutRenderListener() {
            @Override public void onRender(final int channel, final double start,
                                           final double secPerSample, final float[] samples) {
                post(new Runnable() {
                    @Override public void run() { dispatchSoftcutRender(channel, start, secPerSample, samples); }
                });
            }
        });
        this.audio.setOnsetListener(new AudioEngine.OnsetListener() {
            @Override public void onOnset(final double timeSeconds) {
                post(new Runnable() {
                    @Override public void run() {
                        dispatchOsc("onset", new Object[]{1, timeSeconds}, "127.0.0.1", 10111);
                    }
                });
            }
        });
        this.audio.setTapeStateListener(new AudioEngine.TapeStateListener() {
            @Override public void onTapeState(final TapeCore.State state) {
                post(new Runnable() {
                    @Override public void run() { dispatchTapeState(state); }
                });
            }
        });

        this.audio.setVuListener(new AudioEngine.VuListener() {
            @Override public void onVu(final int inputLeft, final int inputRight,
                                       final int outputLeft, final int outputRight) {
                post(new Runnable() {
                    @Override public void run() {
                        dispatchAudioVu(inputLeft, inputRight, outputLeft, outputRight);
                    }
                });
            }
        });
        this.usbSerial = new UsbCdcSerialBridge(this.ctx, new UsbCdcSerialBridge.Listener() {
            @Override public void onConnected(final int deviceId, final String name) {
                post(new Runnable() { @Override public void run() {
                    if (usbCallbackGate.current(deviceId) == null) dispatchSerialConnected(deviceId, name);
                }});
            }
            @Override public void onData(final int deviceId, final byte[] bytes) {
                // Legacy callbacks without a session token are not admitted after hotplug.
            }
            @Override public void onDisconnected(final int deviceId, final String reason) {
                post(new Runnable() { @Override public void run() {
                    if (usbCallbackGate.current(deviceId) == null) dispatchSerialDisconnected(deviceId, reason);
                }});
            }
            @Override public void onConnected(final int deviceId, final long sessionToken, final String name) {
                post(new Runnable() { @Override public void run() {
                    LuaDeviceCallbackGate.Session session = usbCallbackGate.begin(deviceId, sessionToken);
                    if (session != null && usbCallbackGate.accepts(deviceId, sessionToken, false))
                        dispatchSerialConnected(deviceId, name);
                }});
            }
            @Override public void onData(final int deviceId, final long sessionToken, final byte[] bytes) {
                post(new Runnable() { @Override public void run() {
                    if (usbCallbackGate.accepts(deviceId, sessionToken, false))
                        dispatchSerialData(deviceId, bytes);
                }});
            }
            @Override public void onDisconnected(final int deviceId, final long sessionToken, final String reason) {
                post(new Runnable() { @Override public void run() {
                    LuaDeviceCallbackGate.Session session = usbCallbackGate.beginClose(deviceId, sessionToken);
                    if (session == null) return;
                    try {
                        if (usbCallbackGate.acceptsTerminal(deviceId, sessionToken))
                            dispatchSerialDisconnected(deviceId, reason);
                    } finally {
                        usbCallbackGate.finishClose(deviceId, sessionToken);
                    }
                }});
            }
            @Override public void onDevicesChanged() {
                post(new Runnable() { @Override public void run() { callNorns("serial_refresh"); } });
            }
        });
        this.midi = new MidiBridge(this.ctx, new MidiBridge.Listener() {
            @Override public void onMidi(final int deviceId, final int status, final int data1, final int data2) {
                post(new Runnable() { @Override public void run() { dispatchMidi(deviceId, status, data1, data2); } });
            }
            @Override public void onMidiConnection(final int deviceId, final boolean connected, final String reason) {
                post(new Runnable() { @Override public void run() { dispatchMidiConnection(deviceId, connected, reason); } });
            }
            @Override public void onMidiDeviceChanged(final MidiBridge.Device device, final boolean added) {
                post(new Runnable() { @Override public void run() { dispatchMidiDeviceChanged(device, added); } });
            }
            @Override public void onMidiSystemExclusive(final int deviceId, final byte[] data) {
                post(new Runnable() { @Override public void run() { dispatchMidiSystemExclusive(deviceId, data); } });
            }
        });
        this.monome = new MonomeSerialoscBridge(new MonomeSerialoscBridge.Listener() {
            @Override public void onDeviceAdded(final MonomeSerialoscBridge.Device device) {
                post(new Runnable() { @Override public void run() { dispatchMonomeDevice(device, true); } });
            }
            @Override public void onDeviceRemoved(final MonomeSerialoscBridge.Device device) {
                post(new Runnable() { @Override public void run() { dispatchMonomeDevice(device, false); } });
            }
            @Override public void onDeviceInfo(final MonomeSerialoscBridge.Device device) {
                post(new Runnable() { @Override public void run() { dispatchMonomeDeviceInfo(device); } });
            }
            @Override public void onGridKey(final int id, final int x, final int y, final int state) {
                post(new Runnable() { @Override public void run() {
                    callNorns("grid_key", LuaValue.valueOf(id), LuaValue.valueOf(x),
                            LuaValue.valueOf(y), LuaValue.valueOf(state));
                }});
            }
            @Override public void onGridTilt(final int id, final int x, final int y, final int z) {
                post(new Runnable() { @Override public void run() {
                    callNorns("grid_tilt", LuaValue.valueOf(id), LuaValue.valueOf(x),
                            LuaValue.valueOf(y), LuaValue.valueOf(z));
                }});
            }
            @Override public void onArcDelta(final int id, final int ring, final int delta) {
                post(new Runnable() { @Override public void run() {
                    callNorns("arc_delta", LuaValue.valueOf(id), LuaValue.valueOf(ring), LuaValue.valueOf(delta));
                }});
            }
            @Override public void onArcKey(final int id, final int ring, final int state) {
                post(new Runnable() { @Override public void run() {
                    callNorns("arc_key", LuaValue.valueOf(id), LuaValue.valueOf(ring), LuaValue.valueOf(state));
                }});
            }
        });
        this.monome.start();
    }

    void dispose() {
        stop();
        midi.shutdown();
        usbSerial.shutdown();
        monome.close();
    }

    boolean isRunning() {
        return running;
    }

    String getScriptName() {
        return scriptName;
    }

    private double nowSec() {
        return (System.nanoTime() - t0) / 1e9;
    }

    // ------------------------------------------------------------------ ciclo de vida

    synchronized void start(final File script) {
        stopInternal();
        final ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(null, r, "norns-lua", 8L * 1024L * 1024L);
                t.setDaemon(true);
                return t;
            }
        });
        ex.setRemoveOnCancelPolicy(true);
        exec = ex;
        File parent = script.getParentFile();
        scriptName = parent != null ? parent.getName() : script.getName();
        running = true;
        screen.reset();
        audio.start();
        listener.onState(true, scriptName);
        AppLog.i("▶ " + scriptName);
        ex.execute(new Runnable() {
            @Override
            public void run() {
                boot(script, ex);
            }
        });
    }

    synchronized void stop() {
        stopInternal();
    }

    private void stopInternal() {
        vmGeneration++;
        final ScheduledThreadPoolExecutor ex = exec;
        if (ex == null) {
            keyboardDeviceState.releaseAll();
            mouseButtonState.releaseAll();
            hidCallbackGate.clear();
            usbCallbackGate.clear();
            return;
        }
        final KeyboardDeviceState.Release keyboardRelease = keyboardDeviceState.releaseAll();
        final HidButtonState.Release mouseRelease = mouseButtonState.releaseAll();
        exec = null;
        if (running) {
            try {
                Future<?> f = ex.submit(new Runnable() {
                    @Override
                    public void run() {
                        // Libera botões de mouse que possam ter perdido ACTION_BUTTON_RELEASE.
                        for (Map.Entry<Integer, Set<Integer>> entry : mouseRelease.buttonsByDevice.entrySet()) {
                            int deviceId = entry.getKey();
                            if (deviceId < 0) continue;
                            for (Integer buttonCode : entry.getValue()) {
                                callNorns("hid_event", LuaValue.valueOf(deviceId), LuaValue.valueOf(1),
                                        LuaValue.valueOf(buttonCode), LuaValue.ZERO);
                            }
                        }
                        // Esvazia primeiro o estado HID de cada teclado e depois o
                        // estado lógico agregado, antes do cleanup do script.
                        for (Map.Entry<Integer, Set<String>> entry : keyboardRelease.keysByDevice.entrySet()) {
                            int deviceId = entry.getKey();
                            if (deviceId < 0) continue;
                            for (String code : entry.getValue()) {
                                int hidCode = linuxKeyCodeFromName(code);
                                if (hidCode >= 0) callNorns("hid_event", LuaValue.valueOf(deviceId),
                                        LuaValue.valueOf(1), LuaValue.valueOf(hidCode), LuaValue.ZERO);
                            }
                        }
                        for (String code : keyboardRelease.logicalKeys) {
                            callNorns("keyboard_event", LuaValue.valueOf(code), LuaValue.ZERO,
                                    LuaValue.NIL, LuaValue.FALSE, LuaValue.FALSE,
                                    LuaValue.FALSE, LuaValue.FALSE);
                        }
                        callNorns("cleanup");
                    }
                });
                f.get(700, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                AppLog.e("O script não respondeu ao encerrar (ignorado).");
            }
        }
        synchronized (wakeLock) {
            pendingWake = null;
            paramsPending = false;
        }
        ex.shutdownNow();
        hidCallbackGate.clear();
        usbCallbackGate.clear();
        globals = null;
        boolean was = running;
        running = false;
        audio.allNotesOff();
        stopSuperCollider();
        midi.closeAll();
        usbSerial.closeAll();
        osc.close();
        audio.setEngine("none");
        if (was) {
            AppLog.i("■ parado");
            listener.onState(false, "");
        }
    }

    /** Retire Lua-owned resources when boot fails after partially executing a script. */
    private void cleanupFailedLuaStart() {
        Globals g = globals;
        if (g == null) return;
        try {
            LuaValue norns = g.get("_norns");
            if (norns == null || norns.isnil()) return;
            LuaValue cleanup = norns.get("cleanup");
            if (cleanup != null && cleanup.isfunction()) cleanup.call();
        } catch (LuaError e) {
            AppLog.e("Lua cleanup após falha de inicialização: " + e.getMessage());
        } catch (Throwable t) {
            AppLog.e("Erro ao limpar recursos Lua após falha de inicialização: " + t);
        }
    }

    private synchronized void failStart(ScheduledThreadPoolExecutor owner) {
        // A stale boot failure must never retire a newer script's executor.
        if (owner == null || exec != owner) return;
        vmGeneration++;
        final ScheduledThreadPoolExecutor failedExecutor = owner;
        exec = null;
        // _norns.load_script() may return false after top-level code has already
        // created clocks, polls, MIDI/USB/OSC sessions or other Lua resources.
        // Run the same idempotent cleanup used by an explicit script stop before
        // discarding the VM; Java transports are still closed below as a backstop.
        cleanupFailedLuaStart();
        synchronized (wakeLock) {
            pendingWake = null;
            paramsPending = false;
        }
        failedExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        failedExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        failedExecutor.shutdown();

        running = false;
        keyboardDeviceState.releaseAll();
        mouseButtonState.releaseAll();
        pressedGamepadButtons.clear();
        gamepadAxisValues.clear();
        gamepadDpadState.clear();
        mousePositions.clear();
        audio.allNotesOff();
        stopSuperCollider();
        midi.closeAll();
        usbSerial.closeAll();
        osc.close();
        audio.setEngine("none");
        globals = null;
        listener.onState(false, "");
    }

    private void boot(File script, ScheduledThreadPoolExecutor owner) {
        try {
            startSuperCollider(script);
            Globals g = JsePlatform.standardGlobals();
            installHost(g);
            globals = g;
            String bootSrc = readAsset("lua/boot.lua");
            if (bootSrc == null) {
                AppLog.e("lua/boot.lua não encontrado nos assets do app.");
                failStart(owner);
                return;
            }
            g.load(bootSrc, "boot.lua").call();
            LuaValue norns = g.get("_norns");
            LuaValue ok = norns.get("load_script").call(LuaValue.valueOf(script.getAbsolutePath()));
            if (!ok.toboolean()) {
                AppLog.e("O script não pôde ser iniciado (veja os erros acima).");
                failStart(owner);
            }
        } catch (LuaError e) {
            AppLog.e("Lua: " + e.getMessage());
            failStart(owner);
        } catch (Throwable t) {
            AppLog.e("Erro ao iniciar o script: " + t);
            failStart(owner);
        }
    }

    static String javaEngineSelection(String requestedEngine) {
        if ("Stratum".equals(requestedEngine)
                || "32Harmony".equals(requestedEngine)
                || "SimpleDelayKolor".equals(requestedEngine)) {
            return "none";
        }
        return requestedEngine == null || requestedEngine.length() == 0
                ? "none" : requestedEngine;
    }

    private static final java.util.regex.Pattern NATIVE_ENGINE_PATTERN = java.util.regex.Pattern.compile(
            "engine\\.name\\s*=\\s*[\"'](Stratum|32Harmony|SimpleDelayKolor)[\"']");

    /**
     * O servidor scsynth nativo só é necessário para engines que não têm versão Java
     * (Stratum, 32Harmony, SimpleDelayKolor). Com o servidor ativo, o mixer Java (reverb,
     * compressor, níveis ADC/DAC/engine/monitor) é recusado, então os demais scripts
     * permanecem no caminho Java, onde esses controles funcionam.
     */
    static boolean scriptNeedsNativeEngine(String source) {
        return source != null && NATIVE_ENGINE_PATTERN.matcher(source).find();
    }

    private static final java.util.regex.Pattern ENGINE_NAME_PATTERN = java.util.regex.Pattern.compile(
            "engine\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final java.util.Set<String> SUPPORTED_ENGINES = new java.util.HashSet<String>(
            java.util.Arrays.asList("PolyPerc", "Stratum", "32Harmony", "SimpleDelayKolor", "none"));

    /** Nome do engine citado no script, ou null quando o script não usa engine. */
    static String scriptEngineName(String source) {
        if (source == null) return null;
        java.util.regex.Matcher m = ENGINE_NAME_PATTERN.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    /** Engine exigido pelo script mas ainda não disponível neste app, ou null se compatível. */
    static String unsupportedEngine(File script) {
        String name = scriptEngineName(readScriptHead(script));
        return name == null || SUPPORTED_ENGINES.contains(name) ? null : name;
    }

    private static String readScriptHead(File script) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(script.toPath());
            return new String(bytes, 0, Math.min(bytes.length, 1 << 20), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void startSuperCollider(File script) {
        try {
            File plugins = SuperColliderPlugins.install(ctx);
            superColliderPluginDir = plugins;
            superColliderSynthDefDir = SuperColliderPlugins.installSynthDefs(ctx);
            int rate = audio.getSampleRate();
            if (!scriptNeedsNativeEngine(readScriptHead(script))) {
                AppLog.i("SuperCollider: servidor nativo desligado (engine Java; reverb, compressor e níveis ativos).");
                return;
            }
            boolean available = SuperColliderBridge.isAvailable();
            if (!available) {
                AppLog.i("SuperCollider: símbolos nativos indisponíveis; mantendo áudio Java.");
                return;
            }
            // Libera a captura AudioRecord antes de abrir a entrada Oboe do scsynth.
            audio.setNativeInputActive(true);
            superColliderStarted = SuperColliderBridge.start(rate, 64, plugins.getAbsolutePath());
            if (superColliderStarted) {
                boolean nativeInput = SuperColliderBridge.hasInput();
                audio.setNativeInputActive(nativeInput);
                AppLog.i("SuperCollider: servidor nativo iniciado (" + rate
                        + " Hz, bloco 64); captura " + (nativeInput
                        ? "Oboe compartilhada com o fallback Java." : "mantida no AudioRecord Java."));
            } else {
                audio.setNativeInputActive(false);
                AppLog.e("SuperCollider: não foi possível iniciar o servidor; mantendo áudio Java.");
            }
        } catch (Throwable error) {
            superColliderStarted = false;
            audio.setNativeInputActive(false);
            AppLog.e("SuperCollider: falha na inicialização (" + error.getMessage()
                    + "); mantendo áudio Java.");
        }
    }

    private void stopSuperCollider() {
        if (!superColliderStarted) return;
        try {
            SuperColliderBridge.stop();
            audio.setNativeInputActive(false);
            AppLog.i("SuperCollider: servidor nativo encerrado; entrada AudioRecord restaurada.");
        } catch (Throwable error) {
            AppLog.e("SuperCollider: erro ao encerrar (" + error.getMessage() + ").");
        } finally {
            superColliderStarted = false;
        }
    }

    // ------------------------------------------------------------------ chamadas ao Lua

    private void callNorns(String fn, LuaValue... args) {
        try {
            Globals g = globals;
            if (g == null) return;
            LuaValue f = g.get("_norns").get(fn);
            if (f.isfunction()) f.invoke(LuaValue.varargsOf(args));
        } catch (LuaError e) {
            AppLog.e(String.valueOf(e.getMessage()));
        } catch (Throwable t) {
            AppLog.e("Erro interno (" + fn + "): " + t);
        }
    }

    private boolean callNornsBool(String fn) {
        try {
            Globals g = globals;
            if (g == null) return false;
            LuaValue f = g.get("_norns").get(fn);
            return f.isfunction() && f.call().toboolean();
        } catch (Throwable t) {
            AppLog.e("Erro interno (" + fn + "): " + t);
            return false;
        }
    }

    private void post(final Runnable r) {
        final ScheduledThreadPoolExecutor owner = exec;
        final long generation = vmGeneration;
        if (owner == null) return;
        try {
            owner.execute(new Runnable() {
                @Override public void run() {
                    if (vmGeneration != generation || exec != owner || !running) return;
                    r.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // script já parou
        }
    }

    void key(final int n, final int z) {
        post(new Runnable() {
            @Override
            public void run() {
                callNorns("key", LuaValue.valueOf(n), LuaValue.valueOf(z));
            }
        });
    }

    boolean hidMouseMotionEvent(final MotionEvent event) {
        if (event == null || !running || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        InputDevice device = event.getDevice();
        if (device == null || event.getDeviceId() < 0) return false;
        final int id = event.getDeviceId();
        if (!isCurrentInputDevice(id, device)) return false;
        int action = event.getActionMasked();
        boolean handled = false;
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) {
            float x = event.getX(), y = event.getY();
            float[] previous = mousePositions.put(id, new float[]{x, y});
            if (previous != null) {
                int dx = Math.round(x - previous[0]);
                int dy = Math.round(y - previous[1]);
                if (dx != 0) { hidEvent(id, 2, 0, dx); handled = true; }
                if (dy != 0) { hidEvent(id, 2, 1, dy); handled = true; }
            }
        }
        if (action == MotionEvent.ACTION_SCROLL) {
            int wheel = Math.round(event.getAxisValue(MotionEvent.AXIS_VSCROLL));
            int hwheel = Math.round(event.getAxisValue(MotionEvent.AXIS_HSCROLL));
            if (wheel != 0) { hidEvent(id, 2, 8, wheel); handled = true; }
            if (hwheel != 0) { hidEvent(id, 2, 6, hwheel); handled = true; }
        }
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            int button = event.getActionButton();
            int code = button == MotionEvent.BUTTON_PRIMARY ? 0x110
                    : button == MotionEvent.BUTTON_SECONDARY ? 0x111
                    : button == MotionEvent.BUTTON_TERTIARY ? 0x112
                    : button == MotionEvent.BUTTON_BACK ? 0x116
                    : button == MotionEvent.BUTTON_FORWARD ? 0x115 : -1;
            if (code >= 0) {
                boolean changed = action == MotionEvent.ACTION_BUTTON_PRESS
                        ? mouseButtonState.press(id, code) : mouseButtonState.release(id, code);
                if (changed) {
                    hidEvent(id, 1, code, action == MotionEvent.ACTION_BUTTON_PRESS ? 1 : 0);
                    handled = true;
                }
            }
        }
        return handled;
    }

    /** Releases mouse buttons if focus changes before Android delivers button-up. */
    void releaseMouseButtons() {
        dispatchMouseButtonReleases(mouseButtonState.releaseAll());
    }

    /** Releases only mouse buttons belonging to a disconnected input device. */
    void releaseMouseDevice(int deviceId) {
        dispatchMouseButtonReleases(mouseButtonState.removeDevice(deviceId));
        mousePositions.remove(deviceId);
    }

    private void dispatchMouseButtonReleases(final HidButtonState.Release release) {
        if (!running || release.buttonsByDevice.isEmpty()) return;
        final Map<Integer, LuaDeviceCallbackGate.Session> sessions =
                new HashMap<Integer, LuaDeviceCallbackGate.Session>();
        for (Integer id : release.buttonsByDevice.keySet()) sessions.put(id, hidCallbackGate.current(id));
        post(new Runnable() { @Override public void run() {
            for (Map.Entry<Integer, Set<Integer>> entry : release.buttonsByDevice.entrySet()) {
                int deviceId = entry.getKey();
                LuaDeviceCallbackGate.Session session = sessions.get(deviceId);
                if (deviceId < 0 || session == null
                        || !hidCallbackGate.accepts(deviceId, session.token, true)) continue;
                for (Integer buttonCode : entry.getValue()) {
                    callNorns("hid_event", LuaValue.valueOf(deviceId), LuaValue.valueOf(1),
                            LuaValue.valueOf(buttonCode), LuaValue.ZERO);
                }
            }
        }});
    }

    void hidDeviceChanged(final int deviceId, final String deviceName, final int vendorId,
                          final int productId, final boolean connected) {
        hidDeviceChanged(deviceId, deviceName, vendorId, productId, null, connected);
    }

    void hidDeviceChanged(final int deviceId, final String deviceName, final int vendorId,
                          final int productId, final String descriptor, final boolean connected) {
        if (connected) {
            LuaDeviceCallbackGate.Session current = hidCallbackGate.current(deviceId);
            if (current != null && !hidCallbackGate.matchesIdentity(deviceId, current.token, descriptor)) {
                // Queue old-session removal before announcing the replacement on the same Lua executor.
                hidDeviceChanged(deviceId, deviceName, vendorId, productId, null, false);
            }
            final LuaDeviceCallbackGate.Session session = hidCallbackGate.beginIfAbsent(deviceId, descriptor);
            if (!hidCallbackGate.markAnnounced(session)) return;
            post(new Runnable() { @Override public void run() {
                if (!hidCallbackGate.accepts(deviceId, session.token, false)) return;
                InputDevice device = InputDevice.getDevice(deviceId);
                int sources = device == null ? 0 : device.getSources();
                boolean asciiKeyboard = device != null
                        && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
                boolean mouse = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
                boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                        || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
                callNorns("hid_device_add", LuaValue.valueOf(deviceId),
                        LuaValue.valueOf(deviceName == null ? "Android HID" : deviceName),
                        LuaValue.valueOf(vendorId), LuaValue.valueOf(productId),
                        LuaValue.valueOf(asciiKeyboard), LuaValue.valueOf(mouse),
                        LuaValue.valueOf(gamepad));
            }});
        } else {
            final LuaDeviceCallbackGate.Session session = hidCallbackGate.beginClose(deviceId);
            if (session == null) return;
            post(new Runnable() { @Override public void run() {
                try {
                    if (hidCallbackGate.acceptsOrderedTerminal(deviceId, session.token))
                        callNorns("hid_device_remove", LuaValue.valueOf(deviceId));
                } finally {
                    hidCallbackGate.finishClose(deviceId, session.token);
                }
            }});
        }
    }

    private boolean isCurrentInputDevice(int deviceId, InputDevice device) {
        if (deviceId < 0 || device == null) return false;
        LuaDeviceCallbackGate.Session session = hidCallbackGate.current(deviceId);
        return session != null && hidCallbackGate.matchesIdentity(deviceId, session.token, device.getDescriptor());
    }

    private void postHidSessionCallback(final int deviceId, final boolean release, final Runnable callback) {
        if (deviceId < 0 || callback == null) return;
        final LuaDeviceCallbackGate.Session session = hidCallbackGate.current(deviceId);
        if (session == null) return;
        post(new Runnable() { @Override public void run() {
            if (hidCallbackGate.accepts(deviceId, session.token, release)) callback.run();
        }});
    }

    private void hidEvent(final int deviceId, final int eventType, final int code, final int value) {
        if (deviceId < 0) return;
        final LuaDeviceCallbackGate.Session session = hidCallbackGate.current(deviceId);
        if (session == null) return;
        final boolean release = value == 0 && (eventType == 1 || eventType == 3);
        post(new Runnable() { @Override public void run() {
            if (!hidCallbackGate.accepts(deviceId, session.token, release)) return;
            callNorns("hid_event", LuaValue.valueOf(deviceId), LuaValue.valueOf(eventType),
                    LuaValue.valueOf(code), LuaValue.valueOf(value));
        }});
    }

    private static int linuxKeyCodeFromName(String name) {
        if (name == null) return -1;
        switch (name) {
            case "ESC": case "ESCAPE": return 1;
            case "1": return 2; case "2": return 3; case "3": return 4; case "4": return 5;
            case "5": return 6; case "6": return 7; case "7": return 8; case "8": return 9;
            case "9": return 10; case "0": return 11; case "MINUS": return 12; case "EQUAL": case "EQUALS": case "PLUS": return 13;
            case "BACKSPACE": return 14; case "TAB": return 15;
            case "Q": return 16; case "W": return 17; case "E": return 18; case "R": return 19;
            case "T": return 20; case "Y": return 21; case "U": return 22; case "I": return 23;
            case "O": return 24; case "P": return 25; case "LEFTBRACE": case "LEFTBRACKET": return 26;
            case "RIGHTBRACE": case "RIGHTBRACKET": return 27; case "ENTER": return 28; case "LEFTCTRL": return 29;
            case "A": return 30; case "S": return 31; case "D": return 32; case "F": return 33;
            case "G": return 34; case "H": return 35; case "J": return 36; case "K": return 37;
            case "L": return 38; case "SEMICOLON": return 39; case "APOSTROPHE": return 40;
            case "GRAVE": return 41; case "LEFTSHIFT": return 42; case "BACKSLASH": return 43;
            case "Z": return 44; case "X": return 45; case "C": return 46; case "V": return 47;
            case "B": return 48; case "N": return 49; case "M": return 50; case "COMMA": return 51;
            case "DOT": case "PERIOD": return 52; case "SLASH": return 53; case "RIGHTSHIFT": return 54;
            case "LEFTALT": return 56; case "SPACE": return 57; case "CAPSLOCK": return 58;
            case "F1": return 59; case "F2": return 60; case "F3": return 61; case "F4": return 62;
            case "F5": return 63; case "F6": return 64; case "F7": return 65; case "F8": return 66;
            case "F9": return 67; case "F10": return 68; case "NUMLOCK": return 69;
            case "SCROLLLOCK": return 70; case "KP7": return 71; case "KP8": return 72;
            case "KP9": return 73; case "KPMINUS": return 74; case "KP4": return 75;
            case "KP5": return 76; case "KP6": return 77; case "KPPLUS": return 78;
            case "KP1": return 79; case "KP2": return 80; case "KP3": return 81;
            case "KP0": return 82; case "KPDOT": return 83; case "F11": return 87; case "F12": return 88;
            case "KPENTER": return 96; case "RIGHTCTRL": return 97; case "KPSLASH": case "KPDIVIDE": return 98;
            case "SYSRQ": return 99; case "RIGHTALT": return 100; case "HOME": return 102;
            case "UP": return 103; case "PAGEUP": return 104; case "LEFT": return 105;
            case "RIGHT": return 106; case "END": return 107; case "DOWN": return 108;
            case "PAGEDOWN": return 109; case "INSERT": return 110; case "DELETE": return 111;
            case "KPEQUAL": return 117; case "KPJPCOMMA": return 121;
            case "KPLEFTPAREN": return 179; case "KPRIGHTPAREN": return 180;
            case "KPMULTIPLY": case "STAR": return 55; case "AT": return 157; case "POUND": return 4; case "PAUSE": return 119;
            case "LEFTMETA": return 125; case "RIGHTMETA": return 126;
            default: return -1;
        }
    }

    private static int linuxGamepadButtonCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0x130;
            case KeyEvent.KEYCODE_BUTTON_B: return 0x131;
            case KeyEvent.KEYCODE_BUTTON_X: return 0x133;
            case KeyEvent.KEYCODE_BUTTON_Y: return 0x134;
            case KeyEvent.KEYCODE_BUTTON_L1: return 0x136;
            case KeyEvent.KEYCODE_BUTTON_R1: return 0x137;
            case KeyEvent.KEYCODE_BUTTON_L2: return 0x138;
            case KeyEvent.KEYCODE_BUTTON_R2: return 0x139;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 0x13a;
            case KeyEvent.KEYCODE_BUTTON_START: return 0x13b;
            case KeyEvent.KEYCODE_BUTTON_MODE: return 0x13c;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 0x13d;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 0x13e;
            default:
                if (keyCode >= KeyEvent.KEYCODE_BUTTON_1 && keyCode <= KeyEvent.KEYCODE_BUTTON_9)
                    return 0x100 + keyCode - KeyEvent.KEYCODE_BUTTON_1 + 1;
                if (keyCode >= KeyEvent.KEYCODE_BUTTON_10 && keyCode <= KeyEvent.KEYCODE_BUTTON_16)
                    return 0x2c0 + keyCode - KeyEvent.KEYCODE_BUTTON_10;
                return -1;
        }
    }

    void gamepadDeviceChanged(final int deviceId, final String deviceName, final boolean connected) {
        final LuaDeviceCallbackGate.Session session = hidCallbackGate.current(deviceId);
        if (!connected) {
            mousePositions.remove(deviceId);
            gamepadDpadState.removeDevice(deviceId);
            final String prefix = deviceId + ":";
            final HashMap<String, Float> axes = new HashMap<String, Float>();
            java.util.Iterator<Map.Entry<String, Float>> axisIt = gamepadAxisValues.entrySet().iterator();
            while (axisIt.hasNext()) {
                Map.Entry<String, Float> entry = axisIt.next();
                if (entry.getKey().startsWith(prefix)) {
                    axes.put(entry.getKey().substring(prefix.length()), entry.getValue());
                    axisIt.remove();
                }
            }
            final Set<String> buttons = new HashSet<String>();
            java.util.Iterator<String> buttonIt = pressedGamepadButtons.iterator();
            while (buttonIt.hasNext()) {
                String key = buttonIt.next();
                if (key.startsWith(prefix)) {
                    buttons.add(key.substring(prefix.length()));
                    buttonIt.remove();
                }
            }
            post(new Runnable() { @Override public void run() {
                if (session == null || !hidCallbackGate.accepts(deviceId, session.token, true)) return;
                for (String axis : axes.keySet()) {
                    int code = gamepadHidAxisCode(axis);
                    if (code >= 0) callNorns("hid_event", LuaValue.valueOf(deviceId),
                            LuaValue.valueOf(3), LuaValue.valueOf(code), LuaValue.ZERO);
                    callNorns("gamepad_axis", LuaValue.valueOf(axis), LuaValue.ZERO,
                            LuaValue.ONE, LuaValue.valueOf(deviceId),
                            LuaValue.valueOf(deviceName == null ? "Android Gamepad" : deviceName));
                }
                for (String button : buttons) {
                    int code = gamepadHidButtonCode(button);
                    if (code >= 0) callNorns("hid_event", LuaValue.valueOf(deviceId),
                            LuaValue.ONE, LuaValue.valueOf(code), LuaValue.ZERO);
                    callNorns("gamepad_button", LuaValue.valueOf(button), LuaValue.ZERO,
                            LuaValue.valueOf(deviceId),
                            LuaValue.valueOf(deviceName == null ? "Android Gamepad" : deviceName));
                }
                callNorns("gamepad_device", LuaValue.valueOf(deviceId),
                        LuaValue.valueOf(deviceName == null ? "Android Gamepad" : deviceName),
                        LuaValue.FALSE);
            }});
            return;
        }
        if (session == null) return;
        post(new Runnable() { @Override public void run() {
            if (!hidCallbackGate.accepts(deviceId, session.token, false)) return;
            callNorns("gamepad_device", LuaValue.valueOf(deviceId),
                    LuaValue.valueOf(deviceName == null ? "Android Gamepad" : deviceName),
                    LuaValue.TRUE);
        }});
    }

    private static int gamepadHidAxisCode(String axis) {
        if ("leftx".equals(axis)) return 0;
        if ("lefty".equals(axis)) return 1;
        if ("rightx".equals(axis)) return 2;
        if ("righty".equals(axis)) return 5;
        if ("dpadx".equals(axis)) return 0x10;
        if ("dpady".equals(axis)) return 0x11;
        return -1;
    }

    private static int gamepadHidButtonCode(String button) {
        if ("A".equals(button)) return 0x130;
        if ("B".equals(button)) return 0x131;
        if ("X".equals(button)) return 0x133;
        if ("Y".equals(button)) return 0x134;
        if ("TL".equals(button)) return 0x136;
        if ("TR".equals(button)) return 0x137;
        if ("TL2".equals(button)) return 0x138;
        if ("TR2".equals(button)) return 0x139;
        if ("SELECT".equals(button)) return 0x13a;
        if ("START".equals(button)) return 0x13b;
        if ("MODE".equals(button)) return 0x13c;
        if ("THUMBL".equals(button)) return 0x13d;
        if ("THUMBR".equals(button)) return 0x13e;
        if (button != null && button.startsWith("BTN")) {
            try {
                int number = Integer.parseInt(button.substring(3));
                if (number >= 1 && number <= 9) return 0x100 + number;
                if (number >= 10 && number <= 16) return 0x2c0 + number - 10;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    boolean gamepadKeyEvent(final KeyEvent event) {
        if (event == null || !running) return false;
        if (event.getDeviceId() >= 0 && !isCurrentInputDevice(event.getDeviceId(), event.getDevice())) return false;
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                && !event.isFromSource(InputDevice.SOURCE_DPAD)) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            final String axis = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ? "dpadx" : "dpady";
            if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)
                return false;
            final int direction = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP)
                    ? -1 : 1;
            final float value = gamepadDpadState.update(event.getDeviceId(), axis, direction,
                    event.getAction() == KeyEvent.ACTION_DOWN);
            gamepadAxisValues.put(event.getDeviceId() + ":" + axis, value);
            if (event.getDeviceId() >= 0 && event.getDevice() != null) {
                int absCode = "dpadx".equals(axis) ? 0x10 : 0x11;
                int absValue = (int) (value * 32767f);
                hidEvent(event.getDeviceId(), 3, absCode, absValue);
            }
            postHidSessionCallback(event.getDeviceId(), value == 0f, new Runnable() {
                @Override public void run() {
                    callNorns("gamepad_axis", LuaValue.valueOf(axis), LuaValue.valueOf(value),
                            LuaValue.valueOf(1), LuaValue.valueOf(event.getDeviceId()),
                            LuaValue.valueOf(event.getDevice() == null ? "Gamepad" : event.getDevice().getName()));
                }
            });
            return true;
        }
        final String button = gamepadButtonName(keyCode);
        if (button == null) return false;
        final int value = event.getAction() == KeyEvent.ACTION_UP ? 0
                : (event.getRepeatCount() > 0 ? 2 : 1);
        final String pressedKey = event.getDeviceId() + ":" + button;
        if (value == 0) pressedGamepadButtons.remove(pressedKey);
        else pressedGamepadButtons.add(pressedKey);
        final String deviceName = event.getDevice() == null ? "Gamepad" : event.getDevice().getName();
        if (event.getDeviceId() >= 0 && event.getDevice() != null) {
            int hidCode = linuxGamepadButtonCode(keyCode);
            if (hidCode >= 0) hidEvent(event.getDeviceId(), 1, hidCode, value);
        }
        postHidSessionCallback(event.getDeviceId(), value == 0, new Runnable() {
            @Override public void run() {
                callNorns("gamepad_button", LuaValue.valueOf(button), LuaValue.valueOf(value),
                        LuaValue.valueOf(event.getDeviceId()), LuaValue.valueOf(deviceName));
            }
        });
        return true;
    }

    /** Releases held gamepad inputs when the Norns surface loses focus. */
    void releaseGamepadInputs() {
        final HashMap<String, Float> axes = new HashMap<String, Float>(gamepadAxisValues);
        gamepadAxisValues.clear();
        final Set<String> buttons = new HashSet<String>(pressedGamepadButtons);
        pressedGamepadButtons.clear();
        gamepadDpadState.clear();
        if (!running) return;
        post(new Runnable() { @Override public void run() {
            for (Map.Entry<String, Float> entry : axes.entrySet()) {
                String key = entry.getKey();
                int separator = key.indexOf(':');
                if (separator <= 0 || separator == key.length() - 1) continue;
                int deviceId;
                try { deviceId = Integer.parseInt(key.substring(0, separator)); }
                catch (NumberFormatException ignored) { continue; }
                String axis = key.substring(separator + 1);
                callNorns("gamepad_axis", LuaValue.valueOf(axis), LuaValue.ZERO,
                        LuaValue.ONE, LuaValue.valueOf(deviceId), LuaValue.valueOf("Android Gamepad"));
            }
            for (String key : buttons) {
                int separator = key.indexOf(':');
                if (separator <= 0 || separator == key.length() - 1) continue;
                int deviceId;
                try { deviceId = Integer.parseInt(key.substring(0, separator)); }
                catch (NumberFormatException ignored) { continue; }
                callNorns("gamepad_button", LuaValue.valueOf(key.substring(separator + 1)),
                        LuaValue.ZERO, LuaValue.valueOf(deviceId), LuaValue.valueOf("Android Gamepad"));
            }
        }});
    }

    private static String gamepadButtonName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "TL";
            case KeyEvent.KEYCODE_BUTTON_R1: return "TR";
            case KeyEvent.KEYCODE_BUTTON_L2: return "TL2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "TR2";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "MODE";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "THUMBL";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "THUMBR";
            case KeyEvent.KEYCODE_BUTTON_1: return "BTN1";
            case KeyEvent.KEYCODE_BUTTON_2: return "BTN2";
            case KeyEvent.KEYCODE_BUTTON_3: return "BTN3";
            case KeyEvent.KEYCODE_BUTTON_4: return "BTN4";
            case KeyEvent.KEYCODE_BUTTON_5: return "BTN5";
            case KeyEvent.KEYCODE_BUTTON_6: return "BTN6";
            case KeyEvent.KEYCODE_BUTTON_7: return "BTN7";
            case KeyEvent.KEYCODE_BUTTON_8: return "BTN8";
            case KeyEvent.KEYCODE_BUTTON_9: return "BTN9";
            case KeyEvent.KEYCODE_BUTTON_10: return "BTN10";
            case KeyEvent.KEYCODE_BUTTON_11: return "BTN11";
            case KeyEvent.KEYCODE_BUTTON_12: return "BTN12";
            case KeyEvent.KEYCODE_BUTTON_13: return "BTN13";
            case KeyEvent.KEYCODE_BUTTON_14: return "BTN14";
            case KeyEvent.KEYCODE_BUTTON_15: return "BTN15";
            case KeyEvent.KEYCODE_BUTTON_16: return "BTN16";
            default: return null;
        }
    }

    boolean gamepadMotionEvent(final MotionEvent event) {
        if (event == null || !running || event.getAction() != MotionEvent.ACTION_MOVE
                || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
        InputDevice device = event.getDevice();
        if (!isCurrentInputDevice(event.getDeviceId(), device)) return false;
        int source = event.getSource();
        boolean handled = false;
        handled |= dispatchGamepadAxis(event, device, source, "leftx", MotionEvent.AXIS_X);
        handled |= dispatchGamepadAxis(event, device, source, "lefty", MotionEvent.AXIS_Y);
        int rightX = hasMotionRange(device, MotionEvent.AXIS_Z, source)
                ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX;
        int rightY = hasMotionRange(device, MotionEvent.AXIS_RZ, source)
                ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY;
        handled |= dispatchGamepadAxis(event, device, source, "rightx", rightX);
        handled |= dispatchGamepadAxis(event, device, source, "righty", rightY);
        handled |= dispatchGamepadAxis(event, device, source, "dpadx", MotionEvent.AXIS_HAT_X);
        handled |= dispatchGamepadAxis(event, device, source, "dpady", MotionEvent.AXIS_HAT_Y);
        int leftTrigger = hasMotionRange(device, MotionEvent.AXIS_LTRIGGER, source)
                ? MotionEvent.AXIS_LTRIGGER : MotionEvent.AXIS_BRAKE;
        int rightTrigger = hasMotionRange(device, MotionEvent.AXIS_RTRIGGER, source)
                ? MotionEvent.AXIS_RTRIGGER : MotionEvent.AXIS_GAS;
        handled |= dispatchGamepadAxis(event, device, source, "triggerleft", leftTrigger);
        handled |= dispatchGamepadAxis(event, device, source, "triggerright", rightTrigger);
        return handled;
    }

    private static boolean hasMotionRange(InputDevice device, int axis, int source) {
        return device.getMotionRange(axis, source) != null;
    }

    private boolean dispatchGamepadAxis(final MotionEvent event, InputDevice device, int source,
                                        final String name, int axis) {
        InputDevice.MotionRange range = device.getMotionRange(axis, source);
        if (range == null) return false;
        final float value = event.getAxisValue(axis);
        String cacheKey = event.getDeviceId() + ":" + name;
        Float previous = gamepadAxisValues.put(cacheKey, value);
        if (previous != null && Math.abs(previous - value) < 0.002f) return false;
        final float halfResolution = Math.max(0.001f, (range.getMax() - range.getMin()) / 2f);
        final String deviceName = device.getName();
        int absCode = "leftx".equals(name) ? 0 : "lefty".equals(name) ? 1
                : "rightx".equals(name) ? 2 : "righty".equals(name) ? 5
                : "dpadx".equals(name) ? 0x10 : "dpady".equals(name) ? 0x11 : -1;
        if (absCode >= 0) {
            hidEvent(event.getDeviceId(), 3, absCode, (int) (Math.max(-1f, Math.min(1f, value)) * 32767f));
        }
        postHidSessionCallback(event.getDeviceId(), false, new Runnable() {
            @Override public void run() {
                callNorns("gamepad_axis", LuaValue.valueOf(name), LuaValue.valueOf(value),
                        LuaValue.valueOf(halfResolution), LuaValue.valueOf(event.getDeviceId()),
                        LuaValue.valueOf(deviceName));
            }
        });
        return true;
    }

    /** Encaminha teclas físicas Android ao módulo keyboard do norns. */
    boolean keyboardEvent(final KeyEvent event) {
        if (event == null || !running) return false;
        if (event.getDeviceId() >= 0 && !isCurrentInputDevice(event.getDeviceId(), event.getDevice())) return false;
        final String code = keyboardCodeName(event.getKeyCode());
        if (code == null) return false;
        final int value = event.getAction() == KeyEvent.ACTION_UP ? 0
                : (event.getRepeatCount() > 0 ? 2 : 1);
        final int keyboardDeviceId = event.getDeviceId();
        boolean sendNornsKeyEvent = true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            sendNornsKeyEvent = keyboardDeviceState.keyDown(code, keyboardDeviceId);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            sendNornsKeyEvent = keyboardDeviceState.keyUp(code, keyboardDeviceId);
        }
        final int unicode = event.getAction() == KeyEvent.ACTION_UP ? 0
                : event.getUnicodeChar(event.getMetaState());
        final String character = unicode > 0 && !Character.isISOControl(unicode)
                && Character.isValidCodePoint(unicode) ? new String(Character.toChars(unicode)) : null;
        final boolean shift = event.isShiftPressed();
        final boolean alt = event.isAltPressed();
        final boolean ctrl = event.isCtrlPressed();
        final boolean meta = event.isMetaPressed();
        final String[] layoutChars = keyboardLayoutCharacters(event.getDeviceId(), event.getKeyCode());
        if (event.getDeviceId() >= 0 && event.getDevice() != null) {
            int hidCode = linuxKeyCodeFromName(code);
            if (hidCode >= 0) hidEvent(event.getDeviceId(), 1, hidCode, value);
        }
        if (sendNornsKeyEvent) {
            Runnable callback = new Runnable() {
                @Override public void run() {
                    callNorns("keyboard_event", LuaValue.valueOf(code), LuaValue.valueOf(value),
                            character == null ? LuaValue.NIL : LuaValue.valueOf(character),
                            LuaValue.valueOf(shift), LuaValue.valueOf(alt),
                            LuaValue.valueOf(ctrl), LuaValue.valueOf(meta),
                            luaString(layoutChars[0]), luaString(layoutChars[1]),
                            luaString(layoutChars[2]), luaString(layoutChars[3]));
                }
            };
            if (keyboardDeviceId >= 0) postHidSessionCallback(keyboardDeviceId, value == 0, callback);
            else post(callback);
        }
        return true;
    }

    private static LuaValue luaString(String value) {
        return value == null ? LuaValue.NIL : LuaValue.valueOf(value);
    }

    /** Uses Android's active physical-keyboard layout, including Shift and AltGr. */
    private static String[] keyboardLayoutCharacters(int deviceId, int keyCode) {
        KeyCharacterMap map = null;
        try { map = KeyCharacterMap.load(deviceId); } catch (RuntimeException ignored) {}
        if (map == null) {
            try { map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD); }
            catch (RuntimeException ignored) {}
        }
        String[] result = new String[4];
        if (map == null) return result;
        int[] modifiers = {0, KeyEvent.META_SHIFT_ON, KeyEvent.META_ALT_RIGHT_ON,
                KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_RIGHT_ON};
        for (int i = 0; i < modifiers.length; i++) {
            int codePoint = map.get(keyCode, modifiers[i]);
            if (codePoint != 0 && Character.isValidCodePoint(codePoint)
                    && !Character.isISOControl(codePoint)) {
                result[i] = new String(Character.toChars(codePoint));
            }
        }
        return result;
    }

    /** Libera eventos físicos que podem ter perdido o ACTION_UP durante pausa/desfoco. */
    /** Removes only the keys owned by a disconnected physical keyboard. */
    /** Removes only the keys owned by a disconnected physical keyboard. */
    void releaseKeyboardDevice(final int deviceId) {
        KeyboardDeviceState.Release release = keyboardDeviceState.removeDevice(deviceId);
        for (String code : release.keysByDevice.get(deviceId)) {
            int hidCode = linuxKeyCodeFromName(code);
            if (hidCode >= 0 && deviceId >= 0) hidEvent(deviceId, 1, hidCode, 0);
        }
        if (!running || release.logicalKeys.isEmpty()) return;
        postHidSessionCallback(deviceId, true, new Runnable() {
            @Override public void run() {
                for (String code : release.logicalKeys) {
                    callNorns("keyboard_event", LuaValue.valueOf(code), LuaValue.ZERO,
                            LuaValue.NIL, LuaValue.FALSE, LuaValue.FALSE,
                            LuaValue.FALSE, LuaValue.FALSE);
                }
            }
        });
    }

    void releaseKeyboardKeys() {
        final KeyboardDeviceState.Release release = keyboardDeviceState.releaseAll();
        if (release.logicalKeys.isEmpty() && release.keysByDevice.isEmpty()) return;
        // Enfileira os key-ups HID antes de liberar a tecla lógica agregada.
        for (Map.Entry<Integer, Set<String>> entry : release.keysByDevice.entrySet()) {
            int deviceId = entry.getKey();
            for (String code : entry.getValue()) {
                int hidCode = linuxKeyCodeFromName(code);
                if (hidCode >= 0 && deviceId >= 0) hidEvent(deviceId, 1, hidCode, 0);
            }
        }
        if (running && !release.logicalKeys.isEmpty()) {
            post(new Runnable() {
                @Override public void run() {
                    for (String code : release.logicalKeys) {
                        callNorns("keyboard_event", LuaValue.valueOf(code), LuaValue.ZERO,
                                LuaValue.NIL, LuaValue.FALSE, LuaValue.FALSE,
                                LuaValue.FALSE, LuaValue.FALSE);
                    }
                }
            });
        }
    }

    private static String keyboardCodeName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
            return String.valueOf((char) ('A' + keyCode - KeyEvent.KEYCODE_A));
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
            return String.valueOf((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_DEL: return "BACKSPACE";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "DELETE";
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_TAB: return "TAB";
            case KeyEvent.KEYCODE_ESCAPE: return "ESCAPE";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "LEFTSHIFT";
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "RIGHTSHIFT";
            case KeyEvent.KEYCODE_ALT_LEFT: return "LEFTALT";
            case KeyEvent.KEYCODE_ALT_RIGHT: return "RIGHTALT";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "LEFTCTRL";
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "RIGHTCTRL";
            case KeyEvent.KEYCODE_META_LEFT: return "LEFTMETA";
            case KeyEvent.KEYCODE_META_RIGHT: return "RIGHTMETA";
            case KeyEvent.KEYCODE_HOME: return "HOME";
            case KeyEvent.KEYCODE_MOVE_HOME: return "HOME";
            case KeyEvent.KEYCODE_MOVE_END: return "END";
            case KeyEvent.KEYCODE_PAGE_UP: return "PAGEUP";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "PAGEDOWN";
            case KeyEvent.KEYCODE_INSERT: return "INSERT";
            case KeyEvent.KEYCODE_F1: return "F1";
            case KeyEvent.KEYCODE_F2: return "F2";
            case KeyEvent.KEYCODE_F3: return "F3";
            case KeyEvent.KEYCODE_F4: return "F4";
            case KeyEvent.KEYCODE_F5: return "F5";
            case KeyEvent.KEYCODE_F6: return "F6";
            case KeyEvent.KEYCODE_F7: return "F7";
            case KeyEvent.KEYCODE_F8: return "F8";
            case KeyEvent.KEYCODE_F9: return "F9";
            case KeyEvent.KEYCODE_F10: return "F10";
            case KeyEvent.KEYCODE_F11: return "F11";
            case KeyEvent.KEYCODE_F12: return "F12";
            case KeyEvent.KEYCODE_COMMA: return "COMMA";
            case KeyEvent.KEYCODE_PERIOD: return "PERIOD";
            case KeyEvent.KEYCODE_SLASH: return "SLASH";
            case KeyEvent.KEYCODE_BACKSLASH: return "BACKSLASH";
            case KeyEvent.KEYCODE_SEMICOLON: return "SEMICOLON";
            case KeyEvent.KEYCODE_APOSTROPHE: return "APOSTROPHE";
            case KeyEvent.KEYCODE_LEFT_BRACKET: return "LEFTBRACKET";
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return "RIGHTBRACKET";
            case KeyEvent.KEYCODE_MINUS: return "MINUS";
            case KeyEvent.KEYCODE_EQUALS: return "EQUALS";
            case KeyEvent.KEYCODE_GRAVE: return "GRAVE";
            case KeyEvent.KEYCODE_PLUS: return "PLUS";
            case KeyEvent.KEYCODE_AT: return "AT";
            case KeyEvent.KEYCODE_POUND: return "POUND";
            case KeyEvent.KEYCODE_STAR: return "STAR";
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "KPENTER";
            case KeyEvent.KEYCODE_NUMPAD_ADD: return "KPPLUS";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return "KPMINUS";
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return "KPMULTIPLY";
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return "KPDIVIDE";
            case KeyEvent.KEYCODE_NUMPAD_DOT: return "KPDOT";
            case KeyEvent.KEYCODE_NUMPAD_0: return "KP0";
            case KeyEvent.KEYCODE_NUMPAD_1: return "KP1";
            case KeyEvent.KEYCODE_NUMPAD_2: return "KP2";
            case KeyEvent.KEYCODE_NUMPAD_3: return "KP3";
            case KeyEvent.KEYCODE_NUMPAD_4: return "KP4";
            case KeyEvent.KEYCODE_NUMPAD_5: return "KP5";
            case KeyEvent.KEYCODE_NUMPAD_6: return "KP6";
            case KeyEvent.KEYCODE_NUMPAD_7: return "KP7";
            case KeyEvent.KEYCODE_NUMPAD_8: return "KP8";
            case KeyEvent.KEYCODE_NUMPAD_9: return "KP9";
            case KeyEvent.KEYCODE_NUMPAD_COMMA: return "KPJPCOMMA";
            case KeyEvent.KEYCODE_NUMPAD_EQUALS: return "KPEQUAL";
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN: return "KPLEFTPAREN";
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN: return "KPRIGHTPAREN";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "CAPSLOCK";
            case KeyEvent.KEYCODE_NUM_LOCK: return "NUMLOCK";
            case KeyEvent.KEYCODE_SCROLL_LOCK: return "SCROLLLOCK";
            case KeyEvent.KEYCODE_SYSRQ: return "SYSRQ";
            case KeyEvent.KEYCODE_BREAK: return "PAUSE";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_CAMERA:
                return null;
            default:
                String androidName = KeyEvent.keyCodeToString(keyCode);
                return androidName != null && androidName.startsWith("KEYCODE_")
                        ? androidName.substring("KEYCODE_".length()) : null;
        }
    }

    void enc(final int n, final int d) {
        post(new Runnable() {
            @Override
            public void run() {
                callNorns("enc", LuaValue.valueOf(n), LuaValue.valueOf(d));
            }
        });
    }

    void paramNorm(final String id, final double norm) {
        post(new Runnable() {
            @Override
            public void run() {
                callNorns("param_norm", LuaValue.valueOf(id), LuaValue.valueOf(norm));
            }
        });
    }

    void paramDelta(final String id, final int d) {
        post(new Runnable() {
            @Override
            public void run() {
                callNorns("param_delta", LuaValue.valueOf(id), LuaValue.valueOf(d));
            }
        });
    }

    void paramTrigger(final String id) {
        post(new Runnable() {
            @Override
            public void run() {
                callNorns("param_trigger", LuaValue.valueOf(id));
            }
        });
    }

    void paramSetValue(final String id, final String value) {
        post(new Runnable() {
            @Override public void run() {
                callNorns("param_set_value", LuaValue.valueOf(id), LuaValue.valueOf(value));
            }
        });
    }

    void paramPresetRead(final int slot) {
        post(new Runnable() {
            @Override public void run() { callNorns("param_preset_read", LuaValue.valueOf(slot)); }
        });
    }

    void paramPresetWrite(final int slot) {
        post(new Runnable() {
            @Override public void run() { callNorns("param_preset_write", LuaValue.valueOf(slot)); }
        });
    }

    /** Grava o estado atual em dust/data/state-transfer.pset e avisa (thread do Lua) o resultado. */
    void paramStateExport(final java.util.function.Consumer<Boolean> done) {
        post(new Runnable() {
            @Override public void run() {
                done.accept(callNornsBool("param_state_export"));
            }
        });
    }

    void paramStateImport(final java.util.function.Consumer<Boolean> done) {
        post(new Runnable() {
            @Override public void run() {
                done.accept(callNornsBool("param_state_import"));
            }
        });
    }

    void paramPresetDelete(final int slot) {
        post(new Runnable() {
            @Override public void run() { callNorns("param_preset_delete", LuaValue.valueOf(slot)); }
        });
    }

    // ------------------------------------------------------------------ agendamento

    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            callNorns("tick");
        }
    };

    private void wakeAt(double t) {
        ScheduledThreadPoolExecutor ex = exec;
        if (ex == null) return;
        synchronized (wakeLock) {
            if (pendingWake != null) {
                pendingWake.cancel(false);
                pendingWake = null;
            }
            if (t < 0) return;
            long delay = (long) ((t - nowSec()) * 1e9);
            if (delay < 0) delay = 0;
            try {
                pendingWake = ex.schedule(tickTask, delay, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException e) {
                pendingWake = null;
            }
        }
    }

    private void paramsChanged() {
        ScheduledThreadPoolExecutor ex = exec;
        if (ex == null) return;
        synchronized (wakeLock) {
            if (paramsPending) return;
            paramsPending = true;
        }
        try {
            ex.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (wakeLock) {
                        paramsPending = false;
                    }
                    publishParams();
                }
            }, 80, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            synchronized (wakeLock) {
                paramsPending = false;
            }
        }
    }

    private void publishParams() {
        try {
            Globals g = globals;
            if (g == null) return;
            LuaValue f = g.get("_norns").get("params_serialize");
            if (!f.isfunction()) return;
            listener.onParams(f.call().tojstring());
        } catch (LuaError e) {
            AppLog.e(String.valueOf(e.getMessage()));
        } catch (Throwable t) {
            AppLog.e("Erro ao ler parâmetros: " + t);
        }
    }

    // ------------------------------------------------------------------ arquivos / assets

    private String readAsset(String name) {
        synchronized (assetCache) {
            String cached = assetCache.get(name);
            if (cached != null) return cached;
        }
        if (!name.startsWith("lua/")) return null;
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Dust.copyStream(in, bo);
            String s = new String(bo.toByteArray(), UTF8);
            synchronized (assetCache) {
                assetCache.put(name, s);
            }
            return s;
        } catch (IOException e) {
            return null;
        } finally {
            Dust.closeQuietly(in);
        }
    }

    // ------------------------------------------------------------------ instalação do _host e do screen

    private void installHost(Globals g) {
        LuaTable host = new LuaTable();
        host.set("dust", LuaValue.valueOf(dust.root.getAbsolutePath() + "/"));
        for (int i = 0; i < HOST_NAMES.length; i++) {
            host.set(HOST_NAMES[i], new HostFn(i));
        }
        g.set("_host", host);

        LuaTable scr = new LuaTable();
        for (int i = 0; i < SCREEN_NAMES.length; i++) {
            scr.set(SCREEN_NAMES[i], new ScreenFn(i));
        }
        g.set("screen", scr);
    }

    private final class HostFn extends VarArgFunction {
        private final int op;

        HostFn(int op) {
            this.op = op;
        }

        @Override
        public Varargs invoke(Varargs a) {
            try {
                return hostOp(op, a);
            } catch (LuaError e) {
                throw e;
            } catch (Throwable t) {
                throw new LuaError("host: " + t);
            }
        }
    }

    private final class ScreenFn extends VarArgFunction {
        private final int op;

        ScreenFn(int op) {
            this.op = op;
        }

        @Override
        public Varargs invoke(Varargs a) {
            try {
                return screenOp(op, a);
            } catch (LuaError e) {
                throw e;
            } catch (Throwable t) {
                throw new LuaError("screen." + SCREEN_NAMES[op] + ": " + t);
            }
        }
    }

    private static boolean flag(Varargs a, int i) {
        LuaValue v = a.arg(i);
        if (v.isboolean()) return v.toboolean();
        return a.optdouble(i, 0.0) != 0.0;
    }

    private static Object[] oscArgsFromLua(LuaValue values) {
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (!values.istable()) return new Object[0];
        for (int i = 1; i <= values.length(); i++) {
            LuaValue value = values.get(i);
            if (value.isnil()) args.add(null);
            else if (value.isboolean()) args.add(Boolean.valueOf(value.toboolean()));
            else if (value.isnumber()) {
                double number = value.todouble();
                if (number == Math.rint(number) && number >= Integer.MIN_VALUE
                        && number <= Integer.MAX_VALUE) args.add(Integer.valueOf((int) number));
                else if (number == Math.rint(number) && Math.abs(number) <= 9007199254740991d)
                    args.add(Long.valueOf((long) number));
                else args.add(Float.valueOf((float) number));
            } else if (value.istable()) {
                LuaValue typeValue = value.get("__osc_type");
                LuaValue payload = value.get("value");
                String type = typeValue.isnil() ? "" : typeValue.tojstring();
                if ("int64".equals(type) && payload.isnumber()) {
                    double number = payload.todouble();
                    if (number != Math.rint(number) || Math.abs(number) > 9007199254740991d)
                        throw new IllegalArgumentException("OSC int64 must be an exact Lua integer");
                    args.add(Long.valueOf((long) number));
                } else if ("float64".equals(type) && payload.isnumber()) {
                    args.add(Double.valueOf(payload.todouble()));
                } else {
                    LuaValue blob = "blob".equals(type) ? payload : value;
                    if (!blob.istable() || blob.length() > 65507)
                        throw new IllegalArgumentException("OSC table argument must be a byte array");
                    byte[] bytes = new byte[blob.length()];
                    for (int n = 0; n < bytes.length; n++) {
                        int octet = blob.get(n + 1).checkint();
                        if (octet < 0 || octet > 255)
                            throw new IllegalArgumentException("OSC blob byte out of range");
                        bytes[n] = (byte) octet;
                    }
                    args.add(bytes);
                }
            } else args.add(value.tojstring());
        }
        return args.toArray();
    }

    private Varargs hostOp(int op, Varargs a) {
        switch (op) {
            case H_APP_INFO: {
                LuaTable info = new LuaTable();
                info.set("package", LuaValue.valueOf(ctx.getPackageName()));
                try {
                    android.content.pm.PackageInfo packageInfo =
                            ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                    info.set("version_name", LuaValue.valueOf(
                            packageInfo.versionName != null ? packageInfo.versionName : "unknown"));
                    long versionCode = android.os.Build.VERSION.SDK_INT >= 28
                            ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
                    info.set("version_code", LuaValue.valueOf(versionCode));
                } catch (android.content.pm.PackageManager.NameNotFoundException error) {
                    info.set("version_name", LuaValue.valueOf("unknown"));
                    info.set("version_code", LuaValue.valueOf(-1));
                    info.set("error", LuaValue.valueOf(error.getMessage()));
                }
                return info;
            }
            case H_LOG:
                AppLog.add(a.optjstring(1, ""), a.toboolean(2));
                return LuaValue.NONE;
            case H_NOW:
                return LuaValue.valueOf(nowSec());
            case H_WAKE_AT:
                wakeAt(a.checkdouble(1));
                return LuaValue.NONE;
            case H_READ_ASSET: {
                String s = readAsset(a.checkjstring(1));
                return s == null ? LuaValue.NIL : LuaValue.valueOf(s);
            }
            case H_READ_FILE: {
                File f = dust.resolve(a.checkjstring(1));
                if (f == null || !f.isFile()) return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(Dust.readText(f, 16 * 1024 * 1024));
                } catch (IOException e) {
                    return LuaValue.NIL;
                }
            }
            case H_WRITE_FILE: {
                File f = dust.resolve(a.checkjstring(1));
                if (f == null) return LuaValue.FALSE;
                try {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f, a.toboolean(3));
                    try {
                        out.write(a.checkjstring(2).getBytes(UTF8));
                    } finally {
                        Dust.closeQuietly(out);
                    }
                    return LuaValue.TRUE;
                } catch (IOException e) {
                    return LuaValue.FALSE;
                }
            }
            case H_FILE_EXISTS: {
                File f = dust.resolve(a.checkjstring(1));
                return LuaValue.valueOf(f != null && f.exists());
            }
            case H_RESOLVE_SAMPLE_PATH: {
                File f = dust.resolve(a.checkjstring(1));
                if (f == null || !f.isFile() || f.length() < 44 || f.length() > 100L * 1024 * 1024
                        || !"wav".equals(Dust.extension(f.getName()))) return LuaValue.NIL;
                java.io.RandomAccessFile input = null;
                try {
                    input = new java.io.RandomAccessFile(f, "r");
                    byte[] header = new byte[12];
                    input.readFully(header);
                    if (!"RIFF".equals(new String(header, 0, 4, java.nio.charset.Charset.forName("US-ASCII")))
                            || !"WAVE".equals(new String(header, 8, 4, java.nio.charset.Charset.forName("US-ASCII"))))
                        return LuaValue.NIL;
                    return LuaValue.valueOf(f.getCanonicalPath());
                } catch (IOException invalidWav) {
                    return LuaValue.NIL;
                } finally {
                    if (input != null) try { input.close(); } catch (IOException ignored) { }
                }
            }
            case H_AUDIO_FILE_INFO: {
                File source = dust.resolve(a.checkjstring(1));
                if (source == null || !source.isFile())
                    return LuaValue.varargsOf(new LuaValue[]{LuaValue.ZERO, LuaValue.ZERO, LuaValue.ZERO});
                try {
                    WavReader.Info info = WavReader.inspect(source);
                    return LuaValue.varargsOf(new LuaValue[]{
                            LuaValue.valueOf(info.channels),
                            LuaValue.valueOf((double) info.samples),
                            LuaValue.valueOf(info.sampleRate)
                    });
                } catch (IOException invalidAudio) {
                    return LuaValue.varargsOf(new LuaValue[]{LuaValue.ZERO, LuaValue.ZERO, LuaValue.ZERO});
                }
            }
            case H_MKDIR: {
                File f = dust.resolve(a.checkjstring(1));
                if (f == null) return LuaValue.FALSE;
                return LuaValue.valueOf(f.isDirectory() || f.mkdirs());
            }
            case H_LIST_DIR: {
                File f = dust.resolve(a.checkjstring(1));
                if (f == null || !f.isDirectory()) return LuaValue.NIL;
                String[] names = f.list();
                LuaTable t = new LuaTable();
                if (names != null) {
                    java.util.Arrays.sort(names);
                    for (int i = 0; i < names.length; i++) t.set(i + 1, LuaValue.valueOf(names[i]));
                }
                return t;
            }
            case H_REMOVE: {
                File f = dust.resolve(a.checkjstring(1));
                if (f == null || !f.exists()) return LuaValue.FALSE;
                return LuaValue.valueOf(f.delete());
            }
            case H_COPY: {
                // cp e cp -r do shell emulado: copia arquivo ou pasta dentro de dust/ (copy(origem, destino, recursivo))
                File from = dust.resolve(a.checkjstring(1));
                File to = dust.resolve(a.checkjstring(2));
                if (from == null || to == null) return LuaValue.FALSE;
                return LuaValue.valueOf(dust.copy(from, to, a.toboolean(3)));
            }
            case H_REMOVE_ALL: {
                // rm -r do shell emulado: apaga a pasta inteira, menos dust/, code/, data/ e audio/
                File f = dust.resolve(a.checkjstring(1));
                return LuaValue.valueOf(f != null && dust.removeAll(f));
            }
            case H_RENAME: {
                File f1 = dust.resolve(a.checkjstring(1));
                File f2 = dust.resolve(a.checkjstring(2));
                if (f1 == null || f2 == null || !f1.exists()) return LuaValue.FALSE;
                return LuaValue.valueOf(f1.renameTo(f2));
            }
            case H_ENGINE_LOAD: {
                String requestedEngine = a.optjstring(1, "none");
                // Native-only engines must never be routed into the Java SynthCore.
                audio.setEngine(javaEngineSelection(requestedEngine));
                return LuaValue.NONE;
            }
            case H_ENGINE_CMD: {
                String cmd = a.checkjstring(1);
                double v1 = a.isnumber(2) ? a.checkdouble(2) : 0.0;
                double v2 = a.isnumber(3) ? a.checkdouble(3) : 0.0;
                audio.command(cmd, v1, v2);
                return LuaValue.NONE;
            }
            case H_PARAMS_CHANGED:
                paramsChanged();
                return LuaValue.NONE;
            case H_MIDI_DEVICES: {
                LuaTable list = new LuaTable();
                for (MidiBridge.Device d : midi.devices()) {
                    LuaTable item = new LuaTable();
                    item.set("id", LuaValue.valueOf(d.id));
                    item.set("name", LuaValue.valueOf(d.name));
                    item.set("inputs", LuaValue.valueOf(d.inputs));
                    item.set("outputs", LuaValue.valueOf(d.outputs));
                    list.set(list.length() + 1, item);
                }
                return list;
            }
            case H_MIDI_OPEN:
                return LuaValue.valueOf(midi.open(a.checkint(1)));
            case H_MIDI_SEND:
                return LuaValue.valueOf(midi.send(a.checkint(1), a.checkint(2),
                        a.optint(3, 0), a.optint(4, 0)));
            case H_MIDI_SEND_BYTES: {
                LuaValue bytesValue = a.arg(2);
                if (!bytesValue.istable()) return LuaValue.FALSE;
                int count = bytesValue.length();
                if (count < 1 || count > 65536) return LuaValue.FALSE;
                byte[] bytes = new byte[count];
                for (int i = 0; i < count; i++) {
                    LuaValue value = bytesValue.get(i + 1);
                    if (!value.isnumber()) return LuaValue.FALSE;
                    double number = value.todouble();
                    if (!Double.isFinite(number) || number != Math.floor(number)
                            || number < 0 || number > 255) return LuaValue.FALSE;
                    bytes[i] = (byte) ((int) number);
                }
                return LuaValue.valueOf(midi.sendBytes(a.checkint(1), bytes));
            }
            case H_USB_SERIAL_DEVICES: {
                LuaTable list = new LuaTable();
                for (UsbCdcSerialBridge.DeviceInfo device : usbSerial.devices()) {
                    LuaTable item = new LuaTable();
                    item.set("id", LuaValue.valueOf(device.id));
                    item.set("name", LuaValue.valueOf(device.name));
                    item.set("vendor", LuaValue.valueOf(device.vendor));
                    item.set("model", LuaValue.valueOf(device.model));
                    item.set("serial", LuaValue.valueOf(device.serial));
                    item.set("interface", LuaValue.valueOf(device.interfaceName));
                    item.set("vendor_id", LuaValue.valueOf(device.vendorId));
                    item.set("product_id", LuaValue.valueOf(device.productId));
                    list.set(list.length() + 1, item);
                }
                return list;
            }
            case H_USB_SERIAL_OPEN: {
                int baud = UsbCdcSerialBridge.baudFromTermios(a.optint(2, 4098));
                int cflag = a.optint(3, 48);
                return LuaValue.valueOf(baud > 0 && usbSerial.open(a.checkint(1), baud, cflag));
            }
            case H_USB_SERIAL_WRITE: {
                LuaValue data = a.arg(2);
                if (!data.isstring()) return LuaValue.FALSE;
                org.luaj.vm2.LuaString raw = data.checkstring();
                if (raw.m_length > 65536) return LuaValue.FALSE;
                byte[] bytes = new byte[raw.m_length];
                raw.copyInto(0, bytes, 0, raw.m_length);
                return LuaValue.valueOf(usbSerial.write(a.checkint(1), bytes));
            }
            case H_USB_SERIAL_CLOSE:
                closeUsbSerialPort(a.checkint(1));
                return LuaValue.NONE;
            case H_POLL_VALUE:
                return LuaValue.valueOf(audio.pollValue(a.checkjstring(1)));
            case H_MIDI_CLOSE:
                midi.close(a.checkint(1));
                return LuaValue.NONE;
            case H_OSC_SEND: {
                String address = a.checkjstring(1);
                int port = a.checkint(2);
                String path = a.checkjstring(3);
                try { osc.send(address, port, path, oscArgsFromLua(a.arg(4))); }
                catch (Exception error) { AppLog.e("OSC send: " + error.getMessage()); return LuaValue.FALSE; }
                return LuaValue.TRUE;
            }
            case H_OSC_SEND_BUNDLE: {
                String address = a.checkjstring(1);
                int port = a.checkint(2);
                LuaValue entries = a.arg(3);
                if (!entries.istable() || entries.length() > 256) return LuaValue.FALSE;
                byte[][] messages = new byte[entries.length()][];
                try {
                    for (int i = 0; i < entries.length(); i++) {
                        LuaValue entry = entries.get(i + 1);
                        if (!entry.istable()) return LuaValue.FALSE;
                        String path = entry.get(1).checkjstring();
                        messages[i] = OscBridge.encode(path, oscArgsFromLua(entry.get(2)));
                    }
                    double requestedTime = a.optdouble(4, 0.0);
                    if (Double.isNaN(requestedTime) || Double.isInfinite(requestedTime)
                            || requestedTime < 0 || requestedTime > 253402300799000d)
                        return LuaValue.FALSE;
                    long timetag = requestedTime <= 0 ? 1L
                            : OscBridge.unixMillisToNtp((long) requestedTime);
                    osc.sendBundle(address, port, messages, timetag);
                    return LuaValue.TRUE;
                } catch (Exception error) {
                    AppLog.e("OSC bundle send: " + error.getMessage());
                    return LuaValue.FALSE;
                }
            }
            case H_OSC_LISTEN: {
                final int port = a.checkint(1);
                try { osc.listen(port, new OscBridge.Listener() { public void onMessage(final String path, final Object[] values, final String from, final int fromPort) { post(new Runnable() { public void run() { dispatchOsc(path, values, from, fromPort); } }); } }); return LuaValue.TRUE; }
                catch (Exception e) { AppLog.e("OSC listen: " + e.getMessage()); return LuaValue.FALSE; }
            }
            case H_OSC_CLOSE: osc.close(); return LuaValue.NONE;
            case H_SC_AVAILABLE:
                return LuaValue.valueOf(superColliderStarted && SuperColliderBridge.isAvailable());
            case H_SC_PLUGIN_EXISTS: {
                String name = a.checkjstring(1);
                if (name.length() == 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains(".."))
                    return LuaValue.FALSE;
                File root = superColliderPluginDir;
                File plugin = root == null ? null : new File(root, name);
                return LuaValue.valueOf(superColliderStarted && plugin != null
                        && plugin.isFile() && plugin.length() > 0);
            }
            case H_SC_POLL_REPLY: {
                if (!superColliderStarted) return LuaValue.NIL;
                SuperColliderOsc.Reply reply = SuperColliderBridge.pollReply();
                if (reply == null) return LuaValue.NIL;
                LuaTable result = new LuaTable();
                result.set("address", LuaValue.valueOf(reply.address));
                if (reply.command != null) result.set("command", LuaValue.valueOf(reply.command));
                if (reply.message != null) result.set("message", LuaValue.valueOf(reply.message));
                if (reply.syncId != null) result.set("sync_id", LuaValue.valueOf(reply.syncId.intValue()));
                return result;
            }
            case H_SC_SOFTCUT_READY:
                if (!a.optboolean(1, false)) {
                    if (!audio.setNativeSoftcutOutputOwner(false, false, false))
                        AppLog.e("Softcut: falha ao devolver ownership ao motor Java.");
                    audio.resetNativeSoftcutPhaseDiagnostics();
                }
                SuperColliderSoftcutBuffers.setReady(a.optboolean(1, false));
                return LuaValue.NONE;
            case H_SC_SOFTCUT_OUTPUT: {
                boolean enabled = a.optboolean(1, false);
                boolean validated = a.optboolean(2, false)
                        && SuperColliderSoftcutBuffers.isNativeRoutingValidated();
                return LuaValue.valueOf(audio.setNativeSoftcutOutputOwner(enabled,
                        enabled && SuperColliderBridge.isAvailable() && SuperColliderSoftcutBuffers.isReady(), validated));
            }
            case H_SC_SOFTCUT_BUFFER_ISOLATION_PROBE: {
                if (!superColliderStarted || !SuperColliderBridge.isAvailable()
                        || superColliderSynthDefDir == null) return LuaValue.FALSE;
                if (!audio.setNativeSoftcutOutputOwner(false, false, false)) return LuaValue.FALSE;
                float[] gateState = new float[54];
                if (!SuperColliderBridge.readSoftcutDiagnostics(gateState) || gateState[12] > 0.5f)
                    return LuaValue.FALSE;
                for (String bundledName : new String[]{
                        "SoftcutIsolationInput.scsyndef", "SoftcutRecorder.scsyndef", "SoftcutStereoRouteProbe.scsyndef"}) {
                    File synthDef = new File(superColliderSynthDefDir, bundledName);
                    if (!synthDef.isFile() || synthDef.length() <= 0 || synthDef.length() > 65480)
                        return LuaValue.FALSE;
                    java.io.FileInputStream input = null;
                    try {
                        input = new java.io.FileInputStream(synthDef);
                        byte[] data = new byte[(int) synthDef.length()];
                        int offset = 0;
                        while (offset < data.length) {
                            int count = input.read(data, offset, data.length - offset);
                            if (count < 0) return LuaValue.FALSE;
                            offset += count;
                        }
                        if (!SuperColliderBridge.loadSynthDef(data)) return LuaValue.FALSE;
                    } catch (IOException error) {
                        AppLog.e("Softcut BufWr isolation SynthDef: " + error.getMessage());
                        return LuaValue.FALSE;
                    } finally {
                        Dust.closeQuietly(input);
                    }
                }
                return LuaValue.valueOf(SuperColliderSoftcutBuffers.verifyNativeBufferIsolation(
                        (independent, ch1, ch2) -> {
                            AppLog.i("Softcut probe CH1/CH2 SoftcutRecorder/BufWr: independent="
                                    + independent + " ch1=" + ch1 + " ch2=" + ch2);
                            if (!independent) return;
                            boolean queued = SuperColliderSoftcutBuffers.verifyNativeStereoRouting(
                                    (routed, busL, busR, routeL, routeR, pcmL, pcmR) ->
                                            AppLog.i("Softcut stereo probe: routed=" + routed
                                                    + " busL=" + busL + " busR=" + busR
                                                    + " routeL=" + routeL + " routeR=" + routeR
                                                    + " mutedPcmL=" + pcmL + " mutedPcmR=" + pcmR));
                            if (!queued) AppLog.e("Softcut: não foi possível iniciar a prova estéreo mutada.");
                        }));
            }
            case H_SC_SOFTCUT_DIAGNOSTICS: {
                if (!superColliderStarted) return LuaValue.NIL;
                float[] values = new float[54];
                if (!SuperColliderBridge.readSoftcutDiagnostics(values)) return LuaValue.NIL;
                SoftcutPhaseSupervisor.SnapshotStatus freshness =
                        audio.observeNativeSoftcutSnapshot(values[13]);
                LuaTable result = new LuaTable();
                result.set("snapshot_sequence", LuaValue.valueOf(freshness.sequence));
                result.set("snapshot_fresh", LuaValue.valueOf(freshness.fresh));
                double snapshotAge = freshness.ageSeconds();
                result.set("snapshot_age_seconds", LuaValue.valueOf(
                        Double.isInfinite(snapshotAge) ? -1.0 : snapshotAge));
                LuaTable positions = new LuaTable();
                for (int i=0;i<6;i++) positions.set(i+1,LuaValue.valueOf(values[i]));
                result.set("positions",positions);
                LuaTable recorderPositions = new LuaTable();
                LuaTable recorderActive = new LuaTable();
                LuaTable recorderReanchorPending = new LuaTable();
                for (int channel = 1; channel <= 2; channel++) {
                    recorderPositions.set(channel, LuaValue.valueOf(values[13 + channel]));
                    recorderActive.set(channel, LuaValue.valueOf(
                            SuperColliderSoftcutBuffers.isRecording(channel)));
                    recorderReanchorPending.set(channel, LuaValue.valueOf(
                            SuperColliderSoftcutBuffers.isReanchorPending(channel)));
                }
                result.set("recorder_positions", recorderPositions);
                result.set("recorder_active", recorderActive);
                result.set("recorder_reanchor_pending", recorderReanchorPending);
                LuaTable routing = new LuaTable();
                routing.set("master_left_peak",LuaValue.valueOf(values[6]));
                routing.set("master_right_peak",LuaValue.valueOf(values[7]));
                routing.set("softcut_left_peak",LuaValue.valueOf(values[8]));
                routing.set("softcut_right_peak",LuaValue.valueOf(values[9]));
                routing.set("oboe_left_peak",LuaValue.valueOf(values[10]));
                routing.set("oboe_right_peak",LuaValue.valueOf(values[11]));
                routing.set("native_gate_open",LuaValue.valueOf(values[12]>0.5f));
                routing.set("stereo_probe_left_peak", LuaValue.valueOf(values[52]));
                routing.set("stereo_probe_right_peak", LuaValue.valueOf(values[53]));
                result.set("routing",routing);
                LuaTable input = new LuaTable();
                input.set("bus_peak", LuaValue.valueOf(values[16]));
                input.set("bus_rms", LuaValue.valueOf(values[17]));
                input.set("ring_frames_read", LuaValue.valueOf(values[18]));
                input.set("ring_dropped_frames", LuaValue.valueOf(values[19]));
                input.set("ring_skipped_frames", LuaValue.valueOf(values[20]));
                input.set("ring_underrun_frames", LuaValue.valueOf(values[21]));
                input.set("channels", LuaValue.valueOf(values[22]));
                input.set("sample_rate", LuaValue.valueOf(values[23]));
                result.set("input", input);
                LuaTable recorderSignal = new LuaTable();
                for (int channel = 1; channel <= 2; channel++) {
                    int offset = 24 + (channel - 1) * 12;
                    LuaTable signal = new LuaTable();
                    signal.set("physical_input_peak", LuaValue.valueOf(values[offset]));
                    signal.set("physical_input_rms", LuaValue.valueOf(values[offset + 1]));
                    signal.set("scaled_input_peak", LuaValue.valueOf(values[offset + 2]));
                    signal.set("scaled_input_rms", LuaValue.valueOf(values[offset + 3]));
                    signal.set("cut_mix_peak", LuaValue.valueOf(values[offset + 4]));
                    signal.set("cut_mix_rms", LuaValue.valueOf(values[offset + 5]));
                    signal.set("incoming_peak", LuaValue.valueOf(values[offset + 6]));
                    signal.set("incoming_rms", LuaValue.valueOf(values[offset + 7]));
                    signal.set("record_gate_peak", LuaValue.valueOf(values[offset + 8]));
                    signal.set("record_gate_rms", LuaValue.valueOf(values[offset + 9]));
                    signal.set("write_valid_peak", LuaValue.valueOf(values[offset + 10]));
                    signal.set("write_valid_rms", LuaValue.valueOf(values[offset + 11]));
                    recorderSignal.set(channel, signal);
                }
                result.set("recorder_signal", recorderSignal);
                return result;
            }
            case H_SC_SOFTCUT_PHASE_SAMPLE: {
                SoftcutPhaseDiagnostics.Snapshot snapshot=audio.sampleNativeSoftcutPhase(
                        a.checkint(1),a.checkdouble(2),a.checkdouble(3),
                        a.checkdouble(4),a.checkdouble(5),a.optboolean(6,false));
                if(snapshot==null)return LuaValue.NIL;
                LuaTable result=new LuaTable();
                result.set("samples",LuaValue.valueOf(snapshot.samples));
                result.set("last_error_seconds",LuaValue.valueOf(snapshot.lastErrorSeconds));
                result.set("max_abs_error_seconds",LuaValue.valueOf(snapshot.maxAbsErrorSeconds));
                result.set("rms_error_seconds",LuaValue.valueOf(snapshot.rmsErrorSeconds));
                result.set("sample_rate",LuaValue.valueOf(audio.getSampleRate()));
                result.set("reanchor_threshold_seconds",
                        LuaValue.valueOf(audio.nativeSoftcutPhaseReanchorThresholdSeconds()));
                result.set("should_reanchor",
                        LuaValue.valueOf(audio.shouldReanchorNativeSoftcutPhase(snapshot.lastErrorSeconds)));
                result.set("block_frames", LuaValue.valueOf(SuperColliderBridge.blockSize()));
                return result;
            }
            case H_SC_SOFTCUT_RECORDER_SAMPLE: {
                SoftcutRecorderCursorMonitor.Snapshot snapshot =
                        audio.sampleNativeSoftcutRecorderCursor(
                                a.checkint(1), a.checkdouble(2), a.checkdouble(3),
                                a.checkdouble(4), a.checkdouble(5),
                                a.optboolean(6, true), a.optboolean(7, false));
                if (snapshot == null) return LuaValue.NIL;
                LuaTable result = new LuaTable();
                result.set("valid", LuaValue.valueOf(snapshot.valid));
                result.set("recording", LuaValue.valueOf(snapshot.recording));
                result.set("recovery_required", LuaValue.valueOf(snapshot.recoveryRequired));
                result.set("reanchor_allowed", LuaValue.valueOf(snapshot.reanchorAllowed));
                result.set("samples", LuaValue.valueOf(snapshot.samples));
                result.set("last_error_seconds", LuaValue.valueOf(snapshot.lastErrorSeconds));
                result.set("max_abs_error_seconds", LuaValue.valueOf(snapshot.maxAbsErrorSeconds));
                result.set("rms_error_seconds", LuaValue.valueOf(snapshot.rmsErrorSeconds));
                result.set("threshold_seconds", LuaValue.valueOf(snapshot.thresholdSeconds));
                return result;
            }
            case H_SC_SOFTCUT_RECORDER_REANCHOR:
                return LuaValue.valueOf(audio.reanchorNativeSoftcutRecorderCursor(
                        a.checkint(1), a.checkint(2), a.checkdouble(3)));
            case H_SC_SOFTCUT_READBACK:
                if (!superColliderStarted) return LuaValue.FALSE;
                audio.requestNativeSoftcutReadback(a.checkint(1), a.checkint(2), a.checkint(3));
                return LuaValue.TRUE;
            case H_SC_SOFTCUT_RECORD_START:
                if (!superColliderStarted) return LuaValue.FALSE;
                audio.prepareNativeSoftcutRecording(a.checkint(1), a.checkint(2),
                        a.checkdouble(3), a.checkdouble(4), a.checkdouble(5),
                        a.checkdouble(6), a.checkdouble(7), a.checkdouble(8),
                        a.checkdouble(9), a.checkdouble(10), a.checkdouble(11));
                return LuaValue.TRUE;
            case H_SC_SOFTCUT_READBACK_SECONDS:
                if (!superColliderStarted) return LuaValue.FALSE;
                audio.requestNativeSoftcutReadbackSeconds(a.checkint(1), a.checkdouble(2), a.checkdouble(3));
                return LuaValue.TRUE;
            case H_SC_SOFTCUT_RECORD_STOP:
                if (!superColliderStarted) return LuaValue.FALSE;
                audio.finishNativeSoftcutRecording(a.checkint(1), a.checkint(2),
                        a.checkdouble(3), a.checkdouble(4));
                return LuaValue.TRUE;
            case H_SC_SEND: {
                if (!superColliderStarted) return LuaValue.FALSE;
                String path = a.checkjstring(1);
                LuaValue values = a.arg(2);
                java.util.ArrayList<Object> args = new java.util.ArrayList<Object>();
                if (values.istable()) {
                    for (int i = 1; i <= values.length(); i++) {
                        LuaValue value = values.get(i);
                        if (value.isboolean()) args.add(Boolean.valueOf(value.toboolean()));
                        else if (value.isnumber()) args.add(Double.valueOf(value.todouble()));
                        else if (value.isstring()) args.add(value.tojstring());
                        else if (value.istable() && value.get("__norns_sc_int").isnumber()) {
                            double integer = value.get("__norns_sc_int").todouble();
                            if (integer != Math.rint(integer) || integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) return LuaValue.FALSE;
                            args.add(Integer.valueOf((int) integer));
                        } else if (value.istable() && value.get("__norns_sc_float_array").toboolean()) {
                            LuaValue samples = value.get("values");
                            if (!samples.istable() || samples.length() > 12000) return LuaValue.FALSE;
                            float[] floats = new float[samples.length()];
                            for (int j = 1; j <= samples.length(); j++) {
                                LuaValue sample = samples.get(j);
                                if (!sample.isnumber()) return LuaValue.FALSE;
                                double number = sample.todouble();
                                if (Double.isNaN(number) || Double.isInfinite(number)
                                        || Math.abs(number) > Float.MAX_VALUE) return LuaValue.FALSE;
                                floats[j - 1] = (float) number;
                            }
                            args.add(new SuperColliderOsc.FloatArray(floats));
                        } else return LuaValue.FALSE;
                    }
                }
                boolean sent = SuperColliderBridge.sendMessage(path, args.toArray());
                if (sent && "/b_free".equals(path) && args.size() >= 1
                        && args.get(0) instanceof Integer) {
                    SuperColliderSoftcutBuffers.noteNativeBufferFreed(((Integer) args.get(0)).intValue());
                } else if (sent && "/b_alloc".equals(path) && args.size() >= 2
                        && args.get(0) instanceof Integer && args.get(1) instanceof Integer) {
                    SuperColliderSoftcutBuffers.noteNativeBufferAllocated(
                            ((Integer) args.get(0)).intValue(), ((Integer) args.get(1)).intValue());
                }
                return LuaValue.valueOf(sent);
            }
            case H_SC_LOAD_SYNTHDEF: {
                if (!superColliderStarted) return LuaValue.FALSE;
                String requestedPath = a.checkjstring(1);
                File synthDef = dust.resolve(requestedPath);
                if (synthDef == null || !synthDef.isFile()) {
                    // SynthDefs empacotados são resolvidos somente pelo nome do arquivo;
                    // caminhos arbitrários nunca escapam do diretório privado de assets.
                    String bundledName = new File(requestedPath).getName();
                    if (bundledName.length() == 0 || !bundledName.equals(requestedPath)
                            || superColliderSynthDefDir == null) return LuaValue.FALSE;
                    synthDef = new File(superColliderSynthDefDir, bundledName);
                }
                if (!synthDef.isFile() || synthDef.length() <= 0 || synthDef.length() > 65480)
                    return LuaValue.FALSE;
                java.io.FileInputStream input = null;
                try {
                    input = new java.io.FileInputStream(synthDef);
                    byte[] data = new byte[(int) synthDef.length()];
                    int offset = 0;
                    while (offset < data.length) {
                        int count = input.read(data, offset, data.length - offset);
                        if (count < 0) return LuaValue.FALSE;
                        offset += count;
                    }
                    return LuaValue.valueOf(SuperColliderBridge.loadSynthDef(data));
                } catch (IOException error) {
                    AppLog.e("SuperCollider SynthDef: " + error.getMessage());
                    return LuaValue.FALSE;
                } finally {
                    Dust.closeQuietly(input);
                }
            }
            case H_SOFTCUT:
                audio.softcutCommand(a.checkint(1), a.checkjstring(2), a.optdouble(3, 0));
                return LuaValue.NONE;
            case H_SOFTCUT_BUFFER: {
                File wav = dust.resolve(a.checkjstring(1));
                if (wav == null || !wav.isFile()) { AppLog.e("Softcut: arquivo não encontrado."); return LuaValue.FALSE; }
                double sourceStart = a.optdouble(2, 0);
                double destinationStart = a.optdouble(3, 0);
                double duration = a.optdouble(4, -1);
                int bufferChannel = a.optint(6, 1);
                if (bufferChannel < 1 || bufferChannel > 2) {
                    AppLog.e("Softcut: canal de destino deve ser 1 ou 2.");
                    return LuaValue.FALSE;
                }
                try {
                    WavReader.Data data = WavReader.read(wav, a.optint(5, 1));
                    audio.softcutBuffer(bufferChannel, data.samples, data.sampleRate,
                            sourceStart, destinationStart, duration,
                            a.optdouble(7, 0), a.optdouble(8, 1));
                    return LuaValue.TRUE;
                } catch (IOException e) { AppLog.e("Softcut: " + e.getMessage()); return LuaValue.FALSE; }
            }
            case H_SOFTCUT_BUFFER_CLEAR:
                audio.softcutBufferClear(a.checkint(1), a.optdouble(2, 0), a.optdouble(3, -1),
                        a.optdouble(4, 0), a.optdouble(5, 0));
                return LuaValue.NONE;
            case H_SOFTCUT_BUFFER_COPY:
                audio.softcutBufferCopy(a.checkint(1), a.checkint(2), a.optdouble(3, 0),
                        a.optdouble(4, 0), a.optdouble(5, -1), a.optdouble(6, 0),
                        a.optdouble(7, 0), a.optint(8, 0) != 0);
                return LuaValue.NONE;
            case H_SOFTCUT_BUFFER_WRITE: {
                File target = dust.resolve(a.checkjstring(1));
                int channel = a.optint(4, 0);
                if (target == null || (channel != 0 && channel != 1 && channel != 2)) {
                    AppLog.e("Softcut: destino ou canal de exportação inválido.");
                    return LuaValue.FALSE;
                }
                audio.softcutBufferExport(target.getAbsolutePath(), channel,
                        a.optdouble(2, 0), a.optdouble(3, -1));
                return LuaValue.TRUE;
            }
            case H_SOFTCUT_POSITION:
                return LuaValue.valueOf(audio.softcutPosition(a.checkint(1)));
            case H_SOFTCUT_SYNC:
                audio.softcutVoiceSync(a.checkint(1), a.checkint(2), a.optdouble(3, 0));
                return LuaValue.NONE;
            case H_SOFTCUT_BUFFER_RENDER: {
                int channel = Math.max(1, Math.min(2, a.checkint(1)));
                double start = a.optdouble(2, 0);
                double duration = a.optdouble(3, -1);
                int maxSamples = Math.max(1, Math.min(65536, a.optint(4, 1024)));
                audio.requestSoftcutRender(channel, start, duration, maxSamples);
                return LuaValue.NONE;
            }
            case H_MONOME_DEVICES: {
                LuaTable devices = new LuaTable();
                int index = 1;
                for (MonomeSerialoscBridge.Device device : monome.devices()) {
                    LuaTable item = new LuaTable();
                    item.set("id", LuaValue.valueOf(device.id));
                    item.set("serial", LuaValue.valueOf(device.serial));
                    item.set("name", LuaValue.valueOf(device.name));
                    item.set("type", LuaValue.valueOf(device.arc ? "arc" : "grid"));
                    item.set("port", LuaValue.valueOf(device.port));
                    item.set("cols", LuaValue.valueOf(device.cols));
                    item.set("rows", LuaValue.valueOf(device.rows));
                    item.set("rings", LuaValue.valueOf(device.rings));
                    devices.set(index++, item);
                }
                return devices;
            }
            case H_MONOME_SEND:
                return LuaValue.valueOf(monome.send(a.checkint(1), a.checkjstring(2),
                        oscArgsFromLua(a.arg(3))));
            case H_MONOME_REFRESH:
                return LuaValue.valueOf(monome.refresh());
            case H_TAPE_PLAY_OPEN: {
                String tapePath = a.checkjstring(1);
                File source = dust.resolve(tapePath);
                if (source == null || !source.isFile()) {
                    AppLog.e("Tape: arquivo de reprodução não encontrado.");
                    return LuaValue.FALSE;
                }
                try {
                    boolean opened = audio.tapePlayOpen(WavReader.mapStereo(source), tapePath);
                    if (opened) callNorns("tape_play_file", LuaValue.valueOf(tapePath));
                    return LuaValue.valueOf(opened);
                } catch (IOException error) {
                    AppLog.e("Tape: " + error.getMessage());
                    return LuaValue.FALSE;
                }
            }
            case H_TAPE_PLAY_START:
                audio.tapePlayStart();
                return LuaValue.TRUE;
            case H_TAPE_PLAY_PAUSE:
                audio.tapePlayPause(a.arg(1).isboolean() ? a.arg(1).toboolean() : a.arg(1).toint() != 0);
                return LuaValue.TRUE;
            case H_TAPE_PLAY_STOP:
                audio.tapePlayStop();
                return LuaValue.TRUE;
            case H_TAPE_PLAY_LOOP:
                audio.tapePlayLoop(a.arg(1).isboolean() ? a.arg(1).toboolean() : a.arg(1).toint() != 0);
                return LuaValue.TRUE;
            case H_TAPE_RECORD_OPEN: {
                String tapePath = a.checkjstring(1);
                File target = dust.resolve(tapePath);
                boolean opened = target != null && audio.tapeRecordOpen(target);
                if (opened) callNorns("tape_rec_file", LuaValue.valueOf(tapePath));
                return LuaValue.valueOf(opened);
            }
            case H_TAPE_RECORD_START:
                return LuaValue.valueOf(audio.tapeRecordStart());
            case H_TAPE_RECORD_PAUSE:
                audio.tapeRecordPause(a.arg(1).isboolean() ? a.arg(1).toboolean() : a.arg(1).toint() != 0);
                return LuaValue.TRUE;
            case H_TAPE_RECORD_STOP:
                audio.tapeRecordStop();
                return LuaValue.TRUE;
            case H_TAPE_LEVEL:
                audio.tapeLevel(a.optdouble(1, 1));
                return LuaValue.TRUE;
            case H_TAPE_LEVEL_CUT:
                return LuaValue.valueOf(audio.setTapeCutLevel(a.optdouble(1, 0)));
            case H_AUDIO_LEVEL_DAC:
                return LuaValue.valueOf(audio.setMasterOutputLevel(a.optdouble(1, 1)));
            case H_AUDIO_LEVEL_ENG:
                if (isNativeAudioEngineActive()) return LuaValue.FALSE;
                return LuaValue.valueOf(audio.setEngineLevel(a.optdouble(1, 1)));
            case H_AUDIO_LEVEL_CUT:
                return LuaValue.valueOf(audio.setSoftcutMasterLevel(a.optdouble(1, 1)));
            case H_AUDIO_PITCH_ON:
                audio.setPitchAnalysisEnabled(true);
                return LuaValue.TRUE;
            case H_AUDIO_PITCH_OFF:
                audio.setPitchAnalysisEnabled(false);
                return LuaValue.TRUE;
            case H_AUDIO_LEVEL_MONITOR:
                return LuaValue.valueOf(audio.setMonitorLevel(a.optdouble(1, 1)));
            case H_AUDIO_LEVEL_ADC:
                return LuaValue.valueOf(audio.setInputAdcLevel(a.optdouble(1, 1)));
            case H_AUDIO_MONITOR_MODE:
                return LuaValue.valueOf(audio.setMonitorStereo(
                        a.arg(1).isboolean() ? a.arg(1).toboolean() : a.arg(1).toint() != 0));
            case H_TAPE_STATE:
                return tapeStateValue(audio.tapeState());
            case H_DISK_FREE_BYTES:
                return LuaValue.valueOf(Math.max(0L, dust.root.getUsableSpace()));
            case H_AUDIO_FX: {
                if (isNativeAudioEngineActive()) return LuaValue.FALSE;
                String action = a.arg(1).optjstring(null);
                String effectName = a.arg(2).optjstring(null);
                int effect = "compressor".equals(effectName) ? NornsFaustEffects.COMPRESSOR
                        : ("reverb".equals(effectName) ? NornsFaustEffects.REVERB : -1);
                if (effect < 0 || action == null) return LuaValue.FALSE;
                if ("enabled".equals(action)) {
                    LuaValue enabled = a.arg(3);
                    return LuaValue.valueOf(audio.setFxEnabled(effect,
                            enabled.isboolean() ? enabled.toboolean() : enabled.toint() != 0));
                }
                if ("parameter".equals(action)) {
                    String parameter = a.arg(3).optjstring(null);
                    return LuaValue.valueOf(parameter != null
                            && audio.setFxParameter(effect, parameter, a.optdouble(4, Double.NaN)));
                }
                if ("mix".equals(action))
                    return LuaValue.valueOf(audio.setFxMix(effect, a.optdouble(3, Double.NaN)));
                if ("send".equals(action) && effect == NornsFaustEffects.REVERB) {
                    String source = a.arg(3).optjstring(null);
                    return LuaValue.valueOf(source != null
                            && audio.setReverbSendLevel(source, a.optdouble(4, Double.NaN)));
                }
                return LuaValue.FALSE;
            }
            default:
                return LuaValue.NONE;
        }
    }

    private boolean isNativeAudioEngineActive() {
        Globals current = globals;
        if (current == null) return false;
        LuaValue state = current.get("engine").get("_native_state");
        return state.isstring() && ("native".equals(state.tojstring()) || "loading".equals(state.tojstring()));
    }

    private LuaTable tapeStateValue(TapeCore.State state) {
        LuaTable value = new LuaTable();
        value.set("play_state", LuaValue.valueOf(state.playState));
        value.set("play_pos_s", LuaValue.valueOf(state.playPositionSeconds));
        value.set("play_len_s", LuaValue.valueOf(state.playLengthSeconds));
        value.set("rec_state", LuaValue.valueOf(state.recordState));
        value.set("rec_pos_s", LuaValue.valueOf(state.recordPositionSeconds));
        value.set("loop_enabled", LuaValue.valueOf(state.loopEnabled));
        value.set("play_file", state.playFile == null ? LuaValue.NIL : LuaValue.valueOf(state.playFile));
        value.set("rec_file", state.recordFile == null ? LuaValue.NIL : LuaValue.valueOf(state.recordFile));
        return value;
    }

    private void dispatchAudioVu(int inputLeft, int inputRight, int outputLeft, int outputRight) {
        Globals g = globals;
        if (g == null) return;
        // The native Oboe path does not yet expose the four-source VU snapshot.
        // Do not feed Java fallback meters to a script while the native engine owns audio.
        LuaValue engineState = g.get("engine").get("_native_state");
        if (engineState.isstring() && "native".equals(engineState.tojstring())) return;
        LuaValue audioTable = g.get("audio");
        LuaValue callback = audioTable.get("vu");
        if (!callback.isfunction()) return;
        try {
            callback.invoke(LuaValue.varargsOf(new LuaValue[] {
                    LuaValue.valueOf(inputLeft), LuaValue.valueOf(inputRight),
                    LuaValue.valueOf(outputLeft), LuaValue.valueOf(outputRight)
            }));
        } catch (Throwable error) {
            AppLog.e("Audio VU callback: " + error.getMessage());
        }
    }

    private void dispatchTapeState(TapeCore.State state) {
        Globals g = globals;
        if (g == null) return;
        LuaValue audioTable = g.get("audio");
        LuaValue tapeTable = audioTable.istable() ? audioTable.get("tape") : LuaValue.NIL;
        LuaValue update = tapeTable.istable() ? tapeTable.get("_update_state") : LuaValue.NIL;
        LuaTable snapshot = tapeStateValue(state);
        if (update.isfunction()) {
            try { update.invoke(snapshot); }
            catch (Throwable t) { AppLog.e("Tape state callback: " + t.getMessage()); }
        }
        callNorns("tape_status", LuaValue.valueOf(state.playState),
                LuaValue.valueOf(state.playPositionSeconds), LuaValue.valueOf(state.playLengthSeconds),
                LuaValue.valueOf(state.recordState), LuaValue.valueOf(state.recordPositionSeconds),
                LuaValue.valueOf(state.loopEnabled));
    }

    private void dispatchSoftcutRender(int channel, double start, double secPerSample, float[] samples) {
        Globals g = globals;
        if (g == null) return;
        LuaValue softcutTable = g.get("softcut");
        LuaValue callback = softcutTable.get("_event_render");
        if (!callback.isfunction()) return;
        LuaTable data = new LuaTable();
        for (int i = 0; i < samples.length; i++) data.set(i + 1, LuaValue.valueOf(samples[i]));
        try {
            callback.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(channel),
                    LuaValue.valueOf(start), LuaValue.valueOf(secPerSample), data}));
        } catch (Throwable t) {
            AppLog.e("Softcut render callback: " + t.getMessage());
        }
    }

    private void dispatchOsc(String path, Object[] values, String from, int fromPort) {
        Globals g = globals; if (g == null) return;
        LuaValue oscTable = g.get("osc");
        LuaValue nornsTable = g.get("_norns");
        LuaValue event = nornsTable.istable() ? nornsTable.get("osc_event") : LuaValue.NIL;
        if (!event.isfunction()) event = oscTable.get("event");
        if (!event.isfunction()) return;
        LuaTable args = new LuaTable();
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            LuaValue luaValue;
            if (value == null) luaValue = LuaValue.NIL;
            else if (value instanceof Boolean) luaValue = LuaValue.valueOf(((Boolean) value).booleanValue());
            else if (value instanceof Number) luaValue = LuaValue.valueOf(((Number) value).doubleValue());
            else if (value instanceof byte[]) {
                LuaTable blob = new LuaTable();
                byte[] bytes = (byte[]) value;
                for (int n = 0; n < bytes.length; n++) blob.set(n + 1, LuaValue.valueOf(bytes[n] & 255));
                luaValue = blob;
            } else if (value instanceof Character) luaValue = LuaValue.valueOf(String.valueOf(value));
            else luaValue = LuaValue.valueOf(String.valueOf(value));
            args.set(i + 1, luaValue);
        }
        LuaTable source = new LuaTable();
        source.set(1, LuaValue.valueOf(from));
        source.set(2, LuaValue.valueOf(fromPort));
        try {
            event.invoke(LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(path), args, source}));
        } catch (Throwable t) {
            AppLog.e("OSC callback: " + t.getMessage());
        }
    }

    private void dispatchMonomeDevice(MonomeSerialoscBridge.Device device, boolean added) {
        if (device == null) return;
        String callback = device.arc
                ? (added ? "arc_device_add" : "arc_device_remove")
                : (added ? "grid_device_add" : "grid_device_remove");
        callNorns(callback, LuaValue.valueOf(device.id), LuaValue.valueOf(device.serial),
                LuaValue.valueOf(device.name), LuaValue.valueOf(device.cols),
                LuaValue.valueOf(device.rows), LuaValue.valueOf(device.rings));
    }

    private void dispatchMonomeDeviceInfo(MonomeSerialoscBridge.Device device) {
        if (device == null) return;
        String callback = device.arc ? "arc_device_info" : "grid_device_info";
        callNorns(callback, LuaValue.valueOf(device.id), LuaValue.valueOf(device.cols),
                LuaValue.valueOf(device.rows), LuaValue.valueOf(device.rings));
    }

    private void dispatchMidiSystemExclusive(int deviceId, byte[] data) {
        Globals g = globals;
        if (g == null || data == null) return;
        LuaValue midiTable = g.get("midi");
        LuaValue ports = midiTable.get("_ports");
        LuaValue port = ports.istable() ? ports.get(deviceId) : LuaValue.NIL;
        LuaValue devices = midiTable.get("devices");
        LuaValue device = devices.istable() ? devices.get(deviceId) : LuaValue.NIL;
        LuaValue deviceEvent = device.istable() ? device.get("event") : LuaValue.NIL;
        LuaValue vportEvent = LuaValue.NIL;
        LuaValue vports = midiTable.get("vports");
        if (vports.istable()) {
            for (int i = 1; i <= 16; i++) {
                LuaValue slot = vports.get(i);
                if (slot.istable() && slot.get("id").isnumber()
                        && slot.get("id").toint() == deviceId) {
                    vportEvent = slot.get("event");
                    break;
                }
            }
        }
        LuaValue portEvent = port.istable() ? port.get("event") : LuaValue.NIL;
        LuaValue globalEvent = midiTable.get("event");
        if (!deviceEvent.isfunction() && !vportEvent.isfunction()
                && !portEvent.isfunction() && !globalEvent.isfunction()) return;

        LuaTable bytes = new LuaTable();
        for (int i = 0; i < data.length; i++) bytes.set(i + 1, LuaValue.valueOf(data[i] & 0xff));
        if (deviceEvent.isfunction()) {
            try { deviceEvent.call(bytes); }
            catch (Throwable error) { AppLog.e("MIDI device SysEx callback: " + error.getMessage()); }
        }
        if (vportEvent.isfunction()) {
            try { vportEvent.call(bytes); }
            catch (Throwable error) { AppLog.e("MIDI vport SysEx callback: " + error.getMessage()); }
        } else if (portEvent.isfunction()) {
            try { portEvent.call(bytes); }
            catch (Throwable error) { AppLog.e("MIDI port SysEx callback: " + error.getMessage()); }
        }
        if (!deviceEvent.isfunction() && !vportEvent.isfunction()
                && !portEvent.isfunction() && globalEvent.isfunction()) {
            try { globalEvent.call(bytes); }
            catch (Throwable error) { AppLog.e("MIDI global SysEx callback: " + error.getMessage()); }
        }
    }

    private void dispatchMidiDeviceChanged(MidiBridge.Device device, boolean added) {
        Globals g = globals;
        if (g == null || device == null) return;
        LuaValue midiTable = g.get("midi");
        LuaValue devices = midiTable.get("devices");
        LuaValue previousDevice = devices.istable() ? devices.get(device.id) : LuaValue.NIL;

        LuaValue refresh = midiTable.get("refresh");
        if (refresh.isfunction()) {
            try { refresh.call(); } catch (Throwable error) {
                AppLog.e("MIDI refresh callback: " + error.getMessage());
            }
        }

        LuaTable fallback = new LuaTable();
        fallback.set("id", LuaValue.valueOf(device.id));
        fallback.set("name", LuaValue.valueOf(device.name));
        fallback.set("inputs", LuaValue.valueOf(device.inputs));
        fallback.set("outputs", LuaValue.valueOf(device.outputs));
        String type = device.inputs > 0 && device.outputs > 0 ? "duplex"
                : device.outputs > 0 ? "input" : "output";
        fallback.set("type", LuaValue.valueOf(type));

        LuaValue currentDevices = midiTable.get("devices");
        LuaValue deviceObject = added
                ? (currentDevices.istable() ? currentDevices.get(device.id) : LuaValue.NIL)
                : previousDevice;
        if (!deviceObject.istable()) deviceObject = fallback;

        if (!added) {
            LuaValue ports = midiTable.get("_ports");
            if (ports.istable()) {
                LuaValue port = ports.get(device.id);
                if (port.istable()) {
                    port.set("connected", LuaValue.FALSE);
                    port.set("connecting", LuaValue.FALSE);
                    ports.set(device.id, LuaValue.NIL);
                }
            }
        }

        LuaValue updateClockReceive = midiTable.get("update_clock_receive");
        if (updateClockReceive.isfunction()) {
            try { updateClockReceive.call(); } catch (Throwable error) {
                AppLog.e("MIDI clock input refresh: " + error.getMessage());
            }
        }

        LuaValue callback = midiTable.get(added ? "add" : "remove");
        if (callback.isfunction()) {
            try { callback.call(deviceObject); }
            catch (Throwable error) {
                AppLog.e("MIDI " + (added ? "add" : "remove") + " callback: " + error.getMessage());
            }
        }
        if (!added) {
            LuaValue remove = deviceObject.get("remove");
            if (remove.isfunction()) {
                try { remove.call(); }
                catch (Throwable error) { AppLog.e("MIDI device remove callback: " + error.getMessage()); }
            }
        }
    }

    private void closeUsbSerialPort(int deviceId) {
        LuaDeviceCallbackGate.Session session = usbCallbackGate.beginClose(deviceId);
        if (session != null) usbCallbackGate.finishClose(deviceId, session.token);
        usbSerial.close(deviceId);
    }

    private void dispatchSerialConnected(int deviceId, String name) {
        callNorns("serial_connected", LuaValue.valueOf(deviceId), LuaValue.valueOf(name));
    }

    private void dispatchSerialData(int deviceId, byte[] bytes) {
        callNorns("serial_data", LuaValue.valueOf(deviceId), LuaValue.valueOf(bytes));
    }

    private void dispatchSerialDisconnected(int deviceId, String reason) {
        callNorns("serial_disconnected", LuaValue.valueOf(deviceId), LuaValue.valueOf(reason));
    }

    private void dispatchMidiConnection(int deviceId, boolean connected, String reason) {
        Globals g = globals;
        if (g == null) return;
        LuaValue midiTable = g.get("midi");
        LuaValue ports = midiTable.get("_ports");
        LuaValue port = ports.istable() ? ports.get(deviceId) : LuaValue.NIL;
        if (!port.istable()) return;
        port.set("connecting", LuaValue.FALSE);
        port.set("connected", LuaValue.valueOf(connected));
        if (reason != null && !reason.isEmpty()) port.set("connection_error", reason);
        else port.set("connection_error", LuaValue.NIL);
        LuaValue devices = midiTable.get("devices");
        LuaValue device = devices.istable() ? devices.get(deviceId) : LuaValue.NIL;
        if (device.istable()) {
            device.set("connected", LuaValue.valueOf(connected));
            device.set("connecting", LuaValue.FALSE);
            if (reason != null && !reason.isEmpty()) device.set("connection_error", reason);
            else device.set("connection_error", LuaValue.NIL);
        }
        LuaValue vports = midiTable.get("vports");
        if (vports.istable()) {
            for (int i = 1; i <= 16; i++) {
                LuaValue slot = vports.get(i);
                if (slot.istable() && slot.get("id").isnumber()
                        && slot.get("id").toint() == deviceId) {
                    slot.set("connecting", LuaValue.FALSE);
                    slot.set("connected", LuaValue.valueOf(connected));
                    if (reason != null && !reason.isEmpty()) slot.set("connection_error", reason);
                    else slot.set("connection_error", LuaValue.NIL);
                    break;
                }
            }
        }

        // The port is only clock-eligible after Android confirms the async open.
        // Recompute on both success and failure so a pending input cannot remain stale.
        LuaValue updateClockReceive = midiTable.get("update_clock_receive");
        if (updateClockReceive.isfunction()) {
            try { updateClockReceive.call(); } catch (Throwable error) {
                AppLog.e("MIDI clock connection refresh: " + error.getMessage());
            }
        }
    }

    private void dispatchMidi(int deviceId, int status, int data1, int data2) {
        Globals g = globals;
        if (g == null) return;
        LuaValue midiTable = g.get("midi");
        LuaValue ports = midiTable.get("_ports");
        LuaValue port = ports.istable() ? ports.get(deviceId) : LuaValue.NIL;
        boolean realtimeClock = status == 0xf8 || status == 0xfa
                || status == 0xfb || status == 0xfc;
        boolean clockReceiveEnabled = !port.istable()
                || !port.get("_clock_receive_enabled").isboolean()
                || port.get("_clock_receive_enabled").toboolean();
        LuaValue clockTable = g.get("clock");
        LuaValue clockEvent = clockTable.get("_midi_event");
        // clock_receive controls the shared clock, not the script's raw MIDI event stream.
        if (clockReceiveEnabled && clockEvent.isfunction() && realtimeClock) {
            try { clockEvent.call(LuaValue.valueOf(status)); }
            catch (Throwable error) { AppLog.e("MIDI clock callback: " + error.getMessage()); }
        }

        LuaValue devices = midiTable.get("devices");
        LuaValue device = devices.istable() ? devices.get(deviceId) : LuaValue.NIL;
        LuaValue deviceEvent = device.istable() ? device.get("event") : LuaValue.NIL;
        LuaValue vportEvent = LuaValue.NIL;
        LuaValue vports = midiTable.get("vports");
        if (vports.istable()) {
            for (int i = 1; i <= 16; i++) {
                LuaValue slot = vports.get(i);
                if (slot.istable() && slot.get("id").isnumber()
                        && slot.get("id").toint() == deviceId) {
                    vportEvent = slot.get("event");
                    break;
                }
            }
        }
        LuaValue portEvent = port.istable() ? port.get("event") : LuaValue.NIL;
        LuaValue globalEvent = midiTable.get("event");
        if (!deviceEvent.isfunction() && !vportEvent.isfunction()
                && !portEvent.isfunction() && !globalEvent.isfunction()) return;

        // norns scripts expect the original MIDI byte array here;
        // midi.to_msg(data) performs the semantic decoding in Lua.
        LuaTable data = new LuaTable();
        data.set(1, LuaValue.valueOf(status));
        int kind = status & 0xF0;
        if (status < 0xF0) {
            data.set(2, LuaValue.valueOf(data1));
            if (kind != 0xC0 && kind != 0xD0)
                data.set(3, LuaValue.valueOf(data2));
        } else if (status == 0xf1 || status == 0xf3) {
            data.set(2, LuaValue.valueOf(data1));
        } else if (status == 0xf2) {
            data.set(2, LuaValue.valueOf(data1));
            data.set(3, LuaValue.valueOf(data2));
        }
        if (deviceEvent.isfunction()) {
            try { deviceEvent.call(data); }
            catch (Throwable t) { AppLog.e("MIDI device callback: " + t.getMessage()); }
        }
        if (vportEvent.isfunction()) {
            try { vportEvent.call(data); }
            catch (Throwable t) { AppLog.e("MIDI vport callback: " + t.getMessage()); }
        } else if (portEvent.isfunction()) {
            try { portEvent.call(data); }
            catch (Throwable t) { AppLog.e("MIDI port callback: " + t.getMessage()); }
        }
        if (!deviceEvent.isfunction() && !vportEvent.isfunction()
                && !portEvent.isfunction() && globalEvent.isfunction()) {
            try { globalEvent.call(data); }
            catch (Throwable t) { AppLog.e("MIDI global callback: " + t.getMessage()); }
        }
    }

    private ScreenSurface.Image screenImage(LuaValue value) {
        if (value == null || !value.isuserdata()) return null;
        Object raw = value.touserdata();
        return raw instanceof ScreenSurface.Image ? (ScreenSurface.Image) raw : null;
    }

    private File resolveScreenFile(String requested) {
        File source = dust.resolve(requested);
        if (source != null && source.isFile()) return source;
        String folder = scriptName == null || scriptName.trim().isEmpty()
                ? "" : Dust.sanitizeName(scriptName);
        String relative = "data/" + (folder.isEmpty() ? "" : folder + "/") + requested;
        source = dust.resolve(relative);
        return source != null && source.isFile() ? source : null;
    }

    private Varargs screenOp(int op, Varargs a) {
        switch (op) {
            case 0:
                screen.clear();
                return LuaValue.NONE;
            case 1:
                screen.update();
                return LuaValue.NONE;
            case 2:
                screen.setLevel((int) Math.round(a.checkdouble(1)));
                return LuaValue.NONE;
            case 3:
                screen.setAa(flag(a, 1));
                return LuaValue.NONE;
            case 4:
                screen.setLineWidth(a.checkdouble(1));
                return LuaValue.NONE;
            case 5:
                screen.setLineCap(a.optjstring(1, "butt"));
                return LuaValue.NONE;
            case 6:
                screen.setLineJoin(a.optjstring(1, "miter"));
                return LuaValue.NONE;
            case 7:
                screen.setMiterLimit(a.checkdouble(1));
                return LuaValue.NONE;
            case 8:
                screen.move(a.checkdouble(1), a.checkdouble(2));
                return LuaValue.NONE;
            case 9:
                screen.line(a.checkdouble(1), a.checkdouble(2));
                return LuaValue.NONE;
            case 10:
                screen.moveRel(a.checkdouble(1), a.checkdouble(2));
                return LuaValue.NONE;
            case 11:
                screen.lineRel(a.checkdouble(1), a.checkdouble(2));
                return LuaValue.NONE;
            case 12:
                screen.curve(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3),
                        a.checkdouble(4), a.checkdouble(5), a.checkdouble(6));
                return LuaValue.NONE;
            case 13:
                screen.curveRel(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3),
                        a.checkdouble(4), a.checkdouble(5), a.checkdouble(6));
                return LuaValue.NONE;
            case 14:
                screen.arc(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3),
                        a.checkdouble(4), a.checkdouble(5));
                return LuaValue.NONE;
            case 15:
                screen.rect(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3), a.checkdouble(4));
                return LuaValue.NONE;
            case 16:
                screen.circle(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3));
                return LuaValue.NONE;
            case 17:
                screen.pixel(a.checkdouble(1), a.checkdouble(2));
                return LuaValue.NONE;
            case 18:
                screen.close();
                return LuaValue.NONE;
            case 19:
                screen.stroke();
                return LuaValue.NONE;
            case 20:
                screen.fill();
                return LuaValue.NONE;
            case 21:
                screen.text(a.checkjstring(1), 0);
                return LuaValue.NONE;
            case 22:
                screen.text(a.checkjstring(1), 1);
                return LuaValue.NONE;
            case 23:
                screen.text(a.checkjstring(1), 2);
                return LuaValue.NONE;
            case 24:
                return LuaValue.valueOf(screen.textExtents(a.checkjstring(1)));
            case 25:
                screen.setFontFace((int) Math.round(a.checkdouble(1)));
                return LuaValue.NONE;
            case 26:
                screen.setFontSize(a.checkdouble(1));
                return LuaValue.NONE;
            case 27:
                screen.save();
                return LuaValue.NONE;
            case 28:
                screen.restore();
                return LuaValue.NONE;
            case 29:
                screen.translate(a.checkdouble(1), a.checkdouble(2));
                return LuaValue.NONE;
            case 30:
                screen.rotate(a.checkdouble(1));
                return LuaValue.NONE;
            case 31:
                return LuaValue.varargsOf(LuaValue.valueOf(screen.currentX()), LuaValue.valueOf(screen.currentY()));
            case 32: {
                LuaValue mode = a.arg(1);
                String name;
                if (mode.isnumber()) {
                    switch (mode.toint()) {
                        case 0: name = "over"; break;
                        case 1: name = "xor"; break;
                        case 2: name = "add"; break;
                        case 3: name = "multiply"; break;
                        case 4: name = "screen"; break;
                        case 5: name = "overlay"; break;
                        case 6: name = "darken"; break;
                        case 7: name = "lighten"; break;
                        case 8: name = "color_dodge"; break;
                        case 9: name = "color_burn"; break;
                        case 10: name = "hard_light"; break;
                        case 11: name = "soft_light"; break;
                        case 12: name = "difference"; break;
                        case 13: name = "exclusion"; break;
                        default: return LuaValue.FALSE;
                    }
                } else {
                    name = mode.isnil() ? "over" : mode.tojstring();
                }
                return LuaValue.valueOf(screen.setBlendMode(name));
            }
            case 33: {
                String rawName = a.checkjstring(1);
                String fileName = Dust.sanitizeName(rawName);
                if (!fileName.toLowerCase(java.util.Locale.US).endsWith(".png")) fileName += ".png";
                String scriptFolder = scriptName == null || scriptName.trim().isEmpty() ? "" : Dust.sanitizeName(scriptName);
                String relative = "data/" + (scriptFolder.isEmpty() ? "" : scriptFolder + "/") + fileName;
                File target = dust.resolve(relative);
                return LuaValue.valueOf(target != null && screen.exportScreenshot(target));
            }
            case 34: {
                String requested = a.checkjstring(1);
                File source = dust.resolve(requested);
                if (source == null || !source.isFile()) {
                    String scriptFolder = Dust.sanitizeName(scriptName);
                    String relative = "data/" + (scriptFolder.isEmpty() ? "" : scriptFolder + "/") + requested;
                    source = dust.resolve(relative);
                }
                if (source == null || !source.isFile()) return LuaValue.FALSE;
                return LuaValue.valueOf(screen.displayPng(source, a.optdouble(2, 0), a.optdouble(3, 0)));
            }
            case 36: {
                int x = (int) Math.floor(a.checkdouble(1));
                int y = (int) Math.floor(a.checkdouble(2));
                int width = Math.max(1, Math.min(ScreenSurface.W, a.optint(3, 1)));
                int height = Math.max(1, Math.min(ScreenSurface.H, a.optint(4, 1)));
                return LuaValue.valueOf(screen.peekRegion(x, y, width, height));
            }
            case 37: {
                int x = (int) Math.floor(a.checkdouble(1));
                int y = (int) Math.floor(a.checkdouble(2));
                int width = a.checkint(3);
                int height = a.checkint(4);
                return LuaValue.valueOf(screen.pokeRegion(x, y, width, height, a.checkjstring(5)));
            }
            case 35:
                screen.update();
                return LuaValue.NONE;
            case 38: {
                File source = resolveScreenFile(a.checkjstring(1));
                ScreenSurface.Image image = screen.loadPng(source);
                return image == null ? LuaValue.NIL : LuaValue.userdataOf(image);
            }
            case 39: {
                ScreenSurface.Image image = screen.createImage(a.checkint(1), a.checkint(2));
                return image == null ? LuaValue.NIL : LuaValue.userdataOf(image);
            }
            case 40: {
                ScreenSurface.Image image = screenImage(a.arg(1));
                return LuaValue.valueOf(screen.displayImage(image, a.optdouble(2, 0), a.optdouble(3, 0)));
            }
            case 41: {
                ScreenSurface.Image image = screenImage(a.arg(1));
                return LuaValue.valueOf(screen.displayImageRegion(image,
                        a.checkint(2), a.checkint(3), a.checkint(4), a.checkint(5),
                        a.optdouble(6, 0), a.optdouble(7, 0)));
            }
            case 42: {
                ScreenSurface.Image image = screenImage(a.arg(1));
                LuaValue callback = a.arg(2);
                if (image == null || !callback.isfunction() || !screen.beginDrawTo(image)) return LuaValue.FALSE;
                try {
                    callback.call();
                    return LuaValue.TRUE;
                } finally {
                    screen.endDrawTo();
                }
            }
            case 43:
                return LuaValue.valueOf(screen.freeImage(screenImage(a.arg(1))));
            case 44:
                screen.textTrim(a.checkjstring(1), a.checkdouble(2));
                return LuaValue.NONE;
            case 45:
                screen.textRotate(a.checkdouble(1), a.checkdouble(2),
                        a.checkjstring(3), a.checkdouble(4));
                return LuaValue.NONE;
            case 46:
                screen.textCenterRotate(a.checkdouble(1), a.checkdouble(2),
                        a.checkjstring(3), a.checkdouble(4));
                return LuaValue.NONE;
            default:
                return LuaValue.NONE; // export_screenshot e display_png
        }
    }
}
