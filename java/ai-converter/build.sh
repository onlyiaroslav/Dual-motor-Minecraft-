#!/usr/bin/env bash
# Build + test JNI del convertitore offline con AI locale (host Linux, non Android).
# La libreria resta separata da bridge-jni e starling-social: libmod_converter.so.
set -euo pipefail
cd "$(dirname "$0")"

ROOT="$(cd ../.. && pwd)"
export PATH="$HOME/.cargo/bin:$PATH"
export CC="${CC:-/usr/bin/gcc}"

echo "--- build Rust (feature jni) ---"
cargo build -p mod-converter --features jni

OUT=build/classes
mkdir -p "$OUT" build
find src -name '*.java' > build/sources.txt
javac --release 17 -Xlint:all -d "$OUT" @build/sources.txt

# JNI richiede una JVM glibc (il java di Termux non carica .so glibc).
JAVA_BIN="${JAVA_BIN:-/usr/lib/jvm/java-21-openjdk-arm64/bin/java}"
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=java
fi

echo "--- test JNI con $JAVA_BIN ---"
"$JAVA_BIN" -Djava.library.path="$ROOT/target/debug" -cp "$OUT" dev.starling.converter.AiConverterBridgeTest
