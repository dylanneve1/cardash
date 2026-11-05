# CLAUDE.md: CarDash

Notes for AI agents (and people) working on CarDash or bringing up a new head unit or car.

## Project rules
- Plain Android framework only: **no AndroidX, no Gradle, no Compose, no native code**. Build `./build.sh` (aapt2 → javac → d8, build-tools 35+). Test `./test.sh`. Both must pass.
- minSdk 27, runs on 32-bit Android 11 head units (armeabi-v7a). Vendors overstate the Android version: trust `getprop ro.build.version.sdk`.
- Dark UI only, big targets, nothing that needs attention while driving. Offline unless a feature clearly needs the network (LAN-only is fine).
- Unknown vehicle values stay UNKNOWN (`VehicleState` tri-state). Never show a confident zero.
- Car buses are **listen-only**. Never send frames to vehicle CAN, never write to a canbox or MCU.
- Be terse in reports. State what was tested on hardware and what wasn't.

## Code map
- `src/.../HomeActivity.java`: the dash. `M3.java`: palette from wallpaper. `MaterialShape`/`Shapes`: tile shapes.
- `src/.../vehicle/`: `VehicleHub` (shared instance) merges sources into `VehicleState`. `ObdSource` (ELM327 over Bluetooth SPP), `JancarSource` (canbox data via the Jancar vendor service; its action/extra names are partly guesses).
- `src/.../diag/`: the Diagnostics screen and `DiagProvider` (shell/root-only API, see below).
- `docs/car-integration.md`: hardware facts, the discovery method, and what each car platform is likely to expose.

## Adding a new head unit or car (canbox data)
Every ROM family (Jancar, FYT, TopWay, MTCD/PX…) exposes canbox data differently: broadcast intents, a content provider, a bound service, or a serial port. **Find out before writing code.** Use the built-in probe over adb (install CarDash first):

```sh
P=content://ie.claudius.cardash.diag
adb shell content query --uri $P/unit                      # identity, real API level, ROM family
adb shell content query --uri $P/sweep                     # every car-ish intent action declared on the unit
adb shell content query --uri $P/surface/<vendor.pkg>      # one vendor package's receivers/providers/services
adb shell content query --uri $P/serial                    # serial ports (canbox UART is often /dev/ttyS*)
adb shell content call  --uri $P --method sniff --arg 30   # live-capture car broadcasts for 30 s with typed extras
adb shell content call  --uri $P --method sources --arg 5  # which vehicle sources are detected / heard
adb shell content call  --uri $P --method obd --arg 20     # raw ELM327 replies, if a dongle is paired
```
The Diagnostics screen (long-press the clock, or Settings) shows the same, with a plain-text export.

**Capture recipe** (do each action during a `sniff` window, note the time): ignition on → open/close each door → boot → lights on/off → reverse gear → each steering wheel button → rev the engine → drive a little (speed). Then map each action to the broadcast or extra that changed. Attach the export to a "Support my head unit or car" issue.

If nothing shows up in `sniff`, the vendor keeps the data inside its own process (binder or UART). With root: `logcat` from the vendor service, `ls -l /dev/ttyS*`, and the vendor APK's manifest (`dumpsys package <pkg>`) are next.

## Writing a source
Implement `VehicleSource` like `JancarSource`: register receivers through `Receivers`, unregister on stop, map only fields you have **seen** change, log unknown extras under tag `CarDash/CAN`. Add a fixture-based test in `test/` from a real capture.
