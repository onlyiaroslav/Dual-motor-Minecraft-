// Starling — headless JVM host in-process (PRODUCTION VERSION for Android).
//
// Replaces docs/starling_jvm_host.cpp (which was an illustrative snippet).
// Key differences Linux → Android (see docs/STARLING_INTEGRATION.md):
//   1. libjvm.so is NOT linked into the build (the NDK has none): it is loaded with
//      dlopen at runtime from the Android JRE path (PojavLauncher-style).
//   2. AttachCurrentThread has a different signature on Android (JNIEnv**) vs JNI
//      standard (void**): guard __ANDROID__.
//   3. JNI export for BridgeService (ART) and for EngineSink (embedded JVM):
//      two distinct runtimes, the objects do not cross the boundary.
//   4. C callback (starling_jvm_set_action_callback) for the Rust engine:
//      it is the only bridge to the engine's native layer.
//
// Build: starling/CMakeLists.txt (NDK, arm64-v8a).

#include <jni.h>
#include <dlfcn.h>

#include <cstdint>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "starling-jvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) std::printf(__VA_ARGS__)
#define LOGE(...) std::fprintf(stderr, __VA_ARGS__)
#endif

namespace {

JavaVM* g_vm = nullptr;
void* g_jvm_lib = nullptr;
jclass g_transport = nullptr;      // global ref (JVM embedded)
jmethodID g_poll = nullptr;
jclass g_bootstrap = nullptr;      // global ref
jmethodID g_stop = nullptr;
jlong g_handle = 0;

// Callback registered by the Rust engine: receives the action IDs resolved from the LUT.
using ActionCb = void (*)(const int32_t* ids, size_t count);
ActionCb g_action_cb = nullptr;

// Internal function: starts the embedded JVM and bootstraps (real mods, ring,
// tick 20 TPS, EngineSink). Shared between BridgeService and MainActivity.
// Returns true on success, false on error.
bool startJvmEmbedded(JNIEnv* env, const std::string& jreLib,
                      const std::string& classpath, const std::string& mods,
                      const std::string& lut, const std::string& mapping,
                      const std::string& tmp) {
    if (g_vm) return true;  // already started

    // 1. dlopen of the Android JRE (NOT linked into the build: the NDK has no
    //    libjvm). It is loaded with
    //      dlopen at runtime from the Android JRE path (PojavLauncher-style).
    g_jvm_lib = dlopen(jreLib.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g_jvm_lib) {
        LOGE("dlopen of libjvm failed: %s\n", dlerror());
        return false;
    }
    using CreateVmFn = jint (*)(JavaVM**, void**, void*);
    auto create_vm = reinterpret_cast<CreateVmFn>(dlsym(g_jvm_lib, "JNI_CreateJavaVM"));
    if (!create_vm) {
        LOGE("JNI_CreateJavaVM not found in libjvm\n");
        return false;
    }

    // 2. Headless JVM in the same process. tmpdir in the app cache
    //    (Scoped Storage: no permission required).
    std::string cp = "-Djava.class.path=" + classpath;
    std::string tmpdir = "-Djava.io.tmpdir=" + tmp;
    JavaVMOption options[4];
    options[0].optionString = const_cast<char*>("-Djava.awt.headless=true");
    options[1].optionString = const_cast<char*>(cp.c_str());
    options[2].optionString = const_cast<char*>(tmpdir.c_str());
    options[3].optionString = const_cast<char*>("-Xss1m");
    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_8;
    args.nOptions = 4;
    args.options = options;
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv* jenv = nullptr;
    if (create_vm(&g_vm, reinterpret_cast<void**>(&jenv), &args) != JNI_OK || !jenv) {
        LOGE("JNI_CreateJavaVM failed\n");
        return false;
    }

    // 3. Bootstrap in the embedded JVM: mods scan (mapping.bin gate),
    //    real jars, ring, attach, tick 20 TPS, EngineSink.
    jclass bootstrap = jenv->FindClass("dev/starling/bridge/JvmBootstrap");
    if (!bootstrap || jenv->ExceptionCheck()) {
        LOGE("JvmBootstrap not found in classpath\n");
        if (jenv->ExceptionCheck()) jenv->ExceptionClear();
        return false;
    }
    jmethodID start = jenv->GetStaticMethodID(
            bootstrap, "start",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J");
    jstring jMods2 = jenv->NewStringUTF(mods.c_str());
    jstring jLut2 = jenv->NewStringUTF(lut.c_str());
    jstring jMap2 = jenv->NewStringUTF(mapping.c_str());
    g_handle = jenv->CallStaticLongMethod(bootstrap, start, jMods2, jLut2, jMap2);
    jenv->DeleteLocalRef(jMods2);
    jenv->DeleteLocalRef(jLut2);
    jenv->DeleteLocalRef(jMap2);
    if (jenv->ExceptionCheck()) {
        jenv->ExceptionDescribe();
        jenv->ExceptionClear();
        LOGE("JvmBootstrap.start failed\n");
        return false;
    }
    g_bootstrap = static_cast<jclass>(jenv->NewGlobalRef(bootstrap));
    g_stop = jenv->GetStaticMethodID(bootstrap, "stop", "(J)V");

    jclass transport = jenv->FindClass("dev/starling/bridge/NativeTransport");
    g_transport = static_cast<jclass>(jenv->NewGlobalRef(transport));
    g_poll = jenv->GetStaticMethodID(transport, "nativePoll", "(J)I");

    LOGI("Headless JVM started in-process, handle=%lld\n",
         static_cast<long long>(g_handle));
    return true;
}

JNIEnv* attach_env() {
    JNIEnv* env = nullptr;
    jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (rc == JNI_OK) return env;
    if (rc != JNI_EDETACHED) return nullptr;
#if defined(__ANDROID__)
    // Android: jint AttachCurrentThread(JNIEnv**, void*)
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
#else
    // JNI standard (host Linux): jint AttachCurrentThread(void**, void*)
    if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
        return nullptr;
#endif
    // Il thread resta agganciato: attach/detach per frame costerebbe ~µs.
    return env;
}

std::string to_std(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

// ---------------------------------------------------------------------------
// C API for the Rust engine (direct FFI, no JNI on this path)
// ---------------------------------------------------------------------------
extern "C" {

void starling_jvm_set_action_callback(ActionCb cb) { g_action_cb = cb; }

// Called from the engine render thread, 1×/frame. Returns the consumed bytes,
// 0 if nothing to do, -1 on error (JNI exception already logged and cleared).
int32_t starling_jvm_poll_frame(void) {
    if (!g_vm || !g_handle) return 0;
    JNIEnv* env = attach_env();
    if (!env) return -1;
    env->PushLocalFrame(16);
    jint n = env->CallStaticIntMethod(g_transport, g_poll, g_handle);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("nativePoll: exception ( BridgeException: unmapped ID?)\n");
        n = -1;
    }
    env->PopLocalFrame(nullptr);
    return n;
}

void starling_jvm_stop(void) {
    if (!g_vm || !g_handle) return;
    JNIEnv* env = attach_env();
    if (env) {
        env->CallStaticVoidMethod(g_bootstrap, g_stop, g_handle);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
    g_handle = 0;
    // The JVM stays alive for the lifetime of the process (DestroyJavaVM on
    // Android is discouraged by the porting JRE).
}

}  // extern "C"

// ---------------------------------------------------------------------------
// JNI for BridgeService (ART runtime → creates the embedded JVM)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_starling_app_BridgeService_nativeStart(
        JNIEnv* env, jclass,
        jstring jJreLib, jstring jClasspath, jstring jMods,
        jstring jLut, jstring jMapping, jstring jTmpDir) {
    const std::string jreLib = to_std(env, jJreLib);
    const std::string classpath = to_std(env, jClasspath);
    const std::string mods = to_std(env, jMods);
    const std::string lut = to_std(env, jLut);
    const std::string mapping = to_std(env, jMapping);
    const std::string tmp = to_std(env, jTmpDir);
    return startJvmEmbedded(env, jreLib, classpath, mods, lut, mapping, tmp)
               ? JNI_TRUE
               : JNI_FALSE;
}

// JNI for MainActivity (ART → direct game startup, same logic as BridgeService)
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_starling_app_MainActivity_nativeStartGame(
        JNIEnv* env, jclass,
        jstring jJreLib, jstring jClasspath, jstring jMods,
        jstring jLut, jstring jMapping, jstring jTmpDir) {
    const std::string jreLib = to_std(env, jJreLib);
    const std::string classpath = to_std(env, jClasspath);
    const std::string mods = to_std(env, jMods);
    const std::string lut = to_std(env, jLut);
    const std::string mapping = to_std(env, jMapping);
    const std::string tmp = to_std(env, jTmpDir);
    return startJvmEmbedded(env, jreLib, classpath, mods, lut, mapping, tmp)
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_starling_app_BridgeService_nativeStop(JNIEnv*, jclass) {
    starling_jvm_stop();
}

// ---------------------------------------------------------------------------
// JNI for EngineSink (embedded JVM → C callback of the Rust engine)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_dev_starling_bridge_EngineSink_nativeOnActions(
        JNIEnv* env, jclass, jintArray ids) {
    if (!g_action_cb || !ids) return;
    jsize n = env->GetArrayLength(ids);
    if (n <= 0) return;
    jint* elems = env->GetIntArrayElements(ids, nullptr);
    if (elems) {
        g_action_cb(reinterpret_cast<const int32_t*>(elems), static_cast<size_t>(n));
        env->ReleaseIntArrayElements(ids, elems, JNI_ABORT);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}