package org.nornsmobile.app;

/** JNI boundary for the embedded SuperCollider server. */
final class SuperColliderBridge {
    private static final java.util.concurrent.ConcurrentLinkedQueue<SuperColliderOsc.Reply> REPLY_BACKLOG =
            new java.util.concurrent.ConcurrentLinkedQueue<SuperColliderOsc.Reply>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.CountDownLatch> SYNC_WAITERS =
            new java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.CountDownLatch>();
    private static final java.util.concurrent.ConcurrentHashMap<ReadbackKey, ReadbackWaiter> READBACK_WAITERS =
            new java.util.concurrent.ConcurrentHashMap<ReadbackKey, ReadbackWaiter>();

    private static final class ReadbackKey {
        final int bufferId, startFrame, count;
        ReadbackKey(int bufferId, int startFrame, int count) {
            this.bufferId = bufferId; this.startFrame = startFrame; this.count = count;
        }
        @Override public int hashCode() {
            int result = bufferId; result = 31 * result + startFrame; return 31 * result + count;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ReadbackKey)) return false;
            ReadbackKey key = (ReadbackKey) other;
            return bufferId == key.bufferId && startFrame == key.startFrame && count == key.count;
        }
    }
    private static final class ReadbackWaiter {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        volatile SuperColliderOsc.Reply reply;
    }
    private static final boolean LIBRARY_LOADED;
    private static volatile boolean SERVER_STARTED = false;
    private static volatile boolean INPUT_AVAILABLE = false;
    private static volatile int BLOCK_SIZE = 64;
    private static volatile int SERVER_SAMPLE_RATE = 48000;

    static {
        boolean loaded;
        try {
            System.loadLibrary("norns_sc_bridge");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private SuperColliderBridge() { }

    static boolean isNativeBridgeLoaded() {
        return LIBRARY_LOADED;
    }

    static boolean isAvailable() {
        return LIBRARY_LOADED && nativeIsAvailable();
    }

    static String status() {
        if (!LIBRARY_LOADED) return "Ponte JNI do SuperCollider não carregada.";
        return nativeStatus();
    }

    static boolean start(int sampleRate, int blockSize, String pluginPath) {
        SERVER_SAMPLE_RATE = sampleRate >= 8000 && sampleRate <= 192000 ? sampleRate : 48000;
        BLOCK_SIZE = blockSize > 0 ? blockSize : 64;
        boolean started = LIBRARY_LOADED && pluginPath != null
                && nativeStart(sampleRate, BLOCK_SIZE, pluginPath);
        SERVER_STARTED = started;
        INPUT_AVAILABLE = started && nativeHasInput();
        if (started) {
            nativeSetSoftcutOutputProbeMode(false);
            nativeSetSoftcutOutputEnabled(false);
            SuperColliderSoftcutBuffers.reset();
            REPLY_BACKLOG.clear();
            SYNC_WAITERS.clear();
            cancelReadbacks();
        }
        return started;
    }

    static boolean sendMessage(String address, Object... args) {
        if (!LIBRARY_LOADED) return false;
        try {
            byte[] encoded = SuperColliderOsc.encode(address, args);
            return nativeSendPacket(encoded);
        } catch (java.io.IOException error) {
            AppLog.e("SuperCollider OSC: " + error.getMessage());
            return false;
        }
    }

    static boolean loadSynthDef(byte[] synthDef) {
        if (!LIBRARY_LOADED || synthDef == null || synthDef.length == 0) return false;
        try {
            byte[] encoded = SuperColliderOsc.encode("/d_recv", new SuperColliderOsc.Blob(synthDef));
            return nativeSendPacket(encoded);
        } catch (java.io.IOException error) {
            AppLog.e("SuperCollider SynthDef: " + error.getMessage());
            return false;
        }
    }

    static boolean hasInput() {
        return SERVER_STARTED && INPUT_AVAILABLE;
    }

    /** Copies the latest Oboe microphone block into Java fallback without opening a second capture device. */
    static boolean readInput(float[] destination, int frames) {
        if (destination == null || frames <= 0 || frames > destination.length) return false;
        if (!SERVER_STARTED || !INPUT_AVAILABLE || !LIBRARY_LOADED) return false;
        nativeReadInput(destination, frames);
        return true;
    }

    /** Returns one asynchronous OSC server reply, or null when the queue is empty. */
    static SuperColliderOsc.Reply pollReply() {
        SuperColliderOsc.Reply queued = REPLY_BACKLOG.poll();
        if (queued != null) {
            if (routeSyncReply(queued) || routeReadbackReply(queued)) return null;
            return queued;
        }
        if (!isAvailable()) return null;
        byte[] packet = nativePollReply();
        if (packet == null) return null;
        try {
            SuperColliderOsc.Reply reply = SuperColliderOsc.decodeReply(packet);
            return (routeSyncReply(reply) || routeReadbackReply(reply)) ? null : reply;
        } catch (java.io.IOException error) {
            AppLog.e("SuperCollider OSC reply: " + error.getMessage());
            return null;
        }
    }

    private static boolean routeSyncReply(SuperColliderOsc.Reply reply) {
        if (reply == null || !"/synced".equals(reply.address) || reply.syncId == null) return false;
        java.util.concurrent.CountDownLatch waiter = SYNC_WAITERS.get(reply.syncId);
        if (waiter == null) return false;
        waiter.countDown();
        return true;
    }

    private static boolean routeReadbackReply(SuperColliderOsc.Reply reply) {
        if (reply == null || !"/b_setn".equals(reply.address)
                || reply.bufferId == null || reply.startFrame == null || reply.samples == null) return false;
        ReadbackKey key = new ReadbackKey(reply.bufferId.intValue(),
                reply.startFrame.intValue(), reply.samples.length);
        ReadbackWaiter waiter = READBACK_WAITERS.remove(key);
        if (waiter == null) return false;
        waiter.reply = reply;
        waiter.done.countDown();
        return true;
    }

    interface ReplyPoller { SuperColliderOsc.Reply poll(); }
    interface ReplyDeferrer { void requeue(SuperColliderOsc.Reply reply); }

    /**
     * Pumps OSC replies while a Java worker waits for a /b_setn response.
     * Unrelated replies are deferred and restored in order for the Lua loader.
     */
    static boolean pumpUntil(java.util.concurrent.CountDownLatch waiter, long timeoutMs,
                             ReplyPoller poller, ReplyDeferrer deferrer) {
        if (waiter == null || poller == null || deferrer == null) return false;
        java.util.ArrayList<SuperColliderOsc.Reply> deferred = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        try {
            while (waiter.getCount() > 0 && System.currentTimeMillis() < deadline) {
                SuperColliderOsc.Reply reply = poller.poll();
                if (reply != null) {
                    deferred.add(reply);
                } else {
                    try { Thread.sleep(2); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        } finally {
            for (SuperColliderOsc.Reply reply : deferred) deferrer.requeue(reply);
        }
        return waiter.getCount() == 0;
    }

    /** Requests one bounded /b_getn region and actively dispatches its /b_setn reply. */
    static SuperColliderOsc.Reply requestBufferReadback(int bufferId, int startFrame,
                                                         int count, long timeoutMs) {
        if (bufferId < 0 || startFrame < 0 || count < 1 || count > 750) return null;
        ReadbackKey key = new ReadbackKey(bufferId, startFrame, count);
        ReadbackWaiter waiter = new ReadbackWaiter();
        if (READBACK_WAITERS.putIfAbsent(key, waiter) != null) return null;
        try {
            if (!sendMessage("/b_getn", Integer.valueOf(bufferId),
                    Integer.valueOf(startFrame), Integer.valueOf(count))) return null;
            if (!pumpUntil(waiter.done, timeoutMs,
                    SuperColliderBridge::pollReply, SuperColliderBridge::requeueReply)) return null;
            return waiter.reply;
        } finally {
            READBACK_WAITERS.remove(key, waiter);
        }
    }

    private static void cancelReadbacks() {
        for (ReadbackWaiter waiter : READBACK_WAITERS.values()) waiter.done.countDown();
        READBACK_WAITERS.clear();
    }

    static java.util.concurrent.CountDownLatch registerSyncWaiter(int syncId) {
        java.util.concurrent.CountDownLatch waiter = new java.util.concurrent.CountDownLatch(1);
        SYNC_WAITERS.put(syncId, waiter);
        return waiter;
    }

    static void cancelSyncWaiter(int syncId) {
        SYNC_WAITERS.remove(syncId);
    }

    static void requeueReply(SuperColliderOsc.Reply reply) {
        if (reply != null) REPLY_BACKLOG.offer(reply);
    }

    static boolean synchronize(int syncId) {
        return sendMessage("/sync", Integer.valueOf(syncId));
    }

    static boolean sendPacket(byte[] packet) {
        return LIBRARY_LOADED && packet != null && packet.length > 0
                && packet.length <= 65507 && nativeSendPacket(packet);
    }

    static boolean readCpuMetrics(float[] destination) {
        return destination != null && destination.length >= 2 && SERVER_STARTED
                && LIBRARY_LOADED && nativeReadCpuMetrics(destination);
    }

    static boolean readSoftcutDiagnostics(float[] destination) {
        return destination != null && destination.length >= 54 && SERVER_STARTED
                && LIBRARY_LOADED && nativeReadSoftcutDiagnostics(destination);
    }

    static boolean resetSoftcutRecorderDiagnostics(int channel) {
        return channel >= 1 && channel <= 2 && SERVER_STARTED && LIBRARY_LOADED
                && nativeResetSoftcutRecorderDiagnostics(channel);
    }

    static int blockSize() {
        return BLOCK_SIZE;
    }

    static int sampleRate() {
        return SERVER_SAMPLE_RATE;
    }

    static boolean setSoftcutOutputEnabled(boolean enabled) {
        // Closing the gate is always allowed, including while tearing down a failed server.
        return LIBRARY_LOADED && (!enabled || SERVER_STARTED) && nativeSetSoftcutOutputEnabled(enabled);
    }

    static boolean setSoftcutOutputProbeMode(boolean enabled) {
        return LIBRARY_LOADED && (!enabled || SERVER_STARTED) && nativeSetSoftcutOutputProbeMode(enabled);
    }

    static void stop() {
        if (LIBRARY_LOADED) {
            nativeSetSoftcutOutputProbeMode(false);
            nativeSetSoftcutOutputEnabled(false);
        }
        SERVER_STARTED = false;
        INPUT_AVAILABLE = false;
        if (LIBRARY_LOADED) nativeStop();
        SuperColliderSoftcutBuffers.reset();
        REPLY_BACKLOG.clear();
        SYNC_WAITERS.clear();
        cancelReadbacks();
    }

    private static native boolean nativeIsAvailable();
    private static native String nativeStatus();
    private static native boolean nativeStart(int sampleRate, int blockSize, String pluginPath);
    private static native boolean nativeSendPacket(byte[] packet);
    private static native byte[] nativePollReply();
    private static native void nativeReadInput(float[] destination, int frames);
    private static native boolean nativeHasInput();
    private static native boolean nativeSetSoftcutOutputEnabled(boolean enabled);
    private static native boolean nativeSetSoftcutOutputProbeMode(boolean enabled);
    private static native boolean nativeReadCpuMetrics(float[] destination);
    private static native boolean nativeReadSoftcutDiagnostics(float[] destination);
    private static native boolean nativeResetSoftcutRecorderDiagnostics(int channel);
    private static native void nativeStop();
}
