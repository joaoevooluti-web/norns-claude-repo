package org.nornsmobile.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Espelho assíncrono dos dois buffers Java para os buffers do scsynth.
 * O Java continua sendo a fonte de verdade e a única saída audível nesta fase.
 */
final class SuperColliderSoftcutBuffers {
    private static final int[] BUFFER_IDS = {20001, 20002};
    private static final int MAX_CHUNK = 12000;
    private static final int MAX_READBACK_CHUNK = 750;
    // Operações de controle (start/stop/sync) nunca devem ficar bloqueadas por
    // dezenas de blocos de /b_getn de outro canal.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "norns-softcut-native-buffers");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final int[] capacities = {1, 1};
    private static final boolean[] recordingActive = {false, false};
    private static final boolean[] recordingStartPending = {false, false};
    private static final boolean[] recordingStopPending = {false, false};
    private static final boolean[] reanchorPending = {false, false};
    // Exact half-open frame window accepted by the current BufWr session per channel.
    private static final int[] recordingWindowStart = {-1, -1};
    private static final int[] recordingWindowEnd = {-1, -1};
    private static final Object LOCK = new Object();
    private static volatile long generation = 0;
    private static volatile boolean ready = false;
    private static volatile boolean lastBufferIsolationProof = false;
    private static volatile boolean lastStereoRoutingProof = false;
    private static int nextSyncId = 2000000000;

    interface ReadbackListener { boolean onReadback(int channel, int startFrame, float[] samples); }

    interface NativeIsolationProbeListener {
        void onResult(boolean independent, String channel1Fingerprint, String channel2Fingerprint);
    }

    interface NativeStereoRoutingProbeListener {
        void onResult(boolean routed, float leftBusPeak, float rightBusPeak,
                      float leftRoutePeak, float rightRoutePeak,
                      float mutedLeftPeak, float mutedRightPeak);
    }

    private static final int ISOLATION_PROBE_BUFFER_CH1 = 20003;
    private static final int ISOLATION_PROBE_BUFFER_CH2 = 20004;
    // High reserved node IDs avoid colliding with script-created synth nodes.
    private static final int ISOLATION_PROBE_INPUT_NODE_CH1 = 2147000000;
    private static final int ISOLATION_PROBE_INPUT_NODE_CH2 = 2147000001;
    private static final int ISOLATION_PROBE_RECORDER_NODE_CH1 = 2147000002;
    private static final int ISOLATION_PROBE_RECORDER_NODE_CH2 = 2147000003;
    private static final int STEREO_ROUTING_PROBE_NODE = 2147000004;
    private static final int ISOLATION_PROBE_INPUT_BUS_CH1 = 30;
    private static final int ISOLATION_PROBE_INPUT_BUS_CH2 = 31;
    private static final int ISOLATION_PROBE_FRAMES = 64;

    static float[] createIsolationProbePattern(int channel) {
        if (channel != 1 && channel != 2) return new float[0];
        float[] pattern = new float[ISOLATION_PROBE_FRAMES];
        for (int i = 0; i < pattern.length; i++) {
            // Deterministic, signed and channel-specific payloads with no reliance
            // on a shared mono microphone source.
            int step = (i * 17 + (channel == 1 ? 3 : 11)) % 31;
            float magnitude = (step + 1) / 32.0f;
            pattern[i] = channel == 1 ? magnitude : -magnitude;
        }
        return pattern;
    }

    static float[] createBufWrIsolationPattern(int channel) {
        if (channel != 1 && channel != 2) return new float[0];
        float value = channel == 1 ? 0.25f : -0.375f;
        float[] pattern = new float[ISOLATION_PROBE_FRAMES];
        for (int i = 0; i < pattern.length; i++) pattern[i] = value;
        return pattern;
    }

