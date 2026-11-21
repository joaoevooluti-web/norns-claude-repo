#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <string>
#include <unordered_map>
#include <vector>

#include "faust/effects/StereoCompressor.h"
#undef FAUSTCLASS
#include "faust/effects/ZitaReverb.h"
#undef FAUSTCLASS

namespace norns_audio {

class ParameterUI final : public UI {
public:
    struct Parameter { FAUSTFLOAT* zone; };
    std::unordered_map<std::string, Parameter> parameters;

    void openTabBox(const char*) override {}
    void openHorizontalBox(const char*) override {}
    void openVerticalBox(const char*) override {}
    void closeBox() override {}
    void addButton(const char* label, FAUSTFLOAT* zone) override { add(label, zone, 0.f, 1.f); }
    void addCheckButton(const char* label, FAUSTFLOAT* zone) override { add(label, zone, 0.f, 1.f); }
    void addVerticalSlider(const char* label, FAUSTFLOAT* zone, FAUSTFLOAT,
                           FAUSTFLOAT minimum, FAUSTFLOAT maximum, FAUSTFLOAT) override {
        add(label, zone, minimum, maximum);
    }
    void addHorizontalSlider(const char* label, FAUSTFLOAT* zone, FAUSTFLOAT,
                             FAUSTFLOAT minimum, FAUSTFLOAT maximum, FAUSTFLOAT) override {
        add(label, zone, minimum, maximum);
    }
    void addNumEntry(const char* label, FAUSTFLOAT* zone, FAUSTFLOAT,
                     FAUSTFLOAT minimum, FAUSTFLOAT maximum, FAUSTFLOAT) override {
        add(label, zone, minimum, maximum);
    }
    void addHorizontalBargraph(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT) override {}
    void addVerticalBargraph(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT) override {}
    void addSoundfile(const char*, const char*, Soundfile**) override {}

    bool set(const char* name, float value) {
        if (!name || !std::isfinite(value)) return false;
        for (auto& entry : parameters) {
            if (entry.first == name) {
                // APIUI::setParamValue in upstream crone writes the zone directly.
                *entry.second.zone = value;
                return true;
            }
        }
        return false;
    }

private:
    void add(const char* label, FAUSTFLOAT* zone, FAUSTFLOAT minimum, FAUSTFLOAT maximum) {
        (void)minimum;
        (void)maximum;
        if (label && zone) parameters[label] = {zone};
    }
};

/**
 * Exact Faust-generated StereoCompressor and ZitaReverb processors used by
 * monome/norns crone. Scratch memory is allocated once in init(); process()
 * performs no allocation, locking, JNI calls, or file I/O.
 */
class FaustEffectsCore final {
public:
    static constexpr int kCompressor = 0;
    static constexpr int kReverb = 1;

    bool init(int sampleRate, int maxFrames) {
        if (sampleRate < 8000 || sampleRate > 192000 || maxFrames < 1 || maxFrames > 8192)
            return false;
        sampleRate_ = sampleRate;
        maxFrames_ = maxFrames;
        leftIn_.assign(static_cast<size_t>(maxFrames), 0.f);
        rightIn_.assign(static_cast<size_t>(maxFrames), 0.f);
        leftOut_.assign(static_cast<size_t>(maxFrames), 0.f);
        rightOut_.assign(static_cast<size_t>(maxFrames), 0.f);

        compressor_.instanceInit(sampleRate_);
        reverb_.instanceInit(sampleRate_);
        compressor_.buildUserInterface(&compressorUi_);
        reverb_.buildUserInterface(&reverbUi_);

        // Defaults from monome/norns crone/MixerClient.cpp setFxDefaults().
        compressorUi_.set("ratio", 4.f);
        compressorUi_.set("threshold", -12.f);
        compressorUi_.set("attack", 0.005f);
        compressorUi_.set("release", 0.08f);
        compressorUi_.set("gain_pre", 0.f);
        compressorUi_.set("gain_post", 4.f);
        reverbUi_.set("pre_del", 20.f);
        reverbUi_.set("lf_fc", 555.f);
        reverbUi_.set("low_rt60", 4.7f);
        reverbUi_.set("mid_rt60", 2.3f);
        reverbUi_.set("hf_damp", 6666.f);

        compressorEnabled_ = false;
        reverbEnabled_ = false;
        compressorMix_ = 0.f;
        reverbReturn_ = 0.f;
        std::fill(reverbSendLevels_, reverbSendLevels_ + 4, 0.f);
        return true;
    }

    bool setEnabled(int effect, bool enabled) {
        if (effect == kCompressor) compressorEnabled_ = enabled;
        else if (effect == kReverb) reverbEnabled_ = enabled;
        else return false;
        return true;
    }

