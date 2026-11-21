// Gera o SynthDef binário usado pela rota nativa Softcut.
// Executado no CI com sclang; não depende de hardware de áudio.
(
SynthDef(\SoftcutVoice, {
    |out = 9, position_bus = 11, buf = 0, rate = 1, loop_start = 0, loop_end = 1,
     position = 0, play = 0, phase_play = 0, phase_enable = 1,
     loop = 1, level = 0, pan = 0,
     rate_slew_time = 0.001, level_slew_time = 0.001, pan_slew_time = 0.001,
     enable = 0, fade_time = 0.0005, cut_bus = 3,
     pre_filter_fc = 16000, pre_filter_fc_mod = 1, pre_filter_rq = 2,
     pre_filter_lp = 1, pre_filter_hp = 0, pre_filter_bp = 0, pre_filter_br = 0, pre_filter_dry = 0,
     post_filter_fc = 12000, post_filter_fc_mod = 0, post_filter_rq = 2,
     post_filter_lp = 0, post_filter_hp = 0, post_filter_bp = 0, post_filter_br = 0, post_filter_dry = 0|

    var bufferRate = BufRateScale.kr(buf);
    var bufferSampleRate = BufSampleRate.kr(buf);
    var startFrame = loop_start * bufferSampleRate;
    var endFrame = (loop_end * bufferSampleRate).max(startFrame + 1);
    var smoothedRate = Lag.kr(rate, rate_slew_time.max(0));
    var smoothedLevel = Lag.kr(level, level_slew_time.max(0));
    var smoothedPan = Lag.kr(pan, pan_slew_time.max(0));
    var reset = Changed.kr(position) + Changed.kr(loop_start) + Changed.kr(loop_end);
    var smoothedPlay = Lag.kr(play * enable, fade_time.max(0));
    var phaseTracking = phase_play * phase_enable;
    var phase = Phasor.ar(
        reset,
        smoothedRate * bufferRate * phaseTracking,
        startFrame,
        endFrame,
        position * bufferSampleRate
    );
    var boundedPhase = Select.ar(loop, [phase.clip(startFrame, endFrame - 1), phase]);
    var signal = BufRd.ar(1, buf, boundedPhase, 1, 4);
    var gate = smoothedPlay;
    var rateFactor = smoothedRate.abs.min(1).pow(pre_filter_fc_mod);
    var preFreq = (pre_filter_fc * rateFactor).clip(10, SampleRate.ir * 0.45);
    var postFreq = (post_filter_fc * smoothedRate.abs.min(1).pow(post_filter_fc_mod))
        .clip(10, SampleRate.ir * 0.45);
    var preRq = pre_filter_rq.clip(0.001, 1);
    var postRq = post_filter_rq.clip(0.001, 1);
    var preSignal = signal * pre_filter_dry
        + RLPF.ar(signal, preFreq, preRq) * pre_filter_lp
        + RHPF.ar(signal, preFreq, preRq) * pre_filter_hp
        + BPF.ar(signal, preFreq, preRq) * pre_filter_bp
        + BRF.ar(signal, preFreq, preRq) * pre_filter_br;
    var postSignal = preSignal * post_filter_dry
        + RLPF.ar(preSignal, postFreq, postRq) * post_filter_lp
        + RHPF.ar(preSignal, postFreq, postRq) * post_filter_hp
        + BPF.ar(preSignal, postFreq, postRq) * post_filter_bp
        + BRF.ar(preSignal, postFreq, postRq) * post_filter_br;
    var monoOutput = postSignal * gate * smoothedLevel;

    // Internal phase witness only: never routed to Oboe stereo output buses.
    Out.ar(position_bus, boundedPhase / bufferSampleRate);
    Out.ar(cut_bus, monoOutput);
    Out.ar(out, Pan2.ar(monoOutput, smoothedPan));
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");

SynthDef(\SoftcutRecorder, {
    |buf = 20001, in_bus = 2, engine_bus = 31, start_frame = 0, end_frame = 1,
     position = 0, position_bus = 17, diagnostic_bus = 19, rate = 1, record = 0,
     rec_level = 0, pre_level = 1, input_level = 1, adc_level = 1, engine_level = 0,
     enabled = 0, rec_offset = -0.00015,
     recpre_slew_time = 0.001, level_slew_time = 0.001,
     cut_bus_1 = 3, cut_bus_2 = 4, cut_bus_3 = 5,
     cut_bus_4 = 6, cut_bus_5 = 7, cut_bus_6 = 8,
     cut_from_1 = 0, cut_from_2 = 0, cut_from_3 = 0,
     cut_from_4 = 0, cut_from_5 = 0, cut_from_6 = 0|

    var sampleRate = BufSampleRate.kr(buf);
    var bufferFrames = BufFrames.kr(buf);
    var start = start_frame.clip(0, bufferFrames - 1);
    var end = end_frame.clip(start + 1, bufferFrames);
    var recordGate = record * enabled;
    var physicalInput = In.ar(in_bus, 1);
    var engineInput = In.ar(engine_bus, 1)
        * Lag.kr(engine_level, level_slew_time.max(0));
    var scaledInput = physicalInput * Lag.kr(input_level, level_slew_time.max(0))
        * Lag.kr(adc_level, level_slew_time.max(0));
    var reset = Changed.kr(position) + Changed.kr(record);
    var step = rate * BufRateScale.kr(buf) * recordGate;
    var phase = Phasor.ar(reset, step, start, end,
        (position * sampleRate).clip(start, end - 1));
    // Interpolação desligada: parado, BufWr reescreve exatamente a amostra lida.
    var rawWritePhase = phase + (rec_offset * sampleRate);
    var writeValid = (rawWritePhase >= 0) * (rawWritePhase < bufferFrames);
    var writePhase = rawWritePhase.clip(0, bufferFrames - 1);
    var previous = BufRd.ar(1, buf, writePhase, 1, 1);
    var cutMix = In.ar(cut_bus_1, 1) * Lag.kr(cut_from_1, level_slew_time.max(0))
        + In.ar(cut_bus_2, 1) * Lag.kr(cut_from_2, level_slew_time.max(0))
        + In.ar(cut_bus_3, 1) * Lag.kr(cut_from_3, level_slew_time.max(0))
        + In.ar(cut_bus_4, 1) * Lag.kr(cut_from_4, level_slew_time.max(0))
        + In.ar(cut_bus_5, 1) * Lag.kr(cut_from_5, level_slew_time.max(0))
        + In.ar(cut_bus_6, 1) * Lag.kr(cut_from_6, level_slew_time.max(0));
    var incoming = scaledInput + engineInput + cutMix;
    var overdub = previous * Lag.kr(pre_level, recpre_slew_time.max(0))
        + incoming * Lag.kr(rec_level, recpre_slew_time.max(0));
    var written = previous + ((overdub - previous) * recordGate * writeValid);

    // Cursor witness is diagnostic-only: driver reads bus 17/18 but never copies it to Oboe.
    Out.ar(position_bus, writePhase / sampleRate);
    // Diagnostic-only buses: physical input, scaled input, cut matrix, final mix,
    // write gate and validity. They never reach the audible Softcut/Oboe buses.
    Out.ar(diagnostic_bus, [physicalInput, scaledInput, cutMix, incoming,
        K2A.ar(recordGate), K2A.ar(writeValid)]);
    BufWr.ar(written.clip(-1, 1), buf, writePhase, 0);
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");

// Fonte de teste isolada para alimentar os SoftcutRecorder reais em dois buses.
SynthDef(\SoftcutIsolationInput, {
    |out_bus = 30, value = 0.25, duration = 0.02|
    Line.kr(0, 1, duration.max(0.01), doneAction: 2);
    Out.ar(out_bus, DC.ar(value));
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");


// Android-portable 32Harmony engine. This SynthDef is compiled ahead of time;
// no sclang or CroneEngine class runtime is required on the device.
SynthDef(\Norns32Harmony, {
    |out = 0, rate1In = 1, rate2In = 1, rootIn = 32.7,
     range1In = 4, range2In = 4, attackIn = 0.01, decayIn = 3.5,
     lpfCutoffIn = 4000, hpfCutoffIn = 20, quantAmtIn = 0.93,
     step0In = 1, step1In = 1.122462, step2In = 1.259921,
     step3In = 1.498307, step4In = 1.681793,
     freqMultIn = 1, saturationAmtIn = 1|
    var left, right, signal, sat;
    left = Mix.fill(16, {
        var trigger, randomStep, quantizedStep, step, octave, frequency, envelope;
        trigger = Dust.ar(rate1In.clip(0.1, 20000) / 16);
        randomStep = TRand.ar(1.0, 2.0, trigger);
        quantizedStep = Demand.ar(trigger, 0,
            Drand([step0In.clip(0.01, 32), step1In.clip(0.01, 32), step2In.clip(0.01, 32), step3In.clip(0.01, 32), step4In.clip(0.01, 32)], inf));
        step = randomStep + ((quantizedStep - randomStep) * quantAmtIn.clip(0, 1));
        octave = TIRand.ar(1, range1In.clip(1, 9), trigger);
        frequency = (Lag.kr(rootIn.clip(1, 20000), 0.18) * freqMultIn.clip(0.01, 32) * step * (2 ** octave)).clip(10, 20000);
        envelope = EnvGen.ar(Env.perc(attackIn.clip(0.001, 20), decayIn.clip(0.001, 30), 1), trigger);
        SinOsc.ar(frequency) * envelope * 0.04
    });
    right = Mix.fill(16, {
        var trigger, randomStep, quantizedStep, step, octave, frequency, envelope;
        trigger = Dust.ar(rate2In.clip(0.1, 20000) / 16);
        randomStep = TRand.ar(1.0, 2.0, trigger);
        quantizedStep = Demand.ar(trigger, 0,
            Drand([step0In.clip(0.01, 32), step1In.clip(0.01, 32), step2In.clip(0.01, 32), step3In.clip(0.01, 32), step4In.clip(0.01, 32)], inf));
        step = randomStep + ((quantizedStep - randomStep) * quantAmtIn.clip(0, 1));
        octave = TIRand.ar(1, range2In.clip(1, 9), trigger);
        frequency = (Lag.kr(rootIn.clip(1, 20000), 0.18) * freqMultIn.clip(0.01, 32) * step * (2 ** octave)).clip(10, 20000);
        envelope = EnvGen.ar(Env.perc(attackIn.clip(0.001, 20), decayIn.clip(0.001, 30), 1), trigger);
        SinOsc.ar(frequency) * envelope * 0.04
    });
    signal = [left, right];
    signal = HPF.ar(signal, hpfCutoffIn.clip(10, 20000));
    signal = LPF.ar(signal, Lag.kr(lpfCutoffIn.clip(10, 20000), 0.18));
    sat = saturationAmtIn.clip(0.1, 300);
    signal = signal * sat / 2;
    signal = (signal - DC.ar(0.6)).tanh + DC.ar(0.535);
    signal = signal * 2 / sat;
    Out.ar(out, signal);
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");


// Stratum's Android-native engine: MiRings feeds MiClouds in one serial graph.
// This is a precompiled SynthDef; the Android app does not need sclang/CroneEngine.
SynthDef(\NornsStratum, {
    |out = 0, rings_pit = 48, rings_struct = 0.36, rings_bright = 0.5,
     rings_damp = 0.5, rings_pos = 0.33, rings_model = 0,
     rings_poly = 4, rings_easteregg = 0, rings_gate = 0, rings_amp = 0.8,
     rings_odd_even = 0.5, rings_bypass = 0,
     clouds_pos = 0.5, clouds_size = 0.3, clouds_pitch = 0,
     clouds_dens = 0.5, clouds_tex = 0.5, clouds_spread = 0,
     clouds_fb = 0.2, clouds_freeze = 0, clouds_rvb = 0.1,
     clouds_lofi = 0, clouds_mode = 0, clouds_mix = 0.45, clouds_bypass = 0|
    var ringTrig, ringPair, ringsMono, ringsSignal, ringsOut;
    var cloudsSignal, blended, wet;

    ringTrig = Trig.kr(rings_gate, 0.01);
    ringPair = MiRings.ar(
        in: 0, trig: ringTrig, pit: rings_pit,
        struct: rings_struct, bright: rings_bright, damp: rings_damp,
        pos: rings_pos, model: rings_model, poly: rings_poly,
        intern_exciter: 0, easteregg: rings_easteregg,
        bypass: rings_bypass, mul: 1.0, add: 0
    );
    ringsMono = (ringPair[0] * rings_odd_even)
        + (ringPair[1] * (1 - rings_odd_even));
    ringsSignal = ringsMono * rings_amp;
    ringsOut = [ringsSignal, ringsSignal];

    cloudsSignal = MiClouds.ar(
        ringsOut, pit: clouds_pitch, pos: clouds_pos,
        size: clouds_size, dens: clouds_dens, tex: clouds_tex,
        drywet: 1.0, in_gain: 1.0, spread: clouds_spread,
        rvb: clouds_rvb, fb: clouds_fb, freeze: clouds_freeze,
        mode: clouds_mode, lofi: clouds_lofi, trig: 0
    );

    blended = (ringsOut * (1 - clouds_mix)) + (cloudsSignal * clouds_mix);
    wet = (blended * (1 - clouds_bypass)) + (ringsOut * clouds_bypass);
    Out.ar(out, wet);
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");


// Android-portable SimpleDelayKolor sample player. Twelve independent nodes
// are created lazily by boot.lua after their corresponding WAV bank is loaded.
SynthDef(\NornsKolorDelay, {
    |out = 0, delay = 0.03, volume = 0|
    var input = SoundIn.ar([0, 1]);
    var delayed = DelayC.ar(input, 0.03, delay.clip(0.001, 0.03));
    Out.ar(out, delayed * volume.clip(0, 4));
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");

SynthDef(\NornsKolorPlayer, {
    |out = 0, sampleBufnum = 30001, t_trig = 0, lfolfo = 0, currentTime = 0,
     ampMin = 0, ampMax = 0, ampLFOMin = 0, ampLFOMax = 0,
     rateMin = 1, rateMax = 1, rateLFOMin = 0, rateLFOMax = 0,
     panMin = 0, panMax = 0, panLFOMin = 0, panLFOMax = 0,
     lpfMin = 20000, lpfMax = 20000, lpfLFOMin = 0, lpfLFOMax = 0,
     resonanceMin = 2, resonanceMax = 2, resonanceLFOMin = 0, resonanceLFOMax = 0,
     hpfMin = 10, hpfMax = 10, hpfLFOMin = 0, hpfLFOMax = 0,
     sampleStartMin = 0, sampleStartMax = 0, sampleStartLFOMin = 0, sampleStartLFOMax = 0,
     sampleEndMin = 1, sampleEndMax = 1, sampleEndLFOMin = 0, sampleEndLFOMax = 0,
     retrigMin = 1, retrigMax = 1, retrigLFOMin = 0, retrigLFOMax = 0,
     delaySendMin = 1, delaySendMax = 1, delaySendLFOMin = 0, delaySendLFOMax = 0,
     delayFeedbackMin = 1, delayFeedbackMax = 1, delayFeedbackLFOMin = 0, delayFeedbackLFOMax = 0,
     secondsPerBeat = 0.5, t_gate = 0|

    var lfo, amp, rate, pan, lpf, resonance, hpf, sampleStart, sampleEnd;
    var retrig, delaySend, delayFeedback, frames, startFrame, endFrame;
    var phase, bufsnd, snd, duration, envelope, dry, echo;

    lfo = { |minimum, maximum, lfoMinimum, lfoMaximum|
        var phaseOffset = (currentTime * 2pi * lfoMinimum).mod(2pi);
        SinOsc.kr(
            SinOsc.kr(lfolfo.clip(0, 40), phaseOffset,
                mul: (lfoMaximum - lfoMinimum), add: (lfoMaximum + lfoMinimum) / 2),
            phaseOffset, mul: (maximum - minimum) / 2, add: (maximum + minimum) / 2
        )
    };

    amp = lfo.(ampMin, ampMax, ampLFOMin, ampLFOMax).clip(0, 4);
    rate = lfo.(rateMin, rateMax, rateLFOMin, rateLFOMax).clip(-8, 8);
    pan = lfo.(panMin, panMax, panLFOMin, panLFOMax).clip(-1, 1);
    lpf = lfo.(lpfMin, lpfMax, lpfLFOMin, lpfLFOMax).clip(10, SampleRate.ir * 0.45);
    resonance = lfo.(resonanceMin, resonanceMax, resonanceLFOMin, resonanceLFOMax).clip(0.1, 4);
    hpf = lfo.(hpfMin, hpfMax, hpfLFOMin, hpfLFOMax).clip(10, SampleRate.ir * 0.45);
    sampleStart = lfo.(sampleStartMin, sampleStartMax, sampleStartLFOMin, sampleStartLFOMax).clip(0, 1);
    sampleEnd = lfo.(sampleEndMin, sampleEndMax, sampleEndLFOMin, sampleEndLFOMax).clip(0, 1);
    retrig = lfo.(retrigMin, retrigMax, retrigLFOMin, retrigLFOMax);
    delaySend = lfo.(delaySendMin, delaySendMax, delaySendLFOMin, delaySendLFOMax).clip(0, 4);
    delayFeedback = lfo.(delayFeedbackMin, delayFeedbackMax,
        delayFeedbackLFOMin, delayFeedbackLFOMax).clip(0.01, 128);

    frames = BufFrames.kr(sampleBufnum).max(1);
    startFrame = ((sampleStart * (rate > 0)) + (sampleEnd * (rate < 0))) * frames;
    endFrame = ((sampleEnd * (rate > 0)) + (sampleStart * (rate < 0))) * frames;
    endFrame = Select.kr(endFrame > startFrame, [startFrame + 1, endFrame]);
    phase = Phasor.ar(t_trig, BufRateScale.kr(sampleBufnum) * rate,
        startFrame, endFrame, startFrame);
    bufsnd = BufRd.ar(2, sampleBufnum, phase, loop: (retrig > 0), interpolation: 1);
    bufsnd = MoogFF.ar(bufsnd, lpf, resonance);
    bufsnd = HPF.ar(bufsnd, hpf);
    snd = Mix.ar([
        Pan2.ar(bufsnd[0], (-1 + (2 * pan)).clip(-1, 1), amp),
        Pan2.ar(bufsnd[1], (1 + (2 * pan)).clip(-1, 1), amp)
    ]);

    duration = (((sampleEnd - sampleStart).abs / rate.abs.max(0.001))
        * (retrig.max(0) + 1) * BufDur.kr(sampleBufnum) - 0.015).max(0.005);
    envelope = EnvGen.ar(Env([0, 1, 1, 0], [0.005, duration, 0.005]), gate: t_gate);
    dry = snd * envelope;
    echo = CombN.ar(dry, 1,
        (secondsPerBeat.clip(0.001, 4) / 4).clip(0.001, 1),
        (secondsPerBeat.clip(0.001, 4) / 8 * delayFeedback).clip(0.01, 16),
        0.75 * delaySend);
    Out.ar(out, dry + echo);
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");

// Deterministic left/right routing source used only by the muted Oboe probe mode.
SynthDef(\SoftcutStereoRouteProbe, {
    |left_bus = 9, right_bus = 10, left = 0.25, right = -0.375|
    Out.ar(left_bus, DC.ar(left));
    Out.ar(right_bus, DC.ar(right));
}).writeDefFile("app/src/main/assets/scsynth/synthdefs");

0.exit;
)
