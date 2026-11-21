package org.nornsmobile.app;

/** Serializes Softcut output ownership without overlapping Java and Oboe playback. */
final class SoftcutOutputOwnership {
    interface Gate { void setJavaEnabled(boolean enabled); boolean awaitJavaDrain(); boolean setNativeEnabled(boolean enabled); }
    private final Gate gate;
    private volatile boolean nativeOwner;
    private volatile boolean nativeMayBeEnabled;
    private volatile boolean javaEnabled;

    SoftcutOutputOwnership(Gate gate) {
        if (gate == null) throw new IllegalArgumentException("gate");
        this.gate = gate;
        // Keep Java silent until the native output mute is positively confirmed.
        gate.setJavaEnabled(false);
        javaEnabled = false;
        nativeMayBeEnabled = !gate.setNativeEnabled(false);
        if (!nativeMayBeEnabled) {
            gate.setJavaEnabled(true);
            javaEnabled = true;
        }
    }

    synchronized boolean handoffToNative(boolean nativeReady, boolean routeValidated) {
        if (!nativeReady || !routeValidated) return false;
        if (nativeOwner) return true;
        if (nativeMayBeEnabled) return false;
        gate.setJavaEnabled(false);
        javaEnabled = false;
        if (!gate.awaitJavaDrain()) {
            gate.setJavaEnabled(true);
            javaEnabled = true;
            return false;
        }
        if (gate.setNativeEnabled(true)) {
            nativeMayBeEnabled = true;
            nativeOwner = true;
            return true;
        }

        // A failed enable can be ambiguous at the JNI boundary. Confirm mute
        // before restoring Java, otherwise keep both paths silent.
        nativeMayBeEnabled = true;
        if (gate.setNativeEnabled(false)) {
            nativeMayBeEnabled = false;
            gate.setJavaEnabled(true);
            javaEnabled = true;
        }
        return false;
    }

    synchronized boolean handoffToJava() {
        if (nativeMayBeEnabled && !gate.setNativeEnabled(false)) return false;
        nativeMayBeEnabled = false;
        nativeOwner = false;
        gate.setJavaEnabled(true);
        javaEnabled = true;
        return true;
    }

    boolean isNativeOwner() { return nativeOwner; }
    boolean isJavaEnabled() { return javaEnabled; }
}
