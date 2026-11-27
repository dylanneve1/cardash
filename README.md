<p align="center">
  <img src="screenshots/01-home-classic.png" alt="CarDash home screen" width="880">
</p>

<h1 align="center">CarDash</h1>

<p align="center">
  A Material 3 Expressive launcher for Android car head units.<br>
  Dynamic colour, a GPS dash, your music, your apps — on hardware that was never meant to have any of it.
</p>

<p align="center">
  <a href="https://github.com/dylanneve1/cardash/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/dylanneve1/cardash/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Android 5.0+" src="https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="No AndroidX, no Gradle" src="https://img.shields.io/badge/deps-none-blue">
  <img alt="Fonts: OFL 1.1" src="https://img.shields.io/badge/type-Google%20Sans%20Flex-000">
</p>

---

## What it is

Aftermarket head units ship with launchers that look like 2012 and behave worse. CarDash replaces the home screen with something you'd actually want to glance at while driving:

- **Material You, generated on-device.** The whole palette is derived from your wallpaper — or from one of six bundled designs — so the clock, the gauge, the tiles and the controls all belong to one scheme. Works on Android 11, which never had dynamic colour.
- **A dash column that's yours.** A GPS speedometer, a trip meter, and the weather. Show the ones you want, or none and let the app grid take the room.
- **Now playing, without leaning over.** Whatever's playing — Bluetooth, Spotify, the radio app — appears under the clock with transport controls sized for a moving car.
- **Car data when there's a source.** Fuel, coolant, RPM, temperatures and open doors from an ELM327 dongle or the unit's own CAN box. Nothing is shown until something real reports.
- **Expressive motion, or none.** Springs, staggers and crossfades tuned to Material 3's curves — or a *Calm* mode that swaps every one of them for a short fade.
- **Dark only.** A bright screen reflected in a windscreen at night is a hazard, not a preference.

## Presets

Four layouts that were designed as wholes, one tap each. Every individual widget can still be tuned underneath.

| | |
|:--:|:--:|
| ![Classic](screenshots/01-home-classic.png) | ![Driver](screenshots/02-home-driver.png) |
| **Classic** — speed and weather, full media, one wide tile | **Driver** — speed and trip, compact media, no clutter |
| ![Launcher](screenshots/03-home-launcher.png) | ![Minimal](screenshots/04-home-minimal.png) |
| **Launcher** — no dash column, twelve tiles | **Minimal** — clock, a number, tiles |

## Customise

Everything lives behind the gear next to *All apps*. Options are deliberately few and every combination has been looked at.

<p align="center">
  <img src="screenshots/07-settings.png" alt="Settings" width="880">
</p>

| | |
|---|---|
| **Wallpaper** | Six bundled designs — *Harbour, Lagoon, Moss, Ember, Plum, Blush* — drawn to fit your screen. The theme follows. |
| **Colour** | From the wallpaper, or one of six seeds |
| **Background** | *Glass*, *Balanced* or *Solid* — how much wallpaper shows through |
| **Tiles** | One wide hero tile over the rest, or an even grid |
| **Motion** | *Expressive* or *Calm* |
| **Dash widgets** | Speed, Trip, Weather — any combination, including none |
| **Speed gauge** | Arc with ticks, or digits only; *City / Normal / Fast* scale |
| **Date, Now playing, Car data** | Full, compact or hidden |
| **Units** | km/h or mph, °C or °F, 12h or 24h |

<p align="center">
  <img src="screenshots/10-wallpapers.png" alt="Bundled wallpapers" width="880">
</p>


## Make the home screen yours

The home screen is a grid of cells (12 × 8 on a 1280×720 unit, worked out from the screen size) with any number of pages. It looks the way it always did until you change it.

- **Edit:** long-press empty space or any widget (or the clock → *Edit home screen*, or *Settings → Home screen → Edit*, or *All apps → Edit home screen*).
- **Select:** tap a widget (it is outlined). The bar at the bottom then acts on it, and *Deselect*, a tap on empty space or Back lets go.
- **Move:** drag the selected widget, or nudge it with the arrow buttons (they hop over neighbours); it snaps to cells and never lands on another one.
- **Resize:** *Resize*, then the width and height buttons or the round handle in its bottom-right corner.
- **Settings of a widget** and **Remove** are on the same bar. Everything is 48 dp or bigger, and the grid shrinks to sit above the bar, so nothing hides under it.
- **Add:** *Add* in the bar at the bottom (or long-press empty space): an app, clock, date, now playing, speedometer, trip, weather, a single vehicle value (coolant, fuel, rpm, speed) as a bar or a gauge with its own minimum, maximum and warning threshold (default coolant 0–120 °C, red from 110), door status, the all-apps button, the settings button, the vehicle strip.
- **Pages:** swipe sideways between pages. In edit mode, *+ Page* adds an empty page or a ready-made vehicle page (three gauges, doors, speed bar); *Delete page* removes one.
- **Done** keeps the changes, **Cancel** throws them away, **Default** goes back to the original layout.
- **All apps:** swipe up from anywhere on the home screen (or add the all-apps button).
- **Never locked out:** the bar's *Settings* button opens Settings in edit mode; long-pressing the clock opens a menu with Settings; the all-apps screen has Settings and *Edit home screen* buttons.
- **Left- or right-hand drive:** *Settings → Steering wheel* mirrors the layout so the clock side is next to the driver.

