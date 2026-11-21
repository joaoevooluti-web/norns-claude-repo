/*
 * Norns Mobile Android audio backend for SuperCollider 3.14.1.
 *
 * This file is injected into the upstream scsynth target by
 * tools/patch-scsynth-android.py. It is intentionally separate from the JNI
 * bridge: SC_AudioDriver owns the server callback and Oboe owns device I/O.
 */
#include "SC_CoreAudio.h"
#include "SC_HiddenWorld.h"
#include "SC_Prototypes.h"
#include "SC_Time.hpp"
#include "SC_WorldOptions.h"
#include "NornsSpscFloatRing.h"
#include "SoftcutDiagnosticSnapshot.h"
#include "SoftcutSignalAccumulator.h"
#include "SoftcutOutputMixer.h"
#include "SoftcutEngineSendBus.h"

#include <oboe/Oboe.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <chrono>
#include <cmath>
#include <limits>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

int32 server_timeseed() { return timeSeed(); }

int64 oscTimeNow() { return OSCTime(getTime()); }

void initializeScheduler() {}

class SC_AndroidAudioDriver;
namespace {
std::atomic<SC_AndroidAudioDriver*> gAndroidAudioDriver{nullptr};
}

class SC_AndroidAudioDriver final : public SC_AudioDriver, public oboe::AudioStreamCallback {
public:
    explicit SC_AndroidAudioDriver(World* world) : SC_AudioDriver(world) {
        mSoftcutPositionsSeconds.fill(0.0f);
        mSoftcutPositionValid.fill(false);
        mRecorderPositionsSeconds.fill(-1.0f);
        mRecorderPositionValid.fill(false);
        mRouteProbe.fill(0.0f);
        for (auto& generation : mRecorderDiagnosticResetGeneration)
            generation.store(0U, std::memory_order_relaxed);
        gAndroidAudioDriver.store(this, std::memory_order_release);
    }

    ~SC_AndroidAudioDriver() override {
        DriverStop();
        SC_AndroidAudioDriver* expected = this;
        gAndroidAudioDriver.compare_exchange_strong(expected, nullptr, std::memory_order_acq_rel);
    }

    void readJavaInput(float* destination, int frames) noexcept {
        mJavaInputRing.readWithBacklogLimit(destination, frames,
            static_cast<uint64_t>(mBlockFrames > 0 ? mBlockFrames : frames));
    }

    bool hasInput() const noexcept { return static_cast<bool>(mInputStream); }

    bool readCpuMetrics(float* destination, int count) const noexcept {
        if (!destination || count < 2) return false;
        destination[0] = mCpuAverageSnapshot.load(std::memory_order_acquire);
        destination[1] = mCpuPeakSnapshot.load(std::memory_order_acquire);
        return true;
    }

    // Coherent snapshot: legacy routing/phase fields plus input and per-recorder signal probes.
    bool readSoftcutDiagnostics(float* destination, int count) const noexcept {
        if (count < 54 || !mDiagnosticSnapshot.read(destination, static_cast<size_t>(count))) return false;
        // Gate can change between audio callbacks; report its current atomic state.
        destination[12] = mNativeSoftcutOutputEnabled.load(std::memory_order_acquire) ? 1.0f : 0.0f;
        return true;
    }

    bool resetSoftcutRecorderDiagnostics(int channel) noexcept {
        if (channel < 1 || channel > 2) return false;
        mRecorderDiagnosticResetGeneration[static_cast<size_t>(channel - 1)].fetch_add(
            1U, std::memory_order_acq_rel);
        return true;
    }

    bool setSoftcutOutputProbeMode(bool enabled) noexcept {
        if (enabled && (mNativeSoftcutOutputEnabled.load(std::memory_order_acquire)
                || !mStream || mStream->getState() != oboe::StreamState::Started)) return false;
        mResetProbePeaks.store(true, std::memory_order_release);
        mSoftcutOutputProbeMode.store(enabled, std::memory_order_release);
        if (enabled) return true;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(750);
        while (mProbeBlockPending.load(std::memory_order_acquire)
                || mOutputCallbacksInFlight.load(std::memory_order_acquire) != 0) {
            if (std::chrono::steady_clock::now() >= deadline) return false;
            if (mStream) {
                const auto state = mStream->getState();
                if (state != oboe::StreamState::Started && state != oboe::StreamState::Starting) return false;
            }
            std::this_thread::yield();
        }
        return true;
    }

