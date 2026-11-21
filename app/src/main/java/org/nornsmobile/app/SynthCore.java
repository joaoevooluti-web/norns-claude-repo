package org.nornsmobile.app;

/**
 * Núcleo de síntese do Norns Mobile (Java puro, sem classes do Android).
 *
 * Engines embutidos:
 *  - PolyPerc: oscilador de pulso (polyBLEP) + filtro Moog de 4 polos + envelope percussivo,
 *              inspirado no engine PolyPerc do norns.
 *  - TestSine: seno contínuo (hz, amp).
 *  - FM: síntese FM de dois operadores com polifonia, envelope e panorama.
 *  - Pluck: corda dedilhada por síntese Karplus-Strong com amortecimento e panorama.
 *
 * NÃO é thread-safe: use sempre da mesma thread (a thread de áudio).
 */
final class SynthCore {

    static final int ENGINE_NONE = 0;
    static final int ENGINE_POLYPERC = 1;
    static final int ENGINE_TESTSINE = 2;
    static final int ENGINE_FM = 3;
    static final int ENGINE_PLUCK = 4;
    static final int ENGINE_32HARMONY = 5;
    static final int ENGINE_SIMPLE_DELAY = 6;

    private static final int MAX_VOICES = 12;
    private static final double ATTACK = 0.01;
    private static final double E4 = Math.exp(-4.0);
    private static final double TWO_PI = 2.0 * Math.PI;

    private final double sr;
    private final double dt;
    private final Voice[] voices = new Voice[MAX_VOICES];
    private final FmVoice[] fmVoices = new FmVoice[MAX_VOICES];
    private final PluckVoice[] pluckVoices = new PluckVoice[MAX_VOICES];
    private int engine = ENGINE_POLYPERC;
    private long ticker = 0;

    // parâmetros do PolyPerc (valores iniciais iguais aos do engine original)
    private double ppAmp = 0.5;
    private double ppPw = 0.5;
    private double ppRelease = 0.5;
    private double ppCutoff = 1000.0;
    private double ppGain = 2.0;
    private double ppPan = 0.0;

    // TestSine
    private double tsFreq = 440.0;
    private double tsAmp = 0.1;
    private double tsAmpSmooth = 0.0;
    private double tsPhase = 0.0;

    // FM engine: two-operator sine FM with per-note release and stereo pan.
    private double fmAmp = 0.25;
    private double fmIndex = 2.0;
    private double fmRatio = 2.0;
    private double fmRelease = 0.8;
    private double fmPan = 0.0;

    // Pluck (Karplus-Strong): corda excitada por ruído curto e realimentação filtrada.
    private double pluckAmp = 0.35;
    private double pluckDecay = 2.5;
    private double pluckDamping = 0.35;
    private double pluckPan = 0.0;

    private final HarmonyVoice[] harmonyVoices = new HarmonyVoice[32];
    private long harmonyTicker = 0;
    private long harmonyRandom = 0x6a09e667f3bcc909L;
    private double harmonyTime = 0;
    private double hRate1 = 1, hRate2 = 1, hRoot = 32.7, hRange1 = 4, hRange2 = 4;
    private double hAttack = 0.01, hDecay = 3.5, hLpf = 4000, hHpf = 20;
    private double hQuant = 0.93, hFreqMult = 1, hSaturation = 1;
    private final double[] hSteps = {1, Math.pow(2, 2.0 / 12), Math.pow(2, 4.0 / 12),
            Math.pow(2, 7.0 / 12), Math.pow(2, 9.0 / 12)};
    private double hRootSmooth = 32.7, hLpfSmooth = 4000;
    private final Biquad hHpLeft = new Biquad(), hHpRight = new Biquad();
    private final Biquad hLpLeft = new Biquad(), hLpRight = new Biquad();

    private double master = 0.8;
    private double simpleDelayThresholdDb = -60.0;

    SynthCore(int sampleRate) {
        this.sr = sampleRate;
        this.dt = 1.0 / sampleRate;
        for (int i = 0; i < MAX_VOICES; i++) {
            voices[i] = new Voice();
            fmVoices[i] = new FmVoice();
            pluckVoices[i] = new PluckVoice();
        }
        for (int i = 0; i < harmonyVoices.length; i++) harmonyVoices[i] = new HarmonyVoice(i >= 16);
    }