The layout is stored as JSON in the app's preferences (`home_layout`); the model and its tests are plain Java in `layout/` (`./test.sh`).

## Hardware

Built for a Jancar / Rockchip RK3326 unit — Android 11, 32-bit — and kept honest about it:

- **Android 5.0 and up**, targeting Android 11. Everything is framework API; anything newer is guarded.
- **No native code**, so architecture is a non-issue. No AndroidX, no Gradle: `aapt2 → javac → d8`.
- **Google Sans Flex**, the typeface Pixel phones use, bundled under the SIL Open Font License.

| Data source | Hardware | Gives you |
|---|---|---|
| GPS | The unit's own receiver | Speed, trip distance, position for weather |
| `ObdSource` | ELM327 Bluetooth dongle | RPM, speed, coolant; fuel level and outside (or intake) air temperature where the car has the PID; VIN |
| `JancarSource` | CAN box on the harness | Doors, boot, handbrake, reverse, fuel |
| `HcnSource` | HCN head units (`com.hcn.autocan`) | Doors, handbrake, fuel, outside temperature, odometer (see [docs/hcn.md](docs/hcn.md)) |
| `HcnSource` | HCN head units (`com.hcn.autocan`), Raise box | Doors, boot; handbrake, RPM, fuel if the vendor service passes frames on ([docs/hcn.md](docs/hcn.md)) — untested on a car |

OBD-II has no PID for door state — that lives on the body bus — so doors need the CAN box. Where several sources report a field, the OBD dongle wins and the CAN box fills in what it lacks (fuel, for instance). When OBD reports speed, the gauge prefers it over GPS (no lag under braking, no tunnel dropout). Fuel is remembered across restarts; doors are not. A value nobody reports shows a dash, or *Unavailable* when the car says it has no such PID; engine OBD has no inside temperature at all.

The OBD link is tuned for speed: supported PIDs are read once, on CAN several PIDs share one request, RPM and speed go in every cycle and slow values now and then. *Diagnostics* shows the measured requests per second.

## Car pages

A fresh install has three pages: **car information** (RPM, speed, coolant, fuel, outside temperature round a top-down car whose doors swing open), the **home** page where it starts, and **useful information** (clock, now playing, weather, trip). Swipe up for apps. A layout you have saved is never replaced; *Edit → Default* gets the new one.

- **Style** — the car page comes as *Modern* widgets or a monochrome *Classic VW* cluster screen (heading and time, car, outside temperature, odometer and trip). Choose per page in edit mode, or in Settings.
- **Car profile** — make, model and body (3-door, 5-door, estate, saloon). Make and model come from the VIN; the door count doesn't, so pick it.
- **Warnings** — a value's box turns red past its limit (5500 rpm, 110 °C coolant; each widget's gear changes it).
- **Unplug reminder** — with a dongle chosen, three minutes without the engine running raises a notification and a banner, once per stop.

### Trying it without a car

*Diagnostics → Simulated vehicle* replaces every source with made-up data (marked on screen). Drive it from the Phone settings page (sliders, switches, a repeating drive cycle) or from adb:

```sh
P=content://ie.claudius.cardash.diag
adb shell content call --uri $P --method sim --arg "enable=1,rpm=3000,door_fl=1"
adb shell content call --uri $P --method sim --arg "cycle=1"        # play a drive; cycle=0 stops it
adb shell content call --uri $P --method sim --arg "fuel=na"        # a value the car doesn't have
adb shell content call --uri $P --method sim --arg "enable=0"
```

Names: `rpm speed coolant fuel outside odometer door_fl door_fr door_rl door_rr boot bonnet handbrake reverse` (and `reset=1`).

## Phone settings

