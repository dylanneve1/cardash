#!/usr/bin/env bash
# Plain-JVM tests for the Android-free parts (ELM327 parsing, phone settings page, exported-activity launcher).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build/test"
rm -rf "$OUT"; mkdir -p "$OUT"
javac -d "$OUT" \
  "$HERE/src/ie/claudius/cardash/vehicle/ObdParse.java" \
  "$HERE/src/ie/claudius/cardash/remote/RemoteForm.java" \
  "$HERE/src/ie/claudius/cardash/diag/ActivityTargets.java" \
  "$HERE/test/ObdParseTest.java" \
  "$HERE/test/RemoteFormTest.java" \
  "$HERE/test/ActivityTargetsTest.java"
java -cp "$OUT" ObdParseTest
java -cp "$OUT" RemoteFormTest
java -cp "$OUT" ActivityTargetsTest
