// v7b1/mi-UGens — pinned at 10f6824f68ad63475f67dfe3fe5016e2d2634f84
// Copyright (c) 2020 Volker Böhm. See upstream GPL-3.0-or-later license.

MiRings : MultiOutUGen {

    *ar {
        arg in=0, trig=0, pit=60.0, struct=0.25, bright=0.5, damp=0.7, pos=0.25, model=0, poly=1,
        intern_exciter=0, easteregg=0, bypass=0, mul=1.0, add=0;

        ^this.multiNew('audio', in, trig, pit, struct, bright, damp, pos, model, poly,
            intern_exciter, easteregg, bypass).madd(mul, add);
    }

    init { arg ... theInputs;
        inputs = theInputs;
        ^this.initOutputs(2, rate);
    }
}