*Settings → Phone settings → On* serves a small settings page on the local network (the head unit usually sits on the phone's hotspot). The row shows the address, a QR code of it and a 6-digit pairing code: scan it (or type the address) on the phone, enter the code once, and change the dash widgets, layout and units from there. LAN only, off by default, five wrong codes lock it until it is switched off and on.

## Diagnostics

Long-press the clock and choose *Diagnostics* (or *Settings → Diagnostics*). It answers the questions you'd otherwise buy hardware to find out:

- **Unit** — build fingerprint, the *claimed* Android version beside the *actual* API level (vendors overstate it), ABIs, and every package that looks like car integration.
- **Vendor service surface** — the receivers, services and providers those packages declare, with their intent actions, read from their own manifests. A **manifest sweep** of every installed package finds the car service wherever a ROM has renamed it.
- **Launch activities** — every *exported* activity of every installed app, including the ones with no launcher icon (factory menus, canbox setup, `com.mediatek.engineermode`), on its own screen (grouped by app, with icons), a filter box and big tappable rows with a **Launch** button (explicit component, errors shown on the row, never a crash). Factory/engineer/modem/radio/RF/IMEI names and EngineerMode ask for confirmation first. Built for units with no adb: it is the one APK you need.
- **Live broadcasts** — a sniffer armed on every action the sweep found, with each extra typed (`fuel=47 (Integer)`). Re-arms itself when discovery lands.
- **Vehicle sources** — detected / started / last heard / fields known, per source: three failure modes that look identical on the home screen.
- **OBD-II** — paired devices, which one matched, socket state, and the last raw reply per command, verbatim. Settles "this car has no fuel PID" versus "this clone dongle is lying".
- **Key capture** — every key event with keycode, scancode and input device: what a steering-wheel button actually sends.
- **Canbox protocol list** — typed by hand from the factory-settings menu; the one fact no API exposes and the one that decides which box to buy.
- **Wireless Android Auto** — 5 GHz, Wi-Fi Direct, STA+AP concurrency (queryable exactly from API 30), Bluetooth, p2p interface, projection app versions. Tells you whether it's a settings problem, an app problem or a wall.
- **Serial ports** — which UARTs exist.

**Export** writes the whole report as plain text and hands it to any share target; **Copy** puts it on the clipboard. That file is what you give a canbox seller or a forum thread.

### From adb

Every probe is reachable from a shell, no screen needed — a `ContentProvider` that answers only the shell and root UIDs:

```sh
adb shell content query --uri content://ie.claudius.cardash.diag/unit
adb shell content query --uri content://ie.claudius.cardash.diag/sweep
adb shell content query --uri content://ie.claudius.cardash.diag/surface/com.jancar.services
adb shell content query --uri content://ie.claudius.cardash.diag/aa
adb shell content call  --uri content://ie.claudius.cardash.diag --method sniff --arg 15
adb shell content call  --uri content://ie.claudius.cardash.diag --method obd --arg 20
adb shell content call  --uri content://ie.claudius.cardash.diag --method set --arg speed_unit --extra value:s:mph
```

`content query --uri content://ie.claudius.cardash.diag/` lists everything.

## Install

Grab the APK from a [CI run](https://github.com/dylanneve1/cardash/actions) or build your own, then:

```sh
adb install -r cardash.apk
```

Settings → Apps → Default apps → Home app. The stock launcher stays installed; switching back is the same two taps. Long-press the home screen to edit it (tiles included).

## Build

```sh
./build.sh      # -> cardash.apk
./test.sh       # plain-JVM tests: OBD parser, HCN/Raise frames, phone settings page, QR encoder, Bluetooth device list
```

Needs an Android SDK with build-tools 35.0.0+ and platform 34 (`ANDROID_SDK_ROOT` if it isn't in the default place). The script generates a debug keystore on first run; use your own for anything you hand to other people.

### Screenshots

`screenshots/` is produced by `tools/render.py` (Pillow, numpy, fontTools). They are mockups rather than device captures, but faithful ones: every dp value is read from the layout code, text boxes use the bundled fonts' real metrics, colours come from running the app's own colour maths on the JVM, and the backgrounds are the bundled wallpaper designs drawn with the same code. Only the rasteriser and the app icons are stand-ins.

## Licence

Google Sans Flex is © The Google Sans Flex Authors, under the [SIL Open Font License 1.1](assets/fonts/OFL.txt).

## Ideas, bugs, your head unit

Open an issue (there are templates for bugs, feature ideas and support for another head unit or car) or start a [Discussion](https://github.com/dylanneve1/cardash/discussions). Once a week Claudius triages them and opens a pull request for one item; see [CONTRIBUTING.md](CONTRIBUTING.md).
