#!/usr/bin/env bash
# Build + test del bridge Java (zero dipendenze: solo javac/java).
# --release 17: API compatibili con il desugaring di Android (ART).
# RingBuffer usa VarHandle: richiede API 33+ su Android (target 35: OK).
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/classes
mkdir -p "$OUT" build
find src -name '*.java' > build/sources.txt
javac --release 17 -Xlint:all -d "$OUT" @build/sources.txt
echo "--- build OK, eseguo i test ---"
java -cp "$OUT" dev.starling.bridge.BridgeTest
java -cp "$OUT" dev.starling.bridge.RingBufferTest
java -cp "$OUT" dev.starling.bridge.RealJarTest