    /** Seleciona engine conhecido. Engine desconhecido permanece silencioso, nunca simula PolyPerc. */
    boolean setEngine(String name) {
        allNotesOff();
        tsAmpSmooth = 0.0;
        if (name == null || name.length() == 0 || name.equalsIgnoreCase("none")) {
            engine = ENGINE_NONE;
            return true;
        }
        if (name.equals("PolyPerc")) {
            engine = ENGINE_POLYPERC;
            return true;
        }
        if (name.equals("TestSine")) {
            engine = ENGINE_TESTSINE;
            return true;
        }
        if (name.equals("FM")) {
            engine = ENGINE_FM;
            return true;
        }
        if (name.equals("Pluck")) {
            engine = ENGINE_PLUCK;
            return true;
        }
        if (name.equals("32Harmony")) {
            engine = ENGINE_32HARMONY;
            resetHarmony();
            return true;
        }
        if (name.equals("SimpleDelay")) {
            engine = ENGINE_SIMPLE_DELAY;
            simpleDelayThresholdDb = -60.0;
            return true;
        }
        engine = ENGINE_NONE;
        return false;
    }

    int getEngine() {
        return engine;
    }

    double getSimpleDelayThresholdDb() {
        return simpleDelayThresholdDb;
    }

    void allNotesOff() {
        for (int i = 0; i < MAX_VOICES; i++) {
            voices[i].active = false;
            fmVoices[i].active = false;
            pluckVoices[i].active = false;
        }
        for (HarmonyVoice voice : harmonyVoices) voice.active = false;
    }

    /** Executa um comando de engine (equivalente a engine.nome(valor) no norns). */
    void command(String name, double a, double b) {
        if (name == null) return;
        if (engine == ENGINE_POLYPERC) {
            if (name.equals("hz")) trigger(a);
            else if (name.equals("amp")) ppAmp = clamp(a, 0.0, 4.0);
            else if (name.equals("pw")) ppPw = clamp(a, 0.01, 0.99);
            else if (name.equals("release")) ppRelease = clamp(a, 0.005, 20.0);
            else if (name.equals("cutoff")) ppCutoff = clamp(a, 20.0, 20000.0);
            else if (name.equals("gain")) ppGain = clamp(a, 0.0, 4.0);
            else if (name.equals("pan")) ppPan = clamp(a, -1.0, 1.0);
        } else if (engine == ENGINE_TESTSINE) {
            if (name.equals("hz")) tsFreq = clamp(a, 1.0, sr * 0.45);
            else if (name.equals("amp")) tsAmp = clamp(a, 0.0, 1.0);
        } else if (engine == ENGINE_FM) {
            if (name.equals("hz")) triggerFm(a);
            else if (name.equals("amp")) fmAmp = clamp(a, 0.0, 1.0);
            else if (name.equals("index")) fmIndex = clamp(a, 0.0, 20.0);
            else if (name.equals("ratio")) fmRatio = clamp(a, 0.125, 16.0);
            else if (name.equals("release")) fmRelease = clamp(a, 0.005, 30.0);
            else if (name.equals("pan")) fmPan = clamp(a, -1.0, 1.0);
        } else if (engine == ENGINE_PLUCK) {
            if (name.equals("hz")) triggerPluck(a);
            else if (name.equals("amp")) pluckAmp = clamp(a, 0.0, 1.0);
            else if (name.equals("decay")) pluckDecay = clamp(a, 0.05, 20.0);
            else if (name.equals("damping") || name.equals("damp")) pluckDamping = clamp(a, 0.0, 0.99);
            else if (name.equals("pan")) pluckPan = clamp(a, -1.0, 1.0);
        } else if (engine == ENGINE_SIMPLE_DELAY) {
            if (name.equals("threshold")) simpleDelayThresholdDb = clamp(a, -90.0, 0.0);
        } else if (engine == ENGINE_32HARMONY) {
            if (name.equals("rate1In")) {
                hRate1 = clamp(a, 0.1, 20000);
                rescheduleHarmonyBank(false);
            } else if (name.equals("rate2In")) {
                hRate2 = clamp(a, 0.1, 20000);
                rescheduleHarmonyBank(true);
            }
            else if (name.equals("rootIn")) hRoot = clamp(a, 1, 20000);
            else if (name.equals("range1In")) hRange1 = clamp(a, 1, 9);
            else if (name.equals("range2In")) hRange2 = clamp(a, 1, 9);
            else if (name.equals("attackIn")) hAttack = clamp(a, 0.001, 20);
            else if (name.equals("decayIn")) hDecay = clamp(a, 0.001, 30);
            else if (name.equals("lpfCutoffIn")) hLpf = clamp(a, 10, 20000);
            else if (name.equals("hpfCutoffIn")) hHpf = clamp(a, 10, 20000);
            else if (name.equals("quantAmtIn")) hQuant = clamp(a, 0, 1);
            else if (name.equals("step0In")) hSteps[0] = clamp(a, 0.01, 32);
            else if (name.equals("step1In")) hSteps[1] = clamp(a, 0.01, 32);
            else if (name.equals("step2In")) hSteps[2] = clamp(a, 0.01, 32);
            else if (name.equals("step3In")) hSteps[3] = clamp(a, 0.01, 32);
            else if (name.equals("step4In")) hSteps[4] = clamp(a, 0.01, 32);
            else if (name.equals("freqMultIn")) hFreqMult = clamp(a, 0.01, 32);
            else if (name.equals("saturationAmtIn")) hSaturation = clamp(a, 0.1, 300);
        }
    }

