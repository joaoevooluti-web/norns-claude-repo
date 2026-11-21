#include "SoftcutSignalAccumulator.h"

#include <array>
#include <cassert>
#include <cmath>
#include <limits>

int main() {
    {
        // CH1 and CH2 own independent diagnostic accumulators. Resetting one
        // channel must not erase the other channel's measured recorder signal.
        SoftcutSignalAccumulator ch1;
        SoftcutSignalAccumulator ch2;
        const float a[] = {0.25f, -0.5f};
        const float b[] = {0.75f, -0.125f};
        ch1.add(a, 2);
        ch2.add(b, 2);
        const float ch2Peak = ch2.peak();
        const float ch2Rms = ch2.rms();
        ch1.reset();
        assert(ch1.peak() == 0.0f);
        assert(ch1.sampleCount() == 0);
        assert(ch2.peak() == ch2Peak);
        assert(ch2.rms() == ch2Rms);
        assert(ch2.sampleCount() == 2);
    }

    SoftcutSignalAccumulator accumulator;
    const std::array<float, 4> first = {0.5f, -0.5f, 0.0f, 0.0f};
    accumulator.add(first.data(), first.size());
    assert(std::fabs(accumulator.peak() - 0.5f) < 1e-6f);
    assert(std::fabs(accumulator.rms() - 0.35355339f) < 1e-5f);
    assert(accumulator.sampleCount() == 4);

    const std::array<float, 3> second = {1.0f, -1.0f, 0.25f};
    accumulator.add(second.data(), second.size());
    assert(std::fabs(accumulator.peak() - 1.0f) < 1e-6f);
    const float expected = std::sqrt((0.5f + 2.0f + 0.0625f) / 7.0f);
    assert(std::fabs(accumulator.rms() - expected) < 1e-5f);
    assert(accumulator.sampleCount() == 7);

    accumulator.reset();
    assert(accumulator.peak() == 0.0f);
    assert(accumulator.rms() == 0.0f);
    assert(accumulator.sampleCount() == 0);

    const std::array<float, 3> nonFinite = {
        std::numeric_limits<float>::quiet_NaN(),
        std::numeric_limits<float>::infinity(),
        -0.25f
    };
    accumulator.add(nonFinite.data(), nonFinite.size());
    assert(std::fabs(accumulator.peak() - 0.25f) < 1e-6f);
    assert(std::fabs(accumulator.rms() - (0.25f / std::sqrt(3.0f))) < 1e-5f);
    return 0;
}