    bool setNativeSoftcutOutputEnabled(bool enabled) noexcept {
        if (enabled && mSoftcutOutputProbeMode.load(std::memory_order_acquire)) return false;
        mNativeSoftcutOutputEnabled.store(enabled, std::memory_order_release);
        if (enabled) return true;

        // First let every callback that could have copied pre-mute samples finish.
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(750);
        while (mOutputCallbacksInFlight.load(std::memory_order_acquire) != 0) {
            if (std::chrono::steady_clock::now() >= deadline) return false;
            std::this_thread::yield();
        }

        // A callback requested after this point must observe the closed gate.
        // Its completed return is the acknowledgement that a silent PCM block
        // has replaced the last native Softcut block at the Oboe boundary.
        if (!mStream) return true;
        const auto state = mStream->getState();
        if (state != oboe::StreamState::Started && state != oboe::StreamState::Starting)
            return true;
        const uint64_t target = mOutputCallbacksCompleted.load(std::memory_order_acquire) + 1;
        while (mOutputCallbacksCompleted.load(std::memory_order_acquire) < target) {
            const auto currentState = mStream->getState();
            if (currentState != oboe::StreamState::Started
                    && currentState != oboe::StreamState::Starting) return true;
            if (std::chrono::steady_clock::now() >= deadline) return false;
            std::this_thread::yield();
        }
        while (mOutputCallbacksInFlight.load(std::memory_order_acquire) != 0) {
            if (std::chrono::steady_clock::now() >= deadline) return false;
            std::this_thread::yield();
        }
        return true;
    }

protected:
    bool DriverSetup(int* outNumSamplesPerCallback, double* outSampleRate) override {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Shared);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(2);
        builder.setSampleRate(mPreferredSampleRate ? static_cast<int32_t>(mPreferredSampleRate) : 48000);
        builder.setFramesPerDataCallback(static_cast<int32_t>(mWorld->mBufLength));
        builder.setUsage(oboe::Usage::Game);
        builder.setCallback(this);

        const oboe::Result result = builder.openStream(mStream);
        if (result != oboe::Result::OK || !mStream) {
            scprintf("SC_AndroidAudioDriver: Oboe openStream failed: %s\n",
                     oboe::convertToText(result));
            mStream.reset();
            return false;
        }

        mOutputChannelCount = mStream->getChannelCount();
        if (mWorld->mNumOutputs != 2 || mOutputChannelCount != 2 ||
            mStream->getFormat() != oboe::AudioFormat::Float) {
            scprintf("SC_AndroidAudioDriver: unsupported output format/channels\n");
            mStream->close();
            mStream.reset();
            return false;
        }

        *outNumSamplesPerCallback = static_cast<int>(mWorld->mBufLength);
        *outSampleRate = static_cast<double>(mStream->getSampleRate());
        // Oboe's callback frame count is a hint, not a guarantee. Keep one
        // fixed SC block in driver-owned storage and drain it across callbacks.
        mBlockFrames = static_cast<int>(mWorld->mBufLength);
        if (mBlockFrames <= 0) {
            mStream->close();
            mStream.reset();
            return false;
        }
        mBlockOutput.resize(static_cast<size_t>(mBlockFrames) * 2);
        mPendingFrameOffset = mBlockFrames;

        // Entrada mono lógica: Float/Int16 e mono/estéreo para compatibilidade.
        oboe::AudioStreamBuilder inputBuilder;
        inputBuilder.setDirection(oboe::Direction::Input);
        inputBuilder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        inputBuilder.setSharingMode(oboe::SharingMode::Shared);
        inputBuilder.setSampleRate(mStream->getSampleRate());
        inputBuilder.setFramesPerDataCallback(static_cast<int32_t>(mWorld->mBufLength));
        inputBuilder.setCallback(this);
        oboe::Result inputResult = oboe::Result::ErrorUnavailable;
        const std::array<oboe::AudioFormat, 2> inputFormats = {
            oboe::AudioFormat::Float, oboe::AudioFormat::I16
        };
        const std::array<int32_t, 2> inputChannels = {1, 2};
        for (const auto format : inputFormats) {
            for (const auto channelCount : inputChannels) {
                inputBuilder.setFormat(format);
                inputBuilder.setChannelCount(channelCount);
                inputResult = inputBuilder.openStream(mInputStream);
                if (inputResult == oboe::Result::OK && mInputStream
                        && mInputStream->getSampleRate() == mStream->getSampleRate()
                        && (mInputStream->getChannelCount() == 1 || mInputStream->getChannelCount() == 2)) {
                    mInputChannelCount = mInputStream->getChannelCount();
                    break;
                }
                if (mInputStream) {
                    mInputStream->close();
                    mInputStream.reset();
                }
            }
            if (mInputStream) break;
        }
        if (!mInputStream) {
            scprintf("SC_AndroidAudioDriver: Oboe input unavailable (%s); input buses will be silent\n",
                     oboe::convertToText(inputResult));
        }