    bool setParameter(int effect, int parameter, float value) {
        static const char* const compressorNames[] = {
            "ratio", "threshold", "attack", "release", "gain_pre", "gain_post"
        };
        static const char* const reverbNames[] = {
            "pre_del", "lf_fc", "low_rt60", "mid_rt60", "hf_damp"
        };
        if (effect == kCompressor && parameter >= 0 && parameter < 6)
            return compressorUi_.set(compressorNames[parameter], value);
        if (effect == kReverb && parameter >= 0 && parameter < 5)
            return reverbUi_.set(reverbNames[parameter], value);
        return false;
    }

    bool setMix(int effect, float value) {
        if (!std::isfinite(value)) return false;
        if (effect == kCompressor) {
            compressorMix_ = std::max(0.f, std::min(1.f, value));
            return true;
        }
        if (effect == kReverb) {
            reverbReturn_ = std::max(0.f, std::min(8.f, value));
            return true;
        }
        return false;
    }

    // Source IDs: 0=ADC monitor, 1=engine, 2=Tape, 3=Softcut. Each gain is independent.
    bool setReverbSendLevel(int source, float value) {
        if (source < 0 || source >= 4 || !std::isfinite(value)) return false;
        reverbSendLevels_[source] = std::max(0.f, std::min(1.f, value));
        return true;
    }

    bool process(float* masterInterleaved, const float* monitorMono,
                 const float* engineMono, const float* tapeMono,
                 const float* softcutInterleaved, int frames) {
        if (!masterInterleaved || frames < 0 || frames > maxFrames_) return false;
        if (frames == 0) return true;

        if (reverbEnabled_) {
            for (int i = 0; i < frames; ++i) {
                const float monitor = monitorMono && std::isfinite(monitorMono[i]) ? monitorMono[i] : 0.f;
                const float engine = engineMono && std::isfinite(engineMono[i]) ? engineMono[i] : 0.f;
                const float tape = tapeMono && std::isfinite(tapeMono[i]) ? tapeMono[i] : 0.f;
                const float softcutLeft = softcutInterleaved
                        && std::isfinite(softcutInterleaved[2 * i]) ? softcutInterleaved[2 * i] : 0.f;
                const float softcutRight = softcutInterleaved
                        && std::isfinite(softcutInterleaved[2 * i + 1]) ? softcutInterleaved[2 * i + 1] : 0.f;
                const float monoSend = monitor * reverbSendLevels_[0]
                        + engine * reverbSendLevels_[1]
                        + tape * reverbSendLevels_[2];
                leftIn_[i] = monoSend + softcutLeft * reverbSendLevels_[3];
                rightIn_[i] = monoSend + softcutRight * reverbSendLevels_[3];
            }
            FAUSTFLOAT* inputs[2] = {leftIn_.data(), rightIn_.data()};
            FAUSTFLOAT* outputs[2] = {leftOut_.data(), rightOut_.data()};
            reverb_.compute(frames, inputs, outputs);
            for (int i = 0; i < frames; ++i) {
                masterInterleaved[2 * i] += leftOut_[i] * reverbReturn_;
                masterInterleaved[2 * i + 1] += rightOut_[i] * reverbReturn_;
            }
        }

        if (compressorEnabled_ && compressorMix_ > 0.f) {
            for (int i = 0; i < frames; ++i) {
                const float left = masterInterleaved[2 * i];
                const float right = masterInterleaved[2 * i + 1];
                leftIn_[i] = std::isfinite(left) ? left : 0.f;
                rightIn_[i] = std::isfinite(right) ? right : 0.f;
            }
            FAUSTFLOAT* inputs[2] = {leftIn_.data(), rightIn_.data()};
            FAUSTFLOAT* outputs[2] = {leftOut_.data(), rightOut_.data()};
            compressor_.compute(frames, inputs, outputs);
            const float dry = 1.f - compressorMix_;
            for (int i = 0; i < frames; ++i) {
                masterInterleaved[2 * i] = dry * leftIn_[i] + compressorMix_ * leftOut_[i];
                masterInterleaved[2 * i + 1] = dry * rightIn_[i] + compressorMix_ * rightOut_[i];
            }
        }
        return true;
    }

    int sampleRate() const { return sampleRate_; }
    int maxFrames() const { return maxFrames_; }

private:
    int sampleRate_ = 0;
    int maxFrames_ = 0;
    bool compressorEnabled_ = false;
    bool reverbEnabled_ = false;
    float compressorMix_ = 0.f;
    float reverbReturn_ = 0.f;
    float reverbSendLevels_[4] = {0.f, 0.f, 0.f, 0.f};
    StereoCompressor_dsp compressor_;
    ZitaReverb_dsp reverb_;
    ParameterUI compressorUi_;
    ParameterUI reverbUi_;
    std::vector<float> leftIn_, rightIn_, leftOut_, rightOut_;
};

} // namespace norns_audio
