// Starling — modulo di esempio per l'integrazione del bridge (Android 15).
// Adatta i percorsi al layout reale del repo Starling.
//
// Pipeline APK:
//   1. cargoNdkBuild (Exec): cargo-ndk compila libbridge_jni.so → jniLibs/arm64-v8a
//   2. externalNativeBuild (CMake): compila starling_jvm_host.cpp → libstarling_jvm_host.so
//   3. packaging: tutte le .so (bridge_jni, starling_jvm_host, libjvm della JRE)
//      finiscono nell'APK, non compresse (useLegacyPackaging=false, pagine 16 KB)

plugins {
    id("com.android.application") version "8.7.0"
    kotlin("android") version "2.0.21"
}

android {
    namespace = "dev.starling.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.starling.app"
        minSdk = 33          // VarHandle (RingBuffer) richiede API 33+
        targetSdk = 35       // Android 15
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"   // solo ARM64: la JRE porting è arm64
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false   // .so non compresse: mmap diretto, 16 KB OK
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// --- Build Rust prima della build nativa ----------------------------------
// Richiede: rustup target aarch64-linux-android, cargo-ndk, ANDROID_NDK_HOME.
val cargoNdkBuild by tasks.registering(Exec::class) {
    // Repo root (contiene crates/ e .cargo/config.toml con mirror + flag 16 KB)
    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-p", "33",
        "-o", "starling/app/src/main/jniLibs",
        "build", "--release", "-p", "bridge-jni"
    )
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

// Asset richiesti (src/main/assets/):
//   bridge-u8.jar  → jar delle classi bridge per la JVM embedded (jar cf, non dex)
//   mapping.bin    → LUT default generata offline
// jniLibs/arm64-v8a/ oltre a libbridge_jni.so:
//   libjvm.so (+ librerie della JRE Android porting, es. PojavLauncher)