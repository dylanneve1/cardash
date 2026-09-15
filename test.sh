#!/usr/bin/env bash
# Plain-JVM tests for the Android-free parts (ELM327 parsing, colour maths).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build/test"
rm -rf "$OUT"; mkdir -p "$OUT"
javac -d "$OUT" \
  "$HERE/src/ie/claudius/cardash/vehicle/ObdParse.java" \
  "$HERE/test/ObdParseTest.java"
java -cp "$OUT" ObdParseTest
