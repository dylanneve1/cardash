#!/usr/bin/env bash
# Plain-JVM tests for the Android-free parts (ELM327 parsing, HCN/Raise frames, phone settings page, QR code, exported-activity launcher, clock fit, home layout model, Bluetooth device list).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build/test"
rm -rf "$OUT"; mkdir -p "$OUT"
javac -d "$OUT" \
  "$HERE/src/ie/claudius/cardash/vehicle/ObdParse.java" \
  "$HERE/src/ie/claudius/cardash/vehicle/HcnParse.java" \
  "$HERE/src/ie/claudius/cardash/vehicle/ObdReply.java" \
  "$HERE/src/ie/claudius/cardash/vehicle/ObdSchedule.java" \
  "$HERE/src/ie/claudius/cardash/vehicle/RateMeter.java" \
  "$HERE/test/ObdReplyTest.java" \
  "$HERE/src/ie/claudius/cardash/vehicle/VehicleState.java" \
  "$HERE/src/ie/claudius/cardash/vehicle/VehicleMerge.java" \
  "$HERE"/src/ie/claudius/cardash/car/*.java \
  "$HERE/test/CarProfileTest.java" \
  "$HERE/test/CarGeometryTest.java" \
  "$HERE/src/ie/claudius/cardash/remote/RemoteForm.java" \
  "$HERE/src/ie/claudius/cardash/remote/QrCode.java" \
  "$HERE/src/ie/claudius/cardash/diag/ActivityTargets.java" \
  "$HERE/src/ie/claudius/cardash/ClockFit.java" \
  "$HERE/test/ClockFitTest.java" \
  "$HERE/src/ie/claudius/cardash/diag/ExportText.java" \
  "$HERE/src/ie/claudius/cardash/Labels.java" \
  "$HERE"/src/ie/claudius/cardash/layout/*.java \
  "$HERE/test/HomeLayoutTest.java" \
  "$HERE/src/ie/claudius/cardash/bt/BtDevices.java" \
  "$HERE/test/BtDevicesTest.java" \
  "$HERE/test/ObdParseTest.java" \
  "$HERE/test/HcnParseTest.java" \
  "$HERE/test/RemoteFormTest.java" \
  "$HERE/test/QrCodeTest.java" \
  "$HERE/test/ActivityTargetsTest.java" \
  "$HERE/test/ExportTextTest.java"
java -cp "$OUT" ObdParseTest
java -cp "$OUT" ObdReplyTest
java -cp "$OUT" CarProfileTest
java -cp "$OUT" CarGeometryTest
java -cp "$OUT" HcnParseTest
java -cp "$OUT" RemoteFormTest
java -cp "$OUT" QrCodeTest
java -cp "$OUT" ActivityTargetsTest
java -cp "$OUT" ClockFitTest
java -cp "$OUT" ExportTextTest
java -cp "$OUT" HomeLayoutTest
java -cp "$OUT" BtDevicesTest
