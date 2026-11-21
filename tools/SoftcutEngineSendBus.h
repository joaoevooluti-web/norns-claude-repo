#pragma once

#include <cstddef>
#include <cstdint>

namespace SoftcutEngineSendBus {
constexpr int kBusIndex = 31;

// Copies the SuperCollider master instrument output into a dedicated mono bus.
// The bus is marked for the next World_Run block, so the recorder consumes a
// stable, complete block and never reads a buffer while the current graph writes it.
inline void captureNextBlock(float* audioBus, int32_t* audioBusTouched,
                             int blockFrames, int32_t currentBufferCounter,
                             int32_t nextBufferCounter) noexcept {
    if (!audioBus || !audioBusTouched || blockFrames <= 0) return;
    const float* left = audioBus;
    const float* right = audioBus + blockFrames;
    float* destination = audioBus + static_cast<size_t>(kBusIndex) * blockFrames;
    const bool leftTouched = audioBusTouched[0] == currentBufferCounter;
    const bool rightTouched = audioBusTouched[1] == currentBufferCounter;
    for (int frame = 0; frame < blockFrames; ++frame) {
        const float l = leftTouched ? left[frame] : 0.0f;
        const float r = rightTouched ? right[frame] : 0.0f;
        destination[frame] = (l + r) * 0.5f;
    }
    audioBusTouched[kBusIndex] = nextBufferCounter;
}
} // namespace SoftcutEngineSendBus
