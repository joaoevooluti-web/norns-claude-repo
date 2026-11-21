#pragma once

#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <algorithm>

static_assert(std::atomic<float>::is_always_lock_free,
              "Softcut diagnostic values must remain lock-free on the target ABI");

/**
 * One-writer/many-reader lock-free snapshot for real-time Softcut diagnostics.
 *
 * publish() must be called by the single Oboe output callback. Readers never
 * lock or access callback-owned non-atomic storage; a sequence counter prevents
 * accepting fields from two different publications.
 */
class SoftcutDiagnosticSnapshot final {
public:
    static constexpr size_t kValueCount = 54;

    SoftcutDiagnosticSnapshot() noexcept {
        for (auto& value : values_) value.store(0.0f, std::memory_order_relaxed);
    }

    void publish(const float* values) noexcept {
        if (!values) return;
        sequence_.fetch_add(1, std::memory_order_acq_rel); // odd: write in progress
        for (size_t i = 0; i < kValueCount; ++i)
            values_[i].store(values[i], std::memory_order_relaxed);
        sequence_.fetch_add(1, std::memory_order_release); // even: coherent generation
        published_.store(true, std::memory_order_release);
    }

    bool read(float* destination, size_t count) const noexcept {
        if (!destination || count < kValueCount
                || !published_.load(std::memory_order_acquire)) return false;
        std::array<float, kValueCount> snapshot{};
        for (int attempt = 0; attempt < 16; ++attempt) {
            const uint64_t before = sequence_.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            for (size_t i = 0; i < kValueCount; ++i)
                snapshot[i] = values_[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const uint64_t after = sequence_.load(std::memory_order_acquire);
            if (before != after || (after & 1U) != 0U) continue;
            for (size_t i = 0; i < kValueCount; ++i)
                if (!std::isfinite(snapshot[i])) return false;
            std::copy(snapshot.begin(), snapshot.end(), destination);
            return true;
        }
        return false;
    }

private:
    mutable std::atomic<uint64_t> sequence_{0};
    std::atomic<bool> published_{false};
    std::array<std::atomic<float>, kValueCount> values_;
};