        scprintf("SC_AndroidAudioDriver: opened %d Hz, %d output channels, input %s, block %u\n",
                 mStream->getSampleRate(), mOutputChannelCount,
                 mInputStream ? "Oboe mono mix" : "disabled", mWorld->mBufLength);
        return true;
    }

    bool DriverStart() override {
        if (!mStream) return false;
        if (mInputStream) {
            const oboe::Result inputResult = mInputStream->requestStart();
            if (inputResult != oboe::Result::OK) {
                scprintf("SC_AndroidAudioDriver: input requestStart failed: %s; input buses will be silent\n",
                         oboe::convertToText(inputResult));
                mInputStream->close();
                mInputStream.reset();
            }
        }
        const oboe::Result result = mStream->requestStart();
        if (result != oboe::Result::OK) {
            scprintf("SC_AndroidAudioDriver: output requestStart failed: %s\n",
                     oboe::convertToText(result));
            if (mInputStream) mInputStream->requestStop();
            return false;
        }
        mOSCbuftime = oscTimeNow();
        return true;
    }

    bool DriverStop() override {
        bool ok = true;
        if (mInputStream) {
            const oboe::StreamState inputState = mInputStream->getState();
            if (inputState == oboe::StreamState::Started || inputState == oboe::StreamState::Starting)
                mInputStream->requestStop();
            const oboe::Result inputClose = mInputStream->close();
            if (inputClose != oboe::Result::OK) {
                scprintf("SC_AndroidAudioDriver: input close: %s\n", oboe::convertToText(inputClose));
                ok = false;
            }
            mInputStream.reset();
        }
        if (mStream) {
            const oboe::StreamState state = mStream->getState();
            if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting) {
                const oboe::Result stopResult = mStream->requestStop();
                if (stopResult != oboe::Result::OK) {
                    scprintf("SC_AndroidAudioDriver: output requestStop: %s\n",
                             oboe::convertToText(stopResult));
                    ok = false;
                }
            }
            const oboe::Result closeResult = mStream->close();
            if (closeResult != oboe::Result::OK) {
                scprintf("SC_AndroidAudioDriver: output close: %s\n", oboe::convertToText(closeResult));
                ok = false;
            }
            mStream.reset();
        }
        return ok;
    }

