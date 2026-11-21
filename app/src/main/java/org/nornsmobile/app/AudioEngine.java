package org.nornsmobile.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Saída de áudio em tempo real: uma thread de alta prioridade gera blocos com o SynthCore
 * e os escreve num AudioTrack (float, estéreo, modo de baixa latência).
 * Os comandos chegam por uma fila e são aplicados no início de cada bloco.
 */
final class AudioEngine {

    private static final int CMD_ENGINE = 0;
    private static final int CMD_COMMAND = 1;
    private static final int CMD_ALL_OFF = 2;
    private static final int CMD_SOFTCUT = 3;
    private static final int CMD_SOFTCUT_BUFFER = 4;
    private static final int CMD_SOFTCUT_COPY = 5;
    private static final int CMD_SOFTCUT_EXPORT = 6;
    private static final int CMD_SOFTCUT_SYNC = 7;
    private static final int CMD_SOFTCUT_RENDER = 8;
    private static final int CMD_SOFTCUT_NATIVE_READBACK = 9;
    private static final int CMD_SOFTCUT_NATIVE_RECORD_START = 10;
    private static final int CMD_FX_ENABLED = 11;
    private static final int CMD_FX_PARAMETER = 12;
    private static final int CMD_FX_MIX = 13;
    private static final int CMD_FX_SEND = 14;

    interface SoftcutRenderListener {
        void onRender(int channel, double start, double secPerSample, float[] samples);
    }

    interface OnsetListener {
        void onOnset(double timeSeconds);
    }

    interface TapeStateListener {
        void onTapeState(TapeCore.State state);
    }

    interface VuListener {
        void onVu(int inputLeft, int inputRight, int outputLeft, int outputRight);
    }

    private static final class Cmd {
        final int type;
        final String name;
        final double a;
        final double b;
        final int voice;
        final float[] samples;
        final int sourceRate;
        final double sourceStart;
        final double destinationStart;
        final double duration;
        final double fadeTime;
        final double preserve;
        final boolean reverse;
        final double extraA;
        final double extraB;
        final java.util.concurrent.CountDownLatch completion;

        Cmd(int type, String name, double a, double b) {
            this(type, name, a, b, 0, null, 0, 0, 0, 0, 0, 0, false, 0, 0);
        }

        Cmd(int type, String name, double a, double b, int voice, float[] samples, int sourceRate,
            double sourceStart, double destinationStart, double duration) {
            this(type, name, a, b, voice, samples, sourceRate, sourceStart, destinationStart, duration, 0, 0, false, 0, 0, null);
        }

        Cmd(int type, String name, double a, double b, int voice, float[] samples, int sourceRate,
            double sourceStart, double destinationStart, double duration, double fadeTime, double preserve, boolean reverse) {
            this(type, name, a, b, voice, samples, sourceRate, sourceStart, destinationStart,
                    duration, fadeTime, preserve, reverse, 0, 0, null);
        }

        Cmd(int type, String name, double a, double b, int voice, float[] samples, int sourceRate,
            double sourceStart, double destinationStart, double duration, double fadeTime,
            double preserve, boolean reverse, double extraA, double extraB) {
            this(type, name, a, b, voice, samples, sourceRate, sourceStart, destinationStart,
                    duration, fadeTime, preserve, reverse, extraA, extraB, null);
        }

        Cmd(int type, String name, double a, double b, int voice, float[] samples, int sourceRate,
            double sourceStart, double destinationStart, double duration, double fadeTime,
            double preserve, boolean reverse, double extraA, double extraB,
            java.util.concurrent.CountDownLatch completion) {
            this.type = type;
            this.name = name;
            this.a = a;
            this.b = b;
            this.voice = voice;
            this.samples = samples;
            this.sourceRate = sourceRate;
            this.sourceStart = sourceStart;
            this.destinationStart = destinationStart;
            this.duration = duration;
            this.fadeTime = fadeTime;
            this.preserve = preserve;
            this.reverse = reverse;
            this.extraA = extraA;
            this.extraB = extraB;
            this.completion = completion;
        }
    }

    private final Context ctx;
    private final ConcurrentLinkedQueue<Cmd> queue = new ConcurrentLinkedQueue<Cmd>();
    private SynthCore core;
    private SoftcutCore softcut;
    private volatile TapeCore tape;
    private volatile double tapeCutLevel = 0.0;
    private volatile double masterOutputLevel = 1.0;
    private volatile double engineOutputLevel = 1.0;
    private volatile double softcutMasterLevel = 1.0;
    private volatile double inputAdcLevel = 1.0;
    private volatile double monitorLevel = 0.0;
    private volatile boolean tapeCutLevelPending = false;
    private final Object tapeRoutingLock = new Object();
    private boolean nativeSoftcutHandoffInProgress = false;
    private AudioInput input;
    private final AudioPollMetrics pollMetrics = new AudioPollMetrics();
    private volatile long faustEffectsHandle = 0L;
    private volatile boolean reverbEnabled = false;
    private volatile boolean compressorEnabled = false;
    private final AudioVuMeter vuMeter = new AudioVuMeter();
    private int sampleRate = 48000;
    private int frames = 256;
    private volatile boolean running = false;
    private Thread thread;
    private AudioTrack track;
    private volatile String lastError = null;
    private volatile SoftcutRenderListener renderListener;
    private volatile OnsetListener onsetListener;
    private volatile TapeStateListener tapeStateListener;
    private volatile VuListener vuListener;
    private final SimpleOnsetDetector simpleOnsetDetector = new SimpleOnsetDetector();
    private volatile boolean nativeSoftcutJavaEnabled = true;
    private final Object outputOwnershipLock = new Object();
    private final AtomicLong javaFramesWritten = new AtomicLong(0L);
    private volatile int javaMuteDrainTargetFrame = 0;
    private final SoftcutOutputOwnership softcutOutputOwnership;
    private final SoftcutPhaseDiagnostics[] nativePhaseDiagnostics = {
            new SoftcutPhaseDiagnostics(), new SoftcutPhaseDiagnostics(),
            new SoftcutPhaseDiagnostics(), new SoftcutPhaseDiagnostics(),
            new SoftcutPhaseDiagnostics(), new SoftcutPhaseDiagnostics()
    };
    private final SoftcutPhaseSupervisor nativePhaseSupervisor = new SoftcutPhaseSupervisor();
    private final SoftcutRecorderCursorMonitor[] nativeRecorderCursorMonitors = {
            new SoftcutRecorderCursorMonitor(), new SoftcutRecorderCursorMonitor()
    };

    AudioEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.softcutOutputOwnership = new SoftcutOutputOwnership(new SoftcutOutputOwnership.Gate() {
            @Override public void setJavaEnabled(boolean enabled) {
                 synchronized (outputOwnershipLock) {
                    nativeSoftcutJavaEnabled = enabled;
                    if (!enabled) javaMuteDrainTargetFrame = (int) javaFramesWritten.get();
                }
            }
            @Override public boolean awaitJavaDrain() {
                final AudioTrack current = track;
                if (current == null) return true;
                final int target = javaMuteDrainTargetFrame;
                final long deadline = System.nanoTime() + 1_000_000_000L;
                while ((target - current.getPlaybackHeadPosition()) > 0) {
                    if (!running || current.getPlayState() != AudioTrack.PLAYSTATE_PLAYING)
                        return false;
                    if (System.nanoTime() >= deadline) {
                        AppLog.e("Softcut handoff: timeout aguardando drenagem do AudioTrack Java.");
                        return false;
                    }
                    try { Thread.sleep(1); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
            @Override public boolean setNativeEnabled(boolean enabled) {
                // If the native bridge is unavailable, no Oboe Softcut callback can
                // produce audio; treat that as a confirmed-mute state so Java remains usable.
                if (!enabled && !SuperColliderBridge.isAvailable()) return true;
                return SuperColliderBridge.setSoftcutOutputEnabled(enabled);
            }
        });
    }

    boolean setNativeSoftcutOutputOwner(boolean enabled, boolean nativeReady, boolean routeValidated) {
        if (!enabled) return softcutOutputOwnership.handoffToJava();
        synchronized (tapeRoutingLock) {
            TapeCore currentTape = tape;
            if (tapeCutLevel > 0 || tapeCutLevelPending || nativeSoftcutHandoffInProgress
                    || (currentTape != null && currentTape.hasOpenRecordSession())
                    || !nativeReady || !routeValidated || !SuperColliderSoftcutBuffers.isReady()
                    || !SuperColliderSoftcutBuffers.isNativeRoutingValidated()) return false;
            nativeSoftcutHandoffInProgress = true;
        }
        try {
            return softcutOutputOwnership.handoffToNative(nativeReady, routeValidated);
        } finally {
            synchronized (tapeRoutingLock) {
                nativeSoftcutHandoffInProgress = false;
            }
        }
    }

    boolean isRunning() {
        return running;
    }

    void setOnsetListener(OnsetListener listener) {
        onsetListener = listener;
    }

    void setTapeStateListener(TapeStateListener listener) {
        tapeStateListener = listener;
        publishTapeState();
    }

    void setVuListener(VuListener listener) {
        vuListener = listener;
    }

    TapeCore.State tapeState() {
        TapeCore current = tape;
        return current == null ? new TapeCore.State(0, 0.0, 0.0, 0, 0.0, true, null, null)
                : current.getState();
    }

    private void publishTapeState() {
        TapeStateListener callback = tapeStateListener;
        if (callback != null) callback.onTapeState(tapeState());
    }

    /** Reinicia a captura após a permissão de microfone ser concedida em runtime. */
    synchronized boolean startInput() {
        if (!running || input == null) return false;
        return input.isRunning() || input.start();
    }

    /** Alterna entre a captura Oboe compartilhada com scsynth e o fallback AudioRecord. */
    synchronized void setNativeInputActive(boolean active) {
        if (!running || input == null) return;
        if (active) {
            if (input.isRunning()) input.stop();
        } else if (!input.isRunning()) {
            input.start();
        }
    }


    String getLastError() {
        return lastError;
    }

    double pollValue(String name) {
        if (("cpu_avg".equals(name) || "cpu_peak".equals(name))
                && SuperColliderBridge.isAvailable()) {
            float[] cpu = new float[2];
            if (SuperColliderBridge.readCpuMetrics(cpu)) {
                float value = cpu["cpu_avg".equals(name) ? 0 : 1];
                if (Float.isFinite(value) && value >= 0f) return value;
            }
        }
        if (("amp_out_l".equals(name) || "amp_out_r".equals(name))
                && SuperColliderBridge.isAvailable()) {
            // Snapshot indices 10/11 are final left/right PCM peaks after the Oboe output gate.
            // This keeps Norns poll meters meaningful for native engines that bypass AudioTrack.
            float[] diagnostics = new float[54];
            if (SuperColliderBridge.readSoftcutDiagnostics(diagnostics)) {
                float value = diagnostics["amp_out_l".equals(name) ? 10 : 11];
                if (Float.isFinite(value) && value >= 0f) return value;
            }
        }
        return pollMetrics.value(name);
    }

    int getSampleRate() {
        return sampleRate;
    }

    /** Enable/disable the mono ADC pitch estimator, matching audio.pitch_on/off. */
    void setPitchAnalysisEnabled(boolean enabled) {
        pollMetrics.setPitchAnalysisEnabled(enabled);
    }

    synchronized boolean start() {
        if (running) return true;
        Thread previousThread = thread;
        if (previousThread != null && previousThread.isAlive()) {
            lastError = "A saída de áudio anterior ainda está encerrando.";
            AppLog.e("Áudio: nova sessão bloqueada até a thread anterior liberar a saída.");
            return false;
        }
        thread = null;
        TapeCore previousTape = tape;
        if (previousTape != null && !previousTape.isRecordFinalized()) {
            lastError = "Tape ainda está finalizando a gravação anterior.";
            AppLog.e(lastError);
            return false;
        }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                String sr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                String fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
                if (sr != null) sampleRate = Integer.parseInt(sr.trim());
                if (fpb != null) frames = Math.max(128, Math.min(1024, Integer.parseInt(fpb.trim())));
            }
        } catch (RuntimeException e) {
            sampleRate = 48000;
            frames = 256;
        }
        if (sampleRate < 8000 || sampleRate > 192000) sampleRate = 48000;
        // blocos de ~5 ms
        while (frames < sampleRate / 200) frames *= 2;
        releaseFaustEffects();
        reverbEnabled = false;
        compressorEnabled = false;
        faustEffectsHandle = NornsFaustEffects.create(sampleRate, frames);
        core = new SynthCore(sampleRate);
        vuMeter.reset();
        softcut = new SoftcutCore(sampleRate);
        softcut.setMasterLevel(softcutMasterLevel);
        tape = new TapeCore(sampleRate);
        tapeCutLevel = 0.0;
        tapeCutLevelPending = false;
        input = new AudioInput(ctx, sampleRate);
        if (!input.start()) AppLog.i("Softcut: entrada indisponível; gravação externa requer permissão de microfone.");
        queue.clear();
        try {
            int minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT);
            int bytes = Math.max(minBytes > 0 ? minBytes : 0, frames * 2 * 4 * 3);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("AudioTrack não foi inicializado");
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            AppLog.e("Áudio: não foi possível abrir a saída (" + t + ")");
            AudioTrack failedTrack = track;
            track = null;
            if (failedTrack != null) {
                try { failedTrack.release(); } catch (RuntimeException ignored) { }
            }
            releaseFaustEffects();
            TapeCore failedTape = tape;
            if (failedTape != null) {
                failedTape.playStop();
                failedTape.recordStop();
            }
            if (input != null) input.stop();
            return false;
        }
        lastError = null;
        javaFramesWritten.set(0L);
        javaMuteDrainTargetFrame = 0;
        running = true;
        final AudioTrack t = track;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(t);
            }
        }, "norns-audio");
        try {
            thread.start();
        } catch (Throwable startFailure) {
            running = false;
            thread = null;
            lastError = "Não foi possível iniciar a thread de áudio: " + startFailure;
            AppLog.e(lastError);
            releaseFaustEffects();
            TapeCore currentTape = tape;
            if (currentTape != null) {
                currentTape.playStop();
                currentTape.recordStop();
            }
            if (input != null) input.stop();
            try { t.release(); } catch (RuntimeException ignored) { }
            track = null;
            return false;
        }
        AppLog.i("Áudio iniciado: " + sampleRate + " Hz, bloco de " + frames + " amostras");
        return true;
    }

    synchronized void stop() {
        running = false;
        // Do not leave Oboe Softcut audible after the Java AudioTrack lifecycle ends.
        // handoffToJava mutes native output and waits for its callback acknowledgement
        // before changing ownership; on failure Java remains disabled (fail closed).
        if (!softcutOutputOwnership.handoffToJava()) {
            AppLog.e("Softcut: não foi possível confirmar o mute nativo durante a parada; saída Java mantida desativada.");
        }
        TapeCore currentTape = tape;
        if (currentTape != null) {
            currentTape.playStop();
            currentTape.recordStop();
        }
        if (input != null) input.stop();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                lastError = "A thread de áudio ainda está encerrando; uma nova sessão foi bloqueada por segurança.";
                AppLog.e(lastError);
            } else if (thread == t) {
                thread = null;
            }
        }
        if (t == null || !t.isAlive()) releaseFaustEffects();
    }

    private void releaseFaustEffects() {
        long handle = faustEffectsHandle;
        faustEffectsHandle = 0L;
        if (handle != 0L) NornsFaustEffects.destroy(handle);
    }

    private void loop(AudioTrack t) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException e) {
            // segue com a prioridade padrão
        }
        float[] buf = new float[frames * 2];
        float[] inputBlock = new float[frames];
        float[] engineBlock = new float[frames];
        float[] tapeBlock = new float[frames];
        float[] softcutReverbBlock = new float[frames * 2];
        ByteBuffer fxMasterBytes = ByteBuffer.allocateDirect(frames * 2 * Float.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer fxMonitorBytes = ByteBuffer.allocateDirect(frames * Float.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer fxEngineBytes = ByteBuffer.allocateDirect(frames * Float.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer fxTapeBytes = ByteBuffer.allocateDirect(frames * Float.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer fxSoftcutBytes = ByteBuffer.allocateDirect(frames * 2 * Float.BYTES).order(ByteOrder.nativeOrder());
        FloatBuffer fxMaster = fxMasterBytes.asFloatBuffer();
        FloatBuffer fxMonitor = fxMonitorBytes.asFloatBuffer();
        FloatBuffer fxEngine = fxEngineBytes.asFloatBuffer();
        FloatBuffer fxTape = fxTapeBytes.asFloatBuffer();
        FloatBuffer fxSoftcut = fxSoftcutBytes.asFloatBuffer();
        long lastTapeStatePublishNanos = 0L;
        long lastVuPublishNanos = 0L;
        try {
            t.play();
            while (running) {
                long renderStartNanos = System.nanoTime();
                synchronized (outputOwnershipLock) {
                    drain();
                    core.render(buf, frames);
                    AudioEngineGain.apply(buf, engineOutputLevel);
                    // Capture the engine-only block before Softcut is mixed into the master output.
                    // This prevents Softcut playback from feeding itself through the engine send.
                    for (int i = 0; i < frames; i++)
                        engineBlock[i] = (buf[2 * i] + buf[2 * i + 1]) * 0.5f;
                    if (!SuperColliderBridge.readInput(inputBlock, frames)) {
                        if (input != null) input.read(inputBlock, frames);
                        else java.util.Arrays.fill(inputBlock, 0f);
                    }
                    AudioInputGain.apply(inputBlock, inputAdcLevel);
                    if (core.getEngine() == SynthCore.ENGINE_SIMPLE_DELAY
                            && simpleOnsetDetector.process(inputBlock, frames, sampleRate,
                                    core.getSimpleDelayThresholdDb())) {
                        OnsetListener callback = onsetListener;
                        if (callback != null) callback.onOnset(System.nanoTime() * 1.0e-9);
                    }
                    TapeCore currentTape = tape;
                    if (currentTape != null) currentTape.render(buf, frames, tapeBlock);
                    if (currentTape != null && renderStartNanos - lastTapeStatePublishNanos >= 100_000_000L) {
                        lastTapeStatePublishNanos = renderStartNanos;
                        publishTapeState();
                    }
                    java.util.Arrays.fill(softcutReverbBlock, 0f);
                    if (nativeSoftcutJavaEnabled)
                        softcut.render(buf, frames, sampleRate, inputBlock, engineBlock,
                                tapeBlock, softcutReverbBlock);
                    AudioMonitorMix.apply(inputBlock, buf, monitorLevel);
                    long fxHandle = faustEffectsHandle;
                    if (fxHandle != 0L && (reverbEnabled || compressorEnabled)) {
                        fxMaster.clear();
                        fxMaster.put(buf, 0, buf.length);
                        fxMonitor.clear();
                        fxMonitor.put(inputBlock, 0, frames);
                        fxEngine.clear();
                        fxEngine.put(engineBlock, 0, frames);
                        fxTape.clear();
                        fxTape.put(tapeBlock, 0, frames);
                        fxSoftcut.clear();
                        fxSoftcut.put(softcutReverbBlock, 0, softcutReverbBlock.length);
                        if (NornsFaustEffects.process(fxHandle, fxMasterBytes,
                                fxMonitorBytes, fxEngineBytes, fxTapeBytes,
                                fxSoftcutBytes, frames)) {
                            fxMaster.position(0);
                            fxMaster.get(buf, 0, buf.length);
                        }
                    }
                    for (int i = 0; i < buf.length; i++) {
                        if (!Float.isFinite(buf[i])) buf[i] = 0f;
                        else if (buf[i] > 1f) buf[i] = 1f;
                        else if (buf[i] < -1f) buf[i] = -1f;
                    }
                    if (currentTape != null) currentTape.recordBlock(buf, frames);
                    AudioOutputGain.apply(buf, masterOutputLevel);
                    vuMeter.update(inputBlock, buf, frames, sampleRate);
                    pollMetrics.update(inputBlock, buf, frames, sampleRate,
                            System.nanoTime() - renderStartNanos);
                    if (renderStartNanos - lastVuPublishNanos >= 50_000_000L) {
                        lastVuPublishNanos = renderStartNanos;
                        VuListener vuCallback = vuListener;
                        if (vuCallback != null) {
                            vuCallback.onVu(vuMeter.inputLeft(), vuMeter.inputRight(),
                                    vuMeter.outputLeft(), vuMeter.outputRight());
                        }
                    }
                    int w = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                    if (w > 0) javaFramesWritten.addAndGet(w / 2L);
                    if (w < 0) {
                        AppLog.e("Áudio: erro de escrita " + w);
                        break;
                    }
                }
            }
        } catch (Throwable ex) {
            AppLog.e("Áudio: " + ex);
        } finally {
            running = false;
            // Also cover unexpected AudioTrack/native-loop failures: shutdown must
            // not leave Oboe Softcut owning an output after the Java writer exits.
            if (!softcutOutputOwnership.handoffToJava()) {
                AppLog.e("Softcut: falha ao confirmar mute nativo no encerramento da thread; fallback Java permanece sem escrita.");
            }
            TapeCore currentTape = tape;
            if (currentTape != null) {
                currentTape.playStop();
                currentTape.recordStop();
            }
            if (input != null) input.stop();
            try {
                t.stop();
            } catch (RuntimeException e) {
                // ignora
            }
            t.release();
        }
    }

    private void drain() {
        Cmd c;
        while ((c = queue.poll()) != null) {
            if (c.type == CMD_ENGINE) {
                simpleOnsetDetector.reset();
                boolean known = core.setEngine(c.name);
                if (!known) AppLog.i("Engine '" + c.name + "' não está implementada; saída silenciada para evitar substituir por PolyPerc.");
            } else if (c.type == CMD_COMMAND) {
                core.command(c.name, c.a, c.b);
            } else if (c.type == CMD_ALL_OFF) {
                core.allNotesOff();
            } else if (c.type == CMD_SOFTCUT) {
                if ("buffer_clear_region".equals(c.name)) {
                    softcut.clearBuffer(c.voice, c.a, c.b, c.fadeTime, c.preserve);
                    syncNativeSoftcutBuffer(c.voice, c.a, c.b, true);
                } else {
                    softcut.command(c.voice, c.name, c.a);
                    if (c.voice == 0 && "tape_level".equals(c.name)) tapeCutLevelPending = false;
                }
            } else if (c.type == CMD_SOFTCUT_BUFFER) {
                boolean hadBuffer = softcut.bufferLength(c.voice) > 0;
                softcut.bufferRead(c.voice, c.samples, c.sourceRate, sampleRate,
                        c.sourceStart, c.destinationStart, c.duration, c.preserve, c.fadeTime);
                syncNativeSoftcutBuffer(c.voice, c.destinationStart, c.duration, hadBuffer);
            } else if (c.type == CMD_SOFTCUT_COPY) {
                boolean hadDestination = softcut.bufferLength(c.sourceRate) > 0;
                softcut.copyBuffer(c.voice, c.sourceRate, c.sourceStart, c.destinationStart,
                        c.duration, c.fadeTime, c.preserve, c.reverse);
                syncNativeSoftcutBuffer(c.sourceRate, c.destinationStart, c.duration, hadDestination);
            } else if (c.type == CMD_SOFTCUT_SYNC) {
                softcut.voiceSync(c.voice, c.sourceRate, c.a);
            } else if (c.type == CMD_SOFTCUT_RENDER) {
                final SoftcutCore.RenderResult rendered = softcut.renderBufferResult(c.voice, c.a, c.b, c.sourceRate);
                final SoftcutRenderListener callback = renderListener;
                if (callback != null) {
                    final int channel = c.voice;
                    final double start = c.a;
                    callback.onRender(channel, start, rendered.secPerSample, rendered.samples);
                }
            } else if (c.type == CMD_SOFTCUT_NATIVE_READBACK) {
                try {
                    softcut.writeBufferRegion(c.voice, (int)c.a, c.samples);
                } finally {
                    if (c.completion != null) c.completion.countDown();
                }
            } else if (c.type == CMD_SOFTCUT_NATIVE_RECORD_START) {
                int startFrame = (int)Math.max(0, Math.min(16_777_215L, Math.round(c.sourceStart * sampleRate)));
                int endFrame = (int)Math.max(startFrame + 1L,
                        Math.min(16_777_216L, Math.round(c.destinationStart * sampleRate)));
                int requiredEndFrame = (int)Math.max(endFrame,
                        Math.min(16_777_216L, Math.round(c.b * sampleRate)));
                int frames = softcut.ensureBufferLength(c.voice, requiredEndFrame);
                if (frames >= requiredEndFrame) {
                    if (SuperColliderSoftcutBuffers.needsFullSnapshot(c.voice, frames)) {
                        float[] snapshot = softcut.snapshotBuffer(c.voice, 0, -1);
                        SuperColliderSoftcutBuffers.sync(c.voice, frames, 0, snapshot, snapshot);
                    }
                    SuperColliderSoftcutBuffers.prepareRecording(c.voice, frames, c.sourceRate,
                            startFrame, endFrame, c.a, c.duration, c.fadeTime, c.preserve, c.extraA, c.extraB);
                } else {
                    AppLog.e("Softcut nativo: não foi possível reservar o buffer para gravação.");
                }
            } else if (c.type == CMD_FX_ENABLED) {
                NornsFaustEffects.setEnabled(faustEffectsHandle, c.voice, c.a > 0.5);
            } else if (c.type == CMD_FX_PARAMETER) {
                NornsFaustEffects.setParameter(faustEffectsHandle, c.voice, c.sourceRate, c.a);
            } else if (c.type == CMD_FX_MIX) {
                NornsFaustEffects.setMix(faustEffectsHandle, c.voice, c.a);
            } else if (c.type == CMD_FX_SEND) {
                NornsFaustEffects.setReverbSendLevel(faustEffectsHandle, c.sourceRate, c.a);
            } else if (c.type == CMD_SOFTCUT_EXPORT) {
                final float[] snapshot = c.voice == 0
                        ? softcut.snapshotStereo(c.a, c.b)
                        : softcut.snapshotBuffer(c.voice, c.a, c.b);
                final String path = c.name;
                final int channels = c.voice == 0 ? 2 : 1;
                final int exportRate = sampleRate;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            WavWriter.write(new java.io.File(path), snapshot, exportRate, channels);
                            AppLog.i("Softcut: WAV exportado (" + path + ")");
                        } catch (Exception e) {
                            AppLog.e("Softcut: falha ao exportar WAV (" + e.getMessage() + ")");
                        }
                    }
                }, "norns-softcut-export").start();
            }
        }
    }

    private void syncNativeSoftcutBuffer(int channel, double startSec, double durationSec, boolean preserveExisting) {
        if (durationSec == 0 || softcut == null || !SuperColliderBridge.isAvailable()) return;
        int frames = softcut.bufferLength(channel);
        if (frames < 1) return;
        int start = (int)Math.max(0, Math.min(frames,
                Math.round(Math.max(0, startSec) * sampleRate)));
        float[] region = softcut.snapshotBuffer(channel, start / (double)sampleRate, durationSec);
        float[] complete = preserveExisting && SuperColliderSoftcutBuffers.needsFullSnapshot(channel, frames)
                ? softcut.snapshotBuffer(channel, 0, -1) : null;
        SuperColliderSoftcutBuffers.sync(channel, frames, start, region, complete);
    }

    void setEngine(String name) {
        queue.add(new Cmd(CMD_ENGINE, name, 0, 0));
    }

    void command(String name, double a, double b) {
        queue.add(new Cmd(CMD_COMMAND, name, a, b));
    }

    void allNotesOff() {
        queue.add(new Cmd(CMD_ALL_OFF, null, 0, 0));
    }

    double softcutPosition(int voice) {
        SoftcutCore current = softcut;
        return current == null ? 0 : current.position(voice);
    }

    SoftcutPhaseDiagnostics.Snapshot sampleNativeSoftcutPhase(int voice, double javaPosition,
            double nativePosition, double loopStart, double loopEnd, boolean looping) {
        if (voice < 1 || voice > nativePhaseDiagnostics.length) return null;
        return nativePhaseDiagnostics[voice - 1].record(javaPosition,nativePosition,loopStart,loopEnd,looping);
    }
    void resetNativeSoftcutPhaseDiagnostics() {
        for (SoftcutPhaseDiagnostics diagnostics : nativePhaseDiagnostics) diagnostics.reset();
        nativePhaseSupervisor.reset();
        for (SoftcutRecorderCursorMonitor monitor : nativeRecorderCursorMonitors) monitor.reset();
    }

    SoftcutRecorderCursorMonitor.Snapshot sampleNativeSoftcutRecorderCursor(
            int channel, double javaPosition, double nativePosition,
            double loopStart, double loopEnd, boolean looping, boolean recording) {
        if (channel < 1 || channel > nativeRecorderCursorMonitors.length) return null;
        return nativeRecorderCursorMonitors[channel - 1].record(
                javaPosition, nativePosition, loopStart, loopEnd, looping, recording,
                sampleRate, SuperColliderBridge.blockSize());
    }

    boolean reanchorNativeSoftcutRecorderCursor(int channel, int recorderNode, double position) {
        return SuperColliderSoftcutBuffers.reanchorRecordingCursor(channel, recorderNode, position);
    }

    SoftcutPhaseSupervisor.SnapshotStatus observeNativeSoftcutSnapshot(float sequence) {
        return nativePhaseSupervisor.observe(sequence, System.nanoTime());
    }

    double nativeSoftcutPhaseReanchorThresholdSeconds() {
        return SoftcutPhaseSupervisor.reanchorThresholdSeconds(sampleRate, SuperColliderBridge.blockSize());
    }

    boolean shouldReanchorNativeSoftcutPhase(double signedErrorSeconds) {
        return SoftcutPhaseSupervisor.shouldReanchor(
                signedErrorSeconds, sampleRate, SuperColliderBridge.blockSize());
    }
    void softcutVoiceSync(int destination, int source, double offset) {
        queue.add(new Cmd(CMD_SOFTCUT_SYNC, null, offset, 0, destination, null, source, 0, 0, 0));
    }

    void softcutCommand(int voice, String name, double value) {
        if (voice == 0 && "tape_level".equals(name)) {
            setTapeCutLevel(value);
            return;
        }
        queue.add(new Cmd(CMD_SOFTCUT, name, value, 0, voice, null, 0, 0, 0, 0));
    }

    boolean setTapeCutLevel(double level) {
        if (Double.isNaN(level) || Double.isInfinite(level)) return false;
        if (!running && !start()) return false;
        double bounded = Math.max(0, Math.min(1, level));
        synchronized (tapeRoutingLock) {
            if (bounded > 0 && (!nativeSoftcutJavaEnabled || nativeSoftcutHandoffInProgress)) {
                AppLog.e("Tape->Softcut: rota nativa não validada; mantenha a saída Softcut Java.");
                return false;
            }
            tapeCutLevel = bounded;
            tapeCutLevelPending = true;
            queue.add(new Cmd(CMD_SOFTCUT, "tape_level", bounded, 0, 0, null, 0, 0, 0, 0));
        }
        return true;
    }

    boolean tapePlayOpen(WavReader.StereoData data) { return tapePlayOpen(data, null); }

    boolean tapePlayOpen(WavReader.StereoData data, String fileName) {
        if (!running && !start()) return false;
        TapeCore current = tape;
        boolean opened = current != null && current.openPlayback(data, fileName);
        if (opened) publishTapeState();
        return opened;
    }

    boolean tapePlayOpen(WavReader.MappedStereoData data) { return tapePlayOpen(data, null); }

    boolean tapePlayOpen(WavReader.MappedStereoData data, String fileName) {
        if (!running && !start()) return false;
        TapeCore current = tape;
        boolean opened = current != null && current.openPlayback(data, fileName);
        if (opened) publishTapeState();
        return opened;
    }

    void tapePlayStart() { TapeCore current = tape; if (current != null) current.playStart(); publishTapeState(); }
    void tapePlayPause(boolean paused) { TapeCore current = tape; if (current != null) current.playPause(paused); publishTapeState(); }
    void tapePlayStop() { TapeCore current = tape; if (current != null) current.playStop(); publishTapeState(); }
    void tapePlayLoop(boolean enabled) { TapeCore current = tape; if (current != null) current.setLoop(enabled); publishTapeState(); }
    void tapeLevel(double level) {
        if (!running && !start()) return;
        TapeCore current = tape;
        if (current != null) current.setPlaybackLevel(level);
    }

    boolean setMasterOutputLevel(double level) {
        if (Double.isNaN(level) || Double.isInfinite(level) || level < 0.0 || level > 1.0)
            return false;
        masterOutputLevel = level;
        return true;
    }

    boolean setEngineLevel(double level) {
        if (!Double.isFinite(level) || level < 0.0 || level > 1.0) return false;
        engineOutputLevel = level;
        return true;
    }

    boolean setSoftcutMasterLevel(double level) {
        if (!Double.isFinite(level) || level < 0.0 || level > 1.0 || !nativeSoftcutJavaEnabled)
            return false;
        softcutMasterLevel = level;
        SoftcutCore current = softcut;
        return current == null || current.setMasterLevel(level);
    }

    boolean setInputAdcLevel(double level) {
        if (Double.isNaN(level) || Double.isInfinite(level) || level < 0.0 || level > 1.0)
            return false;
        inputAdcLevel = level;
        return true;
    }

    boolean setMonitorLevel(double level) {
        if (Double.isNaN(level) || Double.isInfinite(level)) return false;
        monitorLevel = Math.max(0.0, Math.min(1.0, level));
        return true;
    }

    boolean setFxEnabled(int effect, boolean enabled) {
        if (effect != NornsFaustEffects.COMPRESSOR && effect != NornsFaustEffects.REVERB) return false;
        if (faustEffectsHandle == 0L) return false;
        if (effect == NornsFaustEffects.REVERB) reverbEnabled = enabled;
        else compressorEnabled = enabled;
        queue.add(new Cmd(CMD_FX_ENABLED, null, enabled ? 1.0 : 0.0, 0.0,
                effect, null, 0, 0, 0, 0));
        return true;
    }

    boolean setFxParameter(int effect, String name, double value) {
        int parameter = NornsFaustEffects.parameterId(effect, name);
        if (parameter < 0 || !Double.isFinite(value) || faustEffectsHandle == 0L) return false;
        queue.add(new Cmd(CMD_FX_PARAMETER, null, value, 0.0,
                effect, null, parameter, 0, 0, 0));
        return true;
    }

    boolean setFxMix(int effect, double value) {
        if ((effect != NornsFaustEffects.COMPRESSOR && effect != NornsFaustEffects.REVERB)
                || !Double.isFinite(value) || faustEffectsHandle == 0L) return false;
        queue.add(new Cmd(CMD_FX_MIX, null, value, 0.0,
                effect, null, 0, 0, 0, 0));
        return true;
    }

    boolean setReverbSendLevel(String source, double value) {
        int sourceId = NornsFaustEffects.reverbSendId(source);
        if (sourceId < 0 || !Double.isFinite(value) || faustEffectsHandle == 0L) return false;
        queue.add(new Cmd(CMD_FX_SEND, null, Math.max(0.0, Math.min(1.0, value)), 0.0,
                NornsFaustEffects.REVERB, null, sourceId, 0, 0, 0));
        return true;
    }

    /** The Android capture source is mono; only the upstream mono monitor mode is supported. [run-ci] */
    boolean setMonitorStereo(boolean stereo) {
        if (stereo) {
            AppLog.e("Audio monitor estéreo indisponível: a captura Android atual fornece um único canal ADC.");
            return false;
        }
        return true;
    }

    boolean tapeRecordOpen(java.io.File file) {
        if (!running && !start()) return false;
        synchronized (tapeRoutingLock) {
            if (!nativeSoftcutJavaEnabled || nativeSoftcutHandoffInProgress) {
                AppLog.e("Tape: gravação exige a saída Softcut Java até validar a captura nativa do mix completo.");
                return false;
            }
            TapeCore current = tape;
            boolean opened = current != null && current.openRecord(file);
            if (opened) publishTapeState();
            return opened;
        }
    }
    boolean tapeRecordStart() {
        synchronized (tapeRoutingLock) {
            if (!nativeSoftcutJavaEnabled || nativeSoftcutHandoffInProgress) return false;
            TapeCore current = tape;
            boolean started = current != null && current.recordStart();
            if (started) publishTapeState();
            return started;
        }
    }
    void tapeRecordPause(boolean paused) { TapeCore current = tape; if (current != null) current.recordPause(paused); publishTapeState(); }
    void tapeRecordStop() { TapeCore current = tape; if (current != null) current.recordStop(); publishTapeState(); }

    void softcutBuffer(int bufferChannel, float[] samples, int sourceRate,
                       double sourceStart, double destinationStart, double duration) {
        softcutBuffer(bufferChannel, samples, sourceRate, sourceStart, destinationStart, duration, 0, 1);
    }

    void softcutBuffer(int bufferChannel, float[] samples, int sourceRate,
                       double sourceStart, double destinationStart, double duration,
                       double preserve, double mix) {
        queue.add(new Cmd(CMD_SOFTCUT_BUFFER, null, 0, 0, bufferChannel, samples, sourceRate,
                sourceStart, destinationStart, duration, mix, preserve, false));
    }

    void softcutBufferClear(int bufferChannel, double start, double duration) {
        softcutBufferClear(bufferChannel, start, duration, 0, 0);
    }

    void softcutBufferClear(int bufferChannel, double start, double duration, double fadeTime, double preserve) {
        queue.add(new Cmd(CMD_SOFTCUT, "buffer_clear_region", start, duration,
                bufferChannel, null, 0, 0, 0, 0, fadeTime, preserve, false));
    }

    /** Back-pressures the OSC worker until this bounded block has been applied by the audio thread. */
    private boolean enqueueNativeReadbackAndWait(int channel, int startFrame, float[] samples) {
        if (!running || samples == null || samples.length == 0) return false;
        java.util.concurrent.CountDownLatch applied = new java.util.concurrent.CountDownLatch(1);
        queue.add(new Cmd(CMD_SOFTCUT_NATIVE_READBACK, null, startFrame, 0,
                channel, samples, 0, 0, 0, 0, 0, 0, false, 0, 0, applied));
        try {
            return applied.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Prepares a mirrored buffer and queues native recording without blocking the audio thread. */
    void prepareNativeSoftcutRecording(int channel, int recorderNode, double startSec, double endSec,
                                       double position, double rate, double recLevel, double preLevel,
                                       double inputLevel, double recPreSlew, double requiredEndSec) {
        queue.add(new Cmd(CMD_SOFTCUT_NATIVE_RECORD_START, null, position, requiredEndSec, channel, null,
                recorderNode, startSec, endSec, rate, recLevel, preLevel, false,
                inputLevel, recPreSlew));
    }

    void requestNativeSoftcutReadbackSeconds(int channel, double startSec, double endSec) {
        int start = (int)Math.max(0, Math.min(16_777_215L, Math.round(startSec * sampleRate)));
        int end = (int)Math.max(start, Math.min(16_777_216L, Math.round(endSec * sampleRate)));
        if (end > start) requestNativeSoftcutReadback(channel, start, end - start);
    }

    void finishNativeSoftcutRecording(final int channel, int recorderNode,
                                      double startSec, double endSec) {
        final int start = (int)Math.max(0, Math.min(16_777_215L, Math.round(startSec * sampleRate)));
        int end = (int)Math.max(start, Math.min(16_777_216L, Math.round(endSec * sampleRate)));
        final int count = end - start;
        if (count < 1) return;
        SuperColliderSoftcutBuffers.finishRecording(channel, recorderNode, start, count,
                new SuperColliderSoftcutBuffers.ReadbackListener() {
                    @Override public boolean onReadback(int resultChannel, int resultStart, float[] samples) {
                        return enqueueNativeReadbackAndWait(resultChannel, resultStart, samples);
                    }
                });
    }

    /** Pulls native samples off-thread, then applies them only during audio queue drain. */
    void requestNativeSoftcutReadback(final int channel, final int startFrame, final int count) {
        SuperColliderSoftcutBuffers.readback(channel, startFrame, count,
                new SuperColliderSoftcutBuffers.ReadbackListener() {
                    @Override public boolean onReadback(int resultChannel, int resultStart, float[] samples) {
                        return enqueueNativeReadbackAndWait(resultChannel, resultStart, samples);
                    }
                });
    }

    void setSoftcutRenderListener(SoftcutRenderListener listener) {
        renderListener = listener;
    }

    void requestSoftcutRender(int channel, double start, double duration, int maxSamples) {
        queue.add(new Cmd(CMD_SOFTCUT_RENDER, null, start, duration, channel, null,
                maxSamples, 0, 0, 0));
    }

    void softcutBufferExport(String path, int channel, double start, double duration) {
        queue.add(new Cmd(CMD_SOFTCUT_EXPORT, path, start, duration, channel, null, 0, 0, 0, 0));
    }

    void softcutBufferCopy(int sourceChannel, int destinationChannel, double sourceStart,
                           double destinationStart, double duration, double fadeTime,
                           double preserve, boolean reverse) {
        queue.add(new Cmd(CMD_SOFTCUT_COPY, null, 0, 0, sourceChannel, null, destinationChannel,
                sourceStart, destinationStart, duration, fadeTime, preserve, reverse));
    }

    /** Toca um arpejo curto no PolyPerc para conferir a saída de áudio. */
    void playTest() {
        if (!start()) return;
        setEngine("PolyPerc");
        command("release", 0.6, 0);
        command("cutoff", 2500, 0);
        command("amp", 0.6, 0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                double[] hz = {261.63, 329.63, 392.0, 523.25};
                for (int i = 0; i < hz.length; i++) {
                    command("hz", hz[i], 0);
                    try {
                        Thread.sleep(220);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "norns-audio-test").start();
    }
}
