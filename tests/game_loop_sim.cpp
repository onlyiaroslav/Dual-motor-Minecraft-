// Starling game loop simulation (Linux host, real in-process JVM).
//
// Full chain: mock Java produces in the ring (20 TPS) → libbridge_jni.so
// (Rust) consumes and dispatches in the LUT → Java sink counts the actions.
// The "engine" is this C++ thread polling at 60 FPS with sleep_for.
//
// Checks:
//   - zero losses: actions received == packets produced
//   - zero drops: dropped_bytes == 0
//   - no pending JNI exception, local frame per frame (no ref leak)
//   - 20 attach/detach cycles: RSS stable (anti-leak heuristic)
//
// Build: see scripts/game-loop-sim.sh
// Usage:   game_loop_sim <classesDir> <libbridge_jni.so> <mapping.bin> [frames]

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
        std::fprintf(stderr, "FAIL: JNI exception in %s\n", where);
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

// A "frame": poll with local frame (local refs never accumulate).
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

// Runs a session: start → N frames at 60 FPS → stop → drain → counters → detach.
static bool run_session(int frames, RunStats& out) {
    jstring jMapping = g_env->NewStringUTF(g_mapping_path);
    jstring jSo = g_env->NewStringUTF(g_so_path);
    jlong handle = g_env->CallStaticLongMethod(g_mock, g_start, jMapping, jSo);
    g_env->DeleteLocalRef(jMapping);
    g_env->DeleteLocalRef(jSo);
    if (!check_exc("start") || handle == 0) return false;

    // Warm-up of the JNI/JIT path: the first calls pay the initialization cost.
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

    // Final drain: consumes whatever is left in the ring.
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
                     "usage: %s <classesDir> <libbridge_jni.so> <mapping.bin> [frames]\n",
                     argv[0]);
        return 2;
    }
    const char* classes = argv[1];
    g_so_path = argv[2];
    g_mapping_path = argv[3];
    int frames = argc > 4 ? std::atoi(argv[4]) : 600; // 10 s a 60 FPS

    // 1. JVM headless in the same process (as Starling will do on Android).
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

    // 2. Main session: 600 frames at 60 FPS.
    long rss_before = rss_kb();
    RunStats s;
    if (!run_session(frames, s)) return 1;
    std::printf("session %d frame (60 FPS): produced=%ld received=%ld consumed=%ld bytes "
                "dropped=%ld poll p99=%.3f ms max=%.3f ms\n",
                frames, s.produced, s.received, s.consumed, s.dropped,
                s.p99_poll_ms, s.max_poll_ms);
    if (s.received != s.produced) {
        std::fprintf(stderr, "FAIL: losses! received != produced\n");
        return 1;
    }
    if (s.consumed != s.produced * 2) {
        std::fprintf(stderr, "FAIL: consumed bytes != produced*2\n");
        return 1;
    }
    if (s.dropped != 0) {
        std::fprintf(stderr, "FAIL: bytes dropped (ring overflow)\n");
        return 1;
    }
    // Budget: the bridge cost is in the order of µs; the single outliers are
    // host scheduling. p99 must stay under 5 ms, max under one frame.
    if (s.p99_poll_ms >= 5.0) {
        std::fprintf(stderr, "FAIL: poll p99 over the 5 ms budget\n");
        return 1;
    }
    if (s.max_poll_ms >= 16.667) {
        std::fprintf(stderr, "FAIL: poll max over a frame (16.6 ms)\n");
        return 1;
    }
    std::printf("ok: zero losses, zero drops, poll under budget\n");

    // 3. attach/detach stress: 20 short cycles, RSS must stabilize.
    long rss_cycles_before = rss_kb();
    for (int c = 0; c < 20; c++) {
        RunStats cs;
        if (!run_session(30, cs)) return 1;
        if (cs.received != cs.produced || cs.dropped != 0) {
            std::fprintf(stderr, "FAIL: cycle %d with losses/drops\n", c);
            return 1;
        }
    }
    long rss_cycles_after = rss_kb();
    long growth_kb = rss_cycles_after - rss_cycles_before;
    std::printf("20 attach/detach cycles: RSS %ld → %ld kB (growth %ld kB)\n",
                rss_cycles_before, rss_cycles_after, growth_kb);
    // Heuristic: after the warm-up, 20 cycles must not add significant memory.
    if (growth_kb > 32 * 1024) {
        std::fprintf(stderr, "FAIL: crescita RSS sospetta (leak?)\n");
        return 1;
    }
    std::printf("ok: no crash, RSS stable (initial RSS %ld kB)\n", rss_before);

    g_env->DeleteGlobalRef(g_mock);
    g_env->DeleteGlobalRef(g_transport);
    g_vm->DestroyJavaVM();
    std::printf("GAME LOOP SIM: PASS\n");
    return 0;
}