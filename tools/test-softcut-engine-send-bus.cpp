#include "SoftcutEngineSendBus.h"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>

int main() {
    constexpr int frames = 4;
    std::array<float, 32 * frames> buses{};
    std::array<int32_t, 32> touched{};
    touched.fill(-1);

    const float left[] = {0.2f, -0.4f, 0.6f, -0.8f};
    const float right[] = {0.4f, 0.2f, -0.2f, -0.4f};
    for (int i = 0; i < frames; ++i) {
        buses[i] = left[i];
        buses[frames + i] = right[i];
    }
    touched[0] = 7;
    touched[1] = 7;

    SoftcutEngineSendBus::captureNextBlock(
        buses.data(), touched.data(), frames, 7, 8);

    const float expected[] = {0.3f, -0.1f, 0.2f, -0.6f};
    for (int i = 0; i < frames; ++i)
        assert(std::fabs(buses[31 * frames + i] - expected[i]) < 1e-6f);
    assert(touched[31] == 8);
    assert(buses[0] == left[0] && buses[frames] == right[0]);

    // A stale/unwritten master channel contributes silence, never old samples.
    touched[0] = 9;
    touched[1] = 7;
    SoftcutEngineSendBus::captureNextBlock(
        buses.data(), touched.data(), frames, 7, 8);
    for (int i = 0; i < frames; ++i)
        assert(std::fabs(buses[31 * frames + i] - right[i] * 0.5f) < 1e-6f);

    std::cout << "OK: native engine send uses an isolated mono bus and next-block ownership\n";
    return 0;
}
