# Starling — host JVM headless + bridge Rust (Android, NDK).
#
# Prerequisito: libbridge_jni.so già compilata con cargo-ndk in
# app/src/main/jniLibs/<ABI>/ (task Gradle cargoNdkBuild, vedi build.gradle.kts).
#
# NOTA ANDROID: libjvm.so NON va linkata qui (l'NDK non la fornisce):
# starling_jvm_host.cpp la carica con dlopen a runtime dalla JRE Android.

cmake_minimum_required(VERSION 3.22)
project(starling_jvm_host CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(starling_jvm_host SHARED
        native/starling_jvm_host.cpp)

find_library(log-lib log)
# dl: dlopen/dlsym di libjvm.so a runtime.
target_link_libraries(starling_jvm_host ${log-lib} dl)

# --- Libreria Rust (importata, buildata da cargo-ndk) -----------------------
set(BRIDGE_JNI_SO
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libbridge_jni.so")
if(EXISTS "${BRIDGE_JNI_SO}")
    add_library(bridge_jni SHARED IMPORTED)
    set_target_properties(bridge_jni PROPERTIES IMPORTED_LOCATION "${BRIDGE_JNI_SO}")
    # Link formale: registra la dipendenza runtime e verifica la presenza.
    target_link_libraries(starling_jvm_host bridge_jni)
else()
    message(WARNING "libbridge_jni.so non trovata per ${ANDROID_ABI}: "
            "esegui prima il task Gradle cargoNdkBuild")
endif()

# --- Opzionale: build Rust direttamente da CMake ----------------------------
option(BRIDGE_BUILD_RUST "Compila il bridge Rust con cargo-ndk durante la build" OFF)
if(BRIDGE_BUILD_RUST)
    add_custom_target(bridge_rust ALL
            COMMAND cargo ndk -t ${ANDROID_ABI} --platform 33
                    -o "${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs"
                    build --release --package bridge-jni
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.."
            COMMENT "cargo-ndk: build libbridge_jni.so (${ANDROID_ABI})")
    add_dependencies(starling_jvm_host bridge_rust)
endif()