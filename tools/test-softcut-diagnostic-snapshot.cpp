#include "SoftcutDiagnosticSnapshot.h"

#include <array>
#include <atomic>
#include <cassert>
#include <cstddef>
#include <thread>

int main() {
    SoftcutDiagnosticSnapshot snapshot;
    std::array<float, SoftcutDiagnosticSnapshot::kValueCount> initial{};
    std::array<float, SoftcutDiagnosticSnapshot::kValueCount> observed{};
    assert(!snapshot.read(observed.data(), observed.size()));
    assert(!snapshot.read(observed.data(), SoftcutDiagnosticSnapshot::kValueCount - 1));
    initial[52] = 0.25f;
    initial[53] = 0.375f;
    snapshot.publish(initial.data());
    assert(snapshot.read(observed.data(), observed.size()));
    for (size_t i = 0; i < 52; ++i) assert(observed[i] == 0.0f);
    assert(observed[52] == 0.25f && observed[53] == 0.375f);

    // Start the concurrency phase from a coherent, uniform generation.
    // The initial contract fixture above intentionally has non-uniform values.
    initial.fill(0.0f);
    snapshot.publish(initial.data());

    std::atomic<bool> finished{false};
    std::atomic<unsigned> accepted{0};
    std::thread writer([&]() {
        std::array<float, SoftcutDiagnosticSnapshot::kValueCount> values{};
        for (int generation = 1; generation <= 200000; ++generation) {
            values.fill(static_cast<float>(generation));
            snapshot.publish(values.data());
            if ((generation & 255) == 0) std::this_thread::yield();
        }
        finished.store(true, std::memory_order_release);
    });

    while (!finished.load(std::memory_order_acquire)) {
        if (!snapshot.read(observed.data(), observed.size())) continue;
        const float generation = observed[0];
        for (size_t i = 1; i < observed.size(); ++i)
            assert(observed[i] == generation);
        accepted.fetch_add(1, std::memory_order_relaxed);
    }
    writer.join();

    assert(accepted.load(std::memory_order_relaxed) > 0);
    assert(snapshot.read(observed.data(), observed.size()));
    for (float value : observed) assert(value == 200000.0f);
    return 0;
}