    /**
     * Non-destructive runtime proof that independent server-side BufWr nodes write
     * distinct samples to separate temporary buffers. Uses temporary IDs and frees them;
     * the live Softcut buffers 20001/20002 are never overwritten.
     */
    static boolean verifyNativeBufferIsolation(NativeIsolationProbeListener listener) {
        lastBufferIsolationProof = false;
        lastStereoRoutingProof = false;
        if (listener == null || !SuperColliderBridge.isAvailable()) return false;
        final long taskGeneration = generation;
        try {
            WORKER.execute(() -> {
                boolean independent = false;
                String firstHash = "unavailable";
                String secondHash = "unavailable";
                try {
                    if (taskGeneration != generation || !SuperColliderBridge.isAvailable()) return;
                    final int firstId = ISOLATION_PROBE_BUFFER_CH1;
                    final int secondId = ISOLATION_PROBE_BUFFER_CH2;
                    // Clear stale probe allocations from an interrupted previous run.
                    if (!SuperColliderBridge.sendMessage("/b_free", Integer.valueOf(firstId))
                            || !SuperColliderBridge.sendMessage("/b_free", Integer.valueOf(secondId))
                            || !awaitServerSync(taskGeneration)) return;
                    if (!SuperColliderBridge.sendMessage("/b_alloc", Integer.valueOf(firstId),
                            Integer.valueOf(ISOLATION_PROBE_FRAMES), Integer.valueOf(1))
                            || !SuperColliderBridge.sendMessage("/b_alloc", Integer.valueOf(secondId),
                            Integer.valueOf(ISOLATION_PROBE_FRAMES), Integer.valueOf(1))
                            || !awaitServerSync(taskGeneration)) return;
                    float[] firstPattern = createBufWrIsolationPattern(1);
                    float[] secondPattern = createBufWrIsolationPattern(2);
                    // Feed two isolated input buses, then instantiate the production
                    // SoftcutRecorder SynthDef twice. This exercises its real BufWr UGen.
                    if (!SuperColliderBridge.sendMessage("/s_new", "SoftcutIsolationInput",
                            Integer.valueOf(ISOLATION_PROBE_INPUT_NODE_CH1), Integer.valueOf(0),
                            Integer.valueOf(1), "out_bus", Integer.valueOf(ISOLATION_PROBE_INPUT_BUS_CH1),
                            "value", Float.valueOf(0.25f), "duration", Float.valueOf(1.0f))
                            || !SuperColliderBridge.sendMessage("/s_new", "SoftcutIsolationInput",
                            Integer.valueOf(ISOLATION_PROBE_INPUT_NODE_CH2), Integer.valueOf(0),
                            Integer.valueOf(1), "out_bus", Integer.valueOf(ISOLATION_PROBE_INPUT_BUS_CH2),
                            "value", Float.valueOf(-0.375f), "duration", Float.valueOf(1.0f))) return;
                    if (!SuperColliderBridge.sendMessage("/s_new", "SoftcutRecorder",
                            Integer.valueOf(ISOLATION_PROBE_RECORDER_NODE_CH1), Integer.valueOf(1),
                            Integer.valueOf(1), "buf", Integer.valueOf(firstId),
                            "in_bus", Integer.valueOf(ISOLATION_PROBE_INPUT_BUS_CH1),
                            "start_frame", Integer.valueOf(0), "end_frame", Integer.valueOf(ISOLATION_PROBE_FRAMES),
                            "position", Float.valueOf(0.0f), "position_bus", Integer.valueOf(70),
                            "rate", Float.valueOf(1.0f), "record", Float.valueOf(1.0f),
                            "rec_level", Float.valueOf(1.0f), "pre_level", Float.valueOf(0.0f),
                            "input_level", Float.valueOf(1.0f), "adc_level", Float.valueOf(1.0f),
                            "enabled", Float.valueOf(1.0f), "rec_offset", Float.valueOf(0.0f),
                            "diagnostic_bus", Integer.valueOf(40))
                            || !SuperColliderBridge.sendMessage("/s_new", "SoftcutRecorder",
                            Integer.valueOf(ISOLATION_PROBE_RECORDER_NODE_CH2), Integer.valueOf(1),
                            Integer.valueOf(1), "buf", Integer.valueOf(secondId),
                            "in_bus", Integer.valueOf(ISOLATION_PROBE_INPUT_BUS_CH2),
                            "start_frame", Integer.valueOf(0), "end_frame", Integer.valueOf(ISOLATION_PROBE_FRAMES),
                            "position", Float.valueOf(0.0f), "position_bus", Integer.valueOf(71),
                            "rate", Float.valueOf(1.0f), "record", Float.valueOf(1.0f),
                            "rec_level", Float.valueOf(1.0f), "pre_level", Float.valueOf(0.0f),
                            "input_level", Float.valueOf(1.0f), "adc_level", Float.valueOf(1.0f),
                            "enabled", Float.valueOf(1.0f), "rec_offset", Float.valueOf(0.0f),
                            "diagnostic_bus", Integer.valueOf(46))) return;
                    // Explicit server barrier: both deterministic sources must exist before sampling.
                    if (!awaitServerSync(taskGeneration)) return;
                    try { Thread.sleep(100L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Freeze both writers before readback so no later block can change
                    // the sample payload while /b_getn is being serviced.
                    if (!SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_RECORDER_NODE_CH1))
                            || !SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_RECORDER_NODE_CH2))
                            || !SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_INPUT_NODE_CH1))
                            || !SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_INPUT_NODE_CH2))
                            || !awaitServerSync(taskGeneration)) return;
                    SuperColliderOsc.Reply first = SuperColliderBridge.requestBufferReadback(
                            firstId, 0, ISOLATION_PROBE_FRAMES, 5000);
                    SuperColliderOsc.Reply second = SuperColliderBridge.requestBufferReadback(
                            secondId, 0, ISOLATION_PROBE_FRAMES, 5000);
                    if (first == null || second == null || first.samples == null || second.samples == null
                            || first.samples.length != ISOLATION_PROBE_FRAMES
                            || second.samples.length != ISOLATION_PROBE_FRAMES) return;
                    firstHash = sampleFingerprint(first.samples);
                    secondHash = sampleFingerprint(second.samples);
                    independent = firstHash.equals(sampleFingerprint(firstPattern))
                            && secondHash.equals(sampleFingerprint(secondPattern))
                            && !firstHash.equals(secondHash);
                    AppLog.i("Softcut probe BufWr amostras: CH1 peak=" + samplePeak(first.samples)
                            + " rms=" + sampleRms(first.samples) + " CH2 peak=" + samplePeak(second.samples)
                            + " rms=" + sampleRms(second.samples));
                } finally {
                    boolean cleanupOk = true;
                    cleanupOk = SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_RECORDER_NODE_CH1)) && cleanupOk;
                    cleanupOk = SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_RECORDER_NODE_CH2)) && cleanupOk;
                    cleanupOk = SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_INPUT_NODE_CH1)) && cleanupOk;
                    cleanupOk = SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(ISOLATION_PROBE_INPUT_NODE_CH2)) && cleanupOk;
                    cleanupOk = SuperColliderBridge.sendMessage("/b_free", Integer.valueOf(ISOLATION_PROBE_BUFFER_CH1)) && cleanupOk;
                    cleanupOk = SuperColliderBridge.sendMessage("/b_free", Integer.valueOf(ISOLATION_PROBE_BUFFER_CH2)) && cleanupOk;
                    cleanupOk = awaitServerSync(taskGeneration) && cleanupOk;
                    boolean finalIndependent = independent && cleanupOk;
                    lastBufferIsolationProof = finalIndependent;
                    listener.onResult(finalIndependent, firstHash, secondHash);
                    AppLog.i("Softcut prova nativa de independência CH1/CH2: result="
                            + (finalIndependent ? "PASS" : "FAIL") + " cleanup=" + cleanupOk
                            + " ch1=" + firstHash + " ch2=" + secondHash + " liveBuffersPreserved=true");
                }
            });
            return true;
        } catch (RuntimeException rejected) {
            AppLog.e("Softcut: não foi possível enfileirar prova nativa de independência (" + rejected + ")");
            return false;
        }
    }

    /**
     * Measures the dedicated Softcut L/R buses through the real Oboe mixer while
     * the callback hard-mutes every PCM frame. The normal Softcut output gate stays closed.
     */
    static boolean verifyNativeStereoRouting(NativeStereoRoutingProbeListener listener) {
        // A failed or skipped re-probe must never leave an earlier proof valid.
        lastStereoRoutingProof = false;
        if (listener == null || !SuperColliderBridge.isAvailable() || !lastBufferIsolationProof) return false;
        final long taskGeneration = generation;
        try {
            WORKER.execute(() -> {
                boolean routed = false;
                boolean probeModeEnabled = false;
                float leftBusPeak = -1.0f, rightBusPeak = -1.0f;
                float leftRoutePeak = -1.0f, rightRoutePeak = -1.0f;
                float mutedLeftPeak = -1.0f, mutedRightPeak = -1.0f;
                try {
                    if (taskGeneration != generation || !SuperColliderBridge.isAvailable()) return;
                    float[] before = new float[54];
                    if (!SuperColliderBridge.readSoftcutDiagnostics(before) || before[12] > 0.5f)
                        return; // Never run while the real Softcut output gate is open.
                    if (!SuperColliderBridge.setSoftcutOutputProbeMode(true)) return;
                    probeModeEnabled = true;
                    if (!SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(STEREO_ROUTING_PROBE_NODE))
                            || !awaitServerSync(taskGeneration)) return;
                    if (!SuperColliderBridge.sendMessage("/s_new", "SoftcutStereoRouteProbe",
                            Integer.valueOf(STEREO_ROUTING_PROBE_NODE), Integer.valueOf(0), Integer.valueOf(1),
                            "left_bus", Integer.valueOf(9), "right_bus", Integer.valueOf(10),
                            "left", Float.valueOf(0.25f), "right", Float.valueOf(-0.375f))
                            || !awaitServerSync(taskGeneration)) return;
                    try { Thread.sleep(120L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    float[] values = new float[54];
                    if (!SuperColliderBridge.readSoftcutDiagnostics(values)) return;
                    leftBusPeak = values[8];
                    rightBusPeak = values[9];
                    mutedLeftPeak = values[10];
                    mutedRightPeak = values[11];
                    leftRoutePeak = values[52];
                    rightRoutePeak = values[53];
                    routed = values[13] != before[13]
                            && Math.abs(leftBusPeak - 0.25f) < 0.05f
                            && Math.abs(rightBusPeak - 0.375f) < 0.05f
                            && Math.abs(leftRoutePeak - 0.25f) < 0.05f
                            && Math.abs(rightRoutePeak - 0.375f) < 0.05f
                            && mutedLeftPeak < 0.001f && mutedRightPeak < 0.001f
                            && values[12] < 0.5f;
                } finally {
                    boolean cleanupOk = SuperColliderBridge.sendMessage("/n_free", Integer.valueOf(STEREO_ROUTING_PROBE_NODE));
                    cleanupOk = awaitServerSync(taskGeneration) && cleanupOk;
                    // Reassert the production output mute before leaving probe mode.
                    // This closes the race with any concurrent readiness/ownership change.
                    cleanupOk = SuperColliderBridge.setSoftcutOutputEnabled(false) && cleanupOk;
                    if (probeModeEnabled)
                        cleanupOk = SuperColliderBridge.setSoftcutOutputProbeMode(false) && cleanupOk;
                    boolean finalRouted = routed && cleanupOk;
                    if (!cleanupOk)
                        AppLog.e("Softcut stereo probe: não foi possível confirmar limpeza e saída mutada.");
                    lastStereoRoutingProof = finalRouted;
                    listener.onResult(finalRouted, leftBusPeak, rightBusPeak, leftRoutePeak, rightRoutePeak,
                            mutedLeftPeak, mutedRightPeak);
                    AppLog.i("Softcut prova estéreo Oboe (muted): result=" + (finalRouted ? "PASS" : "FAIL")
                            + " cleanup=" + cleanupOk + " busL=" + leftBusPeak + " busR=" + rightBusPeak
                            + " routeL=" + leftRoutePeak + " routeR=" + rightRoutePeak
                            + " pcmL=" + mutedLeftPeak + " pcmR=" + mutedRightPeak + " nativeGateOpen=false");
                }
            });
            return true;
        } catch (RuntimeException rejected) {
            AppLog.e("Softcut: não foi possível enfileirar prova estéreo (" + rejected + ")");
            return false;
        }
    }

    static boolean isReady() { return ready; }

    static boolean isNativeRoutingValidated() {
        return lastBufferIsolationProof && lastStereoRoutingProof;
    }

    static int nativeBufferIdForChannel(int channel) {
        return channel >= 1 && channel <= BUFFER_IDS.length ? BUFFER_IDS[channel - 1] : -1;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private SuperColliderSoftcutBuffers() { }

    static void reset() {
        generation++;
        ready = false;
        lastBufferIsolationProof = false;
        lastStereoRoutingProof = false;
        synchronized (LOCK) {
            capacities[0] = 1;
            capacities[1] = 1;
            recordingActive[0] = false;
            recordingActive[1] = false;
            recordingStartPending[0] = false;
            recordingStartPending[1] = false;
            recordingStopPending[0] = false;
            recordingStopPending[1] = false;
            reanchorPending[0] = false;
            reanchorPending[1] = false;
            recordingWindowStart[0] = recordingWindowStart[1] = -1;
            recordingWindowEnd[0] = recordingWindowEnd[1] = -1;
        }
    }

    static void setReady(boolean value) {
        if (!value) {
            generation++;
            lastBufferIsolationProof = false;
            lastStereoRoutingProof = false;
        }
        ready = value;
        synchronized (LOCK) {
            if (!value) {
                for (int i = 0; i < BUFFER_IDS.length; i++) {
                    recordingActive[i] = false;
                    recordingStartPending[i] = false;
                    recordingStopPending[i] = false;
                    reanchorPending[i] = false;
                    recordingWindowStart[i] = -1;
                    recordingWindowEnd[i] = -1;
                }
            }
            LOCK.notifyAll();
        }
    }

    static void noteNativeBufferFreed(int bufferId) {
        int index = bufferId == BUFFER_IDS[0] ? 0 : bufferId == BUFFER_IDS[1] ? 1 : -1;
        if (index < 0) return;
        generation++;
        synchronized (LOCK) {
            capacities[index] = 0;
            recordingActive[index] = false;
            recordingStartPending[index] = false;
            recordingStopPending[index] = false;
            reanchorPending[index] = false;
            recordingWindowStart[index] = -1;
            recordingWindowEnd[index] = -1;
        }
    }

    static void noteNativeBufferAllocated(int bufferId, int frames) {
        int index = bufferId == BUFFER_IDS[0] ? 0 : bufferId == BUFFER_IDS[1] ? 1 : -1;
        if (index < 0 || frames < 1) return;
        synchronized (LOCK) {
            capacities[index] = frames;
        }
    }

    static boolean isRecording(int channel) {
        int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length) return false;
        synchronized (LOCK) {
            return recordingActive[index] || recordingStartPending[index]
                    || recordingStopPending[index];
        }
    }

    static boolean isReanchorPending(int channel) {
        int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length) return false;
        synchronized (LOCK) { return reanchorPending[index]; }
    }

    /**
     * Moves a stopped BufWr cursor to the authoritative Java cursor. Work is
     * serialized with start/stop/readback operations; an active or uncertain
     * writer is never repositioned.
     */
    static boolean reanchorRecordingCursor(final int channel, final int recorderNode,
                                            final double positionSeconds) {
        final int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length || recorderNode <= 0
                || !isFinite(positionSeconds) || positionSeconds < 0
                || !Float.isFinite((float)positionSeconds)) return false;
        final long taskGeneration = generation;
        synchronized (LOCK) {
            int capacity = capacities[index];
            if (recordingActive[index] || recordingStartPending[index]
                    || recordingStopPending[index] || reanchorPending[index] || !ready
                    || capacity < 1
                    || positionSeconds > capacity / (double)SuperColliderBridge.sampleRate()) return false;
            reanchorPending[index] = true;
        }
        try {
            WORKER.execute(() -> {
                try {
                    if (taskGeneration != generation || !awaitReady(taskGeneration)
                            || !SuperColliderBridge.isAvailable()) return;
                    synchronized (LOCK) {
                        if (recordingActive[index] || recordingStartPending[index]
                                || recordingStopPending[index]) return;
                    }
                    if (!awaitServerSync(taskGeneration)) {
                        AppLog.e("Softcut nativo: timeout antes de reancorar cursor parado do buffer " + channel);
                        return;
                    }
                    if (taskGeneration != generation) return;
                    if (!SuperColliderBridge.sendMessage("/n_set", Integer.valueOf(recorderNode),
                            "position", Float.valueOf((float)positionSeconds))
                            || !awaitServerSync(taskGeneration)) {
                        AppLog.e("Softcut nativo: falha ao sincronizar reancoragem do cursor do buffer " + channel);
                    }
                } finally {
                    synchronized (LOCK) {
                        if (taskGeneration == generation) reanchorPending[index] = false;
                    }
                }
            });
            return true;
        } catch (RuntimeException rejected) {
            synchronized (LOCK) { reanchorPending[index] = false; }
            AppLog.e("Softcut nativo: não foi possível enfileirar recuperação do cursor (" + rejected + ")");
            return false;
        }
    }

    static boolean needsFullSnapshot(int channel, int requiredFrames) {
        int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length) return false;
        synchronized (LOCK) {
            return requiredFrames > capacities[index];
        }
    }

    /**
     * Enfileira uma região já processada pelo SoftcutCore. fullSnapshot só é
     * necessário quando o buffer nativo precisa crescer; assim evitamos cópias
     * integrais em operações comuns de clear/copy.
     */
    static void sync(int channel, int requiredFrames, int startFrame,
                     float[] region, float[] fullSnapshot) {
        final int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length || requiredFrames < 1
                || startFrame < 0 || region == null) return;
        final int id = nativeBufferIdForChannel(channel);
        final int frames = Math.max(1, requiredFrames);
        final int offset = startFrame;
        final float[] changed = region;
        final float[] complete = fullSnapshot;
        final long taskGeneration = generation;
        WORKER.execute(() -> {
            if (taskGeneration != generation || !awaitReady(taskGeneration)
                    || !SuperColliderBridge.isAvailable()) return;
            boolean grow;
            synchronized (LOCK) {
                grow = frames > capacities[index];
            }
            if (grow) {
                if (complete != null && complete.length < frames) {
                    AppLog.e("Softcut nativo: snapshot completo truncado durante expansão do buffer " + channel);
                    return;
                }
                if (!SuperColliderBridge.sendMessage("/b_free", Integer.valueOf(id))
                        || !SuperColliderBridge.sendMessage("/b_alloc", Integer.valueOf(id),
                        Integer.valueOf(frames), Integer.valueOf(1))
                        || !awaitServerSync(taskGeneration)) {
                    AppLog.e("Softcut nativo: falha ao redimensionar buffer " + channel);
                    return;
                }
                boolean uploaded = complete != null
                        ? writeSamples(id, 0, complete, frames, taskGeneration)
                        : writeSamples(id, offset, changed, Math.min(changed.length, frames - offset), taskGeneration);
                if (taskGeneration != generation || !uploaded) {
                    AppLog.e("Softcut nativo: falha ao restaurar conteúdo do buffer " + channel);
                    return;
                }
                synchronized (LOCK) {
                    if (taskGeneration == generation) capacities[index] = frames;
                }
            } else {
                int count = Math.min(changed.length, Math.max(0, frames - offset));
                if (count > 0 && !writeSamples(id, offset, changed, count, taskGeneration)) {
                    AppLog.e("Softcut nativo: falha ao atualizar região do buffer " + channel);
                }
            }
        });
    }

    private static boolean awaitReady(long taskGeneration) {
        long deadline = System.currentTimeMillis() + 15000;
        synchronized (LOCK) {
            while (!ready && taskGeneration == generation
                    && System.currentTimeMillis() < deadline) {
                try { LOCK.wait(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return ready && taskGeneration == generation;
        }
    }

    private static synchronized int nextSyncId() {
        int value = nextSyncId;
        nextSyncId = value >= 2147483000 ? 2000000000 : value + 1;
        return value;
    }

    private static boolean awaitServerSync(long taskGeneration) {
        int syncId = nextSyncId();
        java.util.concurrent.CountDownLatch waiter = SuperColliderBridge.registerSyncWaiter(syncId);
        if (!SuperColliderBridge.sendMessage("/sync", Integer.valueOf(syncId))) {
            SuperColliderBridge.cancelSyncWaiter(syncId);
            return false;
        }
        java.util.ArrayList<SuperColliderOsc.Reply> deferred = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + 15000;
        try {
            while (System.currentTimeMillis() < deadline && taskGeneration == generation
                    && waiter.getCount() > 0) {
                SuperColliderOsc.Reply reply = SuperColliderBridge.pollReply();
                if (reply != null) deferred.add(reply);
                else {
                    try { Thread.sleep(2); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        } finally {
            SuperColliderBridge.cancelSyncWaiter(syncId);
            for (SuperColliderOsc.Reply reply : deferred) {
                SuperColliderBridge.requeueReply(reply);
            }
        }
        return waiter.getCount() == 0 && taskGeneration == generation;
    }

    private static void logSignalDiagnostics(int channel, String stage) {
        float[] values = new float[54];
        if (!SuperColliderBridge.readSoftcutDiagnostics(values)) {
            AppLog.i("Softcut BufWr signal probe: stage=" + stage + " channel=" + channel
                + " scope=server-lifetime"
                    + " snapshot=unavailable");
            return;
        }
        int offset = 24 + (channel - 1) * 12;
        AppLog.i("Softcut BufWr signal probe: stage=" + stage + " channel=" + channel
                + " sequence=" + values[13]
                + " inputBusPeak=" + values[16] + " inputBusRms=" + values[17]
                + " ringReadFrames=" + values[18] + " ringDropped=" + values[19]
                + " ringSkipped=" + values[20] + " ringUnderrun=" + values[21]
                + " rawInputPeak=" + values[offset] + " rawInputRms=" + values[offset + 1]
                + " scaledInputPeak=" + values[offset + 2] + " scaledInputRms=" + values[offset + 3]
                + " cutMixPeak=" + values[offset + 4] + " cutMixRms=" + values[offset + 5]
                + " incomingPeak=" + values[offset + 6] + " incomingRms=" + values[offset + 7]
                + " recordGatePeak=" + values[offset + 8] + " recordGateRms=" + values[offset + 9]
                + " writeValidPeak=" + values[offset + 10] + " writeValidRms=" + values[offset + 11]
                + " ringAccepted=" + values[48] + " ringQueued=" + values[49]
                + " ringRequested=" + values[50] + " ringPushAttempts=" + values[51]);
    }

    /** Starts the single recorder node for a buffer only after its contents and capacity are synchronized. */
    static void prepareRecording(final int channel, final int requiredFrames, final int recorderNode,
                                 final int startFrame, final int endFrame, final double position,
                                 final double rate, final double recLevel, final double preLevel,
                                 final double inputLevel, final double recPreSlew) {
        final int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length || requiredFrames < 1
                || requiredFrames > 16_777_216 || recorderNode <= 0 || startFrame < 0
                || endFrame <= startFrame || endFrame > requiredFrames || endFrame > 16_777_216
                || !isFinite(position) || position < 0 || !isFinite(rate)
                || !isFinite(recLevel) || !isFinite(preLevel) || !isFinite(inputLevel)
                || !isFinite(recPreSlew) || recPreSlew < 0
                || !Float.isFinite((float)position) || !Float.isFinite((float)rate)
                || !Float.isFinite((float)recLevel) || !Float.isFinite((float)preLevel)
                || !Float.isFinite((float)inputLevel) || !Float.isFinite((float)recPreSlew)
                || position > requiredFrames / (double)SuperColliderBridge.sampleRate()) return;
        final long taskGeneration = generation;
        synchronized (LOCK) {
            // A new session may queue behind a submitted stop; duplicate starts are refused.
            if (recordingStartPending[index]
                    || (recordingActive[index] && !recordingStopPending[index])) return;
            recordingStartPending[index] = true;
        }
        try {
            WORKER.execute(() -> {
                boolean recordCommandSent = false;
                try {
                    if (taskGeneration != generation || !awaitReady(taskGeneration)
                            || !SuperColliderBridge.isAvailable()) return;
                    synchronized (LOCK) {
                        // This start is queued before a rapid stop on the same single-thread worker.
                        // Complete it; the queued stop must then confirm record=0 and read back.
                        if (recordingActive[index]) return;
                    }
                    int capacity;
                    synchronized (LOCK) { capacity = capacities[index]; }
                    if (capacity < requiredFrames) {
                        AppLog.e("Softcut nativo: buffer " + channel + " ainda não tem capacidade para gravar");
                        return;
                    }
                    if (!awaitServerSync(taskGeneration)) {
                        AppLog.e("Softcut nativo: timeout sincronizando buffer " + channel + " antes de gravar");
                        return;
                    }
                    if (taskGeneration != generation) return;
                    AppLog.i("Softcut BufWr start parâmetros: canal=" + channel + " buffer="
                            + nativeBufferIdForChannel(channel) + " node=" + recorderNode + " capacity=" + capacity
                            + " startFrame=" + startFrame + " endFrame=" + endFrame
                            + " position=" + position + " rate=" + rate
                            + " recLevel=" + recLevel + " preLevel=" + preLevel
                            + " inputLevel=" + inputLevel + " recPreSlew=" + recPreSlew
                            + " enabled=1 record=1");
                    boolean started = SuperColliderBridge.sendMessage("/n_set",
                            Integer.valueOf(recorderNode),
                            "start_frame", Float.valueOf(startFrame),
                            "end_frame", Float.valueOf(endFrame),
                            "position", Float.valueOf((float)position),
                            "rate", Float.valueOf((float)rate),
                            "rec_level", Float.valueOf((float)recLevel),
                            "pre_level", Float.valueOf((float)preLevel),
                            "input_level", Float.valueOf((float)inputLevel),
                            "diagnostic_bus", Integer.valueOf(19 + index * 6),
                            "recpre_slew_time", Float.valueOf((float)recPreSlew),
                            // SoftcutRecorder declares enabled=0 by default and its
                            // BufWr gate is record * enabled. Both controls must be
                            // asserted for a recording session to write any samples.
                            "enabled", Integer.valueOf(1),
                            "record", Integer.valueOf(1));
                    if (!started) {
                        AppLog.e("Softcut nativo: não foi possível iniciar o gravador do buffer " + channel);
                        return;
                    }
                    recordCommandSent = true;
                    synchronized (LOCK) {
                        if (taskGeneration == generation) {
                            recordingWindowStart[index] = startFrame;
                            recordingWindowEnd[index] = endFrame;
                        }
                    }
                    boolean confirmed = awaitServerSync(taskGeneration);
                    synchronized (LOCK) {
                        if (taskGeneration == generation) {
                            recordingActive[index] = true;
                            recordingStartPending[index] = false;
                        }
                    }
                    AppLog.i("Softcut BufWr start confirmação: canal=" + channel
                            + " buffer=" + nativeBufferIdForChannel(channel) + " node=" + recorderNode
                            + " sent=true synced=" + confirmed);
                    if (confirmed) {
                        boolean resetDiagnostics = SuperColliderBridge.resetSoftcutRecorderDiagnostics(channel);
                        AppLog.i("Softcut BufWr signal accumulator: stage=start-confirmed channel="
                                + channel + " reset=" + resetDiagnostics);
                        if (!resetDiagnostics) {
                            AppLog.e("Softcut nativo: não foi possível reiniciar os acumuladores de diagnóstico do canal "
                                    + channel);
                        }
                    }
                    if (!confirmed) {
                        // A start sem /synced é ambígua: fechar os dois gates imediatamente.
                        // Mantemos recordingActive=true como estado conservador até um stop
                        // confirmado; o supervisor não pode reancorar um writer incerto.
                        boolean closed = SuperColliderBridge.sendMessage("/n_set",
                                Integer.valueOf(recorderNode),
                                "record", Integer.valueOf(0),
                                "enabled", Integer.valueOf(0));
                        if (closed) awaitServerSync(taskGeneration);
                        AppLog.e("Softcut nativo: estado de gravação incerto após timeout no buffer "
                                + channel + "; gates de escrita fechados por segurança.");
                    }
                } finally {
                    if (!recordCommandSent && taskGeneration == generation) {
                        synchronized (LOCK) { recordingStartPending[index] = false; }
                    }
                }
            });
        } catch (RuntimeException rejected) {
            synchronized (LOCK) { recordingStartPending[index] = false; }
            AppLog.e("Softcut nativo: não foi possível enfileirar gravação (" + rejected + ")");
        }
    }

    /** Stops the recorder, synchronizes the server and reads back only a confirmed recording. */
    static void finishRecording(final int channel, final int recorderNode, final int startFrame,
                                final int count, final ReadbackListener listener) {
        final int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length || recorderNode <= 0
                || startFrame < 0 || count < 1 || listener == null) return;
        final int id = nativeBufferIdForChannel(channel);
        final long taskGeneration = generation;
        synchronized (LOCK) {
            if (!shouldQueueStop(recordingActive[index], recordingStartPending[index],
                    recordingStopPending[index])) {
                if (recordingStopPending[index]) {
                    AppLog.i("Softcut BufWr stop duplicado ignorado: canal=" + channel
                            + " buffer=" + id + " node=" + recorderNode);
                } else {
                    AppLog.i("Softcut BufWr stop ignorado: canal=" + channel + " buffer=" + id
                            + " node=" + recorderNode + " active=false startPending=false");
                }
                return;
            }
            recordingStopPending[index] = true;
        }
        AppLog.i("Softcut BufWr stop solicitado: canal=" + channel + " buffer=" + id
                + " node=" + recorderNode + " startFrame=" + startFrame + " count=" + count);
        WORKER.execute(() -> {
            if (taskGeneration != generation || !awaitReady(taskGeneration)
                    || !SuperColliderBridge.isAvailable()) {
                synchronized (LOCK) {
                    if (taskGeneration == generation) recordingStopPending[index] = false;
                }
                return;
            }
            synchronized (LOCK) {
                if (!recordingActive[index]) {
                    recordingStopPending[index] = false;
                    return;
                }
            }
            // Close both gates in one OSC node-set before /sync. This makes
            // the writer inert after stop and prevents a stale enabled flag from
            // allowing a later record=1 command to write unexpectedly.
            long stopStartedNanos = System.nanoTime();
            boolean stopSent = SuperColliderBridge.sendMessage("/n_set", Integer.valueOf(recorderNode),
                    "record", Integer.valueOf(0),
                    "enabled", Integer.valueOf(0));
            boolean stopSynced = stopSent && awaitServerSync(taskGeneration);
            if (!stopSynced) {
                AppLog.e("Softcut nativo: falha ao parar/sincronizar gravação do buffer " + channel
                        + "; cursor mantido como ativo/incerto, sem permitir reancoragem.");
                synchronized (LOCK) {
                    if (taskGeneration == generation) recordingStopPending[index] = false;
                }
                AppLog.e("Softcut BufWr stop diagnóstico: canal=" + channel + " buffer=" + id
                        + " node=" + recorderNode + " sent=" + stopSent
                        + " synced=false elapsedMs="
                        + ((System.nanoTime() - stopStartedNanos) / 1_000_000L));
                return;
            }
            AppLog.i("Softcut BufWr stop confirmado: canal=" + channel + " buffer=" + id
                    + " node=" + recorderNode + " synced=true elapsedMs="
                    + ((System.nanoTime() - stopStartedNanos) / 1_000_000L));
            logSignalDiagnostics(channel, "stop-confirmed");
            int capacity;
            int windowStart;
            int windowEnd;
            synchronized (LOCK) {
                recordingActive[index] = false;
                recordingStopPending[index] = false;
                capacity = capacities[index];
                windowStart = recordingWindowStart[index];
                windowEnd = recordingWindowEnd[index];
                recordingWindowStart[index] = -1;
                recordingWindowEnd[index] = -1;
            }
            int[] boundedRegion = intersectRecordingWindow(
                    startFrame, count, windowStart, windowEnd);
            if (boundedRegion[1] < 1) {
                AppLog.e("Softcut nativo: readback pós-gravação sem interseção com a janela BufWr do buffer "
                        + channel + " requestedStart=" + startFrame + " requestedCount=" + count
                        + " windowStart=" + windowStart + " windowEnd=" + windowEnd);
                return;
            }
            if (boundedRegion[0] != startFrame || boundedRegion[1] != count) {
                AppLog.i("Softcut BufWr readback limitado à janela gravável: canal=" + channel
                        + " buffer=" + id + " requestedStart=" + startFrame
                        + " requestedCount=" + count + " actualStart=" + boundedRegion[0]
                        + " actualCount=" + boundedRegion[1] + " windowStart=" + windowStart
                        + " windowEnd=" + windowEnd);
            }
            // Schedule readback as bounded chunks. Each chunk yields the worker so
            // another channel's stop/sync cannot sit behind 96 consecutive /b_getn calls.
            try {
                WORKER.execute(() -> readbackConfirmedRecording(channel, id, boundedRegion[0],
                        boundedRegion[1], listener, taskGeneration, capacity, 0));
            } catch (RuntimeException rejected) {
                AppLog.e("Softcut nativo: não foi possível enfileirar readback confirmado do buffer "
                        + channel + " (" + rejected + ")");
            }
        });
    }

    private static void readbackConfirmedRecording(final int channel, final int id,
                                                   final int startFrame, final int count,
                                                   final ReadbackListener listener,
                                                   final long taskGeneration, final int capacity,
                                                   final int offset) {
        if (taskGeneration != generation) return;
        if (offset == 0 && (startFrame > capacity || count > capacity - startFrame)) {
            AppLog.e("Softcut nativo: readback pós-gravação fora dos limites do buffer " + channel);
            return;
        }
        if (offset >= count) return;
        int size = Math.min(MAX_READBACK_CHUNK, count - offset);
        SuperColliderOsc.Reply reply = SuperColliderBridge.requestBufferReadback(
                id, startFrame + offset, size, 15000);
        if (reply == null || reply.samples == null || reply.samples.length != size) {
            AppLog.e("Softcut nativo: falha no readback pós-gravação do buffer " + channel
                    + " na amostra " + (startFrame + offset));
            return;
        }
        if (!listener.onReadback(channel, startFrame + offset, reply.samples)) {
            AppLog.e("Softcut nativo: thread de áudio não confirmou bloco de readback do buffer " + channel);
            return;
        }
        logReadbackChunk(channel, id, startFrame + offset, reply.samples);
        final int nextOffset = nextReadbackOffset(offset, count);
        if (nextOffset >= 0 && taskGeneration == generation) {
            try {
                WORKER.execute(() -> readbackConfirmedRecording(channel, id, startFrame, count,
                        listener, taskGeneration, capacity, nextOffset));
            } catch (RuntimeException rejected) {
                AppLog.e("Softcut nativo: não foi possível continuar readback do buffer "
                        + channel + " (" + rejected + ")");
            }
        }
    }

    /** Intersects a requested readback with the exact half-open BufWr recording window. */
    static int[] intersectRecordingWindow(int requestedStart, int requestedCount,
                                          int windowStart, int windowEnd) {
        if (requestedStart < 0 || requestedCount < 1 || windowStart < 0
                || windowEnd <= windowStart) return new int[]{-1, 0};
        long requestedEnd = (long) requestedStart + (long) requestedCount;
        int actualStart = Math.max(requestedStart, windowStart);
        long actualEnd = Math.min(requestedEnd, (long) windowEnd);
        if (actualEnd <= actualStart) return new int[]{-1, 0};
        return new int[]{actualStart, (int) (actualEnd - actualStart)};
    }

    static boolean shouldQueueStop(boolean recording, boolean startPending, boolean stopPending) {
        return (recording || startPending) && !stopPending;
    }

    static int nextReadbackOffset(int offset, int count) {
        if (offset < 0 || count < 0 || offset >= count) return -1;
        return offset + Math.min(MAX_READBACK_CHUNK, count - offset);
    }

    private static void logReadbackChunk(int channel, int bufferId, int startFrame, float[] samples) {
        double sumSquares = 0;
        double peak = 0;
        for (float sample : samples) {
            double value = (!Float.isNaN(sample) && !Float.isInfinite(sample)) ? sample : 0;
            peak = Math.max(peak, Math.abs(value));
            sumSquares += value * value;
        }
        double rms = samples.length == 0 ? 0 : Math.sqrt(sumSquares / samples.length);
        AppLog.i("Softcut BufWr readback chunk: canal=" + channel + " buffer=" + bufferId
                + " startFrame=" + startFrame + " count=" + samples.length
                + " peak=" + peak + " rms=" + rms
                + " sampleHash=" + sampleFingerprint(samples));
    }

    private static double samplePeak(float[] samples) {
        double peak = 0;
        if (samples != null) for (float sample : samples) {
            if (!Float.isNaN(sample) && !Float.isInfinite(sample))
                peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static double sampleRms(float[] samples) {
        if (samples == null || samples.length == 0) return 0;
        double sumSquares = 0;
        for (float sample : samples) {
            double value = (!Float.isNaN(sample) && !Float.isInfinite(sample)) ? sample : 0;
            sumSquares += value * value;
        }
        return Math.sqrt(sumSquares / samples.length);
    }

    /** Stable fingerprint of the actual float32 readback payload, not just its peak/RMS. */
    static String sampleFingerprint(float[] samples) {
        if (samples == null) return "null";
        long hash = 0xcbf29ce484222325L;
        hash ^= samples.length;
        hash *= 0x100000001b3L;
        for (float sample : samples) {
            int bits = Float.floatToIntBits(sample);
            for (int shift = 24; shift >= 0; shift -= 8) {
                hash ^= (bits >>> shift) & 0xffL;
                hash *= 0x100000001b3L;
            }
        }
        return Long.toHexString(hash);
    }

    /** Readback serialized with mirror writes; chunks fit the bounded native OSC reply queue. */
    static void readback(final int channel, final int startFrame, final int count,
                         final ReadbackListener listener) {
        final int index = channel - 1;
        if (index < 0 || index >= BUFFER_IDS.length || startFrame < 0 || count < 1
                || listener == null) return;
        final int id = nativeBufferIdForChannel(channel);
        final long taskGeneration = generation;
        WORKER.execute(() -> {
            if (taskGeneration != generation || !awaitReady(taskGeneration)
                    || !SuperColliderBridge.isAvailable()) return;
            int capacity;
            synchronized (LOCK) { capacity = capacities[index]; }
            if (startFrame > capacity || count > capacity - startFrame) {
                AppLog.e("Softcut nativo: readback fora dos limites do buffer " + channel);
                return;
            }
            if (!awaitServerSync(taskGeneration)) {
                AppLog.e("Softcut nativo: timeout ao sincronizar antes do readback do buffer " + channel);
                return;
            }
            int offset = 0;
            while (offset < count && taskGeneration == generation) {
                int size = Math.min(MAX_READBACK_CHUNK, count - offset);
                SuperColliderOsc.Reply reply = SuperColliderBridge.requestBufferReadback(
                        id, startFrame + offset, size, 15000);
                if (reply == null || reply.samples == null || reply.samples.length != size) {
                    AppLog.e("Softcut nativo: falha no readback do buffer " + channel
                            + " na amostra " + (startFrame + offset));
                    return;
                }
                if (!listener.onReadback(channel, startFrame + offset, reply.samples)) {
                    AppLog.e("Softcut nativo: thread de áudio não confirmou bloco de readback do buffer " + channel);
                    return;
                }
                logReadbackChunk(channel, id, startFrame + offset, reply.samples);
                offset += size;
            }
        });
    }

    private static boolean writeSamples(int buffer, int start, float[] samples,
                                        int count, long taskGeneration) {
        int offset = 0;
        while (offset < count && taskGeneration == generation) {
            int size = Math.min(MAX_CHUNK, count - offset);
            float[] chunk = new float[size];
            System.arraycopy(samples, offset, chunk, 0, size);
            if (!SuperColliderBridge.sendMessage("/b_setn",
                    Integer.valueOf(buffer), Integer.valueOf(start + offset),
                    Integer.valueOf(size), new SuperColliderOsc.FloatArray(chunk))) {
                return false;
            }
            offset += size;
        }
        return offset == count && taskGeneration == generation;
    }
}
