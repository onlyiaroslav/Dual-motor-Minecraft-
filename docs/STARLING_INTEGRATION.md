# Integrazione Starling (Android) — pacchetto finale

Comandi esatti, build CMake/Gradle, Service, game loop e **modifiche richieste
dal passaggio Linux (VM) → Android (NDK)**, file per file.

---

## 1. Cross-compilazione Rust per Android (comandi esatti)

```bash
# Prerequisiti: NDK r27+ installato (Android Studio → SDK Manager → NDK Side by side)
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.2.12479018"   # adatta il percorso

# Target ARM64 Android
rustup target add aarch64-linux-android

# cargo-ndk (verificato installabile in questo ambiente)
cargo install cargo-ndk --locked

# Build: bridge-jni produce libbridge_jni.so direttamente in jniLibs
cargo ndk -t arm64-v8a --platform 33 \
    -o starling/app/src/main/jniLibs \
    build --release --package bridge-jni
# → starling/app/src/main/jniLibs/arm64-v8a/libbridge_jni.so
```

**Nota importante:** `bridge-u8` è una **rlib** (libreria statica Rust): viene
linkata DENTRO `libbridge_jni.so`. Non serve (né esiste) una `libbridge_u8.so`
separata. Un solo artefatto nativo Rust da impacchettare.

Il `.cargo/config.toml` del repo fornisce già:
- mirror USTC per crates.io (se `static.crates.io` non è raggiungibile);
- `linker = "aarch64-linux-android24-clang"` per il target Android;
- `rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]` (pagine 16 KB,
  richiesta dai device Android 15 recenti).

Se il toolchain NDK non è nel PATH, override esplicito:
```bash
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"
```

## 2. CMake + Gradle

