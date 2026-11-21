package org.nornsmobile.app;

import java.nio.ByteBuffer;

/** JNI facade for the exact Faust-generated Norns crone effects. */
final class NornsFaustEffects {
    static final int COMPRESSOR = 0;
    static final int REVERB = 1;
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("norns_sc_bridge");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
            AppLog.e("Faust DSP indisponível: " + error.getMessage());
        }
        AVAILABLE = loaded;
    }

    private NornsFaustEffects() {}

    static long create(int sampleRate, int maxFrames) {
        if (!AVAILABLE) return 0L;
        try { return nativeCreate(sampleRate, maxFrames); }
        catch (UnsatisfiedLinkError error) { return 0L; }
    }

    static void destroy(long handle) {
        if (AVAILABLE && handle != 0L) nativeDestroy(handle);
    }

    static boolean setEnabled(long handle, int effect, boolean enabled) {
        return AVAILABLE && handle != 0L && nativeSetEnabled(handle, effect, enabled);
    }

    static int parameterId(int effect, String name) {
        if (name == null) return -1;
        if (effect == COMPRESSOR) {
            switch (name) {
                case "ratio": return 0;
                case "threshold": return 1;
                case "attack": return 2;
                case "release": return 3;
                case "gain_pre": return 4;
                case "gain_post": return 5;
                default: return -1;
            }
        }
        if (effect == REVERB) {
            switch (name) {
                case "pre_del": return 0;
                case "lf_fc": return 1;
                case "low_rt60": return 2;
                case "mid_rt60": return 3;
                case "hf_damp": return 4;
                default: return -1;
            }
        }
        return -1;
    }

    static boolean setParameter(long handle, int effect, int parameter, double value) {
        return AVAILABLE && handle != 0L && parameter >= 0 && Double.isFinite(value)
                && nativeSetParameter(handle, effect, parameter, (float)value);
    }

    static boolean setMix(long handle, int effect, double value) {
        return AVAILABLE && handle != 0L && Double.isFinite(value)
                && nativeSetMix(handle, effect, (float)value);
    }

    static int reverbSendId(String source) {
        if ("monitor".equals(source)) return 0;
        if ("engine".equals(source)) return 1;
        if ("tape".equals(source)) return 2;
        if ("softcut".equals(source)) return 3;
        return -1;
    }

    static boolean setReverbSendLevel(long handle, int source, double value) {
        return AVAILABLE && handle != 0L && source >= 0 && source < 4 && Double.isFinite(value)
                && nativeSetReverbSendLevel(handle, source, (float)value);
    }

    static boolean process(long handle, ByteBuffer master, ByteBuffer monitor,
                           ByteBuffer engine, ByteBuffer tape, ByteBuffer softcut, int frames) {
        return AVAILABLE && handle != 0L && master != null
                && nativeProcess(handle, master, monitor, engine, tape, softcut, frames);
    }

    private static native long nativeCreate(int sampleRate, int maxFrames);
    private static native void nativeDestroy(long handle);
    private static native boolean nativeSetEnabled(long handle, int effect, boolean enabled);
    private static native boolean nativeSetParameter(long handle, int effect, int parameter, float value);
    private static native boolean nativeSetMix(long handle, int effect, float value);
    private static native boolean nativeSetReverbSendLevel(long handle, int source, float value);
    private static native boolean nativeProcess(long handle, ByteBuffer master, ByteBuffer monitor,
                                                ByteBuffer engine, ByteBuffer tape, ByteBuffer softcut,
                                                int frames);
}