    private void triggerFm(double hz) {
        if (!(hz > 1.0) || hz > sr * 0.4) return;
        FmVoice voice = null;
        FmVoice oldest = fmVoices[0];
        for (FmVoice candidate : fmVoices) {
            if (!candidate.active) { voice = candidate; break; }
            if (candidate.age < oldest.age) oldest = candidate;
        }
        if (voice == null) voice = oldest;
        voice.start(hz, fmAmp, fmIndex, fmRatio, fmRelease, fmPan, ++ticker);
    }

    private void triggerPluck(double hz) {
        if (!(hz > 10.0) || hz > sr * 0.4) return;
        PluckVoice voice = null;
        PluckVoice oldest = pluckVoices[0];
        for (PluckVoice candidate : pluckVoices) {
            if (!candidate.active) { voice = candidate; break; }
            if (candidate.age < oldest.age) oldest = candidate;
        }
        if (voice == null) voice = oldest;
        voice.start(hz, pluckAmp, pluckDecay, pluckDamping, pluckPan, ++ticker, sr);
    }

    private void trigger(double hz) {
        if (!(hz > 1.0) || hz > sr * 0.4) return;
        Voice v = null;
        long oldest = Long.MAX_VALUE;
        Voice steal = voices[0];
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice c = voices[i];
            if (!c.active) {
                v = c;
                break;
            }
            if (c.age < oldest) {
                oldest = c.age;
                steal = c;
            }
        }
        if (v == null) v = steal;
        v.start(hz, ppAmp, ppPw, ppRelease, ppCutoff, ppGain, ppPan, ++ticker, sr);
    }

    /** Gera 'frames' quadros estéreo intercalados (L,R,L,R...) em out. */
    void render(float[] out, int frames) {
        for (int i = 0; i < frames * 2; i++) out[i] = 0f;
        if (engine == ENGINE_POLYPERC) {
            for (int vi = 0; vi < MAX_VOICES; vi++) {
                Voice v = voices[vi];
                if (!v.active) continue;
                for (int i = 0; i < frames; i++) {
                    double s = v.process(dt);
                    out[2 * i] += (float) (s * v.gl);
                    out[2 * i + 1] += (float) (s * v.gr);
                    if (!v.active) break;
                }
            }
        } else if (engine == ENGINE_TESTSINE) {
            double inc = TWO_PI * tsFreq / sr;
            for (int i = 0; i < frames; i++) {
                tsAmpSmooth += 0.002 * (tsAmp - tsAmpSmooth);
                float s = (float) (Math.sin(tsPhase) * tsAmpSmooth);
                tsPhase += inc;
                if (tsPhase >= TWO_PI) tsPhase -= TWO_PI;
                out[2 * i] += s;
                out[2 * i + 1] += s;
            }
        } else if (engine == ENGINE_FM) {
            for (FmVoice voice : fmVoices) {
                if (!voice.active) continue;
                for (int i = 0; i < frames; i++) {
                    double sample = voice.process(dt);
                    out[2 * i] += (float)(sample * voice.gl);
                    out[2 * i + 1] += (float)(sample * voice.gr);
                    if (!voice.active) break;
                }
            }
        } else if (engine == ENGINE_PLUCK) {
            for (PluckVoice voice : pluckVoices) {
                if (!voice.active) continue;
                for (int i = 0; i < frames; i++) {
                    double sample = voice.process(dt);
                    out[2 * i] += (float)(sample * voice.gl);
                    out[2 * i + 1] += (float)(sample * voice.gr);
                    if (!voice.active) break;
                }
            }
        } else if (engine == ENGINE_32HARMONY) {
            hHpLeft.setHighPass(hHpf, sr);
            hHpRight.setHighPass(hHpf, sr);
            double controlSlew = 1.0 - Math.exp(-(64.0 * dt) / 0.18);
            for (int i = 0; i < frames; i++) {
                if ((i & 63) == 0) {
                    hRootSmooth += (hRoot - hRootSmooth) * controlSlew;
                    hLpfSmooth += (hLpf - hLpfSmooth) * controlSlew;
                    hLpLeft.setLowPass(hLpfSmooth, sr);
                    hLpRight.setLowPass(hLpfSmooth, sr);
                }
                harmonyTime += dt;
                double left = 0, right = 0;
                for (int vi = 0; vi < harmonyVoices.length; vi++) {
                    HarmonyVoice voice = harmonyVoices[vi];
                    double rate = (vi < 16 ? hRate1 : hRate2) / 16.0;
                    if (harmonyTime >= voice.nextTrigger) {
                        triggerHarmony(voice);
                        voice.nextTrigger = harmonyTime + harmonyExponentialInterval(rate);
                    }
                    double sample = processHarmonyVoice(voice);
                    if (voice.right) right += sample; else left += sample;
                }
                left = harmonySaturate(processHarmonyFilter(left, false));
                right = harmonySaturate(processHarmonyFilter(right, true));
                out[2 * i] += (float) left;
                out[2 * i + 1] += (float) right;
            }
        }
        double engineMaster = engine == ENGINE_32HARMONY ? 1.0 : master;
        for (int i = 0; i < frames * 2; i++) out[i] = softClip(out[i] * engineMaster);
    }

    private static float softClip(double x) {
        double a = Math.abs(x);
        if (a < 0.6) return (float) x;
        double y = 0.6 + 0.4 * Math.tanh((a - 0.6) / 0.4);
        return (float) (x < 0 ? -y : y);
    }

    private static double clamp(double v, double lo, double hi) {
        if (v != v) return lo; // NaN
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Lightweight two-operator FM voice for scripts using the common FM controls. */
    private static final class FmVoice {
        boolean active;
        long age;
        double carrierPhase, modulatorPhase, frequency, amplitude, index, ratio, release, elapsed;
        double gl, gr;

        void start(double hz, double amp, double modulationIndex, double modRatio,
                   double releaseSeconds, double pan, long stamp) {
            active = true;
            age = stamp;
            carrierPhase = modulatorPhase = elapsed = 0.0;
            frequency = hz;
            amplitude = amp;
            index = modulationIndex;
            ratio = modRatio;
            release = releaseSeconds;
            double angle = (pan + 1.0) * Math.PI / 4.0;
            gl = Math.cos(angle);
            gr = Math.sin(angle);
        }

        double process(double dt) {
            double attack = Math.min(1.0, elapsed / 0.005);
            double envelope = attack * Math.exp(-4.0 * Math.max(0.0, elapsed - 0.005) / release);
            double sample = Math.sin(carrierPhase + index * Math.sin(modulatorPhase)) * envelope * amplitude;
            carrierPhase += TWO_PI * frequency * dt;
            modulatorPhase += TWO_PI * frequency * ratio * dt;
            if (carrierPhase >= TWO_PI) carrierPhase %= TWO_PI;
            if (modulatorPhase >= TWO_PI) modulatorPhase %= TWO_PI;
            elapsed += dt;
            if (elapsed > 0.005 && envelope < 0.0001) active = false;
            return sample;
        }
    }

    /** Karplus-Strong: delay curto, filtro de amortecimento e feedback calibrado pelo decay. */
    private static final class PluckVoice {
        private static final int MAX_DELAY = 4096;
        final float[] delay = new float[MAX_DELAY];
        boolean active;
        long age;
        int length, cursor;
        double amplitude, feedback, damping, lowpass, energy, elapsed, maxDuration, gl, gr;

        void start(double hz, double amp, double decay, double dampingAmount,
                   double pan, long stamp, double sampleRate) {
            length = Math.max(2, Math.min(MAX_DELAY, (int)Math.round(sampleRate / hz)));
            cursor = 0;
            amplitude = amp;
            damping = dampingAmount;
            feedback = Math.pow(0.001, length / Math.max(1.0, decay * sampleRate));
            elapsed = lowpass = energy = 0.0;
            maxDuration = decay * 1.5;
            age = stamp;
            active = true;
            long seed = stamp * 1103515245L + 12345L;
            for (int i = 0; i < length; i++) {
                seed = seed * 1103515245L + 12345L;
                delay[i] = ((float)((seed >>> 16) & 0x7fff) / 16384.0f - 1.0f) * 0.8f;
            }
            double angle = (pan + 1.0) * Math.PI / 4.0;
            gl = Math.cos(angle);
            gr = Math.sin(angle);
        }

        double process(double dt) {
            float current = delay[cursor];
            float next = delay[(cursor + 1) % length];
            double averaged = (current + next) * 0.5;
            lowpass += (averaged - lowpass) * (1.0 - damping);
            delay[cursor] = (float)(lowpass * feedback);
            cursor = (cursor + 1) % length;
            elapsed += dt;
            double output = current * amplitude;
            energy += (Math.abs(current) - energy) * 0.001;
            if (elapsed > maxDuration || (elapsed > 0.02 && energy < 0.00002)) active = false;
            return output;
        }
    }

    // ---------------------------------------------------------------- voz PolyPerc

    private static double sat(double x) {
        double y = x * 0.5;
        if (y > 3.0) y = 3.0;
        else if (y < -3.0) y = -3.0;
        double y2 = y * y;
        return 2.0 * y * (27.0 + y2) / (27.0 + 9.0 * y2);
    }

    private static double polyBlep(double t, double dt) {
        if (t < dt) {
            double x = t / dt;
            return x + x - x * x - 1.0;
        } else if (t > 1.0 - dt) {
            double x = (t - 1.0) / dt;
            return x * x + x + x + 1.0;
        }
        return 0.0;
    }

    private static final class HarmonyVoice {
        final boolean right;
        boolean active;
        double nextTrigger, phase, frequency, age, attack, decay;
        HarmonyVoice(boolean right) { this.right = right; }
    }

    private long nextHarmonyRandom() {
        harmonyRandom ^= harmonyRandom << 13;
        harmonyRandom ^= harmonyRandom >>> 7;
        harmonyRandom ^= harmonyRandom << 17;
        return harmonyRandom;
    }

    private double harmonyUnit() {
        return ((nextHarmonyRandom() >>> 11) & ((1L << 53) - 1)) / (double)(1L << 53);
    }

    private double harmonyExponentialInterval(double rate) {
        return -Math.log(Math.max(1e-12, 1.0 - harmonyUnit())) / Math.max(0.001, rate);
    }

    private void rescheduleHarmonyBank(boolean right) {
        double rate = (right ? hRate2 : hRate1) / 16.0;
        for (int i = right ? 16 : 0; i < (right ? 32 : 16); i++) {
            harmonyVoices[i].nextTrigger = harmonyTime + harmonyExponentialInterval(rate);
        }
    }

    private void resetHarmony() {
        harmonyTicker = 0;
        harmonyTime = 0;
        hRootSmooth = hRoot;
        hLpfSmooth = hLpf;
        hHpLeft.reset();
        hHpRight.reset();
        hLpLeft.reset();
        hLpRight.reset();
        for (int i = 0; i < harmonyVoices.length; i++) {
            HarmonyVoice voice = harmonyVoices[i];
            voice.active = false;
            double rate = (i < 16 ? hRate1 : hRate2) / 16.0;
            voice.nextTrigger = harmonyExponentialInterval(rate);
        }
    }

    private void triggerHarmony(HarmonyVoice voice) {
        double randomRatio = 1.0 + harmonyUnit();
        double scaleRatio = hSteps[(int)(harmonyUnit() * hSteps.length)];
        double ratio = randomRatio + (scaleRatio - randomRatio) * hQuant;
        double range = voice.right ? hRange2 : hRange1;
        int octave = 1 + (int)(harmonyUnit() * Math.max(1, (int)range));
        voice.frequency = clamp(hRootSmooth * hFreqMult * ratio * Math.pow(2, octave), 10, 20000);
        voice.phase = 0;
        voice.age = 0;
        voice.attack = hAttack;
        voice.decay = hDecay;
        voice.active = true;
        harmonyTicker++;
    }

    private double processHarmonyVoice(HarmonyVoice voice) {
        if (!voice.active) return 0;
        double envelope;
        if (voice.age < voice.attack) {
            double x = voice.age / Math.max(0.001, voice.attack);
            envelope = (1.0 - Math.exp(-4.0 * x)) / (1.0 - Math.exp(-4.0));
        } else {
            double x = (voice.age - voice.attack) / Math.max(0.001, voice.decay);
            if (x >= 1) { voice.active = false; return 0; }
            envelope = Math.exp(-4.0 * x);
        }
        double sample = Math.sin(voice.phase) * envelope * 0.04;
        voice.phase += TWO_PI * voice.frequency / sr;
        if (voice.phase >= TWO_PI) voice.phase -= TWO_PI * Math.floor(voice.phase / TWO_PI);
        voice.age += dt;
        return sample;
    }

    private double processHarmonyFilter(double input, boolean right) {
        if (right) return hLpRight.process(hHpRight.process(input));
        return hLpLeft.process(hHpLeft.process(input));
    }

    private double harmonySaturate(double input) {
        double sat = hSaturation;
        double scaled = input * sat / 2.0;
        return (Math.tanh(scaled - 0.6) + 0.535) * 2.0 / sat;
    }

    private static final class Biquad {
        private static final double Q = 0.7071067811865476;
        private double b0 = 1, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        void setLowPass(double cutoff, double sampleRate) {
            set(cutoff, sampleRate, false);
        }

        void setHighPass(double cutoff, double sampleRate) {
            set(cutoff, sampleRate, true);
        }

        private void set(double cutoff, double sampleRate, boolean highPass) {
            double hz = clamp(cutoff, 10.0, Math.min(20000.0, sampleRate * 0.45));
            double omega = TWO_PI * hz / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * Q);
            double a0 = 1.0 + alpha;
            if (highPass) {
                b0 = (1.0 + cosine) * 0.5 / a0;
                b1 = -(1.0 + cosine) / a0;
                b2 = b0;
            } else {
                b0 = (1.0 - cosine) * 0.5 / a0;
                b1 = (1.0 - cosine) / a0;
                b2 = b0;
            }
            a1 = -2.0 * cosine / a0;
            a2 = (1.0 - alpha) / a0;
        }

        double process(double input) {
            double output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;
            return output;
        }

        void reset() {
            x1 = x2 = y1 = y2 = 0;
        }
    }



    private static final class Voice {
        boolean active;
        long age;
        double phase, inc, pw, amp, rel, k, g, dc;
        double s1, s2, s3, s4, t1, t2, t3, t4;
        double t;
        double gl, gr;

        void start(double hz, double a, double pulseWidth, double release, double cutoff,
                   double gain, double pan, long stamp, double sr) {
            active = true;
            age = stamp;
            phase = 0.0;
            inc = hz / sr;
            pw = pulseWidth;
            amp = a;
            rel = release;
            k = gain * 0.97;
            double fc = Math.min(cutoff, sr * 0.45);
            g = 1.0 - Math.exp(-TWO_PI * fc / sr);
            dc = 2.0 * pulseWidth - 1.0;
            s1 = s2 = s3 = s4 = 0.0;
            t1 = t2 = t3 = t4 = 0.0;
            t = 0.0;
            double ang = (pan + 1.0) * Math.PI / 4.0;
            gl = Math.cos(ang);
            gr = Math.sin(ang);
        }

        double process(double dt) {
            double env;
            if (t < ATTACK) {
                env = (1.0 - Math.exp(-4.0 * (t / ATTACK))) / (1.0 - E4);
            } else {
                double u = (t - ATTACK) / rel;
                if (u >= 1.0) {
                    active = false;
                    return 0.0;
                }
                env = (Math.exp(-4.0 * u) - E4) / (1.0 - E4);
            }
            t += dt;

            // oscilador de pulso com polyBLEP
            double p = phase;
            double v = (p < pw) ? 1.0 : -1.0;
            v += polyBlep(p, inc);
            double q = p - pw;
            if (q < 0.0) q += 1.0;
            v -= polyBlep(q, inc);
            v -= dc;
            phase += inc;
            if (phase >= 1.0) phase -= 1.0;

            // filtro ladder (Moog) com saturação suave
            double a0 = sat(v - k * s4);
            s1 += g * (a0 - t1);
            double n1 = sat(s1);
            s2 += g * (n1 - t2);
            double n2 = sat(s2);
            s3 += g * (n2 - t3);
            double n3 = sat(s3);
            s4 += g * (n3 - t4);
            double n4 = sat(s4);
            t1 = n1;
            t2 = n2;
            t3 = n3;
            t4 = n4;

            return s4 * (1.0 + 0.6 * k) * env * amp;
        }
    }
}
