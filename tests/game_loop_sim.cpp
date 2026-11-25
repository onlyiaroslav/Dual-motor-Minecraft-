// Simulazione del game loop Starling (host Linux, JVM reale in-process).
//
// Catena completa: mock Java produce nel ring (20 TPS) → libbridge_jni.so
// (Rust) consuma e dispatcha nella LUT → sink Java conta le azioni.
// Il "motore" è questo thread C++ che fa polling a 60 FPS con sleep_for.
//
// Verifiche:
//   - zero perdite: azioni ricevute == pacchetti prodotti
//   - zero scarti: dropped_bytes == 0
//   - nessuna eccezione JNI pendente, local frame per frame (no ref leak)
//   - 20 cicli attach/detach: RSS stabile (euristica anti-leak)
//
// Build: vedi scripts/game-loop-sim.sh
// Uso:   game_loop_sim <classesDir> <libbridge_jni.so> <mapping.bin> [frames]

#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

using Clock = std::chrono::steady_clock;

static JavaVM* g_vm = nullptr;
static JNIEnv* g_env = nullptr;
static const char* g_mapping_path = nullptr;
static const char* g_so_path = nullptr;
static jclass g_mock = nullptr;
static jclass g_transport = nullptr;
static jmethodID g_poll = nullptr;
static jmethodID g_start = nullptr;
static jmethodID g_stopProducer = nullptr;
static jmethodID g_detach = nullptr;
static jmethodID g_produced = nullptr;
static jmethodID g_received = nullptr;
static jmethodID g_dropped = nullptr;

static bool check_exc(const char* where) {
    if (g_env->ExceptionCheck()) {
        std::fprintf(stderr, "FAIL: eccezione JNI in %s\n", where);
        g_env->ExceptionDescribe();
        g_env->ExceptionClear();
        return false;
    }
    return true;
}

static long rss_kb() {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            return std::strtol(line.c_str() + 6, nullptr, 10);
        }
    }
    return -1;
}

// Un "frame": poll con local frame (i local ref non si accumulano mai).
static jint poll_frame(jlong handle) {
    g_env->PushLocalFrame(16);
    jint n = g_env->CallStaticIntMethod(g_transport, g_poll, handle);
    g_env->PopLocalFrame(nullptr);
    return n;
}

struct RunStats {
    long consumed = 0;
    long produced = 0;
    long received = 0;
    long dropped = 0;
    double max_poll_ms = 0;
    double p99_poll_ms = 0;
};