public:
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override {
        if (stream && stream->getDirection() == oboe::Direction::Input) {
            captureInput(stream, audioData, numFrames);
            return oboe::DataCallbackResult::Continue;
        }
        OutputCallbackGuard callbackGuard(mOutputCallbacksInFlight, mOutputCallbacksCompleted);
        auto* output = static_cast<float*>(audioData);
        const int32_t channels = mOutputChannelCount;
        const bool probeModeForCallback = mSoftcutOutputProbeMode.load(std::memory_order_acquire);
        if (mResetProbePeaks.exchange(false, std::memory_order_acq_rel)
                || (!probeModeForCallback && !mProbeBlockPending.load(std::memory_order_acquire)))
            mSoftcutProbeOutputPeaks.fill(0.0f);
        if (!output || numFrames <= 0 || channels <= 0) {
            return oboe::DataCallbackResult::Continue;
        }

        std::fill(output, output + static_cast<size_t>(numFrames) * channels, 0.0f);
        const auto started = std::chrono::steady_clock::now();

        bool worldBlockProcessed = false;
        try {
            mFromEngine.Free();
            mToEngine.Perform();
            mOscPacketsToEngine.Perform();

            const int blockFrames = mBlockFrames;
            if (blockFrames <= 0 || mBlockOutput.empty()) {
                mAudioSync.Signal();
                return oboe::DataCallbackResult::Continue;
            }

            int writtenFrames = 0;
            while (writtenFrames < numFrames) {
                // Generate a new fixed-size SuperCollider block only after the
                // previous one has been fully copied to Oboe's output buffer.
                if (mPendingFrameOffset >= blockFrames) {
                    const int32 bufferCounter = mWorld->mBufCounter;
                    const int64 nextTime = mOSCbuftime + mOSCincrement;

                    int64 scheduledTime;
                    while ((scheduledTime = mScheduler.NextTime()) <= nextTime) {
                        const float diffTime =
                            static_cast<float>(scheduledTime - mOSCbuftime) * mOSCtoSamples + 0.5f;
                        const float diffFloor = std::floor(diffTime);
                        mWorld->mSampleOffset = static_cast<int>(diffFloor);
                        mWorld->mSubsampleOffset = diffTime - diffFloor;

                        if (mWorld->mSampleOffset < 0) {
                            mWorld->mSampleOffset = 0;
                        } else if (mWorld->mSampleOffset >= blockFrames) {
                            mWorld->mSampleOffset = blockFrames - 1;
                        }

                        SC_ScheduledEvent event = mScheduler.Remove();
                        event.Perform();
                    }

                    mWorld->mSampleOffset = 0;
                    mWorld->mSubsampleOffset = 0.0f;
                    fillInputBuses(bufferCounter, blockFrames);
                    World_Run(mWorld);
                    captureRecorderDiagnostics(bufferCounter, blockFrames);
                    worldBlockProcessed = true;

                    // The master instrument output remains audible; the independent Softcut buses 9/10
                    // are mixed only after their own safety gate opens (or in muted probe mode).
                    float* outputBuses = mWorld->mAudioBus;
                    int32* outputTouched = mWorld->mAudioBusTouched;
                    const bool nativeEnabled = mNativeSoftcutOutputEnabled.load(std::memory_order_acquire)
                                || probeModeForCallback;
                    for (int channel = 0; channel < 2; ++channel) {
                        const int masterBus = channel;
                        const int bus = 9 + channel;
                        const bool masterTouched = outputTouched[masterBus] == bufferCounter;
                        const float* master = outputBuses + masterBus * blockFrames;
                        float masterPeak = 0.0f;
                        float softcutPeak = 0.0f;
                        if (masterTouched) {
                            for (int frame = 0; frame < blockFrames; ++frame)
                                masterPeak = std::max(masterPeak, std::fabs(master[frame]));
                        }
                        const bool softcutTouched = outputTouched[bus] == bufferCounter;
                        if (softcutTouched) {
                            const float* source = outputBuses + bus * blockFrames;
                            for (int frame = 0; frame < blockFrames; ++frame)
                                softcutPeak = std::max(softcutPeak, std::fabs(source[frame]));
                        }
                        mRouteProbe[channel] = masterPeak;
                        mRouteProbe[2 + channel] = softcutPeak;
                        float* destination = mBlockOutput.data() + channel;
                        const float* softcut = outputBuses + bus * blockFrames;
                        // The SuperCollider master is the normal instrument output and
                        // must remain audible while the independent Softcut gate is closed.
                        // Softcut buses 9/10 are mixed only after their own safety gate opens.
                        for (int frame = 0; frame < blockFrames; ++frame) {
                            const float sample = SoftcutOutputMixer::mixChannel(
                                probeModeForCallback ? 0.0f : (masterTouched ? master[frame] : 0.0f),
                                probeModeForCallback ? false : masterTouched,
                                softcutTouched ? softcut[frame] : 0.0f, softcutTouched,
                                nativeEnabled);
                            destination[static_cast<size_t>(frame) * 2] = sample;
                        }
                    }
                    mPendingProbeMode = probeModeForCallback;
                    if (mPendingProbeMode) {
                        mProbeBlockPending.store(true, std::memory_order_release);
                        for (int channel = 0; channel < 2; ++channel) {
                            float peak = 0.0f;
                            for (int frame = 0; frame < blockFrames; ++frame) {
                                const float sample = mBlockOutput[static_cast<size_t>(frame) * 2 + channel];
                                if (std::isfinite(sample)) peak = std::max(peak, std::fabs(sample));
                            }
                            mSoftcutProbeOutputPeaks[static_cast<size_t>(channel)] = peak;
                        }
                    }
                    // Phase buses 11..16 are inspected but never copied to Oboe.
                    for (int voice = 0; voice < 6; ++voice) {
                        const int positionBus = 11 + voice;
                        bool valid = false;
                        if (outputTouched[positionBus] == bufferCounter) {
                            const float* source = outputBuses + positionBus * blockFrames;
                            const float position = source[blockFrames - 1];
                            if (std::isfinite(position)) {
                                mSoftcutPositionsSeconds[voice] = position;
                                valid = true;
                            }
                        }
                        mSoftcutPositionValid[voice] = valid;
                    }

                    // Recorder cursor witnesses on buses 17/18; never routed into Oboe output.
                    for (int channel = 0; channel < 2; ++channel) {
                        const int positionBus = 17 + channel;
                        bool valid = false;
                        if (outputTouched[positionBus] == bufferCounter) {
                            const float* source = outputBuses + positionBus * blockFrames;
                            const float position = source[blockFrames - 1];
                            if (std::isfinite(position) && position >= 0.0f) {
                                mRecorderPositionsSeconds[channel] = position;
                                valid = true;
                            }
                        }
                        mRecorderPositionValid[channel] = valid;
                    }

                    ++mWorld->mBufCounter;
                    // Capture only the SuperCollider master instrument buses (0/1).
                    // Softcut playback buses 9/10 and recorder diagnostics 19..30 are excluded.
                    // Bus 31 is reserved for the recorder and becomes valid in the next block.
                    SoftcutEngineSendBus::captureNextBlock(
                        mWorld->mAudioBus, mWorld->mAudioBusTouched,
                        blockFrames, bufferCounter, mWorld->mBufCounter);
                    mOSCbuftime = nextTime;
                    mPendingFrameOffset = 0;
                }

                const int available = blockFrames - mPendingFrameOffset;
                const int count = std::min(available, static_cast<int>(numFrames) - writtenFrames);
                const float* source = mBlockOutput.data()
                    + static_cast<size_t>(mPendingFrameOffset) * channels;
                float* destination = output + static_cast<size_t>(writtenFrames) * channels;
                // This stream carries the always-enabled master route. The Softcut
                // safety gate is applied only when buses 9/10 are mixed above.
                if (probeModeForCallback || mPendingProbeMode) {
                    std::fill(destination, destination + static_cast<size_t>(count) * channels, 0.0f);
                } else {
                    std::copy(source, source + static_cast<size_t>(count) * channels, destination);
                }
                mPendingFrameOffset += count;
                if (mPendingProbeMode && mPendingFrameOffset >= blockFrames)
                    mProbeBlockPending.store(false, std::memory_order_release);
                writtenFrames += count;
            }
        } catch (const std::exception& error) {
            scprintf("SC_AndroidAudioDriver: realtime exception: %s\n", error.what());
        } catch (...) {
            scprintf("SC_AndroidAudioDriver: unknown realtime exception\n");
        }

        // Measure the final PCM buffer after the output gate. These are the actual
        // samples returned to Oboe, not merely the pre-gate source bus peaks.
        for (int channel = 0; channel < 2; ++channel) {
            float peak = 0.0f;
            for (int frame = 0; frame < numFrames; ++frame) {
                const float sample = output[static_cast<size_t>(frame) * channels + channel];
                if (std::isfinite(sample)) peak = std::max(peak, std::fabs(sample));
            }
            mRouteProbe[4 + channel] = peak;
        }

        std::array<float, SoftcutDiagnosticSnapshot::kValueCount> diagnosticValues{};
        for (int voice = 0; voice < 6; ++voice) {
            diagnosticValues[voice] = mSoftcutPositionValid[voice]
                ? mSoftcutPositionsSeconds[voice]
                : std::numeric_limits<float>::quiet_NaN();
        }
        for (int index = 0; index < 6; ++index)
            diagnosticValues[6 + index] = mRouteProbe[index];
        diagnosticValues[12] =
            mNativeSoftcutOutputEnabled.load(std::memory_order_acquire) ? 1.0f : 0.0f;
        for (int channel = 0; channel < 2; ++channel)
            diagnosticValues[14 + channel] = mRecorderPositionValid[channel]
                ? mRecorderPositionsSeconds[channel] : -1.0f;
        // The modulo counter remains exactly representable as float and changes each callback.
        if (worldBlockProcessed)
            mDiagnosticCallbackSequence = (mDiagnosticCallbackSequence + 1U) % 1000000U;
        diagnosticValues[13] = static_cast<float>(mDiagnosticCallbackSequence);
        diagnosticValues[52] = mSoftcutProbeOutputPeaks[0];
        diagnosticValues[53] = mSoftcutProbeOutputPeaks[1];
        // Shared input/ring telemetry is cumulative for this driver session. Per-channel
        // recorder resets must not reset it and hide activity from the other channel.
        // dropped/skipped/underrun are lifetime totals, not deltas from a channel reset.
        diagnosticValues[16] = mInputBusAccumulator.peak();
        diagnosticValues[17] = mInputBusAccumulator.rms();
        diagnosticValues[18] = static_cast<float>(mInputFramesReadTotal);
        diagnosticValues[19] = static_cast<float>(mScInputRing.droppedFrames());
        diagnosticValues[20] = static_cast<float>(mScInputRing.skippedFrames());
        diagnosticValues[21] = static_cast<float>(mScInputRing.underrunFrames());
        diagnosticValues[22] = static_cast<float>(mInputChannelCount);
        diagnosticValues[23] = static_cast<float>(mInputStream ? mInputStream->getSampleRate() : 0);
        diagnosticValues[48] = static_cast<float>(mScInputRing.acceptedFrames());
        diagnosticValues[49] = static_cast<float>(mScInputRing.queuedFrames());
        diagnosticValues[50] = static_cast<float>(mScInputRing.requestedFrames());
        diagnosticValues[51] = static_cast<float>(mScInputRing.acceptedFrames()
                + mScInputRing.droppedFrames());
        for (size_t i = 0; i < mRecorderSignalDiagnostics.size(); ++i)
            diagnosticValues[24 + i] = mRecorderSignalDiagnostics[i];
        mDiagnosticSnapshot.publish(diagnosticValues.data());

        const auto ended = std::chrono::steady_clock::now();
        const double elapsed =
            std::chrono::duration<double>(ended - started).count();
        const double callbackSeconds = static_cast<double>(numFrames) /
                                       static_cast<double>(mStream ? mStream->getSampleRate() : 48000);
        const double cpuUsage = callbackSeconds > 0.0 ? elapsed / callbackSeconds * 100.0 : 0.0;
        mAvgCPU += 0.1 * (cpuUsage - mAvgCPU);
        mCpuAverageSnapshot.store(static_cast<float>(mAvgCPU), std::memory_order_release);
        if (cpuUsage > mPeakCPU || --mPeakCounter <= 0) {
            mPeakCPU = cpuUsage;
            mPeakCounter = std::max(1, mMaxPeakCounter);
        }
        mCpuPeakSnapshot.store(static_cast<float>(mPeakCPU), std::memory_order_release);


        mAudioSync.Signal();
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        scprintf("SC_AndroidAudioDriver: stream closed with error: %s\n",
                 oboe::convertToText(error));
    }

