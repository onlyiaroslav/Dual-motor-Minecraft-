#!/usr/bin/env bash
# Simulazione del game loop Starling sulla macchina host:
#   1. build libbridge_jni.so (Rust) + classi Java (incluso GameLoopMock)
#   2. mapping.bin di test
#   3. C++ con JVM REALE in-process: mock Java produce a 20 TPS,
#      il "motore" C++ fa polling a 60 FPS (sleep_for), verifica zero
#      perdite/scarti e stabilità RSS su 20 cicli attach/detach
#   4. esempio Rust puro: stesso pacing 60 FPS senza JVM
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
export CC="${CC:-/usr/bin/gcc}"

cargo build --release -p bridge-jni
bash java/bridge-u8/build.sh
java -cp java/bridge-u8/build/classes dev.starling.bridge.RoundtripDemo

# JVM glibc (Debian): una JVM bionic non può caricare la .so glibc.
JH=""
for d in /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-21-openjdk-*; do
    if [ -x "$d/bin/java" ]; then JH="$d"; break; fi
done
if [ -z "$JH" ]; then echo "JDK glibc non trovato"; exit 1; fi

mkdir -p build
# g++ Debian (glibc): quello Termux nel PATH è un wrapper proot non utilizzabile.
CXX="${CXX:-/usr/bin/g++}"
"$CXX" -std=c++17 -O2 tests/game_loop_sim.cpp -o build/game_loop_sim \
    -I"$JH/include" -I"$JH/include/linux" \
    -L"$JH/lib/server" -ljvm -Wl,-rpath,"$JH/lib/server" -lpthread -ldl

echo "--- simulazione C++ + JVM in-process (600 frame a 60 FPS) ---"
./build/game_loop_sim java/bridge-u8/build/classes \
    "$PWD/target/release/libbridge_jni.so" /tmp/bridge-u8-java/mapping.bin

echo "--- simulazione Rust pura (180 frame a 60 FPS) ---"
cargo run --release -p bridge-jni --example game_loop