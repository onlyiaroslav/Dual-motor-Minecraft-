// Starling — host JVM headless in-process (VERSIONE DI PRODUZIONE per Android).
//
// Sostituisce docs/starling_jvm_host.cpp (che era uno snippet illustrativo).
// Differenze chiave Linux → Android (vedi docs/STARLING_INTEGRATION.md):
//   1. libjvm.so NON si linka alla build (l'NDK non ce l'ha): si carica con
//      dlopen a runtime dal percorso della JRE Android (PojavLauncher-style).
//   2. AttachCurrentThread ha firma diversa su Android (JNIEnv**) vs JNI
//      standard (void**): guard __ANDROID__.
//   3. Export JNI per BridgeService (ART) e per EngineSink (JVM embedded):
//      due runtime distinti, gli oggetti non attraversano il confine.
//   4. Callback C (starling_jvm_set_action_callback) per il motore Rust:
//      è l'unico ponte verso il layer nativo del motore.
//
// Build: starling/CMakeLists.txt (NDK, arm64-v8a).

#include <jni.h>
#include <dlfcn.h>

// L'jni.h dell'NDK Android non definisce JNI_VERSION_1_8 (presente solo nel
// JNI desktop). Lo definiamo qui se manca, per evitare "undeclared identifier".
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>
#include <algorithm>
#include <dirent.h>

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

// Callback registrata dal motore Rust: riceve gli ID azione risolti dalla LUT.
using ActionCb = void (*)(const int32_t* ids, size_t count);
ActionCb g_action_cb = nullptr;

// Precarica le librerie sorelle del JRE (libjli, libjava, libnet, libnio, ...)
// con percorso ASSOLUTO. Necessario: LD_LIBRARY_PATH viene letta dal linker
// solo all'avvio del processo, quindi impostarla dopo non basta. Caricandole
// prima con RTLD_GLOBAL, i DT_NEEDED di libjvm.so sono già soddisfatti.
void preloadJreLibs(const std::string& jvmPath) {
    // .../lib/server/libjvm.so -> libDir = .../lib, home = .../
    const size_t slash = jvmPath.find_last_of('/');
    if (slash == std::string::npos) return;
    const std::string serverDir = jvmPath.substr(0, slash);
    const size_t slash2 = serverDir.find_last_of('/');
    const std::string libDir =
        (slash2 == std::string::npos) ? serverDir : serverDir.substr(0, slash2);
    const size_t slash3 = libDir.find_last_of('/');
    const std::string home =
        (slash3 == std::string::npos) ? libDir : libDir.substr(0, slash3);

    std::vector<std::string> dirs;
    dirs.push_back(libDir + "/jli");
    dirs.push_back(libDir);
    dirs.push_back(serverDir);
    dirs.push_back(home + "/lib");
    dirs.push_back(home + "/lib/jli");
    dirs.push_back(home + "/lib/server");

    std::vector<std::string> seen;
    for (const auto& dir : dirs) {
        DIR* d = opendir(dir.c_str());
        if (!d) continue;
        while (struct dirent* e = readdir(d)) {
            const std::string name = e->d_name;
            if (name.size() < 4) continue;
            if (name.compare(name.size() - 3, 3, ".so") != 0) continue;
            if (name == "libjvm.so") continue;  // caricata per ultima
            const std::string full = dir + "/" + name;
            if (std::find(seen.begin(), seen.end(), name) != seen.end()) continue;
            seen.push_back(name);
            // RTLD_LAZY: eventuali simboli JVM vengono risolti dopo libjvm.
            void* h = dlopen(full.c_str(), RTLD_LAZY | RTLD_GLOBAL);
            if (!h) {
                const char* err = dlerror();
                LOGI("preload %s: %s\n", name.c_str(), err ? err : "errore sconosciuto");
            }
        }
        closedir(d);
    }
}

