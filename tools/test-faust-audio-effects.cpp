#include <cassert>
#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>
#include "NornsFaustEffectsCore.h"

static double energy(const std::vector<float>& x) {
    double sum = 0.0;
    for (float v : x) { assert(std::isfinite(v)); sum += double(v) * v; }
    return sum;
}

int main() {
    constexpr int sr = 48000;
    constexpr int frames = 1024;
    norns_audio::FaustEffectsCore fx;
    assert(fx.init(sr, 8192));

    // Disabled effects are a bit-transparent bypass.
    std::vector<float> dry(2 * 256);
    for (size_t i = 0; i < dry.size(); ++i) dry[i] = (static_cast<int>(i % 7) - 3) * 0.01f;
    std::vector<float> original = dry;
    assert(fx.process(dry.data(), nullptr, nullptr, nullptr, nullptr, 256));
    assert(dry == original);

    // ZitaReverb is the generated Faust processor, not a delay approximation.
    assert(fx.setEnabled(norns_audio::FaustEffectsCore::kReverb, true));
    assert(fx.setMix(norns_audio::FaustEffectsCore::kReverb, 1.f));
    std::vector<float> impulse(2 * 8192, 0.f), monitor(8192, 0.f),
            engine(8192, 0.f), tape(8192, 0.f), softcut(2 * 8192, 0.f);
    engine[0] = 1.f;
    assert(fx.setReverbSendLevel(1, 1.f));
    assert(fx.process(impulse.data(), monitor.data(), engine.data(), tape.data(), softcut.data(), 8192));
    assert(energy(impulse) > 1.0e-8);
    std::vector<float> tail(2 * 8192, 0.f);
    std::fill(engine.begin(), engine.end(), 0.f);
    assert(fx.process(tail.data(), monitor.data(), engine.data(), tape.data(), softcut.data(), 8192));
    assert(energy(tail) > 1.0e-10);

    // Each source reaches the same reverb independently; muted sources cannot leak.
    for (int source = 0; source < 4; ++source) {
        norns_audio::FaustEffectsCore isolated;
        assert(isolated.init(sr, 8192));
        assert(isolated.setEnabled(norns_audio::FaustEffectsCore::kReverb, true));
        assert(isolated.setMix(norns_audio::FaustEffectsCore::kReverb, 1.f));
        assert(isolated.setReverbSendLevel(source, 1.f));
        std::vector<float> out(2 * 8192, 0.f);
        std::vector<float> m(8192, 0.f), e(8192, 0.f), t(8192, 0.f);
        std::vector<float> cut(2 * 8192, 0.f);
        if (source == 0) m[0] = 1.f;
        else if (source == 1) e[0] = 1.f;
        else if (source == 2) t[0] = 1.f;
        else cut[0] = 1.f; // Left-only Softcut input proves the fourth source is stereo.
        assert(isolated.process(out.data(), m.data(), e.data(), t.data(), cut.data(), 8192));
        assert(energy(out) > 1.0e-8);
        norns_audio::FaustEffectsCore mutedFx;
        assert(mutedFx.init(sr, 8192));
        assert(mutedFx.setEnabled(norns_audio::FaustEffectsCore::kReverb, true));
        assert(mutedFx.setMix(norns_audio::FaustEffectsCore::kReverb, 1.f));
        assert(mutedFx.setReverbSendLevel(source, 1.f));
        std::fill(out.begin(), out.end(), 0.f);
        std::fill(m.begin(), m.end(), 0.f);
        std::fill(e.begin(), e.end(), 0.f);
        std::fill(t.begin(), t.end(), 0.f);
        std::fill(cut.begin(), cut.end(), 0.f);
        // Feed a different source while only the selected source's send is open.
        if (source == 0) e[0] = 1.f;
        else m[0] = 1.f;
        assert(mutedFx.process(out.data(), m.data(), e.data(), t.data(), cut.data(), 8192));
        assert(energy(out) < 1.0e-12);
    }
    assert(fx.setReverbSendLevel(3, 0.5f));
    assert(!fx.setReverbSendLevel(4, 1.f));
    assert(!fx.setReverbSendLevel(0, std::numeric_limits<float>::quiet_NaN()));

    // StereoCompressor responds to sustained over-threshold material and mix=1.
    norns_audio::FaustEffectsCore comp;
    assert(comp.init(sr, frames));
    assert(comp.setParameter(norns_audio::FaustEffectsCore::kCompressor, 0, 8.f));
    assert(comp.setParameter(norns_audio::FaustEffectsCore::kCompressor, 1, -18.f));
    assert(comp.setParameter(norns_audio::FaustEffectsCore::kCompressor, 2, 0.001f));
    assert(comp.setParameter(norns_audio::FaustEffectsCore::kCompressor, 3, 0.1f));
    assert(comp.setParameter(norns_audio::FaustEffectsCore::kCompressor, 5, 0.f));
    assert(comp.setEnabled(norns_audio::FaustEffectsCore::kCompressor, true));
    assert(comp.setMix(norns_audio::FaustEffectsCore::kCompressor, 0.f));
    std::vector<float> mixZero(2 * frames, 0.4f);
    const auto mixZeroOriginal = mixZero;
    assert(comp.process(mixZero.data(), nullptr, nullptr, nullptr, nullptr, frames));
    assert(mixZero == mixZeroOriginal);
    assert(comp.setMix(norns_audio::FaustEffectsCore::kCompressor, 1.f));
    std::vector<float> hot(2 * frames, 0.8f);
    const double inputEnergy = energy(hot);
    assert(comp.process(hot.data(), nullptr, nullptr, nullptr, nullptr, frames));
    assert(energy(hot) < inputEnergy * 0.95);

    // Parameter lookup rejects unknown names and non-finite values.
    assert(!comp.setParameter(norns_audio::FaustEffectsCore::kCompressor, 99, 1.f));
    assert(!comp.setParameter(norns_audio::FaustEffectsCore::kCompressor, 0, std::numeric_limits<float>::quiet_NaN()));
    assert(!comp.setMix(norns_audio::FaustEffectsCore::kCompressor, std::numeric_limits<float>::quiet_NaN()));
    assert(!comp.process(hot.data(), nullptr, nullptr, nullptr, nullptr, frames + 1));

    std::puts("Faust ZitaReverb/StereoCompressor deterministic tests passed");
    return 0;
}
