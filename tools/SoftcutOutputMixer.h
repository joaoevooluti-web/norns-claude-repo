#pragma once

#include <algorithm>
#include <cmath>

/** Allocation-free per-channel mixer used at the Oboe PCM boundary. */
class SoftcutOutputMixer final {
public:
    static float mixChannel(float master, bool masterTouched,
                            float softcut, bool softcutTouched,
                            bool softcutEnabled) noexcept {
        float sample = masterTouched ? master : 0.0f;
        if (softcutEnabled && softcutTouched) sample += softcut;
        if (!std::isfinite(sample)) return 0.0f;
        return std::max(-1.0f, std::min(1.0f, sample));
    }
};
