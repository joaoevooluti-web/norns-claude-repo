package org.nornsmobile.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Disk-backed Norns Tape transport.
 *
 * Playback is mapped or prepared before entering the real-time callback. Recording copies
 * bounded audio blocks to a worker queue; disk writes never run on the audio thread.
 */
final class TapeCore {
    private static final int MAX_QUEUE_BLOCKS = 32;
    private static final int MAX_PLAYBACK_FLOATS = 16_777_216;
    private static final long MAX_WAV_DATA_BYTES = 0xffffffffL - 36L;

    private final int outputRate;
    // Serializes writers of the odd/even generation marker; render never takes this lock.
    private final Object playbackMutationLock = new Object();
    private volatile float[] playbackStereo;
    private volatile WavReader.MappedStereoData mappedPlayback;
    private volatile int playbackRate;
    private volatile boolean playing;
    private volatile boolean playPaused;
    private volatile boolean loop = true;
    private volatile String playbackFile;
    private volatile String recordingFile;
    private volatile float playbackLevel = 1f;
    private volatile double playbackPosition;
    private volatile long playbackGeneration;
    private volatile TapeRecorder recorder;

    static final class State {
        final int playState;
        final double playPositionSeconds;
        final double playLengthSeconds;
        final int recordState;
        final double recordPositionSeconds;
        final boolean loopEnabled;
        final String playFile;
        final String recordFile;
        State(int playState, double playPositionSeconds, double playLengthSeconds,
              int recordState, double recordPositionSeconds, boolean loopEnabled,
              String playFile, String recordFile) {
            this.playState = playState;
            this.playPositionSeconds = playPositionSeconds;
            this.playLengthSeconds = playLengthSeconds;
            this.recordState = recordState;
            this.recordPositionSeconds = recordPositionSeconds;
            this.loopEnabled = loopEnabled;
            this.playFile = playFile;
            this.recordFile = recordFile;
        }
    }

    TapeCore(int outputRate) {
        this.outputRate = outputRate;
    }

    boolean openPlayback(WavReader.StereoData data) {
        return openPlayback(data, null);
    }

    boolean openPlayback(WavReader.StereoData data, String fileName) {
        if (data == null || data.samples == null || data.samples.length < 2
                || data.samples.length > MAX_PLAYBACK_FLOATS || (data.samples.length & 1) != 0
                || data.sampleRate < 8000 || data.sampleRate > 384000) {
            return false;
        }
        synchronized (playbackMutationLock) {
            playbackGeneration++;
            playing = false;
            playPaused = false;
            playbackPosition = 0;
            mappedPlayback = null;
            playbackStereo = data.samples;
            playbackRate = data.sampleRate;
            playbackFile = fileName;
            playbackGeneration++;
        }
        return true;
    }

    boolean openPlayback(WavReader.MappedStereoData data) {
        return openPlayback(data, null);
    }

    boolean openPlayback(WavReader.MappedStereoData data, String fileName) {
        if (data == null || data.data == null || data.frameCount < 1
                || data.sampleRate < 8000 || data.sampleRate > 384000) return false;
        synchronized (playbackMutationLock) {
            playbackGeneration++;
            playing = false;
            playPaused = false;
            playbackPosition = 0;
            playbackStereo = null;
            mappedPlayback = data;
            playbackRate = data.sampleRate;
            playbackFile = fileName;
            playbackGeneration++;
        }
        return true;
    }

    void playStart() {
        synchronized (playbackMutationLock) {
            float[] source = playbackStereo;
            WavReader.MappedStereoData mapped = mappedPlayback;
            if ((source != null && source.length >= 2) || (mapped != null && mapped.frameCount > 0)) {
                playbackGeneration++;
                playing = true;
                playPaused = false;
                playbackGeneration++;
            }
        }
    }

    void playPause(boolean paused) {
        synchronized (playbackMutationLock) {
            if (playbackStereo != null || mappedPlayback != null) {
                playbackGeneration++;
                playing = !paused;
                playPaused = paused;
                playbackGeneration++;
            }
        }
    }

    void playStop() {
        synchronized (playbackMutationLock) {
            playbackGeneration++;
            playing = false;
            playPaused = false;
            playbackPosition = 0;
            playbackGeneration++;
        }
    }

    void setLoop(boolean enabled) {
        loop = enabled;
    }

    void setPlaybackLevel(double level) {
        if (Double.isNaN(level) || Double.isInfinite(level)) return;
        playbackLevel = (float)Math.max(0, Math.min(2, level));
    }