private:
    class OutputCallbackGuard {
    public:
        OutputCallbackGuard(std::atomic<uint32_t>& inFlight,
                            std::atomic<uint64_t>& completed) noexcept
            : inFlight_(inFlight), completed_(completed) {
            inFlight_.fetch_add(1, std::memory_order_acq_rel);
        }
        ~OutputCallbackGuard() {
            completed_.fetch_add(1, std::memory_order_release);
            inFlight_.fetch_sub(1, std::memory_order_release);
        }
    private:
        std::atomic<uint32_t>& inFlight_;
        std::atomic<uint64_t>& completed_;
    };

    static constexpr size_t kInputRingFrames = 32768;

    void pushInputSample(float value) noexcept {
        mScInputRing.push(value);
        mJavaInputRing.push(value);
    }

    void captureInput(oboe::AudioStream* stream, const void* data, int32_t frames) noexcept {
        if (!stream || !data || frames <= 0) return;
        const int channels = std::max(1, stream->getChannelCount());
        if (stream->getFormat() == oboe::AudioFormat::Float) {
            const auto* samples = static_cast<const float*>(data);
            for (int32_t frame = 0; frame < frames; ++frame) {
                float mono = 0.0f;
                for (int channel = 0; channel < channels; ++channel)
                    mono += samples[static_cast<size_t>(frame) * channels + channel];
                pushInputSample(std::clamp(mono / channels, -1.0f, 1.0f));
            }
        } else if (stream->getFormat() == oboe::AudioFormat::I16) {
            const auto* samples = static_cast<const int16_t*>(data);
            for (int32_t frame = 0; frame < frames; ++frame) {
                int32_t mono = 0;
                for (int channel = 0; channel < channels; ++channel)
                    mono += samples[static_cast<size_t>(frame) * channels + channel];
                pushInputSample(std::clamp(static_cast<float>(mono) / (32768.0f * channels), -1.0f, 1.0f));
            }
        }
    }

    void fillInputBuses(int32 bufferCounter, int blockFrames) noexcept {
        if (!mWorld || mWorld->mNumInputs == 0) return;
        float* buses = mWorld->mAudioBus + mWorld->mNumOutputs * blockFrames;
        int32* touched = mWorld->mAudioBusTouched + mWorld->mNumOutputs;
        for (uint32 channel = 0; channel < mWorld->mNumInputs; ++channel) {
            float* destination = buses + static_cast<size_t>(channel) * blockFrames;
            if (channel == 0) {
                const int framesRead = mScInputRing.readWithBacklogLimit(destination, blockFrames,
                    static_cast<uint64_t>(blockFrames));
                mInputFramesReadTotal += static_cast<uint64_t>(framesRead);
                mInputBusAccumulator.add(destination, static_cast<size_t>(blockFrames));
            } else {
                std::fill(destination, destination + blockFrames, 0.0f);
            }
            touched[channel] = bufferCounter;
        }
    }

    void captureRecorderDiagnostics(int32 bufferCounter, int blockFrames) noexcept {
        if (!mWorld || !mWorld->mAudioBus || !mWorld->mAudioBusTouched) return;
        const float* buses = mWorld->mAudioBus;
        const int32* touched = mWorld->mAudioBusTouched;
        for (int channel = 0; channel < 2; ++channel) {
            const size_t channelIndex = static_cast<size_t>(channel);
            const uint32_t requestedReset = mRecorderDiagnosticResetGeneration[channelIndex].load(
                std::memory_order_acquire);
            if (requestedReset != mRecorderDiagnosticAppliedGeneration[channelIndex]) {
                for (int metric = 0; metric < 6; ++metric)
                    mRecorderSignalAccumulators[channelIndex * 6 + static_cast<size_t>(metric)].reset();
                mRecorderDiagnosticAppliedGeneration[channelIndex] = requestedReset;
            }
            const int baseBus = 19 + channel * 6;
            for (int metric = 0; metric < 6; ++metric) {
                const int bus = baseBus + metric;
                const size_t accumulatorIndex = channelIndex * 6 + static_cast<size_t>(metric);
                if (touched[bus] == bufferCounter) {
                    const float* source = buses + static_cast<size_t>(bus) * blockFrames;
                    mRecorderSignalAccumulators[accumulatorIndex].add(source,
                        static_cast<size_t>(blockFrames));
                }
                const size_t offset = channelIndex * 12 + static_cast<size_t>(metric) * 2;
                mRecorderSignalDiagnostics[offset] = mRecorderSignalAccumulators[accumulatorIndex].peak();
                mRecorderSignalDiagnostics[offset + 1] = mRecorderSignalAccumulators[accumulatorIndex].rms();
            }
        }
    }

    std::shared_ptr<oboe::AudioStream> mStream;
    std::shared_ptr<oboe::AudioStream> mInputStream;
    int32_t mOutputChannelCount = 0;
    std::atomic<float> mCpuAverageSnapshot{0.0f};
    std::atomic<float> mCpuPeakSnapshot{0.0f};
    std::atomic<bool> mNativeSoftcutOutputEnabled{false};
    std::atomic<bool> mSoftcutOutputProbeMode{false};
    std::atomic<bool> mResetProbePeaks{false};
    std::atomic<bool> mProbeBlockPending{false};
    bool mPendingProbeMode = false;
    std::array<float, 2> mSoftcutProbeOutputPeaks{};
    std::atomic<uint32_t> mOutputCallbacksInFlight{0};
    std::atomic<uint64_t> mOutputCallbacksCompleted{0};
    std::array<float, 6> mSoftcutPositionsSeconds{};
    std::array<bool, 6> mSoftcutPositionValid{};
    std::array<float, 2> mRecorderPositionsSeconds{};
    std::array<bool, 2> mRecorderPositionValid{};
    // master L/R, dedicated Softcut L/R, final post-gate Oboe PCM L/R.
    std::array<float, 6> mRouteProbe{};
    SoftcutDiagnosticSnapshot mDiagnosticSnapshot;
    SoftcutSignalAccumulator mInputBusAccumulator;
    uint64_t mInputFramesReadTotal = 0;
    std::array<float, 24> mRecorderSignalDiagnostics{};
    std::array<SoftcutSignalAccumulator, 12> mRecorderSignalAccumulators{};
    std::array<std::atomic<uint32_t>, 2> mRecorderDiagnosticResetGeneration{};
    std::array<uint32_t, 2> mRecorderDiagnosticAppliedGeneration{};
    uint32_t mDiagnosticCallbackSequence = 0;
    int mBlockFrames = 0;
    int mPendingFrameOffset = 0;
    std::vector<float> mBlockOutput;
    SpscFloatRing<kInputRingFrames> mScInputRing;
    SpscFloatRing<kInputRingFrames> mJavaInputRing;
    int32_t mInputChannelCount = 0;
};

