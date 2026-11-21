#include <jni.h>
#include <cstdint>
#include <new>
#include <string>

#include "NornsFaustEffectsCore.h"

namespace {
norns_audio::FaustEffectsCore* fromHandle(jlong handle) {
    return reinterpret_cast<norns_audio::FaustEffectsCore*>(static_cast<uintptr_t>(handle));
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_nornsmobile_app_NornsFaustEffects_nativeCreate(JNIEnv*, jclass, jint sampleRate, jint maxFrames) {
    auto* processor = new (std::nothrow) norns_audio::FaustEffectsCore();
    if (!processor) return 0;
    try {
        if (!processor->init(sampleRate, maxFrames)) {
            delete processor;
            return 0;
        }
    } catch (...) {
        delete processor;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(processor));
}

extern "C" JNIEXPORT void JNICALL
Java_org_nornsmobile_app_NornsFaustEffects_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_NornsFaustEffects_nativeSetEnabled(JNIEnv*, jclass, jlong handle,
                                                             jint effect, jboolean enabled) {
    auto* processor = fromHandle(handle);
    return processor && processor->setEnabled(effect, enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_NornsFaustEffects_nativeSetParameter(JNIEnv*, jclass, jlong handle,
                                                               jint effect, jint parameter, jfloat value) {
    auto* processor = fromHandle(handle);
    return processor && processor->setParameter(effect, parameter, value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_NornsFaustEffects_nativeSetMix(JNIEnv*, jclass, jlong handle,
                                                         jint effect, jfloat value) {
    auto* processor = fromHandle(handle);
    return processor && processor->setMix(effect, value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_NornsFaustEffects_nativeSetReverbSendLevel(JNIEnv*, jclass, jlong handle,
                                                                     jint source, jfloat value) {
    auto* processor = fromHandle(handle);
    return processor && processor->setReverbSendLevel(source, value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_NornsFaustEffects_nativeProcess(JNIEnv* env, jclass, jlong handle,
                                                          jobject master, jobject monitor,
                                                          jobject engine, jobject tape,
                                                          jobject softcut, jint frames) {
    auto* processor = fromHandle(handle);
    if (!processor || !master || frames < 0 || frames > processor->maxFrames())
        return JNI_FALSE;
    const jlong masterBytes = static_cast<jlong>(frames) * 2 * sizeof(float);
    const jlong sendBytes = static_cast<jlong>(frames) * sizeof(float);
    if (env->GetDirectBufferCapacity(master) < masterBytes) return JNI_FALSE;
    jobject monoSources[3] = {monitor, engine, tape};
    for (jobject source : monoSources)
        if (source && env->GetDirectBufferCapacity(source) < sendBytes) return JNI_FALSE;
    if (softcut && env->GetDirectBufferCapacity(softcut) < masterBytes) return JNI_FALSE;
    auto* masterData = static_cast<float*>(env->GetDirectBufferAddress(master));
    auto* monitorData = monitor ? static_cast<float*>(env->GetDirectBufferAddress(monitor)) : nullptr;
    auto* engineData = engine ? static_cast<float*>(env->GetDirectBufferAddress(engine)) : nullptr;
    auto* tapeData = tape ? static_cast<float*>(env->GetDirectBufferAddress(tape)) : nullptr;
    auto* softcutData = softcut ? static_cast<float*>(env->GetDirectBufferAddress(softcut)) : nullptr;
    if (!masterData || (monitor && !monitorData) || (engine && !engineData)
            || (tape && !tapeData) || (softcut && !softcutData))
        return JNI_FALSE;
    return processor->process(masterData, monitorData, engineData, tapeData,
            softcutData, frames) ? JNI_TRUE : JNI_FALSE;
}
