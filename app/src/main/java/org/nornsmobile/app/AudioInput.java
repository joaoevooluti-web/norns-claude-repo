package org.nornsmobile.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

/** Captura mono Android em ring buffer SPSC, sem bloquear a thread de áudio. */
final class AudioInput {
    private static final int RING_SIZE = 32768;
    private final Context context;
    private final int sampleRate;
    private final AudioInputRingBuffer ring = new AudioInputRingBuffer(RING_SIZE);
    private final AudioInputLifecycleGate lifecycle = new AudioInputLifecycleGate();
    private volatile AudioRecord recorder;
    private volatile Thread thread;

    AudioInput(Context context, int sampleRate) {
        this.context = context.getApplicationContext();
        this.sampleRate = sampleRate;
    }

    boolean isRunning() { return lifecycle.isActive(); }

    synchronized boolean start() {
        if (lifecycle.isActive()) return true;
        // Do not overlap a new recorder with a capture thread that did not exit
        // after a previous stop request.
        Thread previous = thread;
        if (previous != null && previous.isAlive()) return false;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        AudioRecord created = null;
        try {
            created = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, sampleRate / 5 * 2));
            if (created.getState() != AudioRecord.STATE_INITIALIZED) {
                created.release();
                return false;
            }
            created.startRecording();
            recorder = created;
            final long session = lifecycle.begin();
            final AudioRecord captureRecorder = created;
            Thread captureThread = new Thread(new Runnable() {
                @Override public void run() { captureLoop(captureRecorder, session); }
            }, "norns-audio-input");
            thread = captureThread;
            captureThread.start();
            AppLog.i("Softcut: entrada mono Android ativa (" + sampleRate + " Hz).");
            return true;
        } catch (SecurityException e) {
            AppLog.e("Softcut: permissão de microfone não concedida.");
        } catch (RuntimeException e) {
            AppLog.e("Softcut: falha ao iniciar entrada (" + e.getMessage() + ").");
        }
        lifecycle.stop();
        if (created != null) {
            try { created.stop(); } catch (RuntimeException ignored) {}
            try { created.release(); } catch (RuntimeException ignored) {}
        }
        if (recorder == created) recorder = null;
        return false;
    }

    private void captureLoop(AudioRecord source, long session) {
        try {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); } catch (RuntimeException ignored) {}
            short[] block = new short[512];
            while (lifecycle.isActive(session)) {
                int n;
                try { n = source.read(block, 0, block.length); }
                catch (RuntimeException e) { n = AudioRecord.ERROR_INVALID_OPERATION; }
                if (n > 0) {
                    if (lifecycle.isActive(session)) ring.offer(block, n);
                } else if (n < 0) {
                    AppLog.e("Softcut: entrada Android interrompida (" + n + ").");
                    break;
                }
            }
        } finally {
            lifecycle.finish(session);
            try { source.release(); } catch (RuntimeException ignored) {}
            if (recorder == source) recorder = null;
            if (thread == Thread.currentThread()) thread = null;
        }
    }

    void read(float[] destination, int count) {
        ring.read(destination, count);
    }

    synchronized void stop() {
        AudioRecord source = recorder;
        Thread captureThread = thread;
        lifecycle.stop();
        // stop() unblocks AudioRecord.read(); the capture thread owns release()
        // so the native recorder is never released while read() is in flight.
        if (source != null) {
            try { source.stop(); } catch (RuntimeException ignored) {}
        }
        if (captureThread != null && captureThread != Thread.currentThread()) {
            try { captureThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (captureThread == null && source != null) {
            try { source.release(); } catch (RuntimeException ignored) {}
            if (recorder == source) recorder = null;
        }
    }
}
