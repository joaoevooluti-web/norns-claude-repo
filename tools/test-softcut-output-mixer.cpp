#include "SoftcutOutputMixer.h"
#include <cassert>
#include <cmath>
#include <limits>

static bool near(float a, float b, float tolerance = 1.0e-6f) {
    return std::fabs(a - b) <= tolerance;
}

int main() {
    const float masterLeft = 0.125f, masterRight = -0.25f;
    const float softcutLeft = 0.5f, softcutRight = -0.375f;

    // Gate closed: master remains audible, Softcut is independently muted.
    const float closedLeft = SoftcutOutputMixer::mixChannel(
        masterLeft, true, softcutLeft, true, false);
    const float closedRight = SoftcutOutputMixer::mixChannel(
        masterRight, true, softcutRight, true, false);
    assert(near(closedLeft, masterLeft));
    assert(near(closedRight, masterRight));

    // Gate open: each Softcut bus contributes only to its matching PCM channel.
    const float openLeft = SoftcutOutputMixer::mixChannel(
        masterLeft, true, softcutLeft, true, true);
    const float openRight = SoftcutOutputMixer::mixChannel(
        masterRight, true, softcutRight, true, true);
    assert(near(openLeft, 0.625f));
    assert(near(openRight, -0.625f));
    assert(!near(openLeft, masterLeft + softcutRight));
    assert(!near(openRight, masterRight + softcutLeft));

    // Untouched buses contribute silence; stale memory is never read.
    assert(near(SoftcutOutputMixer::mixChannel(0.9f, false, 0.7f, false, true), 0.0f));
    assert(near(SoftcutOutputMixer::mixChannel(masterLeft, true, softcutLeft, false, true),
                masterLeft));

    // Saturation and non-finite protection at the float PCM boundary.
    assert(near(SoftcutOutputMixer::mixChannel(0.8f, true, 0.7f, true, true), 1.0f));
    assert(near(SoftcutOutputMixer::mixChannel(-0.8f, true, -0.7f, true, true), -1.0f));
    assert(near(SoftcutOutputMixer::mixChannel(
        std::numeric_limits<float>::quiet_NaN(), true, 0.0f, false, false), 0.0f));
    assert(near(SoftcutOutputMixer::mixChannel(
        0.0f, true, std::numeric_limits<float>::infinity(), true, true), 0.0f));
    return 0;
}