SC_AudioDriver* SC_NewAudioDriver(World* world) {
    return new SC_AndroidAudioDriver(world);
}


namespace {
std::mutex gNornsWorldMutex;
World* gNornsWorld = nullptr;

constexpr size_t kReplyQueueCapacity = 128;
constexpr int kReplyPacketCapacity = 4096;
struct ReplySlot {
    std::atomic<size_t> sequence;
    int size;
    char data[kReplyPacketCapacity];
};
ReplySlot gReplyQueue[kReplyQueueCapacity];
std::atomic<size_t> gReplyEnqueuePosition{0};
std::atomic<size_t> gReplyDequeuePosition{0};
std::once_flag gReplyQueueInit;

void initializeReplyQueue() {
    for (size_t i = 0; i < kReplyQueueCapacity; ++i) {
        gReplyQueue[i].sequence.store(i, std::memory_order_relaxed);
        gReplyQueue[i].size = 0;
    }
}

// Called only while no World is active (under gNornsWorldMutex) so replies
// from a previous server session cannot leak into the next script.
void resetReplyQueue() {
    gReplyEnqueuePosition.store(0, std::memory_order_relaxed);
    gReplyDequeuePosition.store(0, std::memory_order_relaxed);
    for (size_t i = 0; i < kReplyQueueCapacity; ++i) {
        gReplyQueue[i].size = 0;
        gReplyQueue[i].sequence.store(i, std::memory_order_relaxed);
    }
}

// The server callback only copies a bounded packet and uses atomics. It never
// calls JNI, allocates memory, or waits on a mutex.
bool enqueueReply(const char* data, int size) noexcept {
    if (!data || size <= 0 || size > kReplyPacketCapacity) return false;
    size_t position = gReplyEnqueuePosition.load(std::memory_order_relaxed);
    ReplySlot* slot = nullptr;
    for (;;) {
        slot = &gReplyQueue[position % kReplyQueueCapacity];
        const size_t sequence = slot->sequence.load(std::memory_order_acquire);
        const intptr_t difference = static_cast<intptr_t>(sequence) - static_cast<intptr_t>(position);
        if (difference == 0) {
            if (gReplyEnqueuePosition.compare_exchange_weak(position, position + 1, std::memory_order_relaxed)) break;
        } else if (difference < 0) {
            return false;
        } else {
            position = gReplyEnqueuePosition.load(std::memory_order_relaxed);
        }
    }
    std::memcpy(slot->data, data, static_cast<size_t>(size));
    slot->size = size;
    slot->sequence.store(position + 1, std::memory_order_release);
    return true;
}

void nornsSCReplyCallback(ReplyAddress*, char* data, int size) {
    enqueueReply(data, size);
}

int dequeueReply(char* output, int capacity) noexcept {
    if (!output || capacity <= 0) return 0;
    size_t position = gReplyDequeuePosition.load(std::memory_order_relaxed);
    ReplySlot* slot = nullptr;
    for (;;) {
        slot = &gReplyQueue[position % kReplyQueueCapacity];
        const size_t sequence = slot->sequence.load(std::memory_order_acquire);
        const intptr_t difference = static_cast<intptr_t>(sequence) - static_cast<intptr_t>(position + 1);
        if (difference == 0) {
            if (gReplyDequeuePosition.compare_exchange_weak(position, position + 1, std::memory_order_relaxed)) break;
        } else if (difference < 0) {
            return 0;
        } else {
            position = gReplyDequeuePosition.load(std::memory_order_relaxed);
        }
    }
    const int size = slot->size;
    if (size > capacity) {
        slot->sequence.store(position + kReplyQueueCapacity, std::memory_order_release);
        return -1;
    }
    std::memcpy(output, slot->data, static_cast<size_t>(size));
    slot->sequence.store(position + kReplyQueueCapacity, std::memory_order_release);
    return size;
}
}

