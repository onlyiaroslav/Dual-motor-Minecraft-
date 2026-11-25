// Starling — host JVM headless in-process (snippet C++ per il motore Rust/Vulkan).
//
// Da integrare nel layer nativo di Starling (es. native/jvm_host.cpp), compilato
// con l'NDK e linkato a libjvm.so di un OpenJDK porting Android arm64
// (es. le JRE usate da PojavLauncher). La JVM vive nello STESSO processo del
// motore: niente socket, niente rete.
//
// Flusso:
//   1. JNI_CreateJavaVM (una sola volta, dal Service Android o dall'init nativo)
//   2. bootstrap Java: dev.starling.bridge.JvmBootstrap.start(...)
//      → scansione mod, ring buffer, attach JNI, thread tick 20 TPS
//   3. il render thread del motore chiama nativePoll una volta per frame
//   4. le azioni risolte dalla LUT Rust arrivano al NativeSink (che le inoltra
//      ai sistemi nativi di Starling: audio, particelle, HUD, mondo…)
//
// RISCHI CRASH: vedi docs/ANDROID.md, sezione "Rischi di crash".

#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "starling-jvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace starling {

static JavaVM* g_vm = nullptr;
static jobject g_bootstrap = nullptr;   // global ref, vive per tutto il processo
static jmethodID g_poll = nullptr;      // NativeTransport.nativePoll(J)I
static jclass g_transport = nullptr;    // global ref della classe
static jlong g_handle = 0;

// Chiamato una volta all'avvio (dal Service Android via JNI, o dall'init nativo).
// modsDir/lutDir/mappingPath: storage SPECIFICO dell'app (Scoped Storage OK).
bool JvmHost_Start(const std::string& jreLibPath,
                   const std::string& classpath,
                   const std::string& modsDir,
                   const std::string& lutDir,
                   const std::string& mappingPath) {
    if (g_vm) return true;  // già avviata

    // 1. Crea la JVM headless nello stesso processo.
    JavaVMOption options[3];
    std::string headless = "-Djava.awt.headless=true";
    std::string cp = "-Djava.class.path=" + classpath;
    std::string stack = "-Xss1m";  // stack thread mod contenuto (Android)
    options[0].optionString = const_cast<char*>(headless.c_str());
    options[1].optionString = const_cast<char*>(cp.c_str());
    options[2].optionString = const_cast<char*>(stack.c_str());

    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_8;
    args.nOptions = 3;
    args.options = options;
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv* env = nullptr;
    // libjvm.so della JRE Android deve essere già caricata (System.loadLibrary
    // dal lato Kotlin, o dlopen(jreLibPath + "/libjvm.so", RTLD_NOW)).
    jint rc = JNI_CreateJavaVM(&g_vm, reinterpret_cast<void**>(&env), &args);
    if (rc != JNI_OK || !env) {
        LOGE("JNI_CreateJavaVM fallita: %d", rc);
        return false;
    }

    // 2. Bootstrap Java: scansione mod + ring + attach + tick thread.
    jclass bootstrap = env->FindClass("dev/starling/bridge/JvmBootstrap");
    if (!bootstrap) { LOGE("JvmBootstrap non trovata"); return false; }
    jmethodID start = env->GetStaticMethodID(
        bootstrap, "start",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "Ldev/starling/bridge/NativeTransport$NativeSink;)J");
    if (!start) { LOGE("JvmBootstrap.start non trovata"); return false; }

    // Sink: in Starling reale è un oggetto che inoltra gli ID ai sistemi nativi
    // (es. via un'altra native method del motore). Qui si passa un sink Java
    // fornito dal layer Kotlin/Java di Starling.
    // jobject sink = ... (dal chiamante);
    jobject sink = nullptr;  // TODO: passare il sink reale del motore
    jstring jMods = env->NewStringUTF(modsDir.c_str());
    jstring jLut = env->NewStringUTF(lutDir.c_str());
    jstring jMap = env->NewStringUTF(mappingPath.c_str());
    g_handle = env->CallStaticLongMethod(bootstrap, start, jMods, jLut, jMap, sink);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("JvmBootstrap.start fallita");
        return false;
    }
    g_bootstrap = env->NewGlobalRef(bootstrap);

    // 3. Cache della method ID di poll per il render loop.
    jclass transport = env->FindClass("dev/starling/bridge/NativeTransport");
    g_transport = static_cast<jclass>(env->NewGlobalRef(transport));
    g_poll = env->GetStaticMethodID(transport, "nativePoll", "(J)I");

    LOGI("JVM headless avviata, handle=%lld", static_cast<long long>(g_handle));
    return true;
}

// Chiamata dal RENDER THREAD del motore, una volta per frame (60 FPS).
// Costo: una chiamata JNI + polling del ring (nessuna copia, nessuna rete).
void JvmHost_PollFrame() {
    if (!g_vm || !g_handle) return;
    JNIEnv* env = nullptr;
    // Il render thread è nativo: va agganciato alla JVM (una volta per thread).
    jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    } else if (rc != JNI_OK) {
        return;
    }
    jint consumed = env->CallStaticIntMethod(g_transport, g_poll, g_handle);
    if (env->ExceptionCheck()) {
        // BridgeException: ID non mappato o ring non valido.
        // Fase 3: qui si isola la mod colpevole; per ora log e clear.
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("nativePoll: eccezione (consumed=%d)", consumed);
    }
    // NON detachare qui se il thread resta agganciato per i frame successivi
    // (attach/detach per frame costa ~µs; meglio thread-local cache).
}

void JvmHost_Stop() {
    if (!g_vm || !g_handle) return;
    JNIEnv* env = nullptr;
    g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (env) {
        jclass bootstrap = env->GetObjectClass(g_bootstrap);
        jmethodID stop = env->GetStaticMethodID(bootstrap, "stop", "(J)V");
        env->CallStaticVoidMethod(bootstrap, stop, g_handle);
        if (env->ExceptionCheck()) { env->ExceptionClear(); }
    }
    g_handle = 0;
    // La JVM resta viva per il processo (DestroyJavaVM su Android è sconsigliato).
}

}  // namespace starling