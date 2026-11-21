#!/usr/bin/env python3
"""Validate bundled SuperCollider SynthDef v2 assets used by Norns Mobile."""

import math
import struct
import sys
from pathlib import Path


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        end = self.offset + size
        if end > len(self.data):
            raise ValueError("truncated SynthDef")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def i8(self):
        return struct.unpack(">b", self.take(1))[0]

    def i16(self):
        return struct.unpack(">h", self.take(2))[0]

    def i32(self):
        return struct.unpack(">i", self.take(4))[0]

    def f32(self):
        return struct.unpack(">f", self.take(4))[0]

    def pstring(self):
        size = self.take(1)[0]
        return self.take(size).decode("utf-8")


def parse(path):
    reader = Reader(Path(path).read_bytes())
    if reader.take(4) != b"SCgf":
        raise ValueError("invalid SynthDef magic")
    if reader.i32() != 2:
        raise ValueError("expected SynthDef format version 2")
    if reader.i16() != 1:
        raise ValueError("expected exactly one SynthDef")

    name = reader.pstring()
    constants = [reader.f32() for _ in range(reader.i32())]
    parameters = [reader.f32() for _ in range(reader.i32())]
    param_names = [(reader.pstring(), reader.i32()) for _ in range(reader.i32())]
    ugens = []
    for _ in range(reader.i32()):
        ugen_name = reader.pstring()
        rate = reader.i8()
        input_count = reader.i32()
        output_count = reader.i32()
        special = reader.i16()
        inputs = [(reader.i32(), reader.i32()) for _ in range(input_count)]
        outputs = [reader.i8() for _ in range(output_count)]
        ugens.append((ugen_name, rate, special, inputs, outputs))
    variants = reader.i16()
    for _ in range(variants):
        reader.pstring()
        for _ in parameters:
            reader.f32()
    if reader.offset != len(reader.data):
        raise ValueError("unexpected trailing bytes")
    if variants != 0:
        raise ValueError("unexpected SynthDef variants")

    if name == "TestSine":
        expected_constants = [0.0]
        expected_parameters = [440.0, 0.1]
        expected_names = [("freq", 0), ("amp", 1)]
        expected_ugens = [
            ("Control", 1, 0, [], [1, 1]),
            ("SinOsc", 2, 0, [(0, 0), (-1, 0)], [2]),
            ("BinaryOpUGen", 2, 2, [(1, 0), (0, 1)], [2]),
            ("Out", 2, 0, [(-1, 0), (2, 0)], []),
        ]
    elif name == "SoftcutVoice":
        # O SynthDef é compilado pelo sclang no CI. Validamos sua interface
        # pública e os UGens essenciais sem acoplar o teste à ordenação interna
        # de operações aritméticas gerada por cada versão do compilador.
        required_parameters = {
            "out", "position_bus", "buf", "rate", "loop_start", "loop_end",
            "position", "play", "phase_play", "phase_enable", "loop", "level", "pan",
            "rate_slew_time", "level_slew_time", "pan_slew_time", "enable", "fade_time", "cut_bus",
            "pre_filter_fc", "pre_filter_fc_mod", "pre_filter_rq",
            "pre_filter_lp", "pre_filter_hp", "pre_filter_bp", "pre_filter_br", "pre_filter_dry",
            "post_filter_fc", "post_filter_fc_mod", "post_filter_rq",
            "post_filter_lp", "post_filter_hp", "post_filter_bp", "post_filter_br", "post_filter_dry",
        }
        names = {parameter_name for parameter_name, _ in param_names}
        out_index = next(index for parameter_name, index in param_names if parameter_name == "out")
        position_bus_index = next(index for parameter_name, index in param_names if parameter_name == "position_bus")
        phase_play_index = next(index for parameter_name, index in param_names if parameter_name == "phase_play")
        phase_enable_index = next(index for parameter_name, index in param_names if parameter_name == "phase_enable")
        if not math.isclose(parameters[out_index], 9.0, abs_tol=1e-6):
            raise ValueError("SoftcutVoice: expected dedicated stereo output bus 9/10")
        if not math.isclose(parameters[position_bus_index], 11.0, abs_tol=1e-6):
            raise ValueError("SoftcutVoice: expected phase witness bus 11 as the default")
        if not math.isclose(parameters[phase_play_index], 0.0, abs_tol=1e-6):
            raise ValueError("SoftcutVoice: phase tracker must start stopped")
        if not math.isclose(parameters[phase_enable_index], 1.0, abs_tol=1e-6):
            raise ValueError("SoftcutVoice: phase tracker enable default must be one")
        ugen_names = {ugen[0] for ugen in ugens}
        required_ugens = {"Control", "BufRateScale", "BufSampleRate",
                          "Phasor", "BufRd", "Lag", "HPZ1", "Clip",
                          "Select", "Pan2", "Out", "RLPF", "RHPF", "BPF", "BRF"}
        if not required_parameters.issubset(names):
            missing = sorted(required_parameters - names)
            raise ValueError("SoftcutVoice: missing controls: " + ", ".join(missing))
        if not required_ugens.issubset(ugen_names):
            missing = sorted(required_ugens - ugen_names)
            print("SoftcutVoice actual UGens:", ", ".join(sorted(ugen_names)))
            raise ValueError("SoftcutVoice: missing UGens: " + ", ".join(missing))
        if len(param_names) != len(required_parameters):
            raise ValueError("SoftcutVoice: unexpected public controls")
        if sum(1 for ugen in ugens if ugen[0] == "Out") < 3:
            raise ValueError("SoftcutVoice: audio, cut and phase witness outputs are required")

        # Changed.kr(position) is lowered by sclang to HPZ1 plus comparison
        # UGens. Verify that this edge detector actually reaches Phasor.reset,
        # rather than merely existing elsewhere in the graph.
        phasor_indices = [i for i, ugen in enumerate(ugens) if ugen[0] == "Phasor"]
        if len(phasor_indices) != 1:
            raise ValueError("SoftcutVoice: expected exactly one Phasor")
        def depends_on_ugen(index, target, visited):
            if index < 0 or index >= len(ugens) or index in visited:
                return False
            visited.add(index)
            ugen = ugens[index]
            if ugen[0] == target:
                return True
            return any(
                depends_on_ugen(source, target, visited)
                for source, _ in ugen[3]
                if source >= 0
            )
        reset_source = ugens[phasor_indices[0]][3][0][0]
        if not depends_on_ugen(reset_source, "HPZ1", set()):
            raise ValueError("SoftcutVoice: seek edge detector is not connected to Phasor.reset")

        def depends_on_parameter(index, output_index, visited):
            if index < 0 or index >= len(ugens) or index in visited:
                return False
            visited.add(index)
            for source, source_output in ugens[index][3]:
                if source < 0:
                    continue
                if ugens[source][0] == "Control" and source_output == output_index:
                    return True
                if depends_on_parameter(source, output_index, visited):
                    return True
            return False

        step_source = ugens[phasor_indices[0]][3][1][0]
        for control_index, control_name in (
            (phase_play_index, "phase_play"),
            (phase_enable_index, "phase_enable"),
        ):
            if not depends_on_parameter(step_source, control_index, set()):
                raise ValueError("SoftcutVoice: " + control_name + " is not connected to Phasor.step")

        # position_bus is a declared control and the third Out is the isolated
        # phase witness. Control.kr output indices may be re-grouped by sclang,
        # so keep the stable named-control/output-count checks above.
        if "position_bus" not in names:
            raise ValueError("SoftcutVoice: phase witness bus control is missing")

        # Changed.kr(position/loop bounds) is lowered to HPZ1 + comparison UGens.
        print("OK: SoftcutVoice SynthDef v2 interface, muted phase tracker, seek edge and buses")
        return
    elif name == "SoftcutRecorder":
        required_parameters = {
            "buf", "in_bus", "engine_bus", "start_frame", "end_frame", "position", "position_bus", "diagnostic_bus",
            "rate", "record", "rec_level", "pre_level", "input_level", "engine_level",
            "recpre_slew_time", "level_slew_time", "adc_level", "enabled", "rec_offset",
            "cut_bus_1", "cut_bus_2", "cut_bus_3", "cut_bus_4", "cut_bus_5", "cut_bus_6",
            "cut_from_1", "cut_from_2", "cut_from_3", "cut_from_4", "cut_from_5", "cut_from_6",
        }
        names = {parameter_name for parameter_name, _ in param_names}
        ugen_names = {ugen[0] for ugen in ugens}
        required_ugens = {
            "Control", "BufSampleRate", "BufFrames", "BufRateScale",
            "Phasor", "BufRd", "BufWr", "In", "Lag", "HPZ1", "Out",
        }
        if not required_parameters.issubset(names):
            missing = sorted(required_parameters - names)
            raise ValueError("SoftcutRecorder: missing controls: " + ", ".join(missing))
        if not required_ugens.issubset(ugen_names):
            missing = sorted(required_ugens - ugen_names)
            raise ValueError("SoftcutRecorder: missing UGens: " + ", ".join(missing))
        if len(param_names) != len(required_parameters):
            raise ValueError("SoftcutRecorder: unexpected public controls")
        position_bus_index = next(index for parameter_name, index in param_names if parameter_name == "position_bus")
        diagnostic_bus_index = next(index for parameter_name, index in param_names if parameter_name == "diagnostic_bus")
        if not math.isclose(parameters[position_bus_index], 17.0, abs_tol=1e-6):
            raise ValueError("SoftcutRecorder: expected cursor witness bus 17")
        if not math.isclose(parameters[diagnostic_bus_index], 19.0, abs_tol=1e-6):
            raise ValueError("SoftcutRecorder: expected five-channel diagnostic bus 19")
        input_ugens = [i for i, ugen in enumerate(ugens) if ugen[0] == "In"]
        if len(input_ugens) != 8:
            raise ValueError("SoftcutRecorder: expected physical input, engine send and six cut matrix buses")

        def depends_on_ugen(index, target, visited):
            if index < 0 or index >= len(ugens) or index in visited:
                return False
            visited.add(index)
            ugen = ugens[index]
            if ugen[0] == target:
                return True
            return any(
                depends_on_ugen(source, target, visited)
                for source, _ in ugen[3]
                if source >= 0
            )

        def depends_on_index(index, target_index, visited):
            if index < 0 or index >= len(ugens) or index in visited:
                return False
            if index == target_index:
                return True
            visited.add(index)
            return any(depends_on_index(source, target_index, visited)
                       for source, _ in ugens[index][3] if source >= 0)

        def depends_on_parameter(index, parameter_index, visited):
            if index < 0 or index >= len(ugens) or index in visited:
                return False
            visited.add(index)
            for source, source_output in ugens[index][3]:
                if source < 0:
                    continue
                if ugens[source][0] == "Control" and source_output == parameter_index:
                    return True
                if depends_on_parameter(source, parameter_index, visited):
                    return True
            return False

        engine_level_index = next(index for name, index in param_names if name == "engine_level")
        engine_bus_index = next(index for name, index in param_names if name == "engine_bus")
        if not math.isclose(parameters[engine_bus_index], 31.0, abs_tol=1e-6):
            raise ValueError("SoftcutRecorder: expected reserved native engine send bus 31")
        if not math.isclose(parameters[engine_level_index], 0.0, abs_tol=1e-6):
            raise ValueError("SoftcutRecorder: engine send must start muted")
        engine_input = next((index for index in input_ugens
                             if depends_on_parameter(index, engine_bus_index, set())), -1)
        if engine_input < 0:
            raise ValueError("SoftcutRecorder: engine_bus is not connected to an In UGen")
        phasors = [i for i, ugen in enumerate(ugens) if ugen[0] == "Phasor"]
        writers = [i for i, ugen in enumerate(ugens) if ugen[0] == "BufWr"]
        if len(phasors) != 1 or len(writers) != 1:
            raise ValueError("SoftcutRecorder: expected one Phasor and one exclusive BufWr")
        if not depends_on_ugen(ugens[phasors[0]][3][0][0], "HPZ1", set()):
            raise ValueError("SoftcutRecorder: position change is not connected to Phasor.reset")
        writer_inputs = ugens[writers[0]][3]
        if len(writer_inputs) < 4:
            raise ValueError("SoftcutRecorder: BufWr requires input, buffer, phase and loop")
        buf_index = next(index for parameter_name, index in param_names if parameter_name == "buf")
        # BufWr.multiNewList serializes inputs as [bufnum, phase, loop, *inputArray],
        # not in the public method's argument order [inputArray, bufnum, phase, loop].
        writer_buffer_source, writer_buffer_output = writer_inputs[0]
        if writer_buffer_source < 0 or ugens[writer_buffer_source][0] != "Control" \
                or writer_buffer_output != buf_index:
            raise ValueError("SoftcutRecorder: BufWr buffer must use the recorder's buf control")
        writer_phase_source = writer_inputs[1][0]
        if writer_phase_source < 0 or not depends_on_ugen(writer_phase_source, "Phasor", set()):
            raise ValueError("SoftcutRecorder: BufWr phase must use the recorder Phasor")
        writer_signal_inputs = writer_inputs[3:]
        for input_index in input_ugens:
            if not any(depends_on_index(source, input_index, set())
                       for source, _ in writer_signal_inputs if source >= 0):
                raise ValueError("SoftcutRecorder: an input/cut matrix bus is disconnected from BufWr")
        if not any(depends_on_ugen(source, "BufRd", set()) for source, _ in writer_signal_inputs if source >= 0):
            raise ValueError("SoftcutRecorder: pre-level buffer signal is not connected to BufWr")
        out_ugens = [i for i, ugen in enumerate(ugens) if ugen[0] == "Out"]
        if len(out_ugens) != 2:
            raise ValueError("SoftcutRecorder: expected cursor and isolated signal diagnostic outputs")
        # The recorder witness must tap the exact phase input used by BufWr,
        # after rec_offset, not the unshifted playback/record head.
        cursor_out = next((i for i in out_ugens
                           if any(depends_on_index(source, writer_phase_source, set())
                                  for source, _ in ugens[i][3][1:] if source >= 0)), -1)
        if cursor_out < 0:
            raise ValueError("SoftcutRecorder: cursor witness is not connected to the BufWr write phase")
        diagnostic_out = next((i for i in out_ugens if i != cursor_out), -1)
        if diagnostic_out < 0 or len(ugens[diagnostic_out][3]) != 7:
            raise ValueError("SoftcutRecorder: expected six diagnostic signals plus bus index")
        diagnostic_sources = [source for source, _ in ugens[diagnostic_out][3][1:] if source >= 0]
        if len(diagnostic_sources) != 6:
            raise ValueError("SoftcutRecorder: diagnostic output must expose five signal channels")
        if not depends_on_parameter(diagnostic_sources[3], engine_level_index, set()):
            raise ValueError("SoftcutRecorder: incoming diagnostic omits engine_level")
        if not depends_on_index(diagnostic_sources[3], engine_input, set()):
            raise ValueError("SoftcutRecorder: incoming diagnostic omits the native engine bus")
        if not depends_on_index(diagnostic_sources[0], input_ugens[0], set()):
            raise ValueError("SoftcutRecorder: raw physical input diagnostic is disconnected")
        input_level_index = next(index for name, index in param_names if name == "input_level")
        adc_level_index = next(index for name, index in param_names if name == "adc_level")
        if not depends_on_index(diagnostic_sources[1], input_ugens[0], set()):
            raise ValueError("SoftcutRecorder: scaled input diagnostic omits physical input")
        if not depends_on_parameter(diagnostic_sources[1], input_level_index, set()):
            raise ValueError("SoftcutRecorder: scaled input diagnostic omits input_level")
        if not depends_on_parameter(diagnostic_sources[1], adc_level_index, set()):
            raise ValueError("SoftcutRecorder: scaled input diagnostic omits adc_level")
        # The eight In UGens are physical input, native engine send, and six
        # cut-matrix buses. Do not assume their order: identify each cut input
        # through its own cut_bus_N control, excluding engine_bus explicitly.
        cut_input_ugens = []
        for voice in range(1, 7):
            cut_bus_index = next(
                index for name, index in param_names if name == f"cut_bus_{voice}"
            )
            cut_input = next(
                (index for index in input_ugens
                 if depends_on_parameter(index, cut_bus_index, set())),
                -1,
            )
            if cut_input < 0:
                raise ValueError(
                    f"SoftcutRecorder: cut_bus_{voice} is not connected to an In UGen"
                )
            cut_input_ugens.append(cut_input)
        if len(set(cut_input_ugens)) != 6 or engine_input in cut_input_ugens:
            raise ValueError("SoftcutRecorder: cut matrix inputs are not six distinct buses")
        if not all(depends_on_index(diagnostic_sources[2], input_index, set())
                   for input_index in cut_input_ugens):
            raise ValueError("SoftcutRecorder: cutMix diagnostic omits a cut matrix input")
        if not depends_on_index(diagnostic_sources[3], input_ugens[0], set()):
            raise ValueError("SoftcutRecorder: incoming diagnostic omits scaled physical input")
        if not depends_on_index(diagnostic_sources[3], diagnostic_sources[2], set()):
            raise ValueError("SoftcutRecorder: incoming diagnostic omits cutMix")
        record_index = next(index for name, index in param_names if name == "record")
        enabled_index = next(index for name, index in param_names if name == "enabled")
        if not depends_on_parameter(diagnostic_sources[4], record_index, set()):
            raise ValueError("SoftcutRecorder: recordGate diagnostic is disconnected from record")
        if not depends_on_parameter(diagnostic_sources[4], enabled_index, set()):
            raise ValueError("SoftcutRecorder: recordGate diagnostic is disconnected from enabled")
        diagnostic_bus_source, diagnostic_bus_output = ugens[diagnostic_out][3][0]
        if diagnostic_bus_source < 0 or ugens[diagnostic_bus_source][0] != "Control" \
                or diagnostic_bus_output != diagnostic_bus_index:
            raise ValueError("SoftcutRecorder: diagnostic output bus is not controlled by diagnostic_bus")
        print("OK: SoftcutRecorder SynthDef v2, Oboe input, BufWr write-phase witness and six signal probes")
        return
    elif name == "SoftcutIsolationInput":
        names = {parameter_name for parameter_name, _ in param_names}
        required = {"out_bus", "value", "duration"}
        if names != required:
            raise ValueError("SoftcutIsolationInput: unexpected public controls")
        defaults = dict((parameter_name, parameters[index]) for parameter_name, index in param_names)
        for parameter_name, expected in (("out_bus", 30.0), ("value", 0.25), ("duration", 0.02)):
            if not math.isclose(defaults[parameter_name], expected, abs_tol=1e-6):
                raise ValueError("SoftcutIsolationInput: unexpected default " + parameter_name)
        by_name = {ugen[0]: (index, ugen) for index, ugen in enumerate(ugens)}
        for required_ugen in ("Control", "DC", "Line", "Out"):
            if required_ugen not in by_name:
                raise ValueError("SoftcutIsolationInput: missing UGen " + required_ugen)
        out_ugen = by_name["Out"][1]
        bus_source, bus_output = out_ugen[3][0]
        value_source = out_ugen[3][1][0]
        out_bus_index = next(index for parameter_name, index in param_names if parameter_name == "out_bus")
        if bus_source < 0 or ugens[bus_source][0] != "Control" or bus_output != out_bus_index:
            raise ValueError("SoftcutIsolationInput: Out bus must use out_bus control")
        if value_source < 0 or ugens[value_source][0] != "DC":
            raise ValueError("SoftcutIsolationInput: Out signal must be deterministic DC")
        print("OK: SoftcutIsolationInput provides isolated deterministic DC test bus")
        return
    elif name == "SoftcutStereoRouteProbe":
        required_parameters = {"left_bus", "right_bus", "left", "right"}
        names = {parameter_name for parameter_name, _ in param_names}
        if names != required_parameters or len(param_names) != len(required_parameters):
            raise ValueError("SoftcutStereoRouteProbe: unexpected public controls")
        defaults = dict((parameter_name, parameters[index]) for parameter_name, index in param_names)
        if defaults != {"left_bus": 9.0, "right_bus": 10.0, "left": 0.25, "right": -0.375}:
            raise ValueError("SoftcutStereoRouteProbe: unexpected probe values or buses")
        out_ugens = [ugen for ugen in ugens if ugen[0] == "Out"]
        if len(out_ugens) != 2 or any(len(ugen[3]) != 2 for ugen in out_ugens):
            raise ValueError("SoftcutStereoRouteProbe: expected two mono Out UGens")
        controls = {name: index for name, index in param_names}
        routed_controls = set()
        for ugen in out_ugens:
            source, output = ugen[3][0]
            if source < 0 or ugens[source][0] != "Control":
                raise ValueError("SoftcutStereoRouteProbe: bus must be a named control")
            for control_name in ("left_bus", "right_bus"):
                if output == controls[control_name]:
                    routed_controls.add(control_name)
        if routed_controls != {"left_bus", "right_bus"}:
            raise ValueError("SoftcutStereoRouteProbe: left/right buses are not independently routed")
        if sum(1 for ugen in ugens if ugen[0] == "DC") != 2:
            raise ValueError("SoftcutStereoRouteProbe: expected two independent DC sources")
        print("OK: SoftcutStereoRouteProbe, isolated buses 9/10 and distinct stereo sources")
        return
    elif name == "SoftcutStereoRouteProbe":
        required_parameters = {"left_bus", "right_bus", "left", "right"}
        names = {parameter_name for parameter_name, _ in param_names}
        if names != required_parameters or len(param_names) != len(required_parameters):
            raise ValueError("SoftcutStereoRouteProbe: unexpected public controls")
        defaults = dict((parameter_name, parameters[index]) for parameter_name, index in param_names)
        if defaults != {"left_bus": 9.0, "right_bus": 10.0, "left": 0.25, "right": -0.375}:
            raise ValueError("SoftcutStereoRouteProbe: unexpected probe values or buses")
        out_ugens = [ugen for ugen in ugens if ugen[0] == "Out"]
        if len(out_ugens) != 2 or any(len(ugen[3]) != 2 for ugen in out_ugens):
            raise ValueError("SoftcutStereoRouteProbe: expected two mono Out UGens")
        controls = {name: index for name, index in param_names}
        routed_controls = set()
        for ugen in out_ugens:
            source, output = ugen[3][0]
            if source < 0 or ugens[source][0] != "Control":
                raise ValueError("SoftcutStereoRouteProbe: bus must be a named control")
            for control_name in ("left_bus", "right_bus"):
                if output == controls[control_name]:
                    routed_controls.add(control_name)
        if routed_controls != {"left_bus", "right_bus"}:
            raise ValueError("SoftcutStereoRouteProbe: left/right buses are not independently routed")
        if sum(1 for ugen in ugens if ugen[0] == "DC") != 2:
            raise ValueError("SoftcutStereoRouteProbe: expected two independent DC sources")
        print("OK: SoftcutStereoRouteProbe, isolated buses 9/10 and distinct stereo sources")
        return
    elif name == "Norns32Harmony":
        required_parameters = {
            "out", "rate1In", "rate2In", "rootIn", "range1In", "range2In",
            "attackIn", "decayIn", "lpfCutoffIn", "hpfCutoffIn", "quantAmtIn",
            "step0In", "step1In", "step2In", "step3In", "step4In",
            "freqMultIn", "saturationAmtIn",
        }
        names = {parameter_name for parameter_name, _ in param_names}
        if names != required_parameters or len(param_names) != len(required_parameters):
            raise ValueError("Norns32Harmony: unexpected public controls")
        defaults = dict((parameter_name, parameters[index]) for parameter_name, index in param_names)
        if not math.isclose(defaults["out"], 0.0, abs_tol=1e-6):
            raise ValueError("Norns32Harmony: must write to master output bus 0")
        ugen_names = {ugen[0] for ugen in ugens}
        required_ugens = {"Control", "Dust", "TRand", "Demand", "Drand", "BinaryOpUGen",
                          "TIRand", "Lag", "SinOsc", "EnvGen", "HPF", "LPF", "DC", "Out"}
        if not required_ugens.issubset(ugen_names):
            missing = sorted(required_ugens - ugen_names)
            raise ValueError("Norns32Harmony: missing UGens: " + ", ".join(missing))
        for ugen_name in ("Dust", "TRand", "Demand", "TIRand", "SinOsc", "EnvGen"):
            count = sum(1 for ugen in ugens if ugen[0] == ugen_name)
            if count != 32:
                raise ValueError("Norns32Harmony: expected 32 " + ugen_name + " UGens, got " + str(count))

        if "SelectX" in ugen_names:
            raise ValueError("Norns32Harmony: SelectX must be lowered to portable linear interpolation")

        quant_index = next(index for parameter_name, index in param_names
                           if parameter_name == "quantAmtIn")

        def depends_on_parameter(index, parameter_index, visited):
            if index < 0 or index >= len(ugens) or index in visited:
                return False
            visited.add(index)
            for source, source_output in ugens[index][3]:
                if source < 0:
                    continue
                if ugens[source][0] == "Control" and source_output == parameter_index:
                    return True
                if depends_on_parameter(source, parameter_index, visited):
                    return True
            return False

        sine_oscillators = [ugen for ugen in ugens if ugen[0] == "SinOsc"]
        if len(sine_oscillators) != 32:
            raise ValueError("Norns32Harmony: expected 32 frequency oscillators")
        for voice, oscillator in enumerate(sine_oscillators, start=1):
            if not oscillator[3] or not depends_on_parameter(
                oscillator[3][0][0], quant_index, set()
            ):
                raise ValueError(
                    "Norns32Harmony: quantAmtIn is disconnected from voice " + str(voice)
                )
        out_ugens = [ugen for ugen in ugens if ugen[0] == "Out"]
        if len(out_ugens) != 1 or len(out_ugens[0][3]) != 3:
            raise ValueError("Norns32Harmony: expected one stereo Out to master bus")
        bus_source, bus_output = out_ugens[0][3][0]
        out_index = next(index for parameter_name, index in param_names if parameter_name == "out")
        if bus_source < 0 or ugens[bus_source][0] != "Control" or bus_output != out_index:
            raise ValueError("Norns32Harmony: master Out must use the out control")
        print("OK: Norns32Harmony SynthDef v2, 32 voices, stereo master output and controls")
        return
    elif name == "NornsKolorDelay":
        required_parameters = {"out", "delay", "volume"}
        names = {parameter_name for parameter_name, _ in param_names}
        if names != required_parameters or len(param_names) != len(required_parameters):
            raise ValueError("NornsKolorDelay: unexpected public controls")
        defaults = dict((parameter_name, parameters[index]) for parameter_name, index in param_names)
        for parameter_name, expected in (("out", 0.0), ("delay", 0.03), ("volume", 0.0)):
            if not math.isclose(defaults[parameter_name], expected, abs_tol=1e-6):
                raise ValueError("NornsKolorDelay: unexpected default " + parameter_name)
        ugen_names = {ugen[0] for ugen in ugens}
        if not {"Control", "In", "DelayC", "Out"}.issubset(ugen_names):
            raise ValueError("NornsKolorDelay: missing input, delay or output UGens")
        out_ugens = [ugen for ugen in ugens if ugen[0] == "Out"]
        if len(out_ugens) != 1 or len(out_ugens[0][3]) != 3:
            raise ValueError("NornsKolorDelay: expected stereo output on the master bus")
        out_index = next(index for parameter_name, index in param_names if parameter_name == "out")
        bus_source, bus_output = out_ugens[0][3][0]
        if bus_source < 0 or ugens[bus_source][0] != "Control" or bus_output != out_index:
            raise ValueError("NornsKolorDelay: output must use the out control")
        print("OK: NornsKolorDelay SynthDef v2, stereo input, bounded delay and muted default")
        return
    elif name == "NornsKolorPlayer":
        groups = ("amp", "rate", "pan", "lpf", "resonance", "hpf",
                  "sampleStart", "sampleEnd", "retrig", "delaySend", "delayFeedback")
        required_parameters = {"out", "sampleBufnum", "t_trig", "lfolfo", "currentTime",
                               "secondsPerBeat", "t_gate"}
        for group in groups:
            required_parameters.update((group + "Min", group + "Max",
                                        group + "LFOMin", group + "LFOMax"))
        names = {parameter_name for parameter_name, _ in param_names}
        if names != required_parameters or len(param_names) != len(required_parameters):
            raise ValueError("NornsKolorPlayer: unexpected public controls")
        defaults = dict((parameter_name, parameters[index]) for parameter_name, index in param_names)
        if not math.isclose(defaults["out"], 0.0, abs_tol=1e-6):
            raise ValueError("NornsKolorPlayer: must write to master output bus 0")
        if not math.isclose(defaults["t_gate"], 0.0, abs_tol=1e-6):
            raise ValueError("NornsKolorPlayer: voices must start gated")
        if not math.isclose(defaults["secondsPerBeat"], 0.5, abs_tol=1e-6):
            raise ValueError("NornsKolorPlayer: unexpected default beat duration")
        ugen_names = {ugen[0] for ugen in ugens}
        required_ugens = {"Control", "SinOsc", "BufFrames", "BufRateScale", "Phasor",
                          "BufRd", "MoogFF", "HPF", "Pan2", "EnvGen", "CombN", "Out"}
        if not required_ugens.issubset(ugen_names):
            missing = sorted(required_ugens - ugen_names)
            raise ValueError("NornsKolorPlayer: missing UGens: " + ", ".join(missing))
        if sum(1 for ugen in ugens if ugen[0] == "SinOsc") < 22:
            raise ValueError("NornsKolorPlayer: expected nested LFO oscillators for all 11 controls")
        if sum(1 for ugen in ugens if ugen[0] == "Out") != 1:
            raise ValueError("NornsKolorPlayer: expected one stereo master output")
        print("OK: NornsKolorPlayer SynthDef v2, 12-bank controls, nested LFOs, filters and delay")
        return
    elif name == "NornsStratum":
        required_parameters = {
            "out", "rings_gate", "rings_pit", "rings_struct", "rings_bright",
            "rings_damp", "rings_pos", "rings_model", "rings_poly", "rings_easteregg",
            "rings_amp", "rings_odd_even", "rings_bypass", "clouds_pos", "clouds_size",
            "clouds_pitch", "clouds_dens", "clouds_tex", "clouds_spread", "clouds_fb",
            "clouds_freeze", "clouds_rvb", "clouds_lofi", "clouds_mode", "clouds_mix",
            "clouds_bypass",
        }
        names = {parameter_name for parameter_name, _ in param_names}
        if names != required_parameters or len(param_names) != len(required_parameters):
            raise ValueError("NornsStratum: unexpected public controls")
        defaults = dict((parameter_name, parameters[index]) for parameter_name, index in param_names)
        if not math.isclose(defaults["out"], 0.0, abs_tol=1e-6):
            raise ValueError("NornsStratum: must write to master output bus 0")
        if not math.isclose(defaults["rings_gate"], 0.0, abs_tol=1e-6):
            raise ValueError("NornsStratum: Rings gate must start closed")
        if not math.isclose(defaults["clouds_bypass"], 0.0, abs_tol=1e-6):
            raise ValueError("NornsStratum: Clouds bypass default must be disabled")
        ugen_names = {ugen[0] for ugen in ugens}
        if not {"Control", "Trig", "MiRings", "MiClouds", "Out"}.issubset(ugen_names):
            missing = sorted({"Control", "Trig", "MiRings", "MiClouds", "Out"} - ugen_names)
            raise ValueError("NornsStratum: missing UGens: " + ", ".join(missing))
        rings = [i for i, ugen in enumerate(ugens) if ugen[0] == "MiRings"]
        clouds = [i for i, ugen in enumerate(ugens) if ugen[0] == "MiClouds"]
        outputs = [i for i, ugen in enumerate(ugens) if ugen[0] == "Out"]
        if len(rings) != 1 or len(clouds) != 1 or len(outputs) != 1:
            raise ValueError("NornsStratum: expected one Rings, one Clouds and one master Out")
        if len(ugens[rings[0]][4]) != 2 or len(ugens[clouds[0]][4]) != 2:
            raise ValueError("NornsStratum: Rings and Clouds must both be stereo")
        if len(ugens[outputs[0]][3]) != 3:
            raise ValueError("NornsStratum: expected one stereo Out to master bus")

        def depends_on_ugen(index, target, visited):
            if index < 0 or index >= len(ugens) or index in visited:
                return False
            visited.add(index)
            if ugens[index][0] == target:
                return True
            return any(depends_on_ugen(source, target, visited)
                       for source, _ in ugens[index][3] if source >= 0)

        if not depends_on_ugen(clouds[0], "MiRings", set()):
            raise ValueError("NornsStratum: MiClouds input is not driven by MiRings")
        if not depends_on_ugen(outputs[0], "MiClouds", set()):
            raise ValueError("NornsStratum: Clouds output is not connected to master Out")
        if not depends_on_ugen(outputs[0], "MiRings", set()):
            raise ValueError("NornsStratum: dry Rings path is not connected to master Out")
        out_index = next(index for parameter_name, index in param_names if parameter_name == "out")
        bus_source, bus_output = ugens[outputs[0]][3][0]
        if bus_source < 0 or ugens[bus_source][0] != "Control" or bus_output != out_index:
            raise ValueError("NornsStratum: master Out must use the out control")
        print("OK: NornsStratum stereo serial MiRings -> MiClouds graph and 25 controls")
        return
    elif name == "PolyPerc":
        expected_constants = [0.0, 1.0, 2.0, -99.0, 0.01, 5.0, -4.0]
        expected_parameters = [440.0, 0.5, 0.5, 0.5, 1000.0, 2.0, 0.0]
        expected_names = [
            ("freq", 0), ("amp", 1), ("pw", 2), ("release", 3),
            ("cutoff", 4), ("gain", 5), ("pan", 6),
        ]
        expected_ugens = [
            ("Control", 1, 0, [], [1, 1, 1, 1, 1, 1, 1]),
            ("Pulse", 2, 0, [(0, 0), (0, 2)], [2]),
            ("BinaryOpUGen", 1, 2, [(0, 2), (-1, 2)], [1]),
            ("BinaryOpUGen", 1, 1, [(2, 0), (-1, 1)], [1]),
            ("BinaryOpUGen", 2, 1, [(1, 0), (3, 0)], [2]),
            ("MoogFF", 2, 0, [(4, 0), (0, 4), (0, 5), (-1, 0)], [2]),
            ("EnvGen", 2, 0, [
                (-1, 1), (-1, 1), (-1, 0), (-1, 1), (-1, 2),
                (-1, 0), (-1, 2), (-1, 3), (-1, 3), (-1, 1),
                (-1, 4), (-1, 5), (-1, 6), (-1, 0), (0, 3),
                (-1, 5), (-1, 6),
            ], [2]),
            ("BinaryOpUGen", 2, 2, [(5, 0), (6, 0)], [2]),
            ("Pan2", 2, 0, [(7, 0), (0, 6), (0, 1)], [2, 2]),
            ("Out", 2, 0, [(-1, 0), (8, 0), (8, 1)], []),
        ]
    else:
        raise ValueError("unknown bundled SynthDef: " + name)

    if len(constants) != len(expected_constants) or any(
        not math.isclose(a, b, rel_tol=1e-6, abs_tol=1e-6)
        for a, b in zip(constants, expected_constants)
    ):
        raise ValueError(name + ": unexpected constant table")
    if len(parameters) != len(expected_parameters) or any(
        not math.isclose(a, b, rel_tol=1e-6, abs_tol=1e-6)
        for a, b in zip(parameters, expected_parameters)
    ):
        raise ValueError(name + ": unexpected default parameters")
    if param_names != expected_names:
        raise ValueError(name + ": unexpected parameter names")
    if ugens != expected_ugens:
        raise ValueError(name + ": UGen graph does not match the expected graph")
    print("OK: " + name + " SynthDef v2 structure and UGen graph")


if __name__ == "__main__":
    try:
        if len(sys.argv) < 2:
            raise ValueError("usage: validate-synthdef.py FILE [FILE ...]")
        for filename in sys.argv[1:]:
            parse(filename)
    except (OSError, ValueError, struct.error) as error:
        print("ERROR:", error, file=sys.stderr)
        sys.exit(1)
