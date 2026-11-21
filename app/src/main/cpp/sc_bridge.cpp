#include <jni.h>
#include <dlfcn.h>
#include <mutex>
#include <string>

namespace {
using StartFn = bool (*)(int, int, const char*);
using StopFn = void (*)();
using IsRunningFn = bool (*)();
using SendPacketFn = bool (*)(const char*, int);
using PollReplyFn = int (*)(char*, int);
using ReadInputFn = void (*)(float*, int);
using HasInputFn = bool (*)();
using SetSoftcutOutputFn = bool (*)(bool);
using SetSoftcutOutputProbeModeFn = bool (*)(bool);
using ReadSoftcutDiagnosticsFn = bool (*)(float*, int);
using ReadCpuMetricsFn = bool (*)(float*, int);
using ResetSoftcutRecorderDiagnosticsFn = bool (*)(int);

std::mutex gLibraryMutex;
void* gScsynthHandle = nullptr;

void* scsynthHandle() {
    std::lock_guard<std::mutex> guard(gLibraryMutex);
    if (!gScsynthHandle) {
        // Resolve against the actual packaged library, not RTLD_DEFAULT:
        // Android may load DT_NEEDED dependencies with local symbol visibility.
        gScsynthHandle = dlopen("libscsynth.so", RTLD_NOW | RTLD_LOCAL);
    }
    return gScsynthHandle;
}

template <typename T>
T resolve(const char* name) {
    void* handle = scsynthHandle();
    return handle ? reinterpret_cast<T>(dlsym(handle, name)) : nullptr;
}

StartFn resolveStart() { return resolve<StartFn>("NornsSC_Start"); }
StopFn resolveStop() { return resolve<StopFn>("NornsSC_Stop"); }
IsRunningFn resolveIsRunning() { return resolve<IsRunningFn>("NornsSC_IsRunning"); }
SendPacketFn resolveSendPacket() { return resolve<SendPacketFn>("NornsSC_SendPacket"); }
PollReplyFn resolvePollReply() { return resolve<PollReplyFn>("NornsSC_PollReply"); }
ReadInputFn resolveReadInput() { return resolve<ReadInputFn>("NornsSC_ReadInput"); }
HasInputFn resolveHasInput() { return resolve<HasInputFn>("NornsSC_HasInput"); }
SetSoftcutOutputFn resolveSetSoftcutOutput() { return resolve<SetSoftcutOutputFn>("NornsSC_SetSoftcutOutputEnabled"); }
SetSoftcutOutputProbeModeFn resolveSetSoftcutOutputProbeMode() { return resolve<SetSoftcutOutputProbeModeFn>("NornsSC_SetSoftcutOutputProbeMode"); }
ReadSoftcutDiagnosticsFn resolveReadSoftcutDiagnostics() { return resolve<ReadSoftcutDiagnosticsFn>("NornsSC_ReadSoftcutDiagnostics"); }
ReadCpuMetricsFn resolveReadCpuMetrics() { return resolve<ReadCpuMetricsFn>("NornsSC_ReadCpuMetrics"); }
ResetSoftcutRecorderDiagnosticsFn resolveResetSoftcutRecorderDiagnostics() { return resolve<ResetSoftcutRecorderDiagnosticsFn>("NornsSC_ResetSoftcutRecorderDiagnostics"); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeIsAvailable(JNIEnv*, jclass) {
    return resolveStart() && resolveStop() && resolveIsRunning() && resolveSendPacket()
        && resolvePollReply() && resolveReadInput() && resolveHasInput()
        && resolveSetSoftcutOutput() && resolveSetSoftcutOutputProbeMode() && resolveReadSoftcutDiagnostics()
        && resolveResetSoftcutRecorderDiagnostics() && resolveReadCpuMetrics() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeStatus(JNIEnv* env, jclass) {
    const auto start = resolveStart();
    const auto isRunning = resolveIsRunning();
    const char* loadError = dlerror();
    const std::string status = !start
        ? std::string("Não foi possível resolver NornsSC_Start em libscsynth.so: ") +
              (loadError ? loadError : "símbolo ausente")
        : (isRunning && isRunning()
            ? "SuperCollider 3.14.1 ARM64 ativo com driver Oboe."
            : "Backend SuperCollider 3.14.1 ARM64 disponível; servidor ainda não iniciado.");
    return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeStart(
    JNIEnv* env, jclass, jint sampleRate, jint blockSize, jstring pluginPath) {
    const auto start = resolveStart();
    if (!start || !pluginPath) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(pluginPath, nullptr);
    if (!path) return JNI_FALSE;
    const bool started = start(sampleRate, blockSize, path);
    env->ReleaseStringUTFChars(pluginPath, path);
    return started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeSendPacket(
    JNIEnv* env, jclass, jbyteArray packet) {
    if (!packet) return JNI_FALSE;
    const jsize size = env->GetArrayLength(packet);
    if (size <= 0 || size > 65507) return JNI_FALSE;
    const auto sendPacket = resolveSendPacket();
    if (!sendPacket) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(packet, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool sent = sendPacket(reinterpret_cast<const char*>(bytes), static_cast<int>(size));
    env->ReleaseByteArrayElements(packet, bytes, JNI_ABORT);
    return sent ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeHasInput(JNIEnv*, jclass) {
    const auto hasInput = resolveHasInput();
    return hasInput && hasInput() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeReadInput(
    JNIEnv* env, jclass, jfloatArray destination, jint frames) {
    if (!destination || frames <= 0 || env->GetArrayLength(destination) < frames) return;
    jfloat* samples = env->GetFloatArrayElements(destination, nullptr);
    if (!samples) return;
    const auto readInput = resolveReadInput();
    if (readInput) readInput(samples, frames);
    else for (jint i = 0; i < frames; ++i) samples[i] = 0.0f;
    env->ReleaseFloatArrayElements(destination, samples, 0);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativePollReply(JNIEnv* env, jclass) {
    const auto pollReply = resolvePollReply();
    if (!pollReply) return nullptr;
    char packet[4096];
    const int size = pollReply(packet, sizeof(packet));
    if (size <= 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(packet));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeReadCpuMetrics(
    JNIEnv* env, jclass, jfloatArray destination) {
    if (!destination || env->GetArrayLength(destination) < 2) return JNI_FALSE;
    const auto read = resolveReadCpuMetrics();
    if (!read) return JNI_FALSE;
    jfloat* values = env->GetFloatArrayElements(destination, nullptr);
    if (!values) return JNI_FALSE;
    const bool ok = read(values, 2);
    env->ReleaseFloatArrayElements(destination, values, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeReadSoftcutDiagnostics(
    JNIEnv* env, jclass, jfloatArray destination) {
    if (!destination || env->GetArrayLength(destination) < 54) return JNI_FALSE;
    const auto read = resolveReadSoftcutDiagnostics();
    if (!read) return JNI_FALSE;
    jfloat* values = env->GetFloatArrayElements(destination, nullptr);
    if (!values) return JNI_FALSE;
    const bool ok = read(values, 54);
    env->ReleaseFloatArrayElements(destination, values, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeResetSoftcutRecorderDiagnostics(JNIEnv*, jclass, jint channel) {
    const auto reset = resolveResetSoftcutRecorderDiagnostics();
    return reset && reset(channel) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeSetSoftcutOutputEnabled(JNIEnv*, jclass, jboolean enabled) {
    const auto setter = resolveSetSoftcutOutput();
    return setter && setter(enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeSetSoftcutOutputProbeMode(JNIEnv*, jclass, jboolean enabled) {
    const auto setter = resolveSetSoftcutOutputProbeMode();
    return setter && setter(enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_nornsmobile_app_SuperColliderBridge_nativeStop(JNIEnv*, jclass) {
    const auto stop = resolveStop();
    if (stop) stop();
}