    /** Mixes tape to the master output and exposes a separate mono send to Softcut. */
    void render(float[] master, int frames, float[] tapeMono) {
        if (tapeMono != null) Arrays.fill(tapeMono, 0, Math.min(frames, tapeMono.length), 0f);
        long renderGeneration = playbackGeneration;
        if ((renderGeneration & 1L) != 0L) return;
        float[] source = playbackStereo;
        WavReader.MappedStereoData mapped = mappedPlayback;
        if (!playing || (source == null && mapped == null) || master == null || tapeMono == null
                || renderGeneration != playbackGeneration) return;
        double position = playbackPosition;
        int sourceFrames = source != null ? source.length / 2 : mapped.frameCount;
        if (sourceFrames < 1 || outputRate < 1) {
            if (renderGeneration == playbackGeneration) playing = false;
            return;
        }
        double step = playbackRate / (double)outputRate;
        float gain = playbackLevel;
        int count = Math.min(frames, Math.min(tapeMono.length, master.length / 2));
        boolean ended = false;
        for (int i = 0; i < count && playing && renderGeneration == playbackGeneration; i++) {
            if (position >= sourceFrames || position < 0) {
                if (loop) {
                    position = ((position % sourceFrames) + sourceFrames) % sourceFrames;
                } else {
                    ended = true;
                    break;
                }
            }
            int a = (int)position;
            int b = Math.min(sourceFrames - 1, a + 1);
            double fraction = position - a;
            float left, right;
            if (source != null) {
                left = (float)(source[a * 2] + (source[b * 2] - source[a * 2]) * fraction);
                right = (float)(source[a * 2 + 1] + (source[b * 2 + 1] - source[a * 2 + 1]) * fraction);
            } else {
                float leftA = mapped.sample(a, 0), leftB = mapped.sample(b, 0);
                float rightA = mapped.sample(a, 1), rightB = mapped.sample(b, 1);
                left = (float)(leftA + (leftB - leftA) * fraction);
                right = (float)(rightA + (rightB - rightA) * fraction);
            }
            master[i * 2] += left * gain;
            master[i * 2 + 1] += right * gain;
            tapeMono[i] = (left + right) * 0.5f;
            position += step;
        }
        if (renderGeneration == playbackGeneration) {
            playbackPosition = position;
            if (ended) { playing = false; playPaused = false; }
        }
    }

    State getState() {
        float[] source = playbackStereo;
        WavReader.MappedStereoData mapped = mappedPlayback;
        int sourceFrames = source != null ? source.length / 2 : (mapped != null ? mapped.frameCount : 0);
        // Upstream norns values: EMPTY=0, READY=1, PLAYING=2, PAUSED=3.
        int playState = sourceFrames == 0 ? 0 : (playing ? 2 : (playPaused ? 3 : 1));
        double playLength = playbackRate > 0 ? sourceFrames / (double)playbackRate : 0.0;
        double playPosition = playbackRate > 0 ? playbackPosition / playbackRate : 0.0;
        TapeRecorder current = recorder;
        // Upstream norns values: EMPTY=0, READY=1, RECORDING=2, PAUSED=3.
        int recordState = current == null || current.isFinished() || current.isStopping() ? 0
                : (current.isRecording() ? 2 : (current.isPaused() ? 3 : 1));
        double recordPosition = current == null ? 0.0 : current.positionSeconds();
        return new State(playState, finiteNonNegative(playPosition), finiteNonNegative(playLength),
                recordState, finiteNonNegative(recordPosition), loop, playbackFile, recordingFile);
    }

