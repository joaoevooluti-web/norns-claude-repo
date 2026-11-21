package org.nornsmobile.app;

/**
 * Softcut de seis vozes sobre dois buffers compartilhados.
 * Todo acesso mutável ocorre na thread de áudio.
 */
final class SoftcutCore {
    private static final int VOICES = 6;
    private static final int BUFFER_COUNT = 2;
    // Mesmo limite de amostras usado pelo softcut original (16 Mi amostras por buffer).
    private static final int MAX_BUFFER_SAMPLES = 16_777_216;
    private final Voice[] voices = new Voice[VOICES];
    private final double[][] cutRouting = new double[VOICES][VOICES];
    private final double[][] cutRoutingCurrent = new double[VOICES][VOICES];
    private final double[] engineRouting = new double[VOICES];
    private final double[] engineRoutingCurrent = new double[VOICES];
    private float[][] cutBus = new float[VOICES][];
    private float[][] cutPrevious = new float[VOICES][];
    private final float[][] buffers = new float[BUFFER_COUNT][];
    private final int sampleRate;
    private double adcLevel = 1.0;
    private double tapeLevel = 0.0;
    private volatile double masterLevel = 1.0;

    SoftcutCore(int sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < VOICES; i++) voices[i] = new Voice();
    }

    boolean setMasterLevel(double level) {
        if (!Double.isFinite(level) || level < 0.0 || level > 1.0) return false;
        masterLevel = level;
        return true;
    }

    void bufferRead(int channel, float[] source, int sourceRate, int targetRate,
                    double sourceStartSec, double destinationStartSec, double durationSec) {
        bufferRead(channel, source, sourceRate, targetRate, sourceStartSec, destinationStartSec, durationSec, 0, 1);
    }

    void bufferRead(int channel, float[] source, int sourceRate, int targetRate,
                    double sourceStartSec, double destinationStartSec, double durationSec,
                    double preserve, double mix) {
        int bi = channel - 1;
        if (bi < 0 || bi >= BUFFER_COUNT || source == null || source.length == 0
                || targetRate <= 0 || sourceRate <= 0 || durationSec == 0) return;
        int srcStart = secondsToSamples(sourceStartSec, sourceRate);
        if (srcStart >= source.length) return;
        int srcEnd = source.length;
        if (durationSec > 0) {
            long requestedEnd = (long) srcStart + secondsToSamples(durationSec, sourceRate);
            srcEnd = (int) Math.min(source.length, requestedEnd);
        }
        if (srcEnd <= srcStart) return;
        long converted = (long) Math.ceil((srcEnd - srcStart) * (double) targetRate / sourceRate);
        int dstStart = secondsToSamples(destinationStartSec, targetRate);
        if (dstStart >= MAX_BUFFER_SAMPLES) return;
        int outCount = (int) Math.max(0, Math.min(MAX_BUFFER_SAMPLES - dstStart, converted));
        if (outCount == 0) return;
        int required = dstStart + outCount;
        float[] dst = ensureBufferCapacity(bi, required);
        for (int i = 0; i < outCount; i++) {
            double p = srcStart + (double)i * sourceRate / targetRate;
            int a = Math.min(srcEnd - 1, (int)p);
            int b = Math.min(srcEnd - 1, a + 1);
            double f = p - a;
            double incoming = (source[a] + (source[b] - source[a]) * f) * clamp(mix, 0, 2);
            double existing = dst[dstStart + i] * clamp(preserve, 0, 2);
            dst[dstStart + i] = (float)clamp(existing + incoming, -1, 1);
        }
    }

    void clearBuffer(int channel, double startSec, double durationSec) {
        clearBuffer(channel, startSec, durationSec, 0, 0);
    }

    void clearBuffer(int channel, double startSec, double durationSec, double fadeSec, double preserve) {
        int bi = channel - 1;
        if (bi < 0 || bi >= BUFFER_COUNT || buffers[bi] == null || durationSec == 0) return;
        float[] b = buffers[bi];
        int start = secondsToSamples(startSec, sampleRate);
        int end = durationSec < 0 ? b.length : (int) Math.min(b.length,
                (long) start + secondsToSamples(durationSec, sampleRate));
        if (start >= b.length || end <= start) return;
        int fade = Math.max(0, (int)Math.round(fadeSec * sampleRate));
        double keep = clamp(preserve, 0, 2);
        for (int i = start; i < end; i++) {
            double edge = 1.0;
            if (fade > 0) {
                edge = Math.min(1.0, Math.min((i - start + 1.0) / fade, (end - i) / (double)fade));
            }
            b[i] = (float)(b[i] * (keep + (1.0 - keep) * (1.0 - edge)));
        }
    }

    int bufferLength(int channel) {
        int index = channel - 1;
        return index < 0 || index >= BUFFER_COUNT || buffers[index] == null ? 0 : buffers[index].length;
    }

    /** Ensures a recording region exists; called only from the audio thread. */
    int ensureBufferLength(int channel, int requiredFrames) {
        int index = channel - 1;
        if (index < 0 || index >= BUFFER_COUNT || requiredFrames < 1) return 0;
        float[] data = ensureBufferCapacity(index, requiredFrames);
        return data == null ? 0 : data.length;
    }

    /** Applies a native buffer snapshot; callers must be the audio thread. */
    void writeBufferRegion(int channel, int startFrame, float[] samples) {
        int index = channel - 1;
        if (index < 0 || index >= BUFFER_COUNT || startFrame < 0 || samples == null
                || samples.length == 0 || startFrame >= MAX_BUFFER_SAMPLES) return;
        int count = Math.min(samples.length, MAX_BUFFER_SAMPLES - startFrame);
        float[] target = ensureBufferCapacity(index, startFrame + count);
        if (target == null) return;
        for (int i = 0; i < count; i++) {
            float value = samples[i];
            target[startFrame + i] = (Float.isNaN(value) || Float.isInfinite(value))
                    ? 0f : Math.max(-1f, Math.min(1f, value));
        }
    }

    float[] snapshotBuffer(int channel, double startSec, double durationSec) {
        int bi = channel - 1;
        if (bi < 0 || bi >= BUFFER_COUNT || buffers[bi] == null || durationSec == 0) return new float[0];
        float[] source = buffers[bi];
        int start = secondsToSamples(startSec, sampleRate);
        if (start >= source.length) return new float[0];
        int count = durationSec < 0 ? source.length - start :
                (int) Math.min(source.length - start, secondsToSamples(durationSec, sampleRate));
        return java.util.Arrays.copyOfRange(source, start, start + count);
    }

    static final class RenderResult {
        final float[] samples;
        final double secPerSample;
        RenderResult(float[] samples, double secPerSample) {
            this.samples = samples;
            this.secPerSample = secPerSample;
        }
    }

    RenderResult renderBufferResult(int channel, double startSec, double durationSec, int maxSamples) {
        float[] source = snapshotBuffer(channel, startSec, durationSec);
        if (source.length == 0) return new RenderResult(source, 0);
        int count = maxSamples <= 0 ? source.length : Math.min(source.length, maxSamples);
        float[] result;
        if (count == source.length) result = source;
        else {
            result = new float[count];
            double step = (double) source.length / count;
            for (int i = 0; i < count; i++) {
                int from = (int) Math.floor(i * step);
                int to = Math.min(source.length, Math.max(from + 1, (int) Math.floor((i + 1) * step)));
                double sum = 0;
                for (int j = from; j < to; j++) sum += source[j];
                result[i] = (float) (sum / (to - from));
            }
        }
        return new RenderResult(result, (double) source.length / sampleRate / result.length);
    }

    float[] renderBuffer(int channel, double startSec, double durationSec, int maxSamples) {
        return renderBufferResult(channel, startSec, durationSec, maxSamples).samples;
    }

    float[] snapshotStereo(double startSec, double durationSec) {
        float[] left = snapshotBuffer(1, startSec, durationSec);
        float[] right = snapshotBuffer(2, startSec, durationSec);
        int count = Math.max(left.length, right.length);
        float[] stereo = new float[count * 2];
        for (int i = 0; i < count; i++) {
            stereo[i * 2] = i < left.length ? left[i] : 0;
            stereo[i * 2 + 1] = i < right.length ? right[i] : 0;
        }
        return stereo;
    }

    void copyBuffer(int sourceChannel, int destinationChannel, double sourceSec,
                    double destinationSec, double durationSec, double fadeSec,
                    double preserve, boolean reverse) {
        int si = sourceChannel - 1, di = destinationChannel - 1;
        if (si < 0 || si >= BUFFER_COUNT || di < 0 || di >= BUFFER_COUNT || durationSec == 0) return;
        float[] src = buffers[si];
        if (src == null) return;
        int srcStart = secondsToSamples(sourceSec, sampleRate);
        int dstStart = secondsToSamples(destinationSec, sampleRate);
        if (srcStart >= src.length || dstStart >= MAX_BUFFER_SAMPLES) return;
        int count = durationSec < 0 ? src.length - srcStart :
                (int) Math.min(src.length - srcStart, secondsToSamples(durationSec, sampleRate));
        count = Math.min(count, MAX_BUFFER_SAMPLES - dstStart);
        if (count <= 0) return;
        int required = dstStart + count;
        // Snapshot protege cópias sobrepostas no mesmo buffer.
        float[] snapshot = new float[count];
        for (int i = 0; i < count; i++) snapshot[i] = src[srcStart + i];
        float[] dst = ensureBufferCapacity(di, required);
        int fade = Math.max(0, (int)Math.round(fadeSec * sampleRate));
        double keep = clamp(preserve, 0, 2);
        for (int i = 0; i < count; i++) {
            int target = dstStart + i;
            double edge = 1.0;
            if (fade > 0) edge = Math.min(1.0, Math.min((i + 1.0) / fade, (count - i) / (double)fade));
            double oldGain = keep + (1.0 - keep) * (1.0 - edge);
            float incoming = snapshot[reverse ? count - 1 - i : i];
            dst[target] = (float)clamp(dst[target] * oldGain + incoming * edge, -1, 1);
        }
    }

    void command(int voice, String op, double value) {
        if (op == null) return;
        if ("adc_level".equals(op)) { adcLevel = clamp(value, 0, 2); return; }
        if ("tape_level".equals(op)) { tapeLevel = clamp(value, 0, 2); return; }
        if ("engine_level".equals(op)) {
            if (voice >= 1 && voice <= VOICES) engineRouting[voice - 1] = clamp(value, 0, 2);
            return;
        }
        Voice v = get(voice);
        if (v == null) return;
        if ("clear".equals(op)) { v.playing = false; v.rec = false; v.position = 0; }
        else if ("rec".equals(op)) v.rec = value != 0;
        else if ("rec_level".equals(op)) v.recLevelTarget = clamp(value, 0, 2);
        else if ("pre_level".equals(op)) v.preLevelTarget = clamp(value, 0, 2);
        else if ("recpre_slew_time".equals(op)) v.recPreSlew = Math.max(0, value);
        else if ("rec_offset".equals(op)) v.recOffset = value;
        else if ("input_level".equals(op)) v.inputLevel = clamp(value, 0, 2);
        else if (op.startsWith("cut_level_")) {
            try {
                int destination = Integer.parseInt(op.substring("cut_level_".length())) - 1;
                if (destination >= 0 && destination < VOICES) cutRouting[voice - 1][destination] = clamp(value, 0, 2);
            } catch (NumberFormatException ignored) {}
        }
        else if ("buffer".equals(op)) v.buffer = Math.max(1, Math.min(BUFFER_COUNT, (int)value)) - 1;
        else if ("play".equals(op)) {
            if (value != 0) { v.playing = true; if (v.fadeTime > 0) { v.fade = 0; v.fadeTarget = 1; } else v.fade = 1; }
            else if (v.fadeTime > 0 && v.playing) v.fadeTarget = 0;
            else { v.playing = false; v.fade = 0; }
        }
        else if ("position".equals(op)) v.position = secondsToSamples(value, sampleRate);
        else if ("rate".equals(op)) v.rateTarget = clamp(value, -4, 4);
        else if ("level".equals(op)) v.levelTarget = clamp(value, 0, 2);
        else if ("pan".equals(op)) v.panTarget = clamp(value, -1, 1);
        else if ("pan_slew_time".equals(op)) v.panSlew = Math.max(0, value);
        else if ("loop".equals(op)) v.loop = value != 0;
        else if ("loop_start".equals(op)) v.loopStart = secondsToSamples(value, sampleRate);
        else if ("loop_end".equals(op)) {
            v.loopEnd = Math.max(1, secondsToSamples(value, sampleRate));
            if (v.loopEnd < Integer.MAX_VALUE) ensureBufferCapacity(v.buffer, v.loopEnd);
        }
        else if ("enable".equals(op)) v.enabled = value != 0;
        else if ("level_slew_time".equals(op)) v.levelSlew = Math.max(0, value);
        else if ("rate_slew_time".equals(op)) v.rateSlew = Math.max(0, value);
        else if ("fade_time".equals(op)) v.fadeTime = Math.max(0, value);
        else if ("filter".equals(op)) { v.legacyFilterMode = true; v.filterEnabled = value != 0; }
        else if ("filter_cutoff".equals(op)) v.filterCutoff = clamp(value, 10, sampleRate * 0.45);
        else if ("filter_rq".equals(op)) v.filterRq = clamp(value, 0.0005, 8);
        else if ("filter_type".equals(op)) v.filterType = Math.max(0, Math.min(2, (int)value));
        else if (op.startsWith("pre_filter_")) setFilter(v.preFilter, op.substring(11), value);
        else if (op.startsWith("post_filter_")) setFilter(v.postFilter, op.substring(12), value);
        else if ("filter_fc".equals(op)) v.preFilter.fc = clamp(value, 10, sampleRate * 0.45);
        else if ("filter_fc_mod".equals(op)) v.preFilter.fcMod = clamp(value, 0, 1);
    }

    void voiceSync(int destination, int source, double offsetSec) {
        Voice dst = get(destination);
        Voice src = get(source);
        if (dst == null || src == null) return;
        dst.position = Math.max(0, src.position + offsetSec * sampleRate);
    }

    double position(int voice) {
        Voice v = get(voice);
        return v == null ? 0 : v.position / sampleRate;
    }

    void render(float[] out, int frames, int ignoredRate) {
        render(out, frames, ignoredRate, null);
    }

    void render(float[] out, int frames, int ignoredRate, float[] input) {
        render(out, frames, ignoredRate, input, null);
    }

    /** Engine and Tape are independent sends into the Softcut record path. */
    void render(float[] out, int frames, int ignoredRate, float[] input, float[] engineInput) {
        render(out, frames, ignoredRate, input, engineInput, null);
    }

    void render(float[] out, int frames, int ignoredRate, float[] input,
                float[] engineInput, float[] tapeInput) {
        render(out, frames, ignoredRate, input, engineInput, tapeInput, null);
    }

    /**
     * Renders the same Softcut signal to the master output and, when supplied,
     * to an isolated pre-master stereo send for the Java reverb bus. The caller
     * owns and clears the send buffer once per audio block.
     */
    void render(float[] out, int frames, int ignoredRate, float[] input,
                float[] engineInput, float[] tapeInput, float[] reverbSend) {
        if (cutBus[0] == null || cutBus[0].length < frames) {
            for (int v = 0; v < VOICES; v++) { cutBus[v] = new float[frames]; cutPrevious[v] = new float[frames]; }
        }
        for (int v = 0; v < VOICES; v++) { System.arraycopy(cutBus[v], 0, cutPrevious[v], 0, frames); java.util.Arrays.fill(cutBus[v], 0, frames, 0f); }
        final float outputGain = (float)masterLevel;
        int voiceIndex = 0;
        for (Voice v : voices) {
            final int currentVoice = voiceIndex++;
            if (!v.enabled || (!v.playing && !v.rec)) continue;
            if (v.buffer < 0 || v.buffer >= BUFFER_COUNT) continue;

            float[] data = buffers[v.buffer];
            // Permite gravar em um buffer ainda vazio. O crescimento posterior
            // é feito somente quando necessário e permanece na thread de áudio.
            if (data == null && v.rec) {
                data = ensureBufferCapacity(v.buffer, Math.min(MAX_BUFFER_SAMPLES, Math.max(sampleRate * 10, 2)));
            }
            if (data == null || data.length < 2) continue;
            // Quando loop_end não foi definido, a gravação pode ocupar o buffer inteiro.
            // A capacidade cresce em potências de dois, evitando parar aos 10 segundos.
            long positiveOffsetFrames = Math.max(0L, Math.round(v.recOffset * sampleRate));
            if (v.rec && v.loopEnd == Integer.MAX_VALUE && data.length < MAX_BUFFER_SAMPLES) {
                long requiredWriteEnd = (long)Math.ceil(v.position + positiveOffsetFrames + frames);
                if (requiredWriteEnd > data.length) {
                    int required = (int)Math.min(MAX_BUFFER_SAMPLES,
                            Math.max(requiredWriteEnd, (long)data.length + 1));
                    data = ensureBufferCapacity(v.buffer, required);
                }
            } else if (v.rec && v.loopEnd < Integer.MAX_VALUE) {
                long requiredWriteEnd = Math.min((long)MAX_BUFFER_SAMPLES, (long)v.loopEnd + positiveOffsetFrames);
                if (requiredWriteEnd > data.length)
                    data = ensureBufferCapacity(v.buffer, (int)requiredWriteEnd);
            }

            int end = Math.min(data.length, v.loopEnd);
            int start = Math.min(Math.max(0, end - 1), v.loopStart);
            if (end - start < 2) { v.playing = false; v.rec = false; continue; }

            for (int i = 0; i < frames; i++) {
                if (!v.playing && !v.rec) break;
                v.rate = approach(v.rate, v.rateTarget, v.rateSlew, sampleRate);
                v.pan = approach(v.pan, v.panTarget, v.panSlew, sampleRate);
                v.fade = approach(v.fade, v.fadeTarget, v.fadeTime, sampleRate);
                v.level = approach(v.level, v.levelTarget, v.levelSlew, sampleRate);
                v.recLevel = approach(v.recLevel, v.recLevelTarget, v.recPreSlew, sampleRate);
                v.preLevel = approach(v.preLevel, v.preLevelTarget, v.recPreSlew, sampleRate);

                if (v.position >= end || v.position < start) {
                    if (v.loop) {
                        double length = end - start;
                        v.position = start + (((v.position - start) % length) + length) % length;
                    } else {
                        v.playing = false;
                        v.rec = false;
                        break;
                    }
                }

                int p = Math.max(0, Math.min(data.length - 1, (int)Math.floor(v.position)));
                int q = Math.min(data.length - 1, p + 1);
                double frac = clamp(v.position - p, 0, 1);
                // Hermite cúbico (como o softcut real): evita o serrilhado/"bitcrush" da
                // interpolação linear quando rate != 1 (ex.: halfsecond / awake).
                boolean resampling = Math.abs(v.rate - 1.0) > 1e-6 || frac > 1e-6;
                float sample;
                if (resampling) {
                    double ym1 = data[Math.max(0, p - 1)], y0 = data[p], y1 = data[q];
                    double y2 = data[Math.min(data.length - 1, q + 1)];
                    double c0 = y0, c1 = 0.5 * (y1 - ym1);
                    double c2 = ym1 - 2.5 * y0 + 2.0 * y1 - 0.5 * y2;
                    double c3 = 0.5 * (y2 - ym1) + 1.5 * (y0 - y1);
                    sample = (float)(((c3 * frac + c2) * frac + c1) * frac + c0);
                } else {
                    sample = data[p];
                }

                // A gravação avança mesmo com play desligado. Isso permite
                // gravar uma entrada externa em um buffer vazio ou pré-carregado.
                if (v.rec && v.recLevel > 0) {
                    int writeIndex = p + (int)Math.round(v.recOffset * sampleRate);
                    if (writeIndex >= 0 && writeIndex < data.length) {
                        // level_slew_time também suaviza os pontos de mixagem ADC->voice
                        // e voice->voice, evitando degraus audíveis ao automatizar os sends.
                        v.inputLevelCurrent = approach(v.inputLevelCurrent, v.inputLevel, v.levelSlew, sampleRate);
                        double incoming = input != null && i < input.length ? input[i] * adcLevel * v.inputLevelCurrent : 0.0;
                        engineRoutingCurrent[currentVoice] = approach(engineRoutingCurrent[currentVoice],
                                engineRouting[currentVoice], v.levelSlew, sampleRate);
                        if (engineInput != null && i < engineInput.length)
                            incoming += engineInput[i] * engineRoutingCurrent[currentVoice];
                        if (tapeInput != null && i < tapeInput.length)
                            incoming += tapeInput[i] * tapeLevel;
                        for (int source = 0; source < VOICES; source++) {
                            double sendTarget = cutRouting[source][currentVoice];
                            cutRoutingCurrent[source][currentVoice] = approach(
                                    cutRoutingCurrent[source][currentVoice], sendTarget, v.levelSlew, sampleRate);
                            incoming += cutPrevious[source][i] * cutRoutingCurrent[source][currentVoice];
                        }
                        if (Math.abs(v.rate - 1.0) > 1e-6 && frac > 1e-6 && writeIndex + 1 < data.length) {
                            // Escrita distribuída entre duas amostras vizinhas (peso pela fração),
                            // em vez de arredondar para baixo: reduz aliasing com rate != 1.
                            double w1 = frac, w0 = 1.0 - frac;
                            double target0 = data[writeIndex] * v.preLevel + incoming * v.recLevel;
                            double target1 = data[writeIndex + 1] * v.preLevel + incoming * v.recLevel;
                            data[writeIndex] = (float)clamp(data[writeIndex] + (target0 - data[writeIndex]) * w0, -1.0, 1.0);
                            data[writeIndex + 1] = (float)clamp(data[writeIndex + 1] + (target1 - data[writeIndex + 1]) * w1, -1.0, 1.0);
                        } else {
                            double written = data[writeIndex] * v.preLevel + incoming * v.recLevel;
                            data[writeIndex] = (float)clamp(written, -1.0, 1.0);
                        }
                    }
                }

                if (v.playing) {
                    if (!v.legacyFilterMode || v.filterEnabled)
                        sample = (float)processFilter(v.preFilter, sample, v.rate, sampleRate);
                    sample = (float)processFilter(v.postFilter, sample, v.rate, sampleRate);
                    double angle = (v.pan + 1) * Math.PI / 4;
                    float gain = (float)(v.level * v.fade);
                    float routedSample = sample * gain;
                    // Keep the inter-voice cut bus pre-master; level_cut affects DAC only.
                    cutBus[currentVoice][i] = routedSample;
                    float leftPan = (float)Math.cos(angle);
                    float rightPan = (float)Math.sin(angle);
                    float outputSample = routedSample * outputGain;
                    out[2 * i] += outputSample * leftPan;
                    out[2 * i + 1] += outputSample * rightPan;
                    if (reverbSend != null && reverbSend.length >= frames * 2) {
                        reverbSend[2 * i] += routedSample * leftPan;
                        reverbSend[2 * i + 1] += routedSample * rightPan;
                    }
                    if (v.fadeTarget == 0 && v.fade <= 0.00001) v.playing = false;
                }

                if (v.playing || v.rec) v.position += v.rate;
            }
        }
    }

    private static void setFilter(Filter f, String parameter, double value) {
        if ("fc".equals(parameter)) f.fc = value;
        else if ("fc_mod".equals(parameter)) f.fcMod = clamp(value, 0, 1);
        else if ("rq".equals(parameter)) f.rq = clamp(value, 0.0005, 8);
        else if ("lp".equals(parameter)) f.lp = clamp(value, 0, 2);
        else if ("hp".equals(parameter)) f.hp = clamp(value, 0, 2);
        else if ("bp".equals(parameter)) f.bp = clamp(value, 0, 2);
        else if ("br".equals(parameter)) f.br = clamp(value, 0, 2);
        else if ("dry".equals(parameter)) f.dry = clamp(value, 0, 2);
    }

    // State-variable filter TPT: LP/HP/BP/BR simultâneos, com mix independente.
    // Os coeficientes só mudam quando cutoff, ressonância ou sample rate mudam.
    // Isso evita executar tan() em cada amostra quando os parâmetros estão estáveis.
    private static double processFilter(Filter f, double input, double rate, int sr) {
        double cutoff = f.fc;
        if (f.fcMod == 1.0) cutoff *= Math.min(1.0, Math.abs(rate));
        else if (f.fcMod > 0) cutoff *= Math.pow(Math.min(1.0, Math.abs(rate)), f.fcMod);
        cutoff = clamp(cutoff, 10, sr * 0.45);
        double k = Math.max(0.0005, f.rq);
        if (f.coefficientSampleRate != sr
                || Double.compare(f.coefficientCutoff, cutoff) != 0
                || Double.compare(f.coefficientRq, k) != 0) {
            double g = Math.tan(Math.PI * cutoff / sr);
            f.coefficientA1 = 1.0 / (1.0 + g * (g + k));
            f.coefficientA2 = g * f.coefficientA1;
            f.coefficientA3 = g * f.coefficientA2;
            f.coefficientK = k;
            f.coefficientCutoff = cutoff;
            f.coefficientRq = k;
            f.coefficientSampleRate = sr;
        }
        double v3 = input - f.ic2;
        double v1 = f.coefficientA1 * f.ic1 + f.coefficientA2 * v3;
        double v2 = f.ic2 + f.coefficientA2 * f.ic1 + f.coefficientA3 * v3;
        f.ic1 = 2.0 * v1 - f.ic1;
        f.ic2 = 2.0 * v2 - f.ic2;
        double hp = v3 - f.coefficientK * v1;
        double bp = v1;
        double lp = v2;
        double br = hp + lp;
        return f.dry * input + f.lp * lp + f.hp * hp + f.bp * bp + f.br * br;
    }

    private float[] ensureBufferCapacity(int channelIndex, int required) {
        if (channelIndex < 0 || channelIndex >= BUFFER_COUNT) return null;
        required = Math.max(0, Math.min(MAX_BUFFER_SAMPLES, required));
        float[] current = buffers[channelIndex];
        if (current != null && current.length >= required) return current;
        int size = current == null ? Math.max(2, required) : current.length;
        while (size < required && size < MAX_BUFFER_SAMPLES) {
            size = (int) Math.min(MAX_BUFFER_SAMPLES, Math.max((long) size + 1, (long) size * 2));
        }
        buffers[channelIndex] = current == null ? new float[size] : java.util.Arrays.copyOf(current, size);
        return buffers[channelIndex];
    }

    private static int secondsToSamples(double seconds, int rate) {
        if (Double.isNaN(seconds) || seconds <= 0 || rate <= 0) return 0;
        if (Double.isInfinite(seconds)) return MAX_BUFFER_SAMPLES;
        double samples = seconds * rate;
        return samples >= MAX_BUFFER_SAMPLES ? MAX_BUFFER_SAMPLES : (int) Math.round(samples);
    }

    private static double approach(double current, double target, double seconds, int sr) {
        if (seconds <= 0 || sr <= 0) return target;
        // Tempo exponencial: o tempo informado corresponde à convergência de -60 dB.
        double exponent = -Math.log(1000.0) / Math.max(1.0, seconds * sr);
        return target + (current - target) * Math.exp(exponent);
    }
    private Voice get(int n) { return n >= 1 && n <= VOICES ? voices[n - 1] : null; }
    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class Filter {
        double fc = 12000, fcMod = 0, rq = 1;
        double lp = 1, hp = 0, bp = 0, br = 0, dry = 0;
        double ic1 = 0, ic2 = 0;
        double coefficientCutoff = Double.NaN, coefficientRq = Double.NaN;
        double coefficientA1, coefficientA2, coefficientA3, coefficientK;
        int coefficientSampleRate = 0;
    }

    private static final class Voice {
        int buffer = 0, loopStart = 0, loopEnd = Integer.MAX_VALUE;
        Voice() {
            preFilter.fcMod = 1;
            preFilter.lp = 1;
            postFilter.lp = 0;
            postFilter.dry = 1;
        }
        final Filter preFilter = new Filter();
        final Filter postFilter = new Filter();
        volatile double position = 0;
        double rate = 1, rateTarget = 1, level = 1, levelTarget = 1;
        double pan = 0, panTarget = 0, panSlew = 0, levelSlew = 0, rateSlew = 0, fadeTime = 0, fade = 1, fadeTarget = 1;
        double filterCutoff = 12000, filterRq = 1, filterState = 0;
        int filterType = 0;
        boolean legacyFilterMode = false;
        double recLevel = 0, recLevelTarget = 0, preLevel = 1, preLevelTarget = 1, recPreSlew = 0, recOffset = 0, inputLevel = 1, inputLevelCurrent = 1;
        boolean playing = false, rec = false, loop = false, enabled = true, filterEnabled = false;
    }
}
