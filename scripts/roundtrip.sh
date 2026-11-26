#!/usr/bin/env bash
# Roundtrip completo Java → Rust:
# 1. build + test Java
# 2. la JVM produce mapping.bin + stream.bin
# 3. Rust carica la LUT e dispatcha i byte, verificando le azioni
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
export CC="${CC:-/usr/bin/gcc}"

bash java/bridge-u8/build.sh
java -cp java/bridge-u8/build/classes dev.starling.bridge.RoundtripDemo
cargo run --release -p bridge-u8 --example java_roundtrip