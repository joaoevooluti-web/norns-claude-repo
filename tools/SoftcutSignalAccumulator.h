#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

/** Single-writer accumulator for one recorder diagnostic signal. */
class SoftcutSignalAccumulator final {
public:
    void reset() noexcept {
        peak_ = 0.0f;
        sumSquares_ = 0.0;
        sampleCount_ = 0;
    }

    void add(const float* samples, size_t count) noexcept {
        if (!samples || count == 0) return;
        for (size_t i = 0; i < count; ++i) {
            const float sample = std::isfinite(samples[i]) ? samples[i] : 0.0f;
            peak_ = std::max(peak_, std::fabs(sample));
            sumSquares_ += static_cast<double>(sample) * sample;
        }
        sampleCount_ += count;
    }

    float peak() const noexcept { return peak_; }

    float rms() const noexcept {
        return sampleCount_ == 0 ? 0.0f
            : static_cast<float>(std::sqrt(sumSquares_ / static_cast<double>(sampleCount_)));
    }

    uint64_t sampleCount() const noexcept { return sampleCount_; }

private:
    float peak_ = 0.0f;
    double sumSquares_ = 0.0;
    uint64_t sampleCount_ = 0;
};
