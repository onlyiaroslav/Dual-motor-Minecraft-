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
    // I file Kotlin esistenti (BridgeService, FriendsScreen, SettingsScreen,
    // TexturePackImporter) sono ora in src/main/kotlin/dev/starling/app/
    // (package dev.starling.app): Gradle li trova di default.
    // I bridge Java esistenti (invariati) servono a FriendsActivity e
    // SettingsActivity: li includiamo senza toccarli.
    sourceSets["main"].java.srcDirs(
        file("../../java/social-bridge/src/main/java"),
        file("../../java/ai-converter/src/main/java"),
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Il compilatore Compose è gestito dal BOM (compatibile con Kotlin 2.0.21).
}

// --- Build Rust prima della build nativa ----------------------------------
// Richiede: rustup target aarch64-linux-android, cargo-ndk, ANDROID_NDK_HOME.

// Task 1: libbridge_jni.so (bridge a 8 bit + JNI, usato da starling_jvm_host).
val cargoNdkBuildBridge by tasks.registering(Exec::class) {
    workingDir = projectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-p", "33",
        "-o", "src/main/jniLibs",
        "build", "--release", "-p", "bridge-jni"
    )
}

// Task 2: libmod_converter.so (convertitore mod offline con AI locale, Fase 7).
// Usa la feature "jni" per esportare i simboli JNI verso AiConverterBridge.java.
val cargoNdkBuildModConverter by tasks.registering(Exec::class) {
    workingDir = projectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-p", "33",
        "-o", "src/main/jniLibs",
        "build", "--release", "-p", "mod-converter", "--features", "jni"
    )
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuildBridge, cargoNdkBuildModConverter)
}

// --- Dipendenze Jetpack Compose (UI Doge Client) ---------------------------
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}

// Asset richiesti (src/main/assets/):
//   bridge-u8.jar  → jar delle classi bridge per la JVM embedded (jar cf, non dex)
//   mapping.bin    → LUT default generata offline
// jniLibs/arm64-v8a/ oltre a libbridge_jni.so:
//   libjvm.so (+ librerie della JRE Android porting, es. PojavLauncher)