SCSYNTH_DLLEXPORT_C bool NornsSC_Start(int sampleRate, int blockSize, const char* pluginPath) {
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    if (gNornsWorld) return true;
    std::call_once(gReplyQueueInit, initializeReplyQueue);
    resetReplyQueue();
    if (!pluginPath || pluginPath[0] == '\0') {
        scprintf("NornsSC_Start: plugin path is empty\\n");
        return false;
    }

    WorldOptions options;
    options.mRealTime = true;
    options.mRendezvous = false;
    options.mNumInputBusChannels = 1;
    options.mNumOutputBusChannels = 2;
    // Softcut uses stable native buffer IDs 20001/20002; 20003/20004 are
    // reserved for the temporary, non-destructive CH1/CH2 isolation probe.
    options.mNumBuffers = 20032;
    options.mPreferredSampleRate = static_cast<uint32>(sampleRate > 0 ? sampleRate : 48000);
    options.mBufLength = static_cast<uint32>(blockSize > 0 ? blockSize : 64);
    options.mUGensPluginPath = pluginPath;
    options.mVerbosity = 0;

    gNornsWorld = World_New(&options);
    if (!gNornsWorld) {
        scprintf("NornsSC_Start: World_New failed\\n");
        return false;
    }
    scprintf("NornsSC_Start: SuperCollider World started (%u Hz, block %u)\\n",
             options.mPreferredSampleRate, options.mBufLength);
    return true;
}