File nel repo:
- `starling/CMakeLists.txt` — compila `native/starling_jvm_host.cpp` in
  `libstarling_jvm_host.so`, importa `libbridge_jni.so` da jniLibs, linka
  `log` e `dl`. **libjvm NON è linkata** (l'NDK non la fornisce): caricata con
  `dlopen` a runtime. Opzione `BRIDGE_BUILD_RUST=ON` per invocare cargo-ndk
  direttamente da CMake.
- `starling/build.gradle.kts` — modulo di esempio: `compileSdk 35`,
  `minSdk 33` (VarHandle), `targetSdk 35`, `abiFilters arm64-v8a`,
  `externalNativeBuild.cmake`, `useLegacyPackaging=false` (.so non compresse),
  task `cargoNdkBuild` agganciato a `preBuild`.

Pipeline APK:
```
preBuild → cargoNdkBuild (libbridge_jni.so → jniLibs/arm64-v8a)
         → CMake (libstarling_jvm_host.so)
         → packaging APK (jniLibs: bridge_jni + starling_jvm_host + libjvm della JRE)
assets:  bridge-u8.jar (classi bridge per la JVM embedded, jar cf NON dex)
         mapping.bin (LUT default generata offline)
```

Nel repo Starling reale: copia `starling/native/`, `CMakeLists.txt` e le
sezioni Gradle nel modulo che già compila il layer nativo del motore,
adattando i percorsi.

## 3. Avvio JVM: BridgeService

`starling/BridgeService.kt` (da registrare nel AndroidManifest con
`android:foregroundServiceType="dataSync"` o tipo appropriato):

1. `System.loadLibrary("bridge_jni")` + `"starling_jvm_host"`;
2. estrae `bridge-u8.jar` dagli asset in `filesDir/bridge`;
3. prepara `mods/` e `lut/` in `getExternalFilesDir` (Scoped Storage OK);
   se `mapping.bin` manca, copia il default dagli asset;
4. `nativeStart(jreLib, classpath, mods, lut, mapping, cacheDir)` →
   `dlopen(libjvm.so)` → `JNI_CreateJavaVM` → `JvmBootstrap.start` →
   scansione mod, ring, attach JNI, tick 20 TPS;
5. il **render loop Rust** chiama `starling_jvm_poll_frame()` (C ABI) 1×/frame;
   le azioni arrivano al motore via `starling_jvm_set_action_callback`.

**Confine dei due runtime (punto critico):** ART (Service) e JVM embedded
(OpenJDK) sono distinti; gli oggetti non attraversano il confine. Il sink
NON si passa dal Kotlin: è `EngineSink`, creato dentro la JVM embedded; la sua
native method è risolta nella `.so` caricata nella JVM embedded e inoltra gli
ID alla callback C del motore.

## 4. Simulazione del game loop (verificata in questo ambiente)

```bash
bash scripts/game-loop-sim.sh
```

- `tests/game_loop_sim.cpp`: JVM **reale** in-process (`JNI_CreateJavaVM` con
  OpenJDK host), mock Java (`GameLoopMock`) produce 5 pacchetti/tick a 20 TPS
  nel ring; il "motore" C++ fa polling a **60 FPS** con
  `std::this_thread::sleep_for`, `PushLocalFrame`/`PopLocalFrame` per frame.
  Verifiche: ricevuti == prodotti, byte consumati == prodotti×2, zero scarti,
  max poll < 5 ms, **20 cicli attach/detach con RSS stabile** (euristica
  anti-leak; su Android usare Android Studio Profiler / malloc debug).
- `crates/bridge-jni/examples/game_loop.rs`: stessa simulazione lato Rust puro
  (produttore mock in un thread, consumatore a 60 FPS), senza JVM.

## 5. Modifiche Linux → Android: file per file

| File | Modifica | Motivo |
|---|---|---|
| `starling/native/starling_jvm_host.cpp` (nuovo, sostituisce `docs/starling_jvm_host.cpp`) | **dlopen/dlsym di libjvm.so** invece del link; `AttachCurrentThread` con firma Android `JNIEnv**` (guard `__ANDROID__`, righe ~60-70); export JNI `Java_dev_starling_app_BridgeService_*` e `Java_dev_starling_bridge_EngineSink_nativeOnActions`; opzione `-Djava.io.tmpdir` nella cache app; `android/log.h` con guard | L'NDK non fornisce libjvm; firma JNI diversa su Android; due runtime ART/JVM; logging logcat |
| `.cargo/config.toml` | già pronto: linker NDK + flag pagine 16 KB; override via `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` se il PATH non basta | cross-compilazione |
| `crates/bridge-u8/**` | **NESSUNA modifica** | solo std, `forbid(unsafe_code)`, nessun legame glibc |
| `crates/bridge-jni/src/lib.rs`, `src/ring.rs` | **NESSUNA modifica** | crate `jni` pura Rust, JNI identico su Android; atomici u64 LE validi su aarch64 |
| `java/.../EngineSink.java` (nuovo) | sink interno alla JVM embedded con native method | gli oggetti ART non attraversano il confine JNI_CreateJavaVM |
| `java/.../JvmBootstrap.java` | aggiunto overload `start(mods, lut, mapping)` che usa EngineSink | avvio produzione senza sink esterno |
| `java/.../RingBuffer.java` | nessuna modifica al codice; **minSdk 33** | VarHandle è API 33+ |
| `java/.../HeadlessModHost.java` | nessuna modifica | `System.setProperty("java.awt.headless", ...)` è innocuo anche se la JRE Android non ha java.awt |
| `tests/**`, `java/.../src/test/**`, `scripts/jni-test.sh`, `scripts/game-loop-sim.sh` | **NON inclusi nell'APK** | host-only (JVM glibc, /proc, g++) |
| `starling/CMakeLists.txt`, `starling/build.gradle.kts`, `starling/BridgeService.kt` (nuovi) | build e avvio Android | vedi sopra |
| JRE | procurare un **OpenJDK porting Android arm64** (es. PojavLauncher): `libjvm.so` + librerie in `jniLibs/arm64-v8a` | ART non è una JVM; ABI deve corrispondere alla `.so` Rust |

### Rischi residui specifici Android
- **Linker namespace**: tutte le `.so` in `jniLibs`/`nativeLibraryDir`; mai
  dlopen da percorsi arbitrari (Android 15 lo vieta).
- **Pagine 16 KB**: flag linker già impostato; verificare anche la JRE porting.
- **Foreground service**: Android 15 impone tipi e limiti; dichiarare
  `foregroundServiceType` e gestire `onDestroy` → `nativeStop`.
- **Memoria**: JVM + motore nello stesso processo → monitorare con Profiler;
  `-Xss1m` limita lo stack dei thread mod; ring da 64 KiB per istanza.
- **Leak JNI**: la simulazione verifica RSS su 20 cicli attach/detach; in
  produzione aggiungere `malloc_debug`/ASan nelle build di test.