    private static double finiteNonNegative(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) || value < 0.0 ? 0.0 : value;
    }

    synchronized boolean openRecord(File file) {
        TapeRecorder current = recorder;
        if (file == null || file.isDirectory() || (current != null && !current.isFinished())) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        recorder = new TapeRecorder(file, outputRate);
        recordingFile = file.getPath();
        return true;
    }

    synchronized boolean recordStart() {
        TapeRecorder current = recorder;
        return current != null && current.start();
    }

    void recordPause(boolean paused) {
        TapeRecorder current = recorder;
        if (current != null) current.setPaused(paused);
    }

    void recordStop() {
        TapeRecorder current = recorder;
        if (current != null) current.stop();
    }

    /** Called once per audio block after the complete master mix has been rendered. */
    void recordBlock(float[] master, int frames) {
        TapeRecorder current = recorder;
        if (current != null) current.offer(master, frames);
    }

    boolean isRecording() {
        TapeRecorder current = recorder;
        return current != null && current.isRecording();
    }

    boolean isRecordFinalized() {
        TapeRecorder current = recorder;
        return current == null || current.isFinished();
    }

    boolean hasOpenRecordSession() {
        TapeRecorder current = recorder;
        return current != null && current.hasOpenSession();
    }

    private static final class TapeBlock {
        final float[] samples;
        int count;
        TapeBlock(float[] samples) { this.samples = samples; }
    }

    private static final class TapeRecorder {
        private final File file;
        private final int sampleRate;
        private final ArrayBlockingQueue<TapeBlock> queue = new ArrayBlockingQueue<>(MAX_QUEUE_BLOCKS);
        private final ArrayBlockingQueue<TapeBlock> freeBlocks = new ArrayBlockingQueue<>(MAX_QUEUE_BLOCKS);
        private volatile boolean started;
        private volatile boolean stopRequested;
        private volatile boolean paused;
        private volatile boolean finished;
        private volatile boolean failed;
        private volatile long recordedInputFrames;
        private Thread worker;

        TapeRecorder(File file, int sampleRate) {
            this.file = file;
            this.sampleRate = sampleRate;
            for (int i = 0; i < MAX_QUEUE_BLOCKS; i++)
                freeBlocks.offer(new TapeBlock(new float[2048]));
        }

        synchronized boolean start() {
            if (started || finished || sampleRate < 8000 || sampleRate > 384000) return false;
            started = true;
            worker = new Thread(this::writeLoop, "norns-tape-writer");
            try {
                worker.start();
                return true;
            } catch (Throwable startFailure) {
                failed = true;
                finished = true;
                started = false;
                stopRequested = true;
                worker = null;
                AppLog.e("Tape: não foi possível iniciar o gravador (" + startFailure + ")");
                return false;
            }
        }

        synchronized void setPaused(boolean value) {
            paused = value;
        }

        boolean isRecording() {
            return started && !finished && !stopRequested && !paused;
        }

        boolean isFinished() {
            return finished;
        }

        boolean isStopping() {
            return stopRequested;
        }

        boolean isPaused() {
            return started && !finished && !stopRequested && paused;
        }

        double positionSeconds() {
            return sampleRate > 0 ? recordedInputFrames / (double)sampleRate : 0.0;
        }

        boolean hasOpenSession() {
            return started && !finished;
        }

        synchronized void offer(float[] master, int frames) {
            if (!isRecording() || master == null || frames < 1) return;
            int count = Math.min(frames * 2, master.length) & ~1;
            if (count < 2) return;
            TapeBlock block = freeBlocks.poll();
            if (block == null || count > block.samples.length) {
                if (block != null) freeBlocks.offer(block);
                failed = true;
                stopRequested = true;
                AppLog.e("Tape: fila de gravação cheia; encerrando para evitar bloquear o áudio.");
                return;
            }
            System.arraycopy(master, 0, block.samples, 0, count);
            block.count = count;
            if (!queue.offer(block)) {
                block.count = 0;
                freeBlocks.offer(block);
                failed = true;
                stopRequested = true;
                AppLog.e("Tape: fila de gravação cheia; encerrando para evitar bloquear o áudio.");
            } else {
                recordedInputFrames += count / 2L;
            }
        }

        synchronized void stop() {
            if (!started) {
                finished = true;
                return;
            }
            stopRequested = true;
        }

        private void writeLoop() {
            RandomAccessFile out = null;
            File tempFile = new File(file.getPath() + ".recording.tmp");
            boolean complete = false;
            long dataBytes = 0;
            long totalInputFrames = 0;
            double nextSourcePosition = 0;
            float previousLeft = 0, previousRight = 0;
            float lastLeft = 0, lastRight = 0;
            boolean haveLast = false;
            final double step = sampleRate / 48000.0;
            try {
                if (tempFile.exists() && !tempFile.delete())
                    throw new IOException("Não foi possível limpar a gravação temporária anterior");
                out = new RandomAccessFile(tempFile, "rw");
                out.setLength(0);
                // Norns Tape recordings are always 48 kHz stereo PCM16.
                writeHeader(out, 48000, 0);
                while (true) {
                    TapeBlock block = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (block != null) {
                        int inputFrames = block.count / 2;
                        int maxOutputFrames = (int)Math.ceil((inputFrames + 2.0) / step) + 4;
                        byte[] pcmBlock = new byte[maxOutputFrames * 4];
                        int offset = 0;
                        double blockStart = totalInputFrames;
                        double blockEnd = blockStart + inputFrames;
                        while (nextSourcePosition < blockEnd - 1.0) {
                            double local = nextSourcePosition - blockStart;
                            float left, right;
                            if (local < 0) {
                                double fraction = local + 1.0;
                                left = (float)(previousLeft + (block.samples[0] - previousLeft) * fraction);
                                right = (float)(previousRight + (block.samples[1] - previousRight) * fraction);
                            } else {
                                int a = (int)local;
                                double fraction = local - a;
                                int ai = a * 2;
                                int bi = ai + 2;
                                left = (float)(block.samples[ai] + (block.samples[bi] - block.samples[ai]) * fraction);
                                right = (float)(block.samples[ai + 1] + (block.samples[bi + 1] - block.samples[ai + 1]) * fraction);
                            }
                            offset = putPcm16(pcmBlock, offset, left);
                            offset = putPcm16(pcmBlock, offset, right);
                            nextSourcePosition += step;
                        }
                        totalInputFrames += inputFrames;
                        previousLeft = block.samples[(inputFrames - 1) * 2];
                        previousRight = block.samples[(inputFrames - 1) * 2 + 1];
                        lastLeft = previousLeft;
                        lastRight = previousRight;
                        haveLast = true;
                        if (offset > 0) {
                            if (dataBytes + offset > MAX_WAV_DATA_BYTES) {
                                failed = true;
                                stopRequested = true;
                            } else {
                                out.write(pcmBlock, 0, offset);
                                dataBytes += offset;
                            }
                        }
                        block.count = 0;
                        freeBlocks.offer(block);
                    }
                    if (stopRequested && queue.isEmpty()) break;
                }

                // Flush the final fractional resampling interval without losing the tail.
                if (haveLast) {
                    byte[] tail = new byte[32];
                    int offset = 0;
                    while (nextSourcePosition < totalInputFrames) {
                        offset = putPcm16(tail, offset, lastLeft);
                        offset = putPcm16(tail, offset, lastRight);
                        nextSourcePosition += step;
                    }
                    if (offset > 0 && dataBytes + offset <= MAX_WAV_DATA_BYTES) {
                        out.write(tail, 0, offset);
                        dataBytes += offset;
                    } else if (offset > 0) {
                        failed = true;
                    }
                }

                out.seek(4);
                writeLe32(out, 36L + dataBytes);
                out.seek(40);
                writeLe32(out, dataBytes);
                out.getFD().sync();
                complete = true;
            } catch (Throwable error) {
                failed = true;
                AppLog.e("Tape: falha ao gravar WAV (" + error + ")");
            } finally {
                Dust.closeQuietly(out);
                if (complete && !failed) {
                    if (!replaceTarget(tempFile, file)) {
                        failed = true;
                        tempFile.delete();
                    }
                } else {
                    tempFile.delete();
                }
                finished = true;
                if (failed) AppLog.e("Tape: gravação finalizada com erro; o arquivo anterior foi preservado quando possível (" + file.getName() + ")");
                else AppLog.i("Tape: gravação salva em " + file.getAbsolutePath());
            }
        }

        private static boolean replaceTarget(File temp, File target) {
            File backup = new File(target.getPath() + ".bak");
            if (backup.exists() && !backup.delete()) return false;
            boolean backedUp = false;
            if (target.exists()) {
                if (!target.renameTo(backup)) return false;
                backedUp = true;
            }
            if (!temp.renameTo(target)) {
                if (backedUp && !backup.renameTo(target))
                    AppLog.e("Tape: não foi possível restaurar o WAV anterior: " + backup.getPath());
                return false;
            }
            if (backedUp && backup.exists() && !backup.delete())
                AppLog.i("Tape: backup antigo permaneceu em " + backup.getPath());
            return true;
        }

        private static int putPcm16(byte[] target, int offset, float sample) {
            double value = Float.isFinite(sample) ? Math.max(-1.0, Math.min(1.0, sample)) : 0.0;
            int pcm = (int)Math.round(value < 0 ? value * 32768.0 : value * 32767.0);
            target[offset++] = (byte)(pcm & 255);
            target[offset++] = (byte)((pcm >>> 8) & 255);
            return offset;
        }

        private static void writeHeader(RandomAccessFile out, int rate, long dataBytes) throws IOException {
            out.write(new byte[]{'R','I','F','F'});
            writeLe32(out, 36L + dataBytes);
            out.write(new byte[]{'W','A','V','E','f','m','t',' '});
            writeLe32(out, 16);
            writeLe16(out, 1);
            writeLe16(out, 2);
            writeLe32(out, rate);
            writeLe32(out, (long)rate * 4L);
            writeLe16(out, 4);
            writeLe16(out, 16);
            out.write(new byte[]{'d','a','t','a'});
            writeLe32(out, dataBytes);
        }

        private static void writeLe16(RandomAccessFile out, int value) throws IOException {
            out.write(value & 255);
            out.write((value >>> 8) & 255);
        }

        private static void writeLe32(RandomAccessFile out, long value) throws IOException {
            out.write((int)value & 255);
            out.write((int)(value >>> 8) & 255);
            out.write((int)(value >>> 16) & 255);
            out.write((int)(value >>> 24) & 255);
        }
    }
}
