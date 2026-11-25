# Android (Starling) — build, integrazione e rischi

Guida per portare il bridge in-process (JNI + ring buffer) su **Starling**
(Android ARM64, fork di Horizon Android). Tutto gira nello **stesso processo**:
motore Rust + Vulkan da un lato, JVM headless OpenJDK dall'altro.

---

## 1. Compilare il crate Rust per aarch64-linux-android

Prerequisiti: **NDK r27+**, rustup, mirror crates.io configurato in
`.cargo/config.toml` (già presente nel repo).

```bash
# Target ARM64 Android
rustup target add aarch64-linux-android

# Toolchain NDK nel PATH (adatta il percorso alla tua installazione)
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.2.12479018"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

# Build della cdylib JNI (bridge-u8 è zero-dep, bridge-jni usa solo la crate jni)
cargo build --release -p bridge-jni --target aarch64-linux-android
# → target/aarch64-linux-android/release/libbridge_jni.so
```

Il `.cargo/config.toml` del repo imposta già:

```toml
[target.aarch64-linux-android]
linker = "aarch64-linux-android24-clang"
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]  # pagine 16 KB, Android 15
```

In alternativa: `cargo install cargo-ndk` e
`cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release -p bridge-jni`.

**Copia la `.so` in `app/src/main/jniLibs/arm64-v8a/libbridge_jni.so`.**
Su Android 15 le librerie native devono stare in `jniLibs`/`nativeLibraryDir`:
il linker namespace rifiuta `dlopen` da percorsi arbitrari (verificato anche
nell'ambiente di test Termux/proot).

## 2. Classi Java: due mondi distinti

| Codice | Dove gira | Formato |
|---|---|---|
| `JvmHostService` (Kotlin/Java di Starling) | **ART** (Android runtime) | dex (d8/R8, minSdk 33, target 35) |
| `dev.starling.bridge.*` (questo repo) | **JVM OpenJDK embedded** | `.class`/`.jar` normali, `--release 17` |

La JVM headless è un **OpenJDK porting per Android arm64** (es. le JRE usate da
PojavLauncher): carica jar/class file standard, **non dex**. Quindi le classi
del bridge NON vanno desugarizzate: si impacchettano in `bridge-u8.jar`:

```bash
bash java/bridge-u8/build.sh          # compila + test (host)
cd java/bridge-u8/build/classes
jar cf ../../bridge-u8.jar dev        # jar per la JVM embedded
# copia bridge-u8.jar nello storage dell'app, es. getExternalFilesDir("bridge")
```

`RingBuffer` usa `VarHandle` (API 33+): con target Android 15 (API 35) è OK.
Il codice del bridge resta Java 17 per massima compatibilità.

## 3. Avvio della JVM in un Service Android

```kotlin
// app/src/main/java/.../JvmHostService.kt
class JvmHostService : Service() {
    companion object {
        init {
            System.loadLibrary("jvm")             // OpenJDK porting Android
            System.loadLibrary("bridge_jni")      // crate Rust bridge-jni
            System.loadLibrary("starling_jvm_host") // docs/starling_jvm_host.cpp
        }
        @JvmStatic external fun nativeStart(
            jreLib: String, classpath: String,
            mods: String, lut: String, mapping: String): Boolean
        @JvmStatic external fun nativePoll()
        @JvmStatic external fun nativeStop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val base = getExternalFilesDir(null)!!          // Scoped Storage: OK
        val mods = File(base, "mods").apply { mkdirs() }
        val lut  = File(base, "lut").apply { mkdirs() }
        val bridgeJar = File(base, "bridge/bridge-u8.jar")
        nativeStart(
            File(applicationInfo.nativeLibraryDir, "libjvm.so").absolutePath,
            bridgeJar.absolutePath,
            mods.absolutePath, lut.absolutePath,
            File(lut, "mapping.bin").absolutePath
        )
        return START_STICKY
    }

    override fun onDestroy() { nativeStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
```

Il layer C++ (`docs/starling_jvm_host.cpp`) fa `JNI_CreateJavaVM`, chiama
`JvmBootstrap.start` (scansione mod → jar reali → ring → attach JNI → thread
tick 20 TPS) e cachea `nativePoll`. Il **render thread del motore Rust chiama
`JvmHost_PollFrame()` una volta per frame**: una chiamata JNI, zero copie,
zero rete. Le azioni risolte dalla LUT arrivano al `NativeSink`, che Starling
inoltra ai sistemi nativi (audio, particelle, HUD, mondo).

`ModRegistry.scan` resta il gate d'avvio: `mapping.bin` presente → mod pronta;
assente → coda di analisi **offline** (mai durante il gameplay, zero AI a runtime).

## 4. Struttura del buffer condiviso (riepilogo)

```
header 128 byte, LE, base allineata ≥ 8 (consigliato 64):
  0   u32 magic "BRG1" (0x31475242)
  4   u32 versione = 1
  8   u32 capacità (potenza di 2, ≥ 16)
  12  u32 flags
  16  u64 write_index   ← Java, VarHandle setRelease   (cache line 0)
  24  u64 dropped_bytes ← Java, relaxed
  64  u64 read_index    ← Rust, AtomicU64 Release      (cache line 1)
  128 ... dati
```

- In-process: `ByteBuffer.allocateDirect` (Rust legge via
  `GetDirectBufferAddress`: **zero copie**).
- Cross-process (se mai servisse): `RingBuffer.mapped(file, cap)` su file mmap,
  oppure `android.os.SharedMemory` (API 27+) al posto del file.
- Indici monotoni crescenti; slot = `indice & (capacità-1)`; wrap con due copie.

## 5. Rischi di crash (e mitigazioni implementate)

| # | Rischio | Mitigazione |
|---|---|---|
| 1 | **Disallineamento memoria**: indici u64 atomici su base non allineata → SIGBUS su aarch64 | `RingConsumer::attach` verifica alignment ≥ 8 e rifiuta con `InvalidRing`; `allocateDirect`/mmap garantiscono alignment |
| 2 | **Endianness**: Java scrive LE, Rust legge LE | `ByteOrder.LITTLE_ENDIAN` forzato lato Java, `from_le_bytes`/atomici LE lato Rust; big-endian non supportato (Android è sempre LE) |
| 3 | **Memory ordering**: letture plain degli indici → dati strappati | VarHandle `setRelease`/`getAcquire` ↔ `AtomicU64` `Release`/`Acquire`; dati scritti PRIMA della pubblicazione |
| 4 | **Lifetime del buffer**: GC libera il direct buffer mentre Rust ha il puntatore → use-after-free | Java deve tenere un reference forte al `ByteBuffer` per tutta la vita dell'handle (`JvmBootstrap` lo tiene in un campo statico); MAI detachare senza rilasciare |
| 5 | **False sharing** write/read sulla stessa cache line → latenza ballerina | indici su cache line separate (offset 16 e 64) |
| 6 | **Thread JNI**: chiamare JNI da thread nativo non agganciato → crash | `GetEnv` + `AttachCurrentThread` nel render loop; aggancio una volta per thread, non per frame |
| 7 | **Local ref leak** nei loop JNI → overflow del table | `delete_local_ref` dopo ogni callback; `GlobalRef` per sink/classe, rilasciati in `nativeDetach` |
| 8 | **Eccezioni pendenti**: JNI con eccezione pendente → comportamento indefinito | `ExceptionCheck` dopo ogni `Call*Method`; `flush_actions` propaga l'eccezione del sink senza altre chiamate JNI |
| 9 | **Panic Rust attraverso FFI** = UB | profilo release `panic = "abort"`; `bridge-u8` è `forbid(unsafe_code)`, unsafe confinato in `bridge-jni` |
| 10 | **Overflow del ring**: produttore blocca il thread JVM | mai bloccante: `trySendWire` scarta e conta (`dropped_bytes`); l'OutputStream solleva `IOException`; il motore logga il contatore (indicatore di desync, Fase 3) |
| 11 | **Versioni disallineate** `.so` ↔ classi Java | magic + versione verificati in attach; capacità potenza di 2 verificata; rifiuto esplicito, mai corruzione silenziosa |
| 12 | **Linker namespace Android 15**: dlopen da percorsi fuori namespace → `UnsatisfiedLinkError` | `.so` in `jniLibs/arm64-v8a`; JRE in `nativeLibraryDir` |
| 13 | **Pagine 16 KB** (device Android 15 recenti) | flag linker `-Wl,-z,max-page-size=16384` |
| 14 | **ABI mismatch**: `.so` glibc in JVM bionic o viceversa | compilare tutto con NDK per Android; nei test host usare JVM glibc (Debian OpenJDK) con `.so` glibc |
| 15 | **Mod che toccano classi Minecraft mancanti** → `NoClassDefFoundError` | quarantena per-mod in `RealJarModHost` (catch `Throwable`), host sopravvive; il bootstrap completo Fabric/NeoForge richiede le classi shim MC fornite dal motore (prossimo milestone) |

## 6. Test sulla macchina host

```bash
bash scripts/jni-test.sh    # build .so + test Java + JNI end-to-end in-process
bash scripts/roundtrip.sh   # roundtrip file-based Java → Rust
CC=/usr/bin/gcc cargo test --workspace
```

Nota ambiente: se il `java` nel PATH è una JVM bionic (Termux), i test JNI
vanno eseguiti con una JVM glibc (es. `/usr/lib/jvm/java-21-openjdk-arm64/bin/java`):
lo script lo seleziona automaticamente.