SCSYNTH_DLLEXPORT_C bool NornsSC_SendPacket(const char* data, int size) {
    if (!data || size <= 0 || size > 65507) return false;
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    if (!gNornsWorld) return false;
    // World_SendPacket parses and queues the OSC packet synchronously; the
    // caller-owned bytes may be released as soon as this function returns.
    return World_SendPacketWithContext(gNornsWorld, size, const_cast<char*>(data), &nornsSCReplyCallback, nullptr);
}

SCSYNTH_DLLEXPORT_C int NornsSC_PollReply(char* output, int capacity) {
    std::call_once(gReplyQueueInit, initializeReplyQueue);
    return dequeueReply(output, capacity);
}

SCSYNTH_DLLEXPORT_C void NornsSC_ReadInput(float* output, int frames) {
    if (!output || frames <= 0) return;
    std::unique_lock<std::mutex> guard(gNornsWorldMutex, std::try_to_lock);
    SC_AndroidAudioDriver* driver = guard.owns_lock()
        ? gAndroidAudioDriver.load(std::memory_order_acquire) : nullptr;
    if (driver) {
        driver->readJavaInput(output, frames);
    } else {
        std::fill(output, output + frames, 0.0f);
    }
}

SCSYNTH_DLLEXPORT_C bool NornsSC_ReadCpuMetrics(float* output, int count) {
    if (!output || count < 2) return false;
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    SC_AndroidAudioDriver* driver = gAndroidAudioDriver.load(std::memory_order_acquire);
    return driver && driver->readCpuMetrics(output, count);
}

SCSYNTH_DLLEXPORT_C bool NornsSC_ReadSoftcutDiagnostics(float* output, int count) {
    if (!output || count < 54) return false;
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    SC_AndroidAudioDriver* driver = gAndroidAudioDriver.load(std::memory_order_acquire);
    return driver && driver->readSoftcutDiagnostics(output, count);
}

SCSYNTH_DLLEXPORT_C bool NornsSC_ResetSoftcutRecorderDiagnostics(int channel) {
    if (channel < 1 || channel > 2) return false;
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    SC_AndroidAudioDriver* driver = gAndroidAudioDriver.load(std::memory_order_acquire);
    return driver && driver->resetSoftcutRecorderDiagnostics(channel);
}

SCSYNTH_DLLEXPORT_C bool NornsSC_HasInput() {
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    SC_AndroidAudioDriver* driver = gAndroidAudioDriver.load(std::memory_order_acquire);
    return driver && driver->hasInput();
}

SCSYNTH_DLLEXPORT_C bool NornsSC_SetSoftcutOutputEnabled(bool enabled) {
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    SC_AndroidAudioDriver* driver = gAndroidAudioDriver.load(std::memory_order_acquire);
    if (!driver) return !enabled;
    return driver->setNativeSoftcutOutputEnabled(enabled);
}

SCSYNTH_DLLEXPORT_C bool NornsSC_SetSoftcutOutputProbeMode(bool enabled) {
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    SC_AndroidAudioDriver* driver = gAndroidAudioDriver.load(std::memory_order_acquire);
    if (!driver) return !enabled;
    return driver->setSoftcutOutputProbeMode(enabled);
}

SCSYNTH_DLLEXPORT_C void NornsSC_Stop() {
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    if (!gNornsWorld) return;
    World* world = gNornsWorld;
    gNornsWorld = nullptr;
    World_Cleanup(world, false);
    scprintf("NornsSC_Stop: SuperCollider World stopped\\n");
}

SCSYNTH_DLLEXPORT_C bool NornsSC_IsRunning() {
    std::lock_guard<std::mutex> guard(gNornsWorldMutex);
    return gNornsWorld != nullptr;
}