// Funzione interna: avvia la JVM embedded e fa bootstrap (mod reali, ring,
// tick 20 TPS, EngineSink). Condivisa tra BridgeService e MainActivity.
// Restituisce true se successo, false su errore.
bool startJvmEmbedded(JNIEnv* env, const std::string& jreLib,
                      const std::string& classpath, const std::string& mods,
                      const std::string& lut, const std::string& mapping,
                      const std::string& tmp) {
    (void)env;  // la JVM embedded si aggancia da sola: env ART non serve qui
    if (g_vm) return true;  // già avviata

    // 1. dlopen della JRE Android (NON linkata alla build: l'NDK non ha libjvm).
    //    Il percorso è assoluto e nella memoria PRIVATA dell'app (filesDir):
    //    da Android 10+ lo storage esterno non è caricabile con dlopen.
    preloadJreLibs(jreLib);
    g_jvm_lib = dlopen(jreLib.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g_jvm_lib) {
        LOGE("dlopen libjvm fallita: %s\n", dlerror());
        return false;
    }
    using CreateVmFn = jint (*)(JavaVM**, void**, void*);
    auto create_vm = reinterpret_cast<CreateVmFn>(dlsym(g_jvm_lib, "JNI_CreateJavaVM"));
    if (!create_vm) {
        LOGE("JNI_CreateJavaVM non trovata in libjvm\n");
        return false;
    }

    // 2. JVM headless nello stesso processo. tmpdir nella cache dell'app
    //    (Scoped Storage: nessun permesso richiesto).
    std::string cp = "-Djava.class.path=" + classpath;
    std::string tmpdir = "-Djava.io.tmpdir=" + tmp;

    // java.home: impostato da Kotlin (JreInstaller.prepareEnv) sulla directory
    // privata di estrazione; in fallback si ricava dal percorso di libjvm.so.
    std::string home;
    if (const char* envHome = std::getenv("STARLING_JRE_HOME")) {
        home = envHome;
    } else {
        const size_t s1 = jreLib.find_last_of('/');
        const std::string serverDir = (s1 == std::string::npos) ? "" : jreLib.substr(0, s1);
        const size_t s2 = serverDir.find_last_of('/');
        const std::string libDir = (s2 == std::string::npos) ? serverDir : serverDir.substr(0, s2);
        const size_t s3 = libDir.find_last_of('/');
        home = (s3 == std::string::npos) ? libDir : libDir.substr(0, s3);
    }
    std::string javaHome = "-Djava.home=" + home;
    std::string bootLib = "-Dsun.boot.library.path=" + home + "/lib:" + home + "/lib/server";
    std::string libPath = "-Djava.library.path=" + home + "/lib:" + home + "/lib/server:" +
                          home + "/lib/jli";

    JavaVMOption options[7];
    options[0].optionString = const_cast<char*>("-Djava.awt.headless=true");
    options[1].optionString = const_cast<char*>(cp.c_str());
    options[2].optionString = const_cast<char*>(tmpdir.c_str());
    options[3].optionString = const_cast<char*>("-Xss1m");
    options[4].optionString = const_cast<char*>(javaHome.c_str());
    options[5].optionString = const_cast<char*>(bootLib.c_str());
    options[6].optionString = const_cast<char*>(libPath.c_str());
    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_8;
    args.nOptions = 7;
    args.options = options;
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv* jenv = nullptr;
    if (create_vm(&g_vm, reinterpret_cast<void**>(&jenv), &args) != JNI_OK || !jenv) {
        LOGE("JNI_CreateJavaVM fallita\n");
        return false;
    }

    // 3. Bootstrap nella JVM embedded: scansione mod (mapping.bin gate),
    //    jar reali, ring, attach, tick 20 TPS, EngineSink.
    jclass bootstrap = jenv->FindClass("dev/starling/bridge/JvmBootstrap");
    if (!bootstrap || jenv->ExceptionCheck()) {
        LOGE("JvmBootstrap non trovata nel classpath\n");
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
        LOGE("JvmBootstrap.start fallita\n");
        return false;
    }
    g_bootstrap = static_cast<jclass>(jenv->NewGlobalRef(bootstrap));
    g_stop = jenv->GetStaticMethodID(bootstrap, "stop", "(J)V");

    jclass transport = jenv->FindClass("dev/starling/bridge/NativeTransport");
    g_transport = static_cast<jclass>(jenv->NewGlobalRef(transport));
    g_poll = jenv->GetStaticMethodID(transport, "nativePoll", "(J)I");

    LOGI("JVM headless avviata in-process, handle=%lld\n",
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
// API C per il motore Rust (FFI diretto, niente JNI su questo percorso)
// ---------------------------------------------------------------------------
extern "C" {

void starling_jvm_set_action_callback(ActionCb cb) { g_action_cb = cb; }

// Chiamata dal render thread del motore, 1×/frame. Ritorna i byte consumati,
// 0 se nulla da fare, -1 su errore (eccezione JNI già loggata e pulita).
int32_t starling_jvm_poll_frame(void) {
    if (!g_vm || !g_handle) return 0;
    JNIEnv* env = attach_env();
    if (!env) return -1;
    env->PushLocalFrame(16);
    jint n = env->CallStaticIntMethod(g_transport, g_poll, g_handle);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("nativePoll: eccezione ( BridgeException: ID non mappato?)\n");
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
    // La JVM resta viva per la vita del processo (DestroyJavaVM su Android
    // è sconsigliato dalle JRE porting).
}

}  // extern "C"

// ---------------------------------------------------------------------------
// JNI per BridgeService (runtime ART → crea la JVM embedded)
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

// JNI per MainActivity (ART → avvio gioco diretto, stessa logica di BridgeService)
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
// JNI per EngineSink (JVM embedded → callback C del motore Rust)
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