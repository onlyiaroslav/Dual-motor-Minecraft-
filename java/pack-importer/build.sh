#!/usr/bin/env bash
# Build + test dell'importatore texture pack (core Java, zero dipendenze).
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/classes
mkdir -p "$OUT" build
find src -name '*.java' > build/sources.txt
javac --release 17 -Xlint:all -d "$OUT" @build/sources.txt
echo "--- build OK, eseguo i test ---"
java -cp "$OUT" dev.starling.packs.PackImporterTest