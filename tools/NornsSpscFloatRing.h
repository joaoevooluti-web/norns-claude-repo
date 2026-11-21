#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

template <size_t Capacity>
class SpscFloatRing final {
    static_assert(Capacity >= 2, "SPSC ring capacity must be at least two");
public:
    bool push(float sample) noexcept {
        const uint64_t write = mWrite.load(std::memory_order_relaxed);
        const uint64_t read = mRead.load(std::memory_order_acquire);
        if (write - read >= Capacity) {
            mDropped.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        mSamples[write % Capacity] = sample;
        mWrite.store(write + 1, std::memory_order_release);
        return true;
    }
    int read(float* destination, int frames, bool preferNewest = false) noexcept {
        if (!destination || frames <= 0) return 0;
        mRequested.fetch_add(static_cast<uint64_t>(frames), std::memory_order_relaxed);
        const uint64_t write = mWrite.load(std::memory_order_acquire);
        uint64_t readIndex = mRead.load(std::memory_order_relaxed);
        uint64_t available = write - readIndex;
        if (available > Capacity) { readIndex = write - Capacity; available = Capacity; }
        if (preferNewest && available > static_cast<uint64_t>(frames)) {
            const uint64_t skipped = available - static_cast<uint64_t>(frames);
            readIndex += skipped;
            available -= skipped;
            mSkipped.fetch_add(skipped, std::memory_order_relaxed);
        }
        const int copied = static_cast<int>(std::min<uint64_t>(available, static_cast<uint64_t>(frames)));
        for (int i = 0; i < copied; ++i)
            destination[i] = mSamples[(readIndex + static_cast<uint64_t>(i)) % Capacity];
        std::fill(destination + copied, destination + frames, 0.0f);
        mRead.store(readIndex + static_cast<uint64_t>(copied), std::memory_order_release);
        if (copied < frames) mUnderrun.fetch_add(static_cast<uint64_t>(frames - copied), std::memory_order_relaxed);
        return copied;
    }
    // Keep a bounded amount of input queued instead of discarding every old
    // sample whenever the producer gets ahead of one server block. This absorbs
    // callback jitter while trimming only backlog beyond the requested latency.
    int readWithBacklogLimit(float* destination, int frames, uint64_t maxQueuedFrames) noexcept {
        if (!destination || frames <= 0) return 0;
        mRequested.fetch_add(static_cast<uint64_t>(frames), std::memory_order_relaxed);
        const uint64_t write = mWrite.load(std::memory_order_acquire);
        uint64_t readIndex = mRead.load(std::memory_order_relaxed);
        uint64_t available = write - readIndex;
        if (available > Capacity) { readIndex = write - Capacity; available = Capacity; }
        const uint64_t desired = static_cast<uint64_t>(frames) + maxQueuedFrames;
        if (available > desired) {
            const uint64_t skipped = available - desired;
            readIndex += skipped;
            available -= skipped;
            mSkipped.fetch_add(skipped, std::memory_order_relaxed);
        }
        const int copied = static_cast<int>(std::min<uint64_t>(available, static_cast<uint64_t>(frames)));
        for (int i = 0; i < copied; ++i)
            destination[i] = mSamples[(readIndex + static_cast<uint64_t>(i)) % Capacity];
        std::fill(destination + copied, destination + frames, 0.0f);
        mRead.store(readIndex + static_cast<uint64_t>(copied), std::memory_order_release);
        if (copied < frames) mUnderrun.fetch_add(static_cast<uint64_t>(frames - copied), std::memory_order_relaxed);
        return copied;
    }
    uint64_t acceptedFrames() const noexcept { return mWrite.load(std::memory_order_acquire); }
    uint64_t queuedFrames() const noexcept {
        const uint64_t write = mWrite.load(std::memory_order_acquire);
        const uint64_t read = mRead.load(std::memory_order_acquire);
        return write >= read ? write - read : 0;
    }
    uint64_t requestedFrames() const noexcept { return mRequested.load(std::memory_order_relaxed); }
    uint64_t droppedFrames() const noexcept { return mDropped.load(std::memory_order_relaxed); }
    uint64_t skippedFrames() const noexcept { return mSkipped.load(std::memory_order_relaxed); }
    uint64_t underrunFrames() const noexcept { return mUnderrun.load(std::memory_order_relaxed); }
private:
    std::array<float, Capacity> mSamples{};
    alignas(64) std::atomic<uint64_t> mWrite{0};
    alignas(64) std::atomic<uint64_t> mRead{0};
    std::atomic<uint64_t> mDropped{0}, mSkipped{0}, mUnderrun{0}, mRequested{0};
};
