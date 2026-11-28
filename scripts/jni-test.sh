#!/usr/bin/env bash
# Test JNI end-to-end IN-PROCESS sulla macchina host:
# 1. compila libbridge_jni.so (cdylib Rust)
# 2. build + test Java (ring, jar reali)
# 3. Java produce nel ring condiviso → Rust dispatcha → sink Java verifica
#
# La JVM deve essere glibc (Debian OpenJDK): una JVM bionic (Termux) non può
# caricare una .so glibc e ha restrizioni di linker namespace.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
export CC="${CC:-/usr/bin/gcc}"

cargo build --release -p bridge-jni
bash java/bridge-u8/build.sh

JAVA_BIN=java
for d in /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-21-openjdk-*; do
    if [ -x "$d/bin/java" ]; then JAVA_BIN="$d/bin/java"; break; fi
done
echo "--- JNI test con: $JAVA_BIN ---"
"$JAVA_BIN" --enable-native-access=ALL-UNNAMED \
    -cp java/bridge-u8/build/classes dev.starling.bridge.JniRoundtripTest \
    "$PWD/target/release/libbridge_jni.so"