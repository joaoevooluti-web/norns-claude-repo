# Vendored Faust DSP

The two generated processors in this directory are copied from monome/norns
(main, crone/src/effects/{StereoCompressor.h,ZitaReverb.h}) and were generated
with Faust 2.7.4 from crone/dsp/{StereoCompressor.dsp,ZitaReverb.dsp}.

The Faust architecture headers in faust/dsp/dsp.h and faust/gui/{UI.h,meta.h}
are copied from monome/norns/crone/faust. Their original GPL-3.0-or-later
license notices and Faust special exception are retained unchanged.

The generated processor headers retain their original generated-code notices.
Only the unused APIUI include and the convenience wrapper class that depended
on APIUI were removed; the generated DSP classes and compute() implementations
are otherwise preserved. Runtime controls are bound through the small UI
adapter in NornsFaustEffectsCore.h.

The upstream norns repository distributes under GPL-3.0-or-later; its
LICENSE.txt is included as LICENSE.norns.txt alongside these vendored files.
The Faust architecture headers also retain their original special exception.