// Esegue una sessione: start → N frame a 60 FPS → stop → drain → contatori → detach.
static bool run_session(int frames, RunStats& out) {
    jstring jMapping = g_env->NewStringUTF(g_mapping_path);
    jstring jSo = g_env->NewStringUTF(g_so_path);
    jlong handle = g_env->CallStaticLongMethod(g_mock, g_start, jMapping, jSo);
    g_env->DeleteLocalRef(jMapping);
    g_env->DeleteLocalRef(jSo);
    if (!check_exc("start") || handle == 0) return false;

    // Warm-up del percorso JNI/JIT: le prime chiamate pagano inizializzazione.
    for (int i = 0; i < 10; i++) {
        poll_frame(handle);
        if (!check_exc("warmup")) return false;
    }

    const auto frame_budget = std::chrono::microseconds(16'667); // 60 FPS
    std::vector<double> poll_ms;
    poll_ms.reserve(frames);
    for (int f = 0; f < frames; f++) {
        auto t0 = Clock::now();
        jint n = poll_frame(handle);
        if (!check_exc("nativePoll")) return false;
        if (n < 0) return false;
        out.consumed += n;
        auto elapsed = Clock::now() - t0;
        double ms = std::chrono::duration<double, std::milli>(elapsed).count();
        poll_ms.push_back(ms);
        if (ms > out.max_poll_ms) out.max_poll_ms = ms;
        if (elapsed < frame_budget) {
            std::this_thread::sleep_for(frame_budget - elapsed);
        }
    }
    std::sort(poll_ms.begin(), poll_ms.end());
    out.p99_poll_ms = poll_ms[static_cast<size_t>(poll_ms.size() * 99 / 100)];

    g_env->CallStaticVoidMethod(g_mock, g_stopProducer);
    if (!check_exc("stopProducer")) return false;

    // Drain finale: consuma tutto ciò che resta nel ring.
    for (int i = 0; i < 100; i++) {
        jint n = poll_frame(handle);
        if (!check_exc("drain")) return false;
        if (n <= 0) break;
        out.consumed += n;
    }

    out.produced = g_env->CallStaticLongMethod(g_mock, g_produced);
    out.received = g_env->CallStaticLongMethod(g_mock, g_received);
    out.dropped = g_env->CallStaticLongMethod(g_mock, g_dropped);
    if (!check_exc("contatori")) return false;

    g_env->CallStaticVoidMethod(g_mock, g_detach, handle);
    return check_exc("detach");
}

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr,
                     "uso: %s <classesDir> <libbridge_jni.so> <mapping.bin> [frames]\n",
                     argv[0]);
        return 2;
    }
    const char* classes = argv[1];
    g_so_path = argv[2];
    g_mapping_path = argv[3];
    int frames = argc > 4 ? std::atoi(argv[4]) : 600; // 10 s a 60 FPS

    // 1. JVM headless nello stesso processo (come farà Starling su Android).
    std::string cp = std::string("-Djava.class.path=") + classes;
    JavaVMOption options[3];
    options[0].optionString = const_cast<char*>("-Djava.awt.headless=true");
    options[1].optionString = const_cast<char*>(cp.c_str());
    options[2].optionString = const_cast<char*>("-Xss1m");
    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_8;
    args.nOptions = 3;
    args.options = options;
    args.ignoreUnrecognized = JNI_FALSE;
    if (JNI_CreateJavaVM(&g_vm, reinterpret_cast<void**>(&g_env), &args) != JNI_OK) {
        std::fprintf(stderr, "FAIL: JNI_CreateJavaVM\n");
        return 1;
    }

    jclass mock = g_env->FindClass("dev/starling/bridge/GameLoopMock");
    jclass transport = g_env->FindClass("dev/starling/bridge/NativeTransport");
    if (!mock || !transport || !check_exc("FindClass")) return 1;
    g_mock = static_cast<jclass>(g_env->NewGlobalRef(mock));
    g_transport = static_cast<jclass>(g_env->NewGlobalRef(transport));
    g_poll = g_env->GetStaticMethodID(g_transport, "nativePoll", "(J)I");
    g_start = g_env->GetStaticMethodID(g_mock, "start",
                                       "(Ljava/lang/String;Ljava/lang/String;)J");
    g_stopProducer = g_env->GetStaticMethodID(g_mock, "stopProducer", "()V");
    g_detach = g_env->GetStaticMethodID(g_mock, "detach", "(J)V");
    g_produced = g_env->GetStaticMethodID(g_mock, "producedPackets", "()J");
    g_received = g_env->GetStaticMethodID(g_mock, "receivedActions", "()J");
    g_dropped = g_env->GetStaticMethodID(g_mock, "droppedBytes", "()J");
    if (!check_exc("GetMethodID")) return 1;

    // 2. Sessione principale: 600 frame a 60 FPS.
    long rss_before = rss_kb();
    RunStats s;
    if (!run_session(frames, s)) return 1;
    std::printf("sessione %d frame (60 FPS): prodotti=%ld ricevuti=%ld consumati=%ld byte "
                "scartati=%ld poll p99=%.3f ms max=%.3f ms\n",
                frames, s.produced, s.received, s.consumed, s.dropped,
                s.p99_poll_ms, s.max_poll_ms);
    if (s.received != s.produced) {
        std::fprintf(stderr, "FAIL: perdite! ricevuti != prodotti\n");
        return 1;
    }
    if (s.consumed != s.produced * 2) {
        std::fprintf(stderr, "FAIL: byte consumati != prodotti*2\n");
        return 1;
    }
    if (s.dropped != 0) {
        std::fprintf(stderr, "FAIL: byte scartati (overflow ring)\n");
        return 1;
    }
    // Budget: il costo del bridge è nell'ordine dei µs; gli outlier singoli sono
    // scheduling dell'host. p99 deve stare sotto 5 ms, il max sotto un frame.
    if (s.p99_poll_ms >= 5.0) {
        std::fprintf(stderr, "FAIL: poll p99 oltre il budget di 5 ms\n");
        return 1;
    }
    if (s.max_poll_ms >= 16.667) {
        std::fprintf(stderr, "FAIL: poll max oltre un frame (16.6 ms)\n");
        return 1;
    }
    std::printf("ok: zero perdite, zero scarti, poll sotto budget\n");

    // 3. Stress attach/detach: 20 cicli brevi, RSS deve stabilizzarsi.
    long rss_cycles_before = rss_kb();
    for (int c = 0; c < 20; c++) {
        RunStats cs;
        if (!run_session(30, cs)) return 1;
        if (cs.received != cs.produced || cs.dropped != 0) {
            std::fprintf(stderr, "FAIL: ciclo %d con perdite/scarti\n", c);
            return 1;
        }
    }
    long rss_cycles_after = rss_kb();
    long growth_kb = rss_cycles_after - rss_cycles_before;
    std::printf("20 cicli attach/detach: RSS %ld → %ld kB (crescita %ld kB)\n",
                rss_cycles_before, rss_cycles_after, growth_kb);
    // Euristica: dopo il warmup, 20 cicli non devono aggiungere memoria significativa.
    if (growth_kb > 32 * 1024) {
        std::fprintf(stderr, "FAIL: crescita RSS sospetta (leak?)\n");
        return 1;
    }
    std::printf("ok: nessun crash, RSS stabile (RSS iniziale %ld kB)\n", rss_before);

    g_env->DeleteGlobalRef(g_mock);
    g_env->DeleteGlobalRef(g_transport);
    g_vm->DestroyJavaVM();
    std::printf("GAME LOOP SIM: PASS\n");
    return 0;
}