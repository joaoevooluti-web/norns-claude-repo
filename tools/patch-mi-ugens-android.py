#!/usr/bin/env python3
"""Apply narrow, repeatable RT-allocation fixes to pinned v7b1/mi-UGens sources."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def normalize(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    return "\n".join(line.rstrip() for line in text.split("\n"))


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = normalize(path.read_text(encoding="utf-8"))
    old = normalize(old)
    new = normalize(new)
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one upstream anchor, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

rings = root / "projects/MiRings/MiRings.cpp"
clouds = root / "projects/MiClouds/MiClouds.cpp"

replace_once(
    rings,
    """static void MiRings_Ctor(MiRings *unit) {

    rings::Dsp::setSr(SAMPLERATE);

    if (BUFLENGTH < kBlockSize) {
        Print("MiRings ERROR: sc block size too small!\\n");
        unit = NULL;
        return;
    }
""",
    """static void MiRings_Ctor(MiRings *unit) {

    unit->reverb_buffer = NULL;
    unit->silence = NULL;
    unit->input = NULL;
    unit->prev_trig = false;
    unit->prev_poly = 0;

    rings::Dsp::setSr(SAMPLERATE);

    if (BUFLENGTH < kBlockSize) {
        Print("MiRings ERROR: sc block size too small!\\n");
        SETCALC(ClearUnitOutputs);
        ClearUnitOutputs(unit, 2);
        unit->mDone = true;
        return;
    }
""",
    "MiRings constructor initialization",
)

replace_once(
    rings,
    """    // allocate memory + init with zeros
    unit->reverb_buffer = (uint16_t*)RTAlloc(unit->mWorld, 32768*sizeof(uint16_t));
    memset(unit->reverb_buffer, 0, 32768*sizeof(uint16_t));

    unit->silence = (float*)RTAlloc(unit->mWorld, BUFLENGTH*sizeof(float));
    memset(unit->silence, 0, BUFLENGTH*sizeof(float));

    unit->input = (float*)RTAlloc(unit->mWorld, BUFLENGTH*sizeof(float));
    memset(unit->input, 0, BUFLENGTH*sizeof(float));

    // zero out...
""",
    """    // Allocate all real-time buffers before touching any returned pointer.
    unit->reverb_buffer = (uint16_t*)RTAlloc(unit->mWorld, 32768*sizeof(uint16_t));
    unit->silence = (float*)RTAlloc(unit->mWorld, BUFLENGTH*sizeof(float));
    unit->input = (float*)RTAlloc(unit->mWorld, BUFLENGTH*sizeof(float));

    if (!unit->reverb_buffer || !unit->silence || !unit->input) {
        Print("MiRings ERROR: real-time buffer allocation failed!\\n");
        if (unit->reverb_buffer) RTFree(unit->mWorld, unit->reverb_buffer);
        if (unit->silence) RTFree(unit->mWorld, unit->silence);
        if (unit->input) RTFree(unit->mWorld, unit->input);
        unit->reverb_buffer = NULL;
        unit->silence = NULL;
        unit->input = NULL;
        SETCALC(ClearUnitOutputs);
        ClearUnitOutputs(unit, 2);
        unit->mDone = true;
        return;
    }

    memset(unit->reverb_buffer, 0, 32768*sizeof(uint16_t));
    memset(unit->silence, 0, BUFLENGTH*sizeof(float));
    memset(unit->input, 0, BUFLENGTH*sizeof(float));

    // zero out...
""",
    "MiRings allocation checks",
)

replace_once(
    rings,
    """    if(unit->reverb_buffer) {
        RTFree(unit->mWorld, unit->reverb_buffer);
    }
}
""",
    """    if (unit->reverb_buffer) RTFree(unit->mWorld, unit->reverb_buffer);
    if (unit->silence) RTFree(unit->mWorld, unit->silence);
    if (unit->input) RTFree(unit->mWorld, unit->input);
    unit->reverb_buffer = NULL;
    unit->silence = NULL;
    unit->input = NULL;
}
""",
    "MiRings destructor",
)

replace_once(
    clouds,
    """#include "SC_PlugIn.h"

#include "clouds/dsp/granular_processor.h"
""",
    """#include "SC_PlugIn.h"

#include <new>

#include "clouds/dsp/granular_processor.h"
""",
    "MiClouds nothrow include",
)

replace_once(
    clouds,
    """static void MiClouds_Ctor(MiClouds *unit) {
    
    if(BUFLENGTH < kAudioBlockSize) {
        Print("MiClouds ERROR: Block Size can't be smaller than %d samples\\n", kAudioBlockSize);
        unit = NULL;
        return;
    }
""",
    """static void MiClouds_Ctor(MiClouds *unit) {

    unit->processor = nullptr;
    unit->large_buffer = nullptr;
    unit->small_buffer = nullptr;

    if(BUFLENGTH < kAudioBlockSize) {
        Print("MiClouds ERROR: Block Size can't be smaller than %d samples\\n", kAudioBlockSize);
        SETCALC(ClearUnitOutputs);
        ClearUnitOutputs(unit, 2);
        unit->mDone = true;
        return;
    }
""",
    "MiClouds constructor initialization",
)

replace_once(
    clouds,
    """    unit->large_buffer = (uint8_t*)RTAlloc(unit->mWorld, largeBufSize * sizeof(uint8_t));
    unit->small_buffer = (uint8_t*)RTAlloc(unit->mWorld, smallBufSize * sizeof(uint8_t));

    
    if(unit->large_buffer == NULL || unit->small_buffer == NULL) {
        Print( "mem alloc failed!" );
        unit = NULL;
        return;
    }
    
    unit->sr = SAMPLERATE;
    
    unit->processor = new clouds::GranularProcessor;
    memset(unit->processor, 0, sizeof(*unit->processor));
""",
    """    unit->large_buffer = (uint8_t*)RTAlloc(unit->mWorld, largeBufSize * sizeof(uint8_t));
    unit->small_buffer = (uint8_t*)RTAlloc(unit->mWorld, smallBufSize * sizeof(uint8_t));

    if (!unit->large_buffer || !unit->small_buffer) {
        Print("MiClouds ERROR: real-time buffer allocation failed!");
        if (unit->large_buffer) RTFree(unit->mWorld, unit->large_buffer);
        if (unit->small_buffer) RTFree(unit->mWorld, unit->small_buffer);
        unit->large_buffer = nullptr;
        unit->small_buffer = nullptr;
        SETCALC(ClearUnitOutputs);
        ClearUnitOutputs(unit, 2);
        unit->mDone = true;
        return;
    }

    unit->processor = new (std::nothrow) clouds::GranularProcessor;
    if (!unit->processor) {
        Print("MiClouds ERROR: processor allocation failed!");
        RTFree(unit->mWorld, unit->large_buffer);
        RTFree(unit->mWorld, unit->small_buffer);
        unit->large_buffer = nullptr;
        unit->small_buffer = nullptr;
        SETCALC(ClearUnitOutputs);
        ClearUnitOutputs(unit, 2);
        unit->mDone = true;
        return;
    }

    unit->sr = SAMPLERATE;

    memset(unit->processor, 0, sizeof(*unit->processor));
""",
    "MiClouds allocation checks",
)

replace_once(
    clouds,
    """    if(unit->large_buffer) {
        RTFree(unit->mWorld, unit->large_buffer);
    }
    if(unit->small_buffer) {
        RTFree(unit->mWorld, unit->small_buffer);
    }
    if(unit->processor) delete unit->processor;
}
""",
    """    if (unit->large_buffer) RTFree(unit->mWorld, unit->large_buffer);
    if (unit->small_buffer) RTFree(unit->mWorld, unit->small_buffer);
    if (unit->processor) delete unit->processor;
    unit->large_buffer = nullptr;
    unit->small_buffer = nullptr;
    unit->processor = nullptr;
}
""",
    "MiClouds destructor",
)

print("MiRings: initialized and checked all RTAlloc buffers; destructor frees all three")
print("MiClouds: checked buffers and nothrow processor allocation; destructor is null-safe")
