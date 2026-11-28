// Starling — modulo Android del launcher Doge Client.
//
// Pipeline APK:
//   1. cargoNdkBuildBridge / cargoNdkBuildModConverter / cargoNdkBuildRender /
//      cargoNdkBuildSocial (Exec): compilano le librerie Rust in
//      starling/src/main/jniLibs/arm64-v8a/
//   2. externalNativeBuild (CMake): compila starling_jvm_host.cpp
//   3. packaging: tutte le .so finiscono nell'APK, non compresse
//      (useLegacyPackaging=false, pagine 16 KB)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.starling.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

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

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false   // .so non compresse: mmap diretto, 16 KB OK
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    // I file Kotlin esistenti (BridgeService, FriendsScreen, SettingsScreen,
    // TexturePackImporter) sono in src/main/kotlin/dev/starling/app/
    // (package dev.starling.app): Gradle li trova di default.
    // I bridge Java esistenti (invariati) servono a FriendsActivity,
    // SettingsActivity e TexturePackImporter: li includiamo senza toccarli.
    // NB: i percorsi sono relativi a questa cartella (starling/), quindi
    // "../java/..." punta alla root del repository.
    sourceSets["main"].java.srcDirs(
        file("../java/social-bridge/src/main/java"),
        file("../java/ai-converter/src/main/java"),
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// --- Build Rust prima della build nativa ----------------------------------
// Richiede: rustup target aarch64-linux-android, cargo-ndk, ANDROID_NDK_HOME.

// Task 1: libbridge_jni.so (bridge a 8 bit + JNI, usato da starling_jvm_host).
val cargoNdkBuildBridge by tasks.registering(Exec::class) {
    // I task girano dalla root del repository: lì c'è Cargo.toml.
    workingDir = rootProject.projectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "--platform", "33",
        "-o", "starling/src/main/jniLibs",
        "build", "--release", "--package", "bridge-jni"
    )
}

// Task 2: libmod_converter.so (convertitore mod offline con AI locale, Fase 7).
// Usa la feature "jni" per esportare i simboli JNI verso AiConverterBridge.java.
val cargoNdkBuildModConverter by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "--platform", "33",
        "-o", "starling/src/main/jniLibs",
        "build", "--release", "--package", "mod-converter", "--features", "jni"
    )
}

// Task 3: libstarling_render.so (renderer voxel wgpu/Vulkan + game loop,
// crate starling-render). I simboli C dell'host JVM (starling_jvm_poll_frame)
// sono risolti a runtime da libstarling_jvm_host.so, caricata prima in
// GameActivity: nessun link formale necessario qui.
val cargoNdkBuildRender by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "--platform", "33",
        "-o", "starling/src/main/jniLibs",
        "build", "--release", "--package", "starling-render"
    )
}

// Task 4: libstarling_social.so (lista amici + mini-server auth offline).
// Senza questa libreria la tab "Amici" non può aprire SocialBridge.
val cargoNdkBuildSocial by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "--platform", "33",
        "-o", "starling/src/main/jniLibs",
        "build", "--release", "--package", "starling-social", "--features", "jni"
    )
}

tasks.named("preBuild") {
    dependsOn(
        cargoNdkBuildBridge,
        cargoNdkBuildModConverter,
        cargoNdkBuildRender,
        cargoNdkBuildSocial,
    